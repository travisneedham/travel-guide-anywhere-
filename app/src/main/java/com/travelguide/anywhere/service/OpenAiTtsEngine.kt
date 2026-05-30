package com.travelguide.anywhere.service

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

class OpenAiTtsEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    val apiKey: String,
    val model: String = "tts-1-hd"
) : TtsEngine {

    override val isReady = true

    private var mediaPlayer: MediaPlayer? = null
    private var speakJob: Job? = null
    private var isPaused = false
    private var currentFile: File? = null

    // Prewarm state — captured and nulled before stop() so stop() doesn't delete them.
    private var prewarmJob: Job? = null
    @Volatile private var prewarmText: String? = null
    @Volatile private var prewarmResult: File? = null

    override val canResume: Boolean get() = isPaused && mediaPlayer != null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun prewarm(text: String, speechRate: Float) {
        if (prewarmText == text) return  // already in progress or complete for this text
        prewarmJob?.cancel()
        prewarmResult?.delete()
        prewarmResult = null
        prewarmText = text
        prewarmJob = scope.launch {
            try {
                val audioBytes = fetchAudio(text, speechRate)
                val file = File(context.cacheDir, "tts_oai_pre_${UUID.randomUUID()}.mp3")
                file.writeBytes(audioBytes)
                prewarmResult = file
                Log.d(TAG, "Prewarm complete (${audioBytes.size / 1024}kb)")
            } catch (e: Exception) {
                prewarmText = null  // allow retry
                Log.w(TAG, "Prewarm failed: ${e.message}")
            }
        }
    }

    override fun speak(text: String, speechRate: Float, onStart: () -> Unit, onDone: () -> Unit, onError: () -> Unit, onEnqueued: () -> Unit, onProgress: ((Float) -> Unit)?) {
        // Detach prewarm state before stop() so stop() doesn't clean it up.
        val savedText = prewarmText.also { prewarmText = null }
        val savedFile = prewarmResult.also { prewarmResult = null }
        val savedJob = prewarmJob.also { prewarmJob = null }
        stop()
        speakJob = scope.launch {
            try {
                val file = if (savedText == text && savedFile?.exists() == true) {
                    Log.d(TAG, "Using prewarmed audio")
                    savedJob?.cancel()
                    onProgress?.invoke(1.0f)
                    savedFile
                } else {
                    savedJob?.cancel()
                    savedFile?.delete()
                    val audioBytes = fetchAudio(text, speechRate, onProgress)
                    File(context.cacheDir, "tts_oai_${UUID.randomUUID()}.mp3").also { it.writeBytes(audioBytes) }
                }
                currentFile = file
                withContext(Dispatchers.Main) {
                    val mp = MediaPlayer()
                    try {
                        mp.setDataSource(file.absolutePath)
                        mp.setOnCompletionListener {
                            isPaused = false
                            file.delete()
                            currentFile = null
                            onDone()
                        }
                        mp.setOnErrorListener { _, _, _ ->
                            isPaused = false
                            file.delete()
                            currentFile = null
                            onError()
                            true
                        }
                        mp.prepare()
                        mediaPlayer = mp
                        onStart()
                        mp.start()
                    } catch (e: Exception) {
                        if (mediaPlayer === mp) mediaPlayer = null
                        mp.release()
                        throw e
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "OpenAI TTS error: ${e.message}")
                withContext(Dispatchers.Main) { onError() }
            }
        }
    }

    override fun setSpeed(rate: Float) {
        try {
            mediaPlayer?.playbackParams = android.media.PlaybackParams().setSpeed(rate.coerceAtLeast(0.1f))
        } catch (e: Exception) {
            Log.w(TAG, "setSpeed failed: ${e.message}")
        }
    }

    override fun pause() {
        mediaPlayer?.pause()
        isPaused = true
    }

    override fun resume() {
        if (isPaused) {
            mediaPlayer?.start()
            isPaused = false
        }
    }

    override fun stop() {
        speakJob?.cancel()
        speakJob = null
        prewarmJob?.cancel()
        prewarmJob = null
        prewarmResult?.delete()
        prewarmResult = null
        prewarmText = null
        isPaused = false
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        mediaPlayer?.release()
        mediaPlayer = null
        currentFile?.delete()
        currentFile = null
    }

    override fun shutdown() = stop()

    private suspend fun fetchAudio(
        text: String,
        speechRate: Float,
        onProgress: ((Float) -> Unit)? = null,
    ): ByteArray = withContext(Dispatchers.IO) {
        val speed = speechRate.coerceIn(0.25f, 4.0f)
        val json = JSONObject()
            .put("model", model)
            .put("input", text)
            .put("voice", "onyx")
            .put("response_format", "mp3")
            .put("speed", speed.toDouble())
            .toString()

        val request = Request.Builder()
            .url("https://api.openai.com/v1/audio/speech")
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("OpenAI TTS HTTP ${response.code}: ${response.body?.string()?.take(200)}")
            }
            val body = response.body ?: throw Exception("Empty response from OpenAI TTS")
            if (onProgress == null) {
                body.bytes()
            } else {
                val contentLength = body.contentLength()
                val stream = body.byteStream()
                if (contentLength <= 0L) {
                    // No Content-Length header — read all at once, report done.
                    stream.readBytes().also { onProgress(1.0f) }
                } else {
                    val out = java.io.ByteArrayOutputStream(contentLength.toInt())
                    val buf = ByteArray(8192)
                    var totalRead = 0L
                    var n: Int
                    while (stream.read(buf).also { n = it } != -1) {
                        out.write(buf, 0, n)
                        totalRead += n
                        onProgress((totalRead.toFloat() / contentLength).coerceAtMost(1.0f))
                    }
                    out.toByteArray()
                }
            }
        }
    }

    companion object {
        private const val TAG = "OpenAiTtsEngine"
    }
}
