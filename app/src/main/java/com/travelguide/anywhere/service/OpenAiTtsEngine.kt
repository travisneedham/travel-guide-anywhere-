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

    override val canResume: Boolean get() = isPaused && mediaPlayer != null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    override fun speak(text: String, speechRate: Float, onStart: () -> Unit, onDone: () -> Unit, onError: () -> Unit) {
        stop()
        speakJob = scope.launch {
            try {
                val audioBytes = fetchAudio(text, speechRate)
                val file = File(context.cacheDir, "tts_oai_${UUID.randomUUID()}.mp3")
                file.writeBytes(audioBytes)
                currentFile = file
                withContext(Dispatchers.Main) {
                    mediaPlayer = MediaPlayer().apply {
                        setDataSource(file.absolutePath)
                        setOnCompletionListener {
                            isPaused = false
                            file.delete()
                            currentFile = null
                            onDone()
                        }
                        setOnErrorListener { _, _, _ ->
                            isPaused = false
                            file.delete()
                            currentFile = null
                            onError()
                            true
                        }
                        prepare()
                        onStart()
                        start()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "OpenAI TTS error: ${e.message}")
                withContext(Dispatchers.Main) { onError() }
            }
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
        isPaused = false
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        mediaPlayer?.release()
        mediaPlayer = null
        currentFile?.delete()
        currentFile = null
    }

    override fun shutdown() = stop()

    private suspend fun fetchAudio(text: String, speechRate: Float): ByteArray =
        withContext(Dispatchers.IO) {
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
                response.body?.bytes() ?: throw Exception("Empty response from OpenAI TTS")
            }
        }

    companion object {
        private const val TAG = "OpenAiTtsEngine"
    }
}
