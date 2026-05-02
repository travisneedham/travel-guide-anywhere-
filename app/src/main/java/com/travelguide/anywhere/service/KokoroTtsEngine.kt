package com.travelguide.anywhere.service

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class KokoroTtsEngine(
    private val context: Context,
    private val modelDir: File,
    val voiceSid: Int = DEFAULT_VOICE_ID,
) : TtsEngine {

    // Private scope so shutdown() cancels both the init job and any speak job.
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override var isReady: Boolean = false
        private set

    private var tts: OfflineTts? = null
    private var speakJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isPaused = false
    private var currentFile: File? = null

    override val canResume: Boolean get() = isPaused && mediaPlayer != null

    init {
        engineScope.launch {
            try {
                val config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        kokoro = OfflineTtsKokoroModelConfig(
                            model = File(modelDir, "model.onnx").absolutePath,
                            voices = File(modelDir, "voices.bin").absolutePath,
                            tokens = File(modelDir, "tokens.txt").absolutePath,
                            dataDir = File(modelDir, "espeak-ng-data").absolutePath,
                        ),
                        numThreads = 2,
                        debug = false,
                        provider = "cpu",
                    )
                )
                tts = OfflineTts(config = config)
                isReady = true
                Log.i(TAG, "Kokoro TTS engine ready")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Kokoro TTS: ${e.message}")
            }
        }
    }

    override fun speak(
        text: String,
        speechRate: Float,
        onStart: () -> Unit,
        onDone: () -> Unit,
        onError: () -> Unit,
    ) {
        stop()
        val engine = tts ?: run { onError(); return }
        speakJob = engineScope.launch {
            try {
                val wavFile = File(context.cacheDir, "kokoro_${UUID.randomUUID()}.wav")
                currentFile = wavFile

                withContext(Dispatchers.Default) {
                    val audio = engine.generate(text = text, sid = voiceSid, speed = speechRate)
                    audio.save(wavFile.absolutePath)
                }

                withContext(Dispatchers.Main) {
                    mediaPlayer = MediaPlayer().apply {
                        setDataSource(wavFile.absolutePath)
                        setOnCompletionListener {
                            isPaused = false
                            wavFile.delete()
                            currentFile = null
                            onDone()
                        }
                        setOnErrorListener { _, _, _ ->
                            isPaused = false
                            wavFile.delete()
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
                Log.e(TAG, "Kokoro speak error: ${e.message}")
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
        isPaused = false
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        mediaPlayer?.release()
        mediaPlayer = null
        currentFile?.delete()
        currentFile = null
    }

    override fun shutdown() {
        engineScope.cancel()
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        mediaPlayer?.release()
        mediaPlayer = null
        currentFile?.delete()
        currentFile = null
    }

    companion object {
        private const val TAG = "KokoroTtsEngine"
        const val DEFAULT_VOICE_ID = 0
    }
}
