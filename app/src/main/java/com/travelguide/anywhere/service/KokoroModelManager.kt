package com.travelguide.anywhere.service

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KokoroModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    sealed class DownloadState {
        object NotDownloaded : DownloadState()
        data class Downloading(val progress: Float) : DownloadState()
        object Ready : DownloadState()
        data class Error(val message: String) : DownloadState()
    }

    private val _state = MutableStateFlow<DownloadState>(DownloadState.NotDownloaded)
    val state: StateFlow<DownloadState> = _state

    // Root dir where the tarball extracts to: filesDir/kokoro-en-v0_19/
    val modelDir: File get() = File(context.filesDir, MODEL_DIR_NAME)

    val isReady: Boolean
        get() = File(modelDir, "model.onnx").exists() &&
                File(modelDir, "voices.bin").exists() &&
                File(modelDir, "tokens.txt").exists()

    init {
        if (isReady) _state.value = DownloadState.Ready
    }

    fun downloadIfNeeded(scope: CoroutineScope) {
        if (_state.value is DownloadState.Downloading) return
        if (isReady) { _state.value = DownloadState.Ready; return }
        scope.launch { download() }
    }

    private suspend fun download() = withContext(Dispatchers.IO) {
        _state.value = DownloadState.Downloading(0f)
        val tempFile = File(context.cacheDir, "kokoro-model.tar.bz2")
        try {
            val request = Request.Builder().url(MODEL_URL).build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("Empty response")
                val totalBytes = body.contentLength()
                var downloadedBytes = 0L
                var lastReportedPercent = -1

                body.byteStream().use { input ->
                    tempFile.outputStream().use { output ->
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

            _state.value = DownloadState.Downloading(0.99f)
            extractTarBz2(tempFile, context.filesDir)
            tempFile.delete()
            Log.i(TAG, "Kokoro model ready at ${modelDir.absolutePath}")
            _state.value = DownloadState.Ready
        } catch (e: Exception) {
            tempFile.delete()
            Log.e(TAG, "Kokoro download failed: ${e.message}")
            _state.value = DownloadState.Error(e.message ?: "Unknown error")
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
        private const val MODEL_DIR_NAME = "kokoro-en-v0_19"
        const val MODEL_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-en-v0_19.tar.bz2"
    }
}
