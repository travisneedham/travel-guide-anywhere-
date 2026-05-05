package com.travelguide.anywhere.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KokoroModelManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // Application-scoped so downloads survive navigation / screen rotation.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Dedicated client with no body-logging interceptor — the shared app client buffers
    // the entire response for logging which OOMs on a 305 MB download.
    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .build()

    sealed class DownloadState {
        object NotDownloaded : DownloadState()
        data class Downloading(val progress: Float) : DownloadState()
        object Extracting : DownloadState()
        object Ready : DownloadState()
        data class Error(val message: String) : DownloadState()
    }

    private val _state = MutableStateFlow<DownloadState>(DownloadState.NotDownloaded)
    val state: StateFlow<DownloadState> = _state

    val modelDir: File get() = File(context.filesDir, MODEL_DIR_NAME)
    private val voicePreviewDir: File get() = File(context.filesDir, "voice_previews")

    fun voicePreviewFile(sid: Int) = File(voicePreviewDir, "voice_$sid.wav")

    val isReady: Boolean
        get() = File(modelDir, "model.onnx").exists() &&
                File(modelDir, "voices.bin").exists() &&
                File(modelDir, "tokens.txt").exists()

    @Volatile private var previewGenerationStarted = false

    /** Generate and cache a short preview WAV for every voice that doesn't have one yet.
     *  Runs in the app-scoped IO coroutine; safe to call multiple times. */
    fun ensureVoicePreviews(voices: List<Pair<String, Int>>, template: String) {
        if (!isReady || previewGenerationStarted) return
        previewGenerationStarted = true
        scope.launch {
            try {
                voicePreviewDir.mkdirs()
                val tts = withContext(Dispatchers.IO) {
                    OfflineTts(config = OfflineTtsConfig(
                        model = OfflineTtsModelConfig(
                            kokoro = OfflineTtsKokoroModelConfig(
                                model = File(modelDir, "model.onnx").absolutePath,
                                voices = File(modelDir, "voices.bin").absolutePath,
                                tokens = File(modelDir, "tokens.txt").absolutePath,
                                dataDir = File(modelDir, "espeak-ng-data").absolutePath,
                                lang = "en-us",
                            ),
                            numThreads = 1,
                            debug = false,
                            provider = "cpu",
                        )
                    ))
                }
                for ((label, sid) in voices) {
                    if (!isActive) break
                    val dest = voicePreviewFile(sid)
                    if (dest.exists()) continue
                    val name = label.substringBefore(" ")
                    withContext(Dispatchers.IO) {
                        tts.generate(text = template.format(name), sid = sid, speed = 0.95f)
                            .save(dest.absolutePath)
                    }
                    Log.d(TAG, "Preview cached: $label")
                }
                Log.i(TAG, "Voice preview cache complete")
            } catch (e: Exception) {
                previewGenerationStarted = false // allow retry on next launch
                Log.w(TAG, "Voice preview generation failed: ${e.message}")
            }
        }
    }

    init {
        if (isReady) _state.value = DownloadState.Ready
    }

    fun downloadIfNeeded() {
        if (_state.value is DownloadState.Downloading) return
        if (isReady) { _state.value = DownloadState.Ready; return }
        scope.launch { download() }
    }

    private suspend fun download() = withContext(Dispatchers.IO) {
        // Keep CPU and WiFi alive for the full download even when the screen is off.
        val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TravelGuide:KokoroDownload")
            .also { it.acquire(40 * 60 * 1000L) } // 40-minute cap
        @Suppress("DEPRECATION")
        val wifiLock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TravelGuide:KokoroDownload")
            .also { it.acquire() }

        val tempFile = File(context.cacheDir, "kokoro-model.tar.bz2")
        try {
            // Resume from a partial download left by a previous interrupted attempt.
            val resumeFrom = if (tempFile.exists()) tempFile.length() else 0L
            if (resumeFrom > 0) Log.i(TAG, "Resuming download from byte $resumeFrom")

            val request = Request.Builder()
                .url(MODEL_URL)
                .apply { if (resumeFrom > 0) header("Range", "bytes=$resumeFrom-") }
                .build()

            downloadClient.newCall(request).execute().use { response ->
                val resuming = response.code == 206
                if (!response.isSuccessful && !resuming) throw IOException("HTTP ${response.code}")
                if (!resuming && resumeFrom > 0) {
                    // Server doesn't support Range; discard partial file and start over.
                    tempFile.delete()
                    Log.w(TAG, "Server ignored Range header — restarting download")
                }

                val body = response.body ?: throw IOException("Empty response")
                val contentLength = body.contentLength()
                val startBytes = if (resuming) resumeFrom else 0L
                val totalBytes = if (contentLength > 0) startBytes + contentLength else 0L
                var downloadedBytes = startBytes
                var lastReportedPercent = if (totalBytes > 0) ((startBytes * 100) / totalBytes).toInt() else -1

                if (totalBytes > 0) _state.value = DownloadState.Downloading(startBytes.toFloat() / totalBytes)

                // Append when resuming, overwrite otherwise.
                FileOutputStream(tempFile, resuming).use { output ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            downloadedBytes += read
                            if (totalBytes > 0) {
                                val pct = (downloadedBytes * 100 / totalBytes).toInt()
                                if (pct != lastReportedPercent) {
                                    lastReportedPercent = pct
                                    _state.value = DownloadState.Downloading(downloadedBytes.toFloat() / totalBytes)
                                }
                            }
                        }
                    }
                }
            }

            _state.value = DownloadState.Extracting
            extractTarBz2(tempFile, context.filesDir)
            tempFile.delete()
            Log.i(TAG, "Kokoro model ready at ${modelDir.absolutePath}")
            _state.value = DownloadState.Ready
        } catch (e: Exception) {
            // Keep the temp file so the next attempt can resume rather than restart.
            Log.e(TAG, "Kokoro download failed: ${e.message}")
            _state.value = DownloadState.Error(e.message ?: "Unknown error")
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
            if (wifiLock.isHeld) wifiLock.release()
        }
    }

    private fun extractTarBz2(archive: File, destDir: File) {
        BZip2CompressorInputStream(archive.inputStream().buffered()).use { bzIn ->
            TarArchiveInputStream(bzIn).use { tarIn ->
                var entry = tarIn.nextTarEntry
                while (entry != null) {
                    val dest = File(destDir, entry.name)
                    if (entry.isDirectory) {
                        dest.mkdirs()
                    } else {
                        dest.parentFile?.mkdirs()
                        dest.outputStream().use { tarIn.copyTo(it) }
                    }
                    entry = tarIn.nextTarEntry
                }
            }
        }
    }

    companion object {
        private const val TAG = "KokoroModelManager"
        private const val MODEL_DIR_NAME = "kokoro-multi-lang-v1_0"
        const val MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-multi-lang-v1_0.tar.bz2"
    }
}
