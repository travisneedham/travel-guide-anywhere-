package com.travelguide.anywhere.service

import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalLlmModelManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES)
        .build()

    enum class ChatTemplate { PHI, CHATML }

    enum class LocalModel(
        val displayName: String,
        val fileName: String,
        val downloadUrl: String,
        val approxSizeGb: Double,
        val chatTemplate: ChatTemplate,
    ) {
        PHI4_MINI(
            displayName = "Phi-4 Mini",
            fileName = "phi-4-mini-q4_k_m.gguf",
            downloadUrl = "https://huggingface.co/unsloth/Phi-4-mini-instruct-GGUF/resolve/main/Phi-4-mini-instruct-Q4_K_M.gguf",
            approxSizeGb = 2.5,
            chatTemplate = ChatTemplate.PHI,
        ),
        SMOLLM2(
            displayName = "SmolLM2 1.7B",
            fileName = "smollm2-1.7b-q4_k_m.gguf",
            downloadUrl = "https://huggingface.co/HuggingFaceTB/SmolLM2-1.7B-Instruct-GGUF/resolve/main/smollm2-1.7b-instruct-q4_k_m.gguf",
            approxSizeGb = 1.1,
            chatTemplate = ChatTemplate.CHATML,
        ),
    }

    sealed class DownloadState {
        object NotDownloaded : DownloadState()
        data class Downloading(val progress: Float) : DownloadState()
        object Ready : DownloadState()
        data class Error(val message: String) : DownloadState()
    }

    private val modelsDir = File(context.filesDir, "local_llm_models")

    private val stateFlows: Map<LocalModel, MutableStateFlow<DownloadState>> =
        LocalModel.values().associateWith { MutableStateFlow(DownloadState.NotDownloaded) }

    init {
        modelsDir.mkdirs()
        LocalModel.values().forEach { model ->
            if (modelFile(model).exists()) {
                stateFlows[model]!!.value = DownloadState.Ready
            }
        }
    }

    fun modelFile(model: LocalModel): File = File(modelsDir, model.fileName)

    fun isReady(model: LocalModel): Boolean = modelFile(model).exists()

    fun stateFlowFor(model: LocalModel): StateFlow<DownloadState> =
        stateFlows[model] ?: error("Unknown model: $model")

    fun downloadIfNeeded(model: LocalModel) {
        val current = stateFlows[model]?.value
        if (current is DownloadState.Downloading) return
        if (isReady(model)) {
            stateFlows[model]!!.value = DownloadState.Ready
            return
        }
        scope.launch { download(model) }
    }

    private suspend fun download(model: LocalModel) = withContext(Dispatchers.IO) {
        val wakeLock = (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TravelGuide:LocalLlmDownload")
            .also { it.acquire(60 * 60 * 1000L) } // 60-minute cap
        @Suppress("DEPRECATION")
        val wifiLock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
            .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TravelGuide:LocalLlmDownload")
            .also { it.acquire() }

        val tempFile = File(context.cacheDir, model.fileName + ".part")
        try {
            val resumeFrom = if (tempFile.exists()) tempFile.length() else 0L
            if (resumeFrom > 0) Log.i(TAG, "Resuming ${model.displayName} download from byte $resumeFrom")

            val request = Request.Builder()
                .url(model.downloadUrl)
                .apply { if (resumeFrom > 0) header("Range", "bytes=$resumeFrom-") }
                .build()

            downloadClient.newCall(request).execute().use { response ->
                val resuming = response.code == 206
                if (!response.isSuccessful && !resuming) throw IOException("HTTP ${response.code}")
                if (!resuming && resumeFrom > 0) {
                    tempFile.delete()
                    Log.w(TAG, "Server ignored Range header — restarting download of ${model.displayName}")
                }

                val body = response.body ?: throw IOException("Empty response")
                val contentLength = body.contentLength()
                val startBytes = if (resuming) resumeFrom else 0L
                val totalBytes = if (contentLength > 0) startBytes + contentLength else 0L
                var downloadedBytes = startBytes
                var lastReportedPercent = if (totalBytes > 0) ((startBytes * 100) / totalBytes).toInt() else -1

                if (totalBytes > 0) stateFlows[model]!!.value = DownloadState.Downloading(startBytes.toFloat() / totalBytes)

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
                                    stateFlows[model]!!.value = DownloadState.Downloading(downloadedBytes.toFloat() / totalBytes)
                                }
                            }
                        }
                    }
                }
            }

            val finalFile = modelFile(model)
            tempFile.renameTo(finalFile)
            Log.i(TAG, "${model.displayName} model ready at ${finalFile.absolutePath}")
            stateFlows[model]!!.value = DownloadState.Ready
        } catch (e: Exception) {
            Log.e(TAG, "${model.displayName} download failed: ${e.message}")
            stateFlows[model]!!.value = DownloadState.Error(e.message ?: "Unknown error")
        } finally {
            if (wakeLock.isHeld) wakeLock.release()
            if (wifiLock.isHeld) wifiLock.release()
        }
    }

    companion object {
        private const val TAG = "LocalLlmModelManager"
    }
}
