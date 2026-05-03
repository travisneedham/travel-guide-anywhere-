package com.travelguide.anywhere.service

import android.content.Context
import android.media.MediaPlayer
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
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
        val chunks = splitIntoChunks(normalizeForTts(text))

        speakJob = engineScope.launch {
            var onStartCalled = false

            // Kick off generation + prepare for chunk 0 immediately.
            // Each Deferred produces a fully-prepared MediaPlayer so mp.start()
            // can fire with zero setup delay when the current chunk ends.
            var pendingPlay: Deferred<Pair<MediaPlayer, File>?> = async(Dispatchers.IO) {
                preparePlayer(engine, chunks[0], speechRate)
            }

            for (idx in chunks.indices) {
                if (!isActive) { pendingPlay.cancel(); return@launch }
                val isLast = idx == chunks.lastIndex

                val result = pendingPlay.await() ?: run {
                    withContext(Dispatchers.Main) { onError() }
                    return@launch
                }
                val (mp, wavFile) = result

                // While this chunk plays, generate + prepare the next one.
                if (!isLast) {
                    pendingPlay = async(Dispatchers.IO) {
                        preparePlayer(engine, chunks[idx + 1], speechRate)
                    }
                }

                if (!isActive) {
                    mp.release()
                    wavFile.delete()
                    if (!isLast) pendingPlay.cancel()
                    return@launch
                }

                val ok = withContext(Dispatchers.Main) {
                    suspendCancellableCoroutine { cont ->
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
                    if (!isLast) pendingPlay.cancel()
                    withContext(Dispatchers.Main) { onError() }
                    return@launch
                }

                if (isLast) withContext(Dispatchers.Main) { onDone() }
            }
        }
    }

    // Generates WAV and prepares a MediaPlayer in one background step so
    // the player is ready to start() the instant the previous chunk ends.
    private fun preparePlayer(engine: OfflineTts, chunk: String, speechRate: Float): Pair<MediaPlayer, File>? {
        val wavFile = File(context.cacheDir, "kokoro_${UUID.randomUUID()}.wav")
        return try {
            val t0 = System.currentTimeMillis()
            engine.generate(text = chunk, sid = voiceSid, speed = speechRate).save(wavFile.absolutePath)
            val genMs = System.currentTimeMillis() - t0
            val mp = MediaPlayer().also {
                it.setDataSource(wavFile.absolutePath)
                it.prepare()
            }
            val totalMs = System.currentTimeMillis() - t0
            Log.d(TAG, "chunk[${chunk.length}ch] gen=${genMs}ms prepare+total=${totalMs}ms")
            mp to wavFile
        } catch (e: Exception) {
            Log.e(TAG, "preparePlayer error: ${e.message}")
            wavFile.delete()
            null
        }
    }

    // Converts 4-digit years (1000–2099) to their spoken form so Kokoro reads
    // them naturally. Claude is also instructed to do this, but this is a safety net.
    private fun normalizeForTts(text: String): String =
        Regex("""\b(1[0-9]{3}|20[0-9]{2})\b""").replace(text) { yearToWords(it.value.toInt()) }

    private fun yearToWords(year: Int): String {
        val ones = listOf(
            "", "one", "two", "three", "four", "five", "six", "seven", "eight", "nine",
            "ten", "eleven", "twelve", "thirteen", "fourteen", "fifteen",
            "sixteen", "seventeen", "eighteen", "nineteen"
        )
        val tens = listOf("", "", "twenty", "thirty", "forty", "fifty", "sixty", "seventy", "eighty", "ninety")
        fun pair(n: Int) = when {
            n < 20     -> ones[n]
            n % 10 == 0 -> tens[n / 10]
            else        -> "${tens[n / 10]}-${ones[n % 10]}"
        }
        if (year == 2000) return "two thousand"
        if (year in 2001..2009) return "two thousand ${ones[year - 2000]}"
        val hi = year / 100
        val lo = year % 100
        return when {
            lo == 0 -> "${pair(hi)} hundred"
            lo < 10 -> "${pair(hi)} oh ${ones[lo]}"
            else    -> "${pair(hi)} ${pair(lo)}"
        }
    }

    // Split narration into sentence-grouped chunks of ≤ MAX_CHUNK_CHARS.
    // Generation of chunk N+1 runs in parallel with playback of chunk N,
    // so transitions are gapless as long as generation ≤ playback duration.
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
