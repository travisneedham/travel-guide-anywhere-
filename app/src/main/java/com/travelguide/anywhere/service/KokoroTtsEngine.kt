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

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Single-thread dispatcher so that two generation jobs never access the
    // OfflineTts model concurrently (the model is not thread-safe).
    private val genDispatcher = Dispatchers.IO.limitedParallelism(1)

    override var isReady: Boolean = false
        private set

    private var tts: OfflineTts? = null
    private var speakJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isPaused = false

    // Pre-generated audio for the NEXT narration, produced while the current
    // narration is playing so speak() can start immediately when called.
    private var prewarmText: String? = null
    private var prewarmDeferred: Deferred<List<Pair<MediaPlayer, File>>?>? = null

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

    /**
     * Pre-generate ALL audio chunks for [text] while the current narration plays.
     * When speak() is later called with the same text it awaits this deferred and
     * starts playback immediately rather than waiting for generation.
     */
    override fun prewarm(text: String, speechRate: Float) {
        if (text == prewarmText && prewarmDeferred?.isActive == true) return
        prewarmDeferred?.cancel()
        val engine = tts ?: return
        val chunks = splitIntoChunks(normalizeForTts(text))
        prewarmText = text
        prewarmDeferred = engineScope.async(genDispatcher) {
            Log.d(TAG, "Prewarm: generating ${chunks.size} chunks (${text.length} chars)")
            generateAllChunks(engine, chunks, speechRate)
        }
    }

    override fun speak(
        text: String,
        speechRate: Float,
        onStart: () -> Unit,
        onDone: () -> Unit,
        onError: () -> Unit,
        onEnqueued: () -> Unit,
    ) {
        stop()
        val engine = tts ?: run { onError(); return }
        val chunks = splitIntoChunks(normalizeForTts(text))

        speakJob = engineScope.launch {
            // Grab whatever prewarm state exists
            val preDeferred = prewarmDeferred.also { prewarmDeferred = null }
            val preText    = prewarmText.also    { prewarmText    = null }

            // Step 1: Obtain all pre-generated (MediaPlayer, wavFile) pairs.
            val players: List<Pair<MediaPlayer, File>> = if (preText == text && preDeferred != null) {
                // Pre-generation was started for this exact text; await it.
                Log.d(TAG, "Awaiting pre-generated audio for \"${text.take(40)}...\"")
                val pregenerated = preDeferred.await()
                if (pregenerated != null) {
                    Log.d(TAG, "Using ${pregenerated.size} pre-generated chunks")
                    pregenerated
                } else {
                    // Prewarm failed; fall through to fresh generation.
                    withContext(genDispatcher) { generateAllChunks(engine, chunks, speechRate) }
                        ?: run { withContext(Dispatchers.Main) { onError() }; return@launch }
                }
            } else {
                // Different text or no prewarm: cancel any stale prewarm, generate fresh.
                preDeferred?.cancel()
                withContext(genDispatcher) { generateAllChunks(engine, chunks, speechRate) }
                    ?: run { withContext(Dispatchers.Main) { onError() }; return@launch }
            }

            if (!isActive) { players.release(); return@launch }

            // Step 2: All audio is ready — signal callers, then play.
            withContext(Dispatchers.Main) {
                onStart()     // emits SPEAKING state, triggers prefetch of next narration
                onEnqueued()  // kept for interface compatibility; no-op in current service
            }

            // Step 3: Play all chunks back-to-back (no generation gaps).
            for ((idx, playerFile) in players.withIndex()) {
                if (!isActive) { players.drop(idx).release(); return@launch }
                val (mp, wavFile) = playerFile
                val isLast = idx == players.lastIndex

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
                    players.drop(idx + 1).release()
                    withContext(Dispatchers.Main) { onError() }
                    return@launch
                }
                if (isLast) withContext(Dispatchers.Main) { onDone() }
            }
            if (players.isEmpty()) withContext(Dispatchers.Main) { onDone() }
        }
    }

    // Generates WAV for every chunk sequentially and prepares a MediaPlayer for
    // each, ready to start() immediately. Returns null if any chunk fails.
    // Must be called on genDispatcher to ensure single-threaded model access.
    private fun generateAllChunks(
        engine: OfflineTts,
        chunks: List<String>,
        speechRate: Float,
    ): List<Pair<MediaPlayer, File>>? {
        val results = mutableListOf<Pair<MediaPlayer, File>>()
        for (chunk in chunks) {
            val r = preparePlayer(engine, chunk, speechRate) ?: run {
                results.release()
                return null
            }
            results.add(r)
        }
        Log.d(TAG, "Generated ${results.size} chunks")
        return results
    }

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
            Log.d(TAG, "chunk[${chunk.length}ch] gen=${genMs}ms total=${System.currentTimeMillis() - t0}ms")
            mp to wavFile
        } catch (e: Exception) {
            Log.e(TAG, "preparePlayer error: ${e.message}")
            wavFile.delete()
            null
        }
    }

    private fun List<Pair<MediaPlayer, File>>.release() =
        forEach { (mp, f) -> try { mp.release() } catch (_: Exception) {}; f.delete() }

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

        const val VOICE_PREVIEW_TEMPLATE =
            "Hi, my name is %s. It would be my pleasure to be your tour guide today."
    }
}
