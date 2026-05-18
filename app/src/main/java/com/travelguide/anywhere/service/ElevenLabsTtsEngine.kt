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

class ElevenLabsTtsEngine(
    private val context: Context,
    private val scope: CoroutineScope,
    val apiKey: String,
    val voiceId: String = DEFAULT_VOICE_ID
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
                val audioBytes = fetchAudio(text)
                val file = File(context.cacheDir, "tts_el_pre_${UUID.randomUUID()}.mp3")
                file.writeBytes(audioBytes)
                prewarmResult = file
                Log.d(TAG, "Prewarm complete (${audioBytes.size / 1024}kb)")
            } catch (e: Exception) {
                prewarmText = null  // allow retry
                Log.w(TAG, "Prewarm failed: ${e.message}")
            }
        }
    }

    override fun speak(text: String, speechRate: Float, onStart: () -> Unit, onDone: () -> Unit, onError: () -> Unit, onEnqueued: () -> Unit) {
        // Detach prewarm state before stop() so stop() doesn't clean it up.
        val savedText = prewarmText.also { prewarmText = null }
        val savedFile = prewarmResult.also { prewarmResult = null }
        val savedJob = prewarmJob.also { prewarmJob = null }
        stop()
        speakJob = scope.launch {
            try {
                val file = if (savedText == text && savedFile?.exists() == true) {
                    Log.d(TAG, "Using prewarmed audio")
                    savedJob?.cancel()  // job is done since file exists; cancel is a no-op
                    savedFile
                } else {
                    savedJob?.cancel()
                    savedFile?.delete()
                    val audioBytes = fetchAudio(text)
                    File(context.cacheDir, "tts_el_${UUID.randomUUID()}.mp3").also { it.writeBytes(audioBytes) }
                }
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
                Log.e(TAG, "ElevenLabs TTS error: ${e.message}")
                withContext(Dispatchers.Main) { onError() }
            }
        }
    }

    override fun setSpeed(rate: Float) {
        try {
            mediaPlayer?.playbackParams = android.media.PlaybackParams().setSpeed(rate.coerceAtLeast(0.1f))
        } catch (e: Exception) {
            Log.w(TAG, "ElevenLabs setSpeed failed: ${e.message}")
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

    private suspend fun fetchAudio(text: String): ByteArray = withContext(Dispatchers.IO) {
        val json = JSONObject()
            .put("text", text)
            .put("model_id", "eleven_turbo_v2_5")
            .put("voice_settings", JSONObject()
                .put("stability", 0.5)
                .put("similarity_boost", 0.75)
                .put("style", 0.3)
                .put("use_speaker_boost", true))
            .toString()

        val request = Request.Builder()
            .url("https://api.elevenlabs.io/v1/text-to-speech/$voiceId?output_format=mp3_44100_128")
            .header("xi-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(json.toRequestBody("application/json".toMediaType()))
            .build()

        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("ElevenLabs TTS HTTP ${response.code}: ${response.body?.string()?.take(200)}")
            }
            response.body?.bytes() ?: throw Exception("Empty response from ElevenLabs")
        }
    }

    companion object {
        private const val TAG = "ElevenLabsTtsEngine"
        // George — warm, authoritative narrator voice
        const val DEFAULT_VOICE_ID = "JBFqnCBsd6RMkjVDRZzb"
    }
}
