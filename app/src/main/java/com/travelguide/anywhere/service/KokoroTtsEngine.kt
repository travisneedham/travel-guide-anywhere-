package com.travelguide.anywhere.service

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume

class KokoroTtsEngine(
    private val context: Context,
    private val modelDir: File,
    val voiceSid: Int = DEFAULT_VOICE_ID,
    private val lang: String = "en-us",
) : TtsEngine {

    // Private scope so shutdown() cancels both the init job and any speak job.
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override var isReady: Boolean = false
        private set

    private var tts: OfflineTts? = null
    private var speakJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isPaused = false

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
                            lang = lang,
                        ),
                        numThreads = 1,
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
        val chunks = splitIntoChunks(text)

        speakJob = engineScope.launch {
            var onStartCalled = false
            for ((idx, chunk) in chunks.withIndex()) {
                if (!isActive) return@launch
                val isLast = idx == chunks.lastIndex

                // Generate audio for this chunk on the CPU-bound dispatcher.
                val wavFile = File(context.cacheDir, "kokoro_${UUID.randomUUID()}.wav")
                try {
                    withContext(Dispatchers.Default) {
                        engine.generate(text = chunk, sid = voiceSid, speed = speechRate)
                            .save(wavFile.absolutePath)
                    }
                } catch (e: CancellationException) {
                    wavFile.delete()
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Kokoro generate error on chunk $idx: ${e.message}")
                    wavFile.delete()
                    withContext(Dispatchers.Main) { onError() }
                    return@launch
                }

                if (!isActive) { wavFile.delete(); return@launch }

                // Play the chunk and suspend until it finishes.
                val ok = withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine { cont ->
                        val mp = MediaPlayer()
                        try {
                            mp.setDataSource(wavFile.absolutePath)
                            mp.setOnCompletionListener {
                                wavFile.delete()
                                mediaPlayer = null
                                cont.resume(true)
                            }
                            mp.setOnErrorListener { _, _, _ ->
                                wavFile.delete()
                                mediaPlayer = null
                                cont.resume(false)
                                true
                            }
                            mp.prepare()
                        } catch (e: Exception) {
                            mp.release()
                            wavFile.delete()
                            cont.resume(false)
                            return@suspendCancellableCoroutine
                        }
                        mediaPlayer?.release()
                        mediaPlayer = mp
                        if (!onStartCalled) {
                            onStartCalled = true
                            onStart()
                        }
                        mp.start()
                        cont.invokeOnCancellation {
                            try { mp.stop() } catch (_: Exception) {}
                            mp.release()
                            mediaPlayer = null
                            wavFile.delete()
                        }
                    }
                }

                if (!ok) {
                    withContext(Dispatchers.Main) { onError() }
                    return@launch
                }

                if (isLast) withContext(Dispatchers.Main) { onDone() }
            }
        }
    }

    // Split narration into sentence-grouped chunks of ≤ MAX_CHUNK_CHARS.
    // Each chunk generates in ~20–25 s on device; audio plays for ~30 s,
    // so playback stays ahead of generation for the full narration.
    private fun splitIntoChunks(text: String): List<String> {
        val sentences = text.split(Regex("(?<=[.!?])\\s+"))
            .map { it.trim() }.filter { it.isNotEmpty() }
        val chunks = mutableListOf<String>()
        val buf = StringBuilder()
        for (sentence in sentences) {
            if (buf.isNotEmpty() && buf.length + sentence.length + 1 > MAX_CHUNK_CHARS) {
                chunks += buf.toString()
                buf.clear()
            }
            if (buf.isNotEmpty()) buf.append(' ')
            buf.append(sentence)
        }
        if (buf.isNotEmpty()) chunks += buf.toString()
        return chunks.ifEmpty { listOf(text) }
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
        val mp = mediaPlayer
        mediaPlayer = null
        try { mp?.stop() } catch (_: Exception) {}
        mp?.release()
    }

    override fun shutdown() {
        engineScope.cancel()
        val mp = mediaPlayer
        mediaPlayer = null
        try { mp?.stop() } catch (_: Exception) {}
        mp?.release()
    }

    companion object {
        private const val TAG = "KokoroTtsEngine"
        const val DEFAULT_VOICE_ID = 0
        private const val MAX_CHUNK_CHARS = 400
    }
}
