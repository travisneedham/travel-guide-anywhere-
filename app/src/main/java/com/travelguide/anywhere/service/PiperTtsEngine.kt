package com.travelguide.anywhere.service

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * On-device TTS using Piper VITS models via sherpa-onnx. Each instance is tied to a single
 * downloaded voice (a directory under piper-voices/). Generation is fast enough on modern
 * hardware that we synthesize the whole narration into one WAV before playing — no Kokoro-
 * style adaptive chunking pipeline needed.
 */
class PiperTtsEngine(
    private val context: Context,
    private val voiceDir: File,
    private val voiceId: String,
) : TtsEngine {

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // Single-thread dispatcher — OfflineTts is not thread-safe across generations.
    private val genDispatcher = Dispatchers.IO.limitedParallelism(1)

    override var isReady: Boolean = false
        private set

    private var tts: OfflineTts? = null
    private var speakJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isPaused = false
    @Volatile private var currentPlayRate: Float = 1.0f

    // Prewarm state — captured and nulled before stop() so stop() doesn't delete them.
    private var prewarmJob: Job? = null
    @Volatile private var prewarmText: String? = null
    @Volatile private var prewarmResult: File? = null

    override val canResume: Boolean get() = isPaused && mediaPlayer != null

    init {
        engineScope.launch {
            try {
                tts = OfflineTts(
                    config = OfflineTtsConfig(
                        model = OfflineTtsModelConfig(
                            vits = OfflineTtsVitsModelConfig(
                                model = File(voiceDir, "$voiceId.onnx").absolutePath,
                                tokens = File(voiceDir, "tokens.txt").absolutePath,
                                dataDir = File(voiceDir, "espeak-ng-data").absolutePath,
                            ),
                            numThreads = 1,
                            debug = false,
                            provider = "cpu",
                        )
                    )
                )
                isReady = true
                Log.i(TAG, "Piper TTS engine ready: $voiceId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Piper TTS: ${e.message}")
            }
        }
    }

    override fun prewarm(text: String, speechRate: Float) {
        if (prewarmText == text) return
        prewarmJob?.cancel()
        prewarmResult?.delete()
        prewarmResult = null
        prewarmText = text
        prewarmJob = engineScope.launch {
            try {
                val file = generateWav(text, speechRate, "piper_pre_")
                    ?: throw Exception("generation returned null")
                prewarmResult = file
                Log.d(TAG, "Piper prewarm complete (${file.length() / 1024}kb)")
            } catch (e: Exception) {
                prewarmText = null  // allow retry
                Log.w(TAG, "Piper prewarm failed: ${e.message}")
            }
        }
    }

    override fun speak(
        text: String,
        speechRate: Float,
        onStart: () -> Unit,
        onDone: () -> Unit,
        onError: () -> Unit,
        onEnqueued: () -> Unit,
        onProgress: ((Float) -> Unit)?,
    ) {
        // Detach prewarm state BEFORE stop() so stop() doesn't clean it up.
        val savedText = prewarmText.also { prewarmText = null }
        val savedFile = prewarmResult.also { prewarmResult = null }
        val savedJob = prewarmJob.also { prewarmJob = null }
        stop()
        speakJob = engineScope.launch {
            try {
                val file = if (savedText == text && savedFile?.exists() == true) {
                    Log.d(TAG, "Using prewarmed Piper audio")
                    savedJob?.cancel()
                    onProgress?.invoke(1.0f)
                    savedFile
                } else {
                    savedJob?.cancel()
                    savedFile?.delete()
                    generateWav(text, speechRate, "piper_", onProgress)
                        ?: throw Exception("audio generation failed")
                }
                withContext(Dispatchers.Main) {
                    mediaPlayer = MediaPlayer().apply {
                        setDataSource(file.absolutePath)
                        setOnCompletionListener {
                            isPaused = false
                            file.delete()
                            mediaPlayer = null
                            onDone()
                        }
                        setOnErrorListener { _, _, _ ->
                            isPaused = false
                            file.delete()
                            mediaPlayer = null
                            onError()
                            true
                        }
                        prepare()
                        try {
                            playbackParams = android.media.PlaybackParams()
                                .setSpeed(currentPlayRate.coerceAtLeast(0.1f))
                        } catch (_: Exception) { /* falls back to 1.0× */ }
                        onStart()
                        onEnqueued()
                        start()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Piper speak error: ${e.message}")
                withContext(Dispatchers.Main) { onError() }
            }
        }
    }

    private suspend fun generateWav(
        text: String,
        speechRate: Float,
        prefix: String,
        onProgress: ((Float) -> Unit)? = null,
    ): File? = withContext(genDispatcher) {
        val engine = tts ?: return@withContext null
        val outFile = File(context.cacheDir, "$prefix${UUID.randomUUID()}.wav")
        try {
            if (onProgress != null) {
                // Piper VITS outputs at 22050 Hz. Estimate total samples from text length:
                // ~65 ms of audio per character at 1.0× speed → 22050 * 0.065 ≈ 1433 samples/char.
                val estimatedSamples = (text.length * 1433 / speechRate).toLong().coerceAtLeast(1)
                var samplesGenerated = 0L
                engine.generateWithCallback(
                    text = text,
                    sid = 0,
                    speed = speechRate,
                ) { samples ->
                    samplesGenerated += samples.size
                    onProgress((samplesGenerated.toFloat() / estimatedSamples).coerceAtMost(0.95f))
                    1  // continue
                }.save(outFile.absolutePath)
                onProgress(1.0f)
            } else {
                engine.generate(text = text, sid = 0, speed = speechRate)
                    .save(outFile.absolutePath)
            }
            outFile
        } catch (e: Exception) {
            Log.e(TAG, "Piper generate error: ${e.message}")
            outFile.delete()
            null
        }
    }

    override fun setSpeed(rate: Float) {
        currentPlayRate = rate
        try {
            mediaPlayer?.playbackParams = android.media.PlaybackParams()
                .setSpeed(rate.coerceAtLeast(0.1f))
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
        val mp = mediaPlayer
        mediaPlayer = null
        try { mp?.stop() } catch (_: Exception) {}
        mp?.release()
    }

    override fun shutdown() {
        engineScope.cancel()
        prewarmResult?.delete()
        prewarmResult = null
        prewarmText = null
        val mp = mediaPlayer
        mediaPlayer = null
        try { mp?.stop() } catch (_: Exception) {}
        mp?.release()
    }

    companion object {
        private const val TAG = "PiperTtsEngine"
        const val VOICE_PREVIEW_TEMPLATE =
            "Hi, my name is %s. It would be my pleasure to be your tour guide today."
    }
}
