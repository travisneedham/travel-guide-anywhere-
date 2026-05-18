package com.travelguide.anywhere.service

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages per-voice download state for Piper TTS. Unlike Kokoro (one big bundle for all
 * voices), Piper voices are individual ~60-110 MB archives. Voices download independently
 * and only when a user selects them in settings.
 */
@Singleton
class PiperModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    sealed class VoiceState {
        object NotDownloaded : VoiceState()
        data class Downloading(val progress: Float) : VoiceState()
        object Extracting : VoiceState()
        object Ready : VoiceState()
        data class Error(val message: String) : VoiceState()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .build()

    private val voiceStates = ConcurrentHashMap<String, MutableStateFlow<VoiceState>>()
    private val downloadJobs = ConcurrentHashMap<String, Job>()

    /** Root directory holding all extracted Piper voices. */
    private val baseDir: File get() = File(context.filesDir, "piper-voices")

    /** Directory holding the .onnx + tokens.txt + espeak-ng-data for one voice. */
    fun voiceDir(voiceId: String): File = File(baseDir, "vits-piper-$voiceId")

    /** Cached preview WAV played in the settings picker. */
    fun voicePreviewFile(voiceId: String): File = File(voiceDir(voiceId), "preview.wav")

    fun isVoiceReady(voiceId: String): Boolean {
        val dir = voiceDir(voiceId)
        return File(dir, "$voiceId.onnx").exists() &&
            File(dir, "$voiceId.onnx.json").exists() &&
            File(dir, "tokens.txt").exists() &&
            File(dir, "espeak-ng-data").isDirectory
    }

    /** StateFlow for one voice — reflects whether it is downloaded, downloading, etc. */
    fun stateFor(voiceId: String): StateFlow<VoiceState> = voiceStates.getOrPut(voiceId) {
        MutableStateFlow(if (isVoiceReady(voiceId)) VoiceState.Ready else VoiceState.NotDownloaded)
    }

    /** Kick off a background download for [voice] if not already downloaded or in progress. */
    fun downloadVoiceIfNeeded(voice: PiperVoice) {
        val flow = voiceStates.getOrPut(voice.id) {
            MutableStateFlow(if (isVoiceReady(voice.id)) VoiceState.Ready else VoiceState.NotDownloaded)
        }
        when (flow.value) {
            is VoiceState.Ready,
            is VoiceState.Downloading,
            is VoiceState.Extracting -> return
            else -> Unit
        }
        downloadJobs[voice.id]?.cancel()
        downloadJobs[voice.id] = scope.launch { downloadAndExtract(voice, flow) }
    }

    private suspend fun downloadAndExtract(
        voice: PiperVoice,
        flow: MutableStateFlow<VoiceState>,
    ) = withContext(Dispatchers.IO) {
        val tempFile = File(context.cacheDir, "${voice.archiveBaseName}.tar.bz2")
        try {
            flow.value = VoiceState.Downloading(0f)
            baseDir.mkdirs()

            val resumeFrom = if (tempFile.exists()) tempFile.length() else 0L
            val request = Request.Builder()
                .url(voice.downloadUrl)
                .apply { if (resumeFrom > 0) header("Range", "bytes=$resumeFrom-") }
                .build()

            downloadClient.newCall(request).execute().use { response ->
                val resuming = response.code == 206
                if (!response.isSuccessful && !resuming) throw IOException("HTTP ${response.code}")
                if (!resuming && resumeFrom > 0) {
                    tempFile.delete()
                    Log.w(TAG, "Server ignored Range — restarting download for ${voice.id}")
                }

                val body = response.body ?: throw IOException("Empty response")
                val contentLength = body.contentLength()
                val startBytes = if (resuming) resumeFrom else 0L
                val totalBytes = if (contentLength > 0) startBytes + contentLength else 0L
                var downloaded = startBytes
                var lastPct = -1

                FileOutputStream(tempFile, resuming).use { output ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            downloaded += read
                            if (totalBytes > 0) {
                                val pct = (downloaded * 100 / totalBytes).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    flow.value = VoiceState.Downloading(downloaded.toFloat() / totalBytes)
                                }
                            }
                        }
                    }
                }
            }

            flow.value = VoiceState.Extracting
            extractTarBz2(tempFile, baseDir)
            tempFile.delete()

            if (isVoiceReady(voice.id)) {
                flow.value = VoiceState.Ready
                Log.i(TAG, "Piper voice ready: ${voice.id}")
            } else {
                flow.value = VoiceState.Error("Files missing after extraction")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Download failed for ${voice.id}: ${e.message}")
            flow.value = VoiceState.Error(e.message ?: "Download failed")
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
        private const val TAG = "PiperModelManager"
    }
}
