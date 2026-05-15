package com.travelguide.anywhere.service

import android.content.Context
import android.media.MediaPlayer
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import kotlin.coroutines.resume

class KokoroTtsEngine(
    private val context: Context,
    private val modelDir: File,
    val voiceSid: Int = DEFAULT_VOICE_ID,
    private val lang: String = "en-us",
) : TtsEngine {

    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Single-thread dispatcher so two generation jobs never access the OfflineTts model
    // concurrently (the model is not thread-safe). Playback runs on Main so it overlaps gen.
    private val genDispatcher = Dispatchers.IO.limitedParallelism(1)

    override var isReady: Boolean = false
        private set

    private var tts: OfflineTts? = null
    private var speakJob: Job? = null
    private var mediaPlayer: MediaPlayer? = null
    private var isPaused = false

    // Rate applied to every new MediaPlayer so mid-narration slider changes take effect on
    // all subsequent chunks, not just the one that was playing when setSpeed() was called.
    @Volatile private var currentPlayRate: Float = 1.0f

    // Pre-running adaptive pipeline for the NEXT narration. Generation begins while the
    // current narration is still playing, so by speak() time the ready-trigger has usually
    // already fired and playback can start immediately (T_between ≈ 0).
    private var prewarmedPipeline: NarrationPipeline? = null

    override val canResume: Boolean get() = isPaused && mediaPlayer != null

    init {
        engineScope.launch {
            try {
                tts = OfflineTts(
                    config = OfflineTtsConfig(
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
                )
                isReady = true
                Log.i(TAG, "Kokoro TTS engine ready (chunkChars=$MAX_CHUNK_CHARS adaptive=true safety=$SAFETY_MARGIN)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize Kokoro TTS: ${e.message}")
            }
        }
    }

    override fun prewarm(text: String, speechRate: Float) {
        val existing = prewarmedPipeline
        if (existing != null && existing.text == text && existing.speechRate == speechRate) return
        val engine = tts ?: return
        existing?.cancel("replaced by new prewarm")
        prewarmedPipeline = startPipeline(engine, text, speechRate, labelPrefix = "prewarm")
    }

    override fun speak(
        text: String,
        speechRate: Float,
        onStart: () -> Unit,
        onDone: () -> Unit,
        onError: () -> Unit,
        onEnqueued: () -> Unit,
    ) {
        // Detach the prewarm reference BEFORE stop() so stop() doesn't kill what
        // we are about to consume. stop() only ever clears the engine's own field.
        val candidate = prewarmedPipeline
        prewarmedPipeline = null
        stop()
        val engine = tts ?: run {
            candidate?.cancel("engine null in speak")
            onError(); return
        }

        val pipeline = if (candidate != null && candidate.text == text && candidate.speechRate == speechRate) {
            candidate.diag.markConsumedFromPrewarm()
            candidate
        } else {
            // Either different text or different speed (user moved slider). Discard prewarmed
            // audio — it was generated at the wrong rate so the adaptive projection is invalid.
            candidate?.cancel("prewarmed rate=${candidate?.speechRate} != requested=$speechRate")
            startPipeline(engine, text, speechRate, labelPrefix = "fresh")
        }

        speakJob = engineScope.launch {
            val waitStart = SystemClock.elapsedRealtime()
            try {
                pipeline.readyTrigger.await()
                pipeline.diag.tWaitedForReadyMs = SystemClock.elapsedRealtime() - waitStart
                if (!isActive) return@launch
                withContext(Dispatchers.Main) {
                    onStart()
                    onEnqueued()
                }
                playFromPipeline(pipeline, onError, onDone)
            } catch (e: Throwable) {
                Log.w(TAG, "speak coroutine ended: ${e.javaClass.simpleName} ${e.message}")
                if (isActive) withContext(Dispatchers.Main) { onError() }
            } finally {
                pipeline.cancel("speak finished")
                pipeline.diag.emit()
            }
        }
    }

    /**
     * Starts a background pipeline that generates chunks sequentially while concurrently
     * pushing each ready (MediaPlayer, wavFile) onto a channel. After each chunk, an
     * adaptive algorithm projects whether enough chunks have buffered for the rest of
     * playback to complete without pauses — once true, [NarrationPipeline.readyTrigger]
     * fires so the consumer can start playback.
     */
    private fun startPipeline(
        engine: OfflineTts,
        text: String,
        speechRate: Float,
        labelPrefix: String,
    ): NarrationPipeline {
        val chunks = splitIntoChunks(normalizeForTts(text))
        val channel = Channel<ChunkPlayer>(Channel.UNLIMITED)
        val trigger = CompletableDeferred<Long>()
        val diag = TtsDiagnostics(
            label = labelPrefix,
            textLen = text.length,
            chunkChars = chunks.map { it.length },
        )
        Log.i(TAG, "[TTS-DIAG] pipeline start label=$labelPrefix chars=${text.length} chunks=${chunks.size} sizes=${diag.chunkChars}")

        val pipeline = NarrationPipeline(text, speechRate, channel, trigger, diag)
        pipeline.genJob = engineScope.launch(genDispatcher) {
            val t0 = SystemClock.elapsedRealtime()
            val chars = IntArray(chunks.size) { chunks[it].length }
            val genMs = LongArray(chunks.size)
            val playMs = LongArray(chunks.size)
            try {
                for (i in chunks.indices) {
                    if (!isActive) {
                        Log.d(TAG, "[TTS-DIAG] gen canceled at chunk $i/${chunks.size}")
                        return@launch
                    }
                    val tg = SystemClock.elapsedRealtime()
                    val cp = preparePlayer(engine, chunks[i], speechRate) ?: run {
                        Log.e(TAG, "[TTS-DIAG] gen failed at chunk $i — closing pipeline")
                        if (!trigger.isCompleted) trigger.completeExceptionally(RuntimeException("gen failed at chunk $i"))
                        channel.close(RuntimeException("gen failed at chunk $i"))
                        return@launch
                    }
                    genMs[i] = SystemClock.elapsedRealtime() - tg
                    playMs[i] = wavDurationMs(cp.wavFile)
                    diag.recordGen(i, genMs[i], playMs[i])
                    val sent = channel.trySend(cp).isSuccess
                    if (!sent) {
                        // Channel closed (consumer gone). Release and stop.
                        try { cp.mp.release() } catch (_: Exception) {}
                        cp.wavFile.delete()
                        return@launch
                    }

                    if (!trigger.isCompleted) {
                        val needed = computeAdaptiveBufN(
                            measuredGenMs = genMs,
                            measuredPlayMs = playMs,
                            measured = i + 1,
                            allChunkChars = chars,
                            safetyMargin = SAFETY_MARGIN,
                        )
                        diag.recordAdaptiveDecision(i + 1, needed)
                        if (i + 1 >= needed) {
                            val tFirst = SystemClock.elapsedRealtime() - t0
                            diag.tFirstMs = tFirst
                            diag.bufNUsed = needed
                            trigger.complete(tFirst)
                        }
                    }
                }
                if (!trigger.isCompleted) {
                    // Generated all chunks without ever triggering — that means even bufN=N is
                    // the projection. Fire now (zero-pause guaranteed since everything is ready).
                    val tFirst = SystemClock.elapsedRealtime() - t0
                    diag.tFirstMs = tFirst
                    diag.bufNUsed = chunks.size
                    trigger.complete(tFirst)
                }
                channel.close()
            } catch (e: Throwable) {
                if (!trigger.isCompleted) trigger.completeExceptionally(e)
                channel.close(e)
            }
        }
        return pipeline
    }

    private suspend fun playFromPipeline(
        pipeline: NarrationPipeline,
        onError: () -> Unit,
        onDone: () -> Unit,
    ) {
        var chunkIdx = 0
        var prevPlayEnd = SystemClock.elapsedRealtime()
        var first = true
        try {
            for (cp in pipeline.channel) {
                val receivedAt = SystemClock.elapsedRealtime()
                val pauseMs = if (first) 0L else maxOf(0L, receivedAt - prevPlayEnd)
                pipeline.diag.recordPause(chunkIdx, pauseMs)
                first = false

                val ok = playOne(cp.mp, cp.wavFile)
                if (!ok) {
                    withContext(Dispatchers.Main) { onError() }
                    return
                }
                prevPlayEnd = SystemClock.elapsedRealtime()
                chunkIdx++
            }
            withContext(Dispatchers.Main) { onDone() }
        } catch (e: Throwable) {
            Log.e(TAG, "playback error: ${e.message}")
            withContext(Dispatchers.Main) { onError() }
        }
    }

    private suspend fun playOne(mp: MediaPlayer, wavFile: File): Boolean = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { cont ->
            mp.setOnCompletionListener {
                wavFile.delete()
                mediaPlayer = null
                if (cont.isActive) cont.resume(true)
            }
            mp.setOnErrorListener { _, _, _ ->
                wavFile.delete()
                mediaPlayer = null
                if (cont.isActive) cont.resume(false)
                true
            }
            mediaPlayer?.release()
            mediaPlayer = mp
            val rate = currentPlayRate
            if (rate != 1.0f) {
                try { mp.playbackParams = android.media.PlaybackParams().setSpeed(rate.coerceAtLeast(0.1f)) } catch (_: Exception) {}
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

    private fun preparePlayer(engine: OfflineTts, chunk: String, speechRate: Float): ChunkPlayer? {
        val wavFile = File(context.cacheDir, "kokoro_${UUID.randomUUID()}.wav")
        return try {
            engine.generate(text = chunk, sid = voiceSid, speed = speechRate)
                .save(wavFile.absolutePath)
            val mp = MediaPlayer().also {
                it.setDataSource(wavFile.absolutePath)
                it.prepare()
            }
            ChunkPlayer(mp, wavFile)
        } catch (e: Exception) {
            Log.e(TAG, "preparePlayer error: ${e.message}")
            wavFile.delete()
            null
        }
    }

    /**
     * Returns the minimum bufN that yields zero predicted pauses across the full narration.
     *
     * For measured chunks we use observed gen/play ms. For unmeasured chunks we estimate
     * play time from char count (avg ms/char of measured) and gen time as projectedPlay ×
     * lifetimeRatio × safetyMargin. The safety margin compensates for thermal throttling
     * worsening over the rest of the narration — early "cool" chunks underestimate later
     * gen times, so we inflate the projection.
     */
    private fun computeAdaptiveBufN(
        measuredGenMs: LongArray,
        measuredPlayMs: LongArray,
        measured: Int,
        allChunkChars: IntArray,
        safetyMargin: Double,
    ): Int {
        val total = allChunkChars.size
        if (total == 0) return 1
        if (measured == 0) return total

        val ratios = (0 until measured).map { i ->
            if (measuredPlayMs[i] > 0) measuredGenMs[i].toDouble() / measuredPlayMs[i].toDouble() else 1.0
        }
        val avgRatio = ratios.average()

        val totalMeasuredPlay = (0 until measured).sumOf { measuredPlayMs[it] }
        val totalMeasuredChars = (0 until measured).sumOf { allChunkChars[it] }
        val playMsPerChar =
            if (totalMeasuredChars > 0) totalMeasuredPlay.toDouble() / totalMeasuredChars else DEFAULT_PLAY_MS_PER_CHAR

        val projPlayMs = LongArray(total) { i ->
            if (i < measured) measuredPlayMs[i] else (allChunkChars[i] * playMsPerChar).toLong()
        }
        val projGenMs = LongArray(total) { i ->
            if (i < measured) measuredGenMs[i] else (projPlayMs[i] * avgRatio * safetyMargin).toLong()
        }
        val projGenReady = LongArray(total)
        projGenReady[0] = projGenMs[0]
        for (i in 1 until total) projGenReady[i] = projGenReady[i - 1] + projGenMs[i]

        for (n in 1..total) {
            val tFirst = projGenReady[n - 1]
            var prevPlayEnd = tFirst + projPlayMs[0]
            var hasPause = false
            for (i in 1 until total) {
                if (projGenReady[i] > prevPlayEnd) { hasPause = true; break }
                prevPlayEnd = maxOf(prevPlayEnd, projGenReady[i]) + projPlayMs[i]
            }
            if (!hasPause) return n
        }
        return total
    }

    private fun wavDurationMs(file: File): Long = try {
        file.inputStream().use { s ->
            val h = ByteArray(44).also { s.read(it) }
            fun int32(off: Int) = ByteBuffer.wrap(h, off, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong()
            fun int16(off: Int) = ByteBuffer.wrap(h, off, 2).order(ByteOrder.LITTLE_ENDIAN).short.toLong()
            val rate = int32(24); val ch = int16(22); val bps = int16(34)
            val dataBytes = file.length() - 44
            if (rate <= 0 || ch <= 0 || bps <= 0) 0L
            else (dataBytes * 1000L) / (rate * ch * (bps / 8L))
        }
    } catch (_: Exception) { 0L }

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
        currentPlayRate = rate
        try {
            mediaPlayer?.playbackParams = android.media.PlaybackParams().setSpeed(rate.coerceAtLeast(0.1f))
        } catch (e: Exception) {
            Log.w(TAG, "setSpeed failed: ${e.message}")
        }
        // If the prewarmed pipeline was generated at a different rate, cancel it now rather
        // than discovering the mismatch later. It will be regenerated at the new rate when
        // speak() is called after the current narration ends.
        val pw = prewarmedPipeline
        if (pw != null && pw.speechRate != rate) {
            Log.i(TAG, "setSpeed: discarding prewarm (was ${pw.speechRate}×, now $rate×)")
            pw.cancel("speed changed during playback")
            prewarmedPipeline = null
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
        prewarmedPipeline?.cancel("engine stop")
        prewarmedPipeline = null
        isPaused = false
        val mp = mediaPlayer
        mediaPlayer = null
        try { mp?.stop() } catch (_: Exception) {}
        mp?.release()
    }

    override fun shutdown() {
        prewarmedPipeline?.cancel("shutdown")
        prewarmedPipeline = null
        engineScope.cancel()
        val mp = mediaPlayer
        mediaPlayer = null
        try { mp?.stop() } catch (_: Exception) {}
        mp?.release()
    }

    // ── Internal types ───────────────────────────────────────────────────────────────────

    private data class ChunkPlayer(val mp: MediaPlayer, val wavFile: File)

    private class NarrationPipeline(
        val text: String,
        val speechRate: Float,
        val channel: Channel<ChunkPlayer>,
        val readyTrigger: CompletableDeferred<Long>,
        val diag: TtsDiagnostics,
    ) {
        var genJob: Job? = null
        private var canceled = false

        fun cancel(reason: String) {
            if (canceled) return
            canceled = true
            genJob?.cancel()
            try { channel.close() } catch (_: Exception) {}
            // Drain leftover prepared players so we don't leak MediaPlayer + WAV files.
            while (true) {
                val r = channel.tryReceive().getOrNull() ?: break
                try { r.mp.release() } catch (_: Exception) {}
                r.wavFile.delete()
            }
            if (!readyTrigger.isCompleted) {
                readyTrigger.completeExceptionally(RuntimeException("pipeline canceled: $reason"))
            }
        }
    }

    private class TtsDiagnostics(
        val label: String,
        val textLen: Int,
        val chunkChars: List<Int>,
    ) {
        private val chunkCount = chunkChars.size
        var bufNUsed: Int = 0
        var tFirstMs: Long = 0
        var tWaitedForReadyMs: Long = 0
        private var consumedFromPrewarm: Boolean = false

        private val genMs = LongArray(chunkCount)
        private val playMs = LongArray(chunkCount)
        private val pauseMs = LongArray(chunkCount)
        private val adaptiveDecisions = mutableListOf<Pair<Int, Int>>()

        fun markConsumedFromPrewarm() { consumedFromPrewarm = true }

        fun recordGen(idx: Int, genMillis: Long, playMillis: Long) {
            if (idx in genMs.indices) { genMs[idx] = genMillis; playMs[idx] = playMillis }
        }

        fun recordAdaptiveDecision(measured: Int, needed: Int) {
            adaptiveDecisions += measured to needed
        }

        fun recordPause(idx: Int, pause: Long) {
            if (idx in pauseMs.indices) pauseMs[idx] = pause
        }

        fun emit() {
            val tag = "TTS-DIAG"
            val src = if (consumedFromPrewarm) "prewarmed→consumed" else label
            val maxPause = pauseMs.maxOrNull() ?: 0L
            val totalPause = pauseMs.sum()
            Log.i(TAG, "[$tag] ── narration end (source=$src) ──")
            Log.i(TAG, "[$tag] textLen=$textLen chunks=$chunkCount bufN=$bufNUsed T_first=${fmtS(tFirstMs)}s wait_before_play=${fmtS(tWaitedForReadyMs)}s")
            Log.i(TAG, "[$tag] adaptive: ${adaptiveDecisions.joinToString(", ") { "after${it.first}→need${it.second}" }}")
            Log.i(TAG, "[$tag] chars : ${chunkChars.joinToString(" ")}")
            Log.i(TAG, "[$tag] gen(s): ${genMs.joinToString(" ") { fmtS(it) }}")
            Log.i(TAG, "[$tag] play(s): ${playMs.joinToString(" ") { fmtS(it) }}")
            Log.i(TAG, "[$tag] pauses(s): ${pauseMs.joinToString(" ") { fmtS(it) }}  max=${fmtS(maxPause)}s total=${fmtS(totalPause)}s")
        }

        private fun fmtS(ms: Long) = "%.1f".format(ms / 1000f)
    }

    companion object {
        private const val TAG = "KokoroTtsEngine"
        const val DEFAULT_VOICE_ID = 0

        // Chunk size validated by TtsExperiment runs G–S on Samsung SM-F766U1 — 200 char
        // chunks with adaptive buffering produced zero mid-narration pauses.
        private const val MAX_CHUNK_CHARS = 200

        // Inflates the projected gen/play ratio for unmeasured chunks. Early-narration
        // chunks generate fast (cool CPU); later chunks slow down from thermal throttling,
        // so a backward-looking average is optimistic. 1.15 = assume future chunks run 15%
        // slower than the lifetime average so far.
        private const val SAFETY_MARGIN = 1.15

        // Fallback when no measured play data exists yet — average Kokoro play ms per char.
        private const val DEFAULT_PLAY_MS_PER_CHAR = 55.0

        const val VOICE_PREVIEW_TEMPLATE =
            "Hi, my name is %s. It would be my pleasure to be your tour guide today."
    }
}
