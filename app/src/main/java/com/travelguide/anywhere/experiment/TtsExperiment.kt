package com.travelguide.anywhere.experiment

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.BreakIterator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

private const val TAG = "TtsExperiment"

// ── Hard-coded test narration (real place, ~309 words, written as spoken prose) ──────────────
// Source: The Sixth Floor Museum at Dealey Plaza, Dallas TX — ~35 miles from Denton TX.
val EXPERIMENT_TEXT = """Standing here in north Texas, we are about thirty-five miles from one of the most significant and somber sites in American history. The Sixth Floor Museum at Dealey Plaza occupies the former Texas School Book Depository building in downtown Dallas, and it preserves the story of President John Fitzgerald Kennedy's assassination on November twenty-second, nineteen sixty-three.

That Friday afternoon, Kennedy's motorcade wound through cheering crowds along Elm Street below the building. Three shots shattered the air. The president slumped forward, fatally wounded. Within hours, the nation and the world would never be quite the same.

The building itself is a seven-story red brick warehouse built in nineteen oh one, completely ordinary by appearance. But the sixth floor corner window became the most scrutinized square footage in the world after investigators concluded that Lee Harvey Oswald fired from that position. The original window is preserved exactly as investigators documented it, with boxes stacked just as they were that day.

The museum opened in nineteen eighty-nine and draws over three hundred thousand visitors each year. The exhibits are remarkably balanced and thorough. You move through Kennedy's life and presidency, the climate of Cold War America, the motorcade route, and the chaotic hours afterward. Film footage, photographs, and artifacts from that era are presented alongside careful historical context.

What strikes most visitors is the view from the window itself. Looking down at the plaza, you can see the white X painted on Elm Street marking where Kennedy was struck. The geometry of the moment becomes viscerally real. Dealey Plaza below is smaller than most people imagine from photographs, almost intimate in scale.

Whether you accept the Warren Commission conclusions or find yourself drawn to the countless alternative theories, the museum respects your intelligence and presents the evidence without steering your conclusions. It is history at its most human and its most haunting."""

// ── Strategy definitions ──────────────────────────────────────────────────────────────────────
// bufferChunks = 99  → pre-gen ALL chunks before playback
// bufferChunks = -1  → adaptive: measure gen/play ratio from early chunks, compute minimum bufN
data class TtsStrategy(
    val id: String,
    val chunkChars: Int,
    val bufferChunks: Int,      // pre-generate this many chunks before playback (1 = pure stream)
    val bufferAudioSec: Float,  // OR buffer until this many seconds of audio ready (0 = use bufferChunks)
    val description: String
)

val STRATEGIES = listOf(
    TtsStrategy("M", 200, bufferChunks =  3, bufferAudioSec = 0f, "Buffer 3 chunks | 200-char (baseline = prev best)"),
    TtsStrategy("N", 200, bufferChunks =  4, bufferAudioSec = 0f, "Buffer 4 chunks | 200-char (key candidate)"),
    TtsStrategy("O", 200, bufferChunks =  5, bufferAudioSec = 0f, "Buffer 5 chunks | 200-char (safety margin)"),
    TtsStrategy("P", 150, bufferChunks =  4, bufferAudioSec = 0f, "Buffer 4 chunks | 150-char"),
    TtsStrategy("R", 200, bufferChunks = 99, bufferAudioSec = 0f, "Pre-gen ALL     | 200-char (zero-pause reference)"),
    TtsStrategy("S", 200, bufferChunks = -1, bufferAudioSec = 0f, "Adaptive buffer  | 200-char (auto-calibrate)"),
)

// Two rounds in different orders — detects thermal carry-over effects.
val ROUND_ORDERS = listOf(
    listOf("M", "N", "O", "P", "R", "S"),
    listOf("O", "S", "N", "R", "M", "P"),
)

// ── Result data classes ───────────────────────────────────────────────────────────────────────

data class ChunkMeasurement(
    val index: Int,
    val charCount: Int,
    val genMs: Long,
    val playMs: Long,   // estimated from WAV header, not actual playback
    val pauseMs: Long,  // how long playback would stall waiting for this chunk (0 = no stall)
    val thermalC: Float?
)

data class NarrationMeasurement(
    val narrationNumber: Int,  // 1 = first, 2 = second (pre-generated during narration 1 playback)
    val chunks: List<ChunkMeasurement>,
    val effectiveBufN: Int,           // actual chunks pre-buffered before playback starts
    val timeToFirstAudioMs: Long,     // wall-clock from start to when first audio could play
    val maxPauseMs: Long,
    val totalPauseMs: Long,
    val timeBetweenPreviousMs: Long,  // 0 for narration 1; gap after narration 1 ends before 2 starts
    val thermalStartC: Float?,
    val thermalEndC: Float?
)

data class StrategyRun(
    val strategy: TtsStrategy,
    val round: Int,
    val narrations: List<NarrationMeasurement>,
    val thermalDriftRatio: Float,  // (avg of last 3 chunk gen) / (first chunk gen)
    val cooledThermalC: Float?     // temperature after cooldown, before this run started
)

// ── Main experiment class ─────────────────────────────────────────────────────────────────────

class TtsExperiment(
    private val context: Context,
    private val modelDir: File,
    private val voiceSid: Int,
    private val onProgress: (percent: Int, status: String) -> Unit
) {

    private val thermalPaths = listOf(
        "/sys/class/thermal/thermal_zone0/temp",
        "/sys/class/thermal/thermal_zone1/temp",
        "/sys/class/thermal/thermal_zone4/temp",
        "/sys/class/thermal/thermal_zone7/temp",
        "/sys/class/thermal/thermal_zone10/temp",
    )
    private var goodThermalPath: String? = null

    private fun readThermalC(): Float? {
        if (goodThermalPath == null) {
            for (path in thermalPaths) {
                val v = readThermalPath(path)
                if (v != null && v in 20f..90f) { goodThermalPath = path; return v }
            }
            return null
        }
        return readThermalPath(goodThermalPath!!)
    }

    private fun readThermalPath(path: String): Float? = try {
        val raw = File(path).readText().trim().toLong()
        if (raw > 1000) raw / 1000f else raw.toFloat()
    } catch (_: Exception) { null }

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

    private fun splitSentences(text: String): List<String> {
        val bi = BreakIterator.getSentenceInstance(Locale.US)
        bi.setText(text)
        val out = mutableListOf<String>()
        var start = bi.first(); var end = bi.next()
        while (end != BreakIterator.DONE) {
            text.substring(start, end).trim().takeIf { it.isNotBlank() }?.let { out += it }
            start = end; end = bi.next()
        }
        return out
    }

    private fun splitIntoChunks(text: String, maxChars: Int): List<String> {
        val sentences = splitSentences(text)
        val chunks = mutableListOf<String>()
        val buf = StringBuilder()
        for (s in sentences) {
            if (buf.isNotEmpty() && buf.length + s.length + 1 > maxChars) {
                chunks += buf.toString().trim(); buf.clear()
            }
            if (buf.isNotEmpty()) buf.append(' ')
            buf.append(s)
        }
        if (buf.isNotBlank()) chunks += buf.toString().trim()
        return chunks.ifEmpty { listOf(text) }
    }

    // ── Adaptive buffer sizing ────────────────────────────────────────────────────────────────
    // Uses measured gen/play ratios from the first `measured` chunks to project remaining chunk
    // gen times, then simulates the timing model. Returns the minimum bufN that predicts zero
    // mid-narration pauses given the current thermal state.
    private fun computeAdaptiveBufN(
        genMs: LongArray,
        playMs: LongArray,
        measured: Int       // how many chunks have been measured so far
    ): Int {
        val chunkCount = genMs.size
        if (measured == 0) return 1

        // Average gen/play ratio from measured chunks (proxy for thermal load).
        val avgRatio = (0 until measured).map { i ->
            if (playMs[i] > 0) genMs[i].toDouble() / playMs[i].toDouble() else 1.0
        }.average()

        // Project remaining chunk gen times using avgRatio × their play durations.
        val projGenMs = LongArray(chunkCount) { i ->
            if (i < measured) genMs[i] else (playMs[i] * avgRatio).toLong()
        }

        val projGenReady = LongArray(chunkCount)
        projGenReady[0] = projGenMs[0]
        for (i in 1 until chunkCount) { projGenReady[i] = projGenReady[i - 1] + projGenMs[i] }

        // Find smallest bufN that yields zero pauses in the projected timing model.
        for (n in 1..chunkCount) {
            val tFirst = projGenReady[n - 1]
            var prevPlayEnd = tFirst + playMs[0]
            var wouldHavePause = false
            for (i in 1 until chunkCount) {
                if (projGenReady[i] > prevPlayEnd) { wouldHavePause = true; break }
                prevPlayEnd = maxOf(prevPlayEnd, projGenReady[i]) + playMs[i]
            }
            if (!wouldHavePause) return n
        }
        return chunkCount
    }

    // ── Core runner ───────────────────────────────────────────────────────────────────────────

    suspend fun run(): List<StrategyRun> = withContext(Dispatchers.Default) {
        val tempFiles = mutableListOf<File>()
        val tts = initTts()

        val totalChunks = ROUND_ORDERS.sumOf { order ->
            order.sumOf { id ->
                val s = STRATEGIES.first { it.id == id }
                splitIntoChunks(EXPERIMENT_TEXT, s.chunkChars).size * 2  // 2 narrations
            }
        }
        var completedChunks = 0

        fun progress(status: String) {
            val pct = ((completedChunks.toFloat() / totalChunks) * 100).roundToInt().coerceIn(0, 99)
            onProgress(pct, status)
        }

        val results = mutableListOf<StrategyRun>()

        try {
            for ((roundIdx, order) in ROUND_ORDERS.withIndex()) {
                val round = roundIdx + 1

                if (roundIdx > 0) {
                    progress("⏳ Cooling down 60s between rounds...")
                    delay(60_000L)
                }

                for ((idx, stratId) in order.withIndex()) {
                    if (!coroutineContext.isActive) return@withContext results

                    val strat = STRATEGIES.first { it.id == stratId }

                    if (idx > 0) {
                        progress("⏳ Cooling down 20s before strategy ${strat.id}...")
                        delay(20_000L)
                    }

                    val cooledTemp = readThermalC()
                    progress("Round $round · Strategy ${strat.id}: ${strat.description}")

                    val narResults = mutableListOf<NarrationMeasurement>()
                    val chunks = splitIntoChunks(EXPERIMENT_TEXT, strat.chunkChars)

                    // narration 1 = fresh; narration 2 = simulates pre-warm during narration 1.
                    var narrationPlayWindowMs = 0L

                    for (narNum in 1..2) {
                        val thermalStart = readThermalC()
                        val chunkResults = mutableListOf<ChunkMeasurement>()
                        val tempFilesThisNar = mutableListOf<File>()

                        val genMs = LongArray(chunks.size)
                        val playMs = LongArray(chunks.size)
                        val thermals = arrayOfNulls<Float>(chunks.size)

                        for (i in chunks.indices) {
                            if (!coroutineContext.isActive) return@withContext results

                            val wavFile = File(context.cacheDir, "exp_${UUID.randomUUID()}.wav")
                            tempFilesThisNar += wavFile
                            tempFiles += wavFile

                            val t0 = SystemClock.elapsedRealtime()
                            tts.generate(text = chunks[i], sid = voiceSid, speed = 1.0f)
                                .save(wavFile.absolutePath)
                            genMs[i] = SystemClock.elapsedRealtime() - t0
                            playMs[i] = wavDurationMs(wavFile)
                            thermals[i] = readThermalC()

                            completedChunks++
                            progress("Round $round · ${strat.id} · Nar $narNum · Chunk ${i + 1}/${chunks.size} (${genMs[i]}ms)")
                        }

                        // ── Timing model ─────────────────────────────────────────────────────
                        // Assumes concurrent generation + playback: generation thread runs serially
                        // from T=0; playback thread starts after N chunks are buffered.
                        // genReady[i] = cumulative gen time — when chunk i is available.
                        val genReady = LongArray(chunks.size)
                        genReady[0] = genMs[0]
                        for (i in 1 until chunks.size) { genReady[i] = genReady[i - 1] + genMs[i] }

                        val effectiveBufN: Int = when {
                            strat.bufferAudioSec > 0f -> {
                                val threshMs = (strat.bufferAudioSec * 1000f).toLong()
                                var audioAccum = 0L
                                var n = 0
                                while (n < chunks.size && audioAccum < threshMs) {
                                    audioAccum += playMs[n]; n++
                                }
                                n.coerceIn(1, chunks.size)
                            }
                            strat.bufferChunks == -1 ->
                                // Adaptive: use all measured gen/play data to project minimum safe bufN.
                                computeAdaptiveBufN(genMs, playMs, measured = chunks.size)
                            else -> strat.bufferChunks.coerceIn(1, chunks.size)
                        }

                        val timeToFirstAudio = genReady[effectiveBufN - 1]
                        val pauses = LongArray(chunks.size)
                        val playStart = LongArray(chunks.size)
                        val playEnd = LongArray(chunks.size)

                        playStart[0] = timeToFirstAudio
                        playEnd[0] = playStart[0] + playMs[0]
                        for (i in 1 until chunks.size) {
                            pauses[i] = maxOf(0L, genReady[i] - playEnd[i - 1])
                            playStart[i] = maxOf(playEnd[i - 1], genReady[i])
                            playEnd[i] = playStart[i] + playMs[i]
                        }

                        val narPlayWindowMs = if (chunks.isEmpty()) 0L else playEnd.last() - playStart[0]
                        val totalPauseMs = pauses.sum()
                        val maxPauseMs = pauses.max()

                        // T_between: nar2 pre-gen starts when nar1 begins playing.
                        // T_between = max(0, time until nar2 bufferN ready - nar1 play window).
                        val timeBetween = if (narNum == 1) {
                            narrationPlayWindowMs = narPlayWindowMs
                            0L
                        } else {
                            maxOf(0L, genReady[effectiveBufN - 1] - narrationPlayWindowMs)
                        }

                        for (i in chunks.indices) {
                            chunkResults += ChunkMeasurement(
                                index = i,
                                charCount = chunks[i].length,
                                genMs = genMs[i],
                                playMs = playMs[i],
                                pauseMs = pauses[i],
                                thermalC = thermals[i]
                            )
                        }

                        val thermalEnd = readThermalC()
                        narResults += NarrationMeasurement(
                            narrationNumber = narNum,
                            chunks = chunkResults,
                            effectiveBufN = effectiveBufN,
                            timeToFirstAudioMs = timeToFirstAudio,
                            maxPauseMs = maxPauseMs,
                            totalPauseMs = totalPauseMs,
                            timeBetweenPreviousMs = timeBetween,
                            thermalStartC = thermalStart,
                            thermalEndC = thermalEnd
                        )

                        tempFilesThisNar.forEach { it.delete() }
                    }

                    val allGenTimes = narResults.flatMap { n -> n.chunks.map { it.genMs } }
                    val firstGen = allGenTimes.firstOrNull()?.toFloat() ?: 1f
                    val last3Avg = allGenTimes.takeLast(3).average().toFloat()
                    val drift = if (firstGen > 0) last3Avg / firstGen else 1f

                    results += StrategyRun(
                        strategy = strat,
                        round = round,
                        narrations = narResults,
                        thermalDriftRatio = drift,
                        cooledThermalC = cooledTemp
                    )
                }
            }
        } finally {
            tempFiles.forEach { runCatching { it.delete() } }
        }

        onProgress(100, "Done!")
        results
    }

    private fun initTts(): OfflineTts = OfflineTts(
        config = OfflineTtsConfig(
            model = OfflineTtsModelConfig(
                kokoro = OfflineTtsKokoroModelConfig(
                    model = File(modelDir, "model.onnx").absolutePath,
                    voices = File(modelDir, "voices.bin").absolutePath,
                    tokens = File(modelDir, "tokens.txt").absolutePath,
                    dataDir = File(modelDir, "espeak-ng-data").absolutePath,
                    lang = "en-us"
                ),
                numThreads = 1, debug = false, provider = "cpu"
            )
        )
    )

    // ── Results formatter ─────────────────────────────────────────────────────────────────────

    fun formatResults(runs: List<StrategyRun>): String {
        val sb = StringBuilder()
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        sb.appendLine("═══════════════════════════════════════════════════════════")
        sb.appendLine("  TRAVEL GUIDE — TTS ZERO-PAUSE EXPERIMENT RESULTS")
        sb.appendLine("═══════════════════════════════════════════════════════════")
        sb.appendLine("Date          : $ts")
        sb.appendLine("Android       : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Device        : ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Kokoro voice  : SID $voiceSid")
        sb.appendLine("Test text     : ${EXPERIMENT_TEXT.split("\\s+".toRegex()).size} words / ${EXPERIMENT_TEXT.length} chars")
        sb.appendLine()
        sb.appendLine("CONTEXT")
        sb.appendLine("  Previous experiment (G-L) showed buffer-3/200-char (strategy I) was best at")
        sb.appendLine("  MaxPause=5.6s, but one pause remained per narration at the 213-char penultimate")
        sb.appendLine("  chunk. Math from that data predicts buffer-4 eliminates all pauses: at ~44s")
        sb.appendLine("  T_first the playback thread stays ~7s ahead of the gen thread through chunk 11.")
        sb.appendLine()
        sb.appendLine("PRIORITY: MaxPause = 0. T_first and T_between are accepted costs.")
        sb.appendLine()
        sb.appendLine("STRATEGIES")
        STRATEGIES.forEach { s ->
            val bufDesc = when {
                s.bufferAudioSec > 0f -> "buffer ${s.bufferAudioSec}s audio before playback"
                s.bufferChunks == -1  -> "adaptive: compute bufN from measured gen/play ratio"
                s.bufferChunks >= 99  -> "pre-generate ALL chunks before playback"
                else -> "buffer ${s.bufferChunks} chunk(s) before playback"
            }
            sb.appendLine("  ${s.id}: ${s.description}  [$bufDesc]")
        }
        sb.appendLine()
        sb.appendLine("NOTE: Timing model assumes concurrent generation + playback (background thread).")
        sb.appendLine("      'Pause' = how long playback would stall waiting for next chunk.")
        sb.appendLine("      'T_between' does NOT include Claude API latency (~5-15s real overhead).")
        sb.appendLine("      'bufN' = effective buffer count used for that narration.")
        sb.appendLine()

        val rounds = runs.groupBy { it.round }
        for ((roundNum, roundRuns) in rounds.entries.sortedBy { it.key }) {
            sb.appendLine("═══════════════════════════════════════════════════════════")
            sb.appendLine("  ROUND $roundNum")
            sb.appendLine("═══════════════════════════════════════════════════════════")
            sb.appendLine()

            for (run in roundRuns) {
                sb.appendLine("┌─ Strategy ${run.strategy.id}: ${run.strategy.description}")
                run.cooledThermalC?.let { sb.appendLine("│  Temp after cooldown: ${"%.1f".format(it)}°C") }

                for (nar in run.narrations) {
                    val label = if (nar.narrationNumber == 1) "Narration 1 (fresh)" else "Narration 2 (prewarm)"
                    sb.appendLine("│")
                    sb.appendLine("│  ── $label  [bufN=${nar.effectiveBufN}] ──")
                    nar.thermalStartC?.let {
                        sb.appendLine("│     Thermal: ${"%.1f".format(it)}°C → ${nar.thermalEndC?.let { e -> "%.1f".format(e) + "°C" } ?: "?"}")
                    }
                    sb.appendLine("│     Chunks  : ${nar.chunks.size} (chars: ${nar.chunks.joinToString(" ") { it.charCount.toString() }})")
                    sb.appendLine("│     Gen(s)  : ${nar.chunks.joinToString(" ") { "%.1f".format(it.genMs / 1000f) }}")
                    sb.appendLine("│     Play(s) : ${nar.chunks.joinToString(" ") { "%.1f".format(it.playMs / 1000f) }}")
                    sb.appendLine("│     Thermal : ${nar.chunks.joinToString(" ") { it.thermalC?.let { t -> "%.0f".format(t) } ?: "?" }}")
                    if (nar.narrationNumber == 1) {
                        sb.appendLine("│     ⏱ T_first (wait before audio): ${"%.1f".format(nar.timeToFirstAudioMs / 1000f)}s")
                    } else {
                        sb.appendLine("│     ⏱ T_between (gap after nar 1): ${"%.1f".format(nar.timeBetweenPreviousMs / 1000f)}s")
                    }
                    if (nar.maxPauseMs > 0) {
                        sb.appendLine("│     ⚠ Pauses(s): ${nar.chunks.joinToString(" ") { "%.1f".format(it.pauseMs / 1000f) }}  [max: ${"%.1f".format(nar.maxPauseMs / 1000f)}s]")
                    } else {
                        sb.appendLine("│     ✓ No mid-narration pauses")
                    }
                }

                sb.appendLine("│")
                sb.appendLine("│  Thermal drift: ${"%.2f".format(run.thermalDriftRatio)}× (>1.30 = throttling)")
                sb.appendLine("└─────────────────────────────────────────────────────")
                sb.appendLine()
            }
        }

        sb.appendLine("═══════════════════════════════════════════════════════════")
        sb.appendLine("  SUMMARY TABLE")
        sb.appendLine("═══════════════════════════════════════════════════════════")
        sb.appendLine()

        val header = "Strat | T_first(s)       | MaxPause(s)      | T_between(s)     | Drift"
        val sep    = "------+------------------+------------------+------------------+-------"
        sb.appendLine(header)
        sb.appendLine(sep)

        val stratIds = STRATEGIES.map { it.id }
        for (id in stratIds) {
            val stratRuns = runs.filter { it.strategy.id == id }
            if (stratRuns.isEmpty()) continue

            fun colVal(extract: (StrategyRun) -> String): String {
                return stratRuns.joinToString(" / ") { extract(it) }.padEnd(16)
            }

            val tFirst = colVal { r -> r.narrations.firstOrNull()?.timeToFirstAudioMs?.let { "%.1f".format(it / 1000f) } ?: "?" }
            val maxP   = colVal { r -> r.narrations.maxOfOrNull { n -> n.maxPauseMs }?.let { "%.1f".format(it / 1000f) } ?: "?" }
            val tBet   = colVal { r -> r.narrations.drop(1).firstOrNull()?.timeBetweenPreviousMs?.let { "%.1f".format(it / 1000f) } ?: "?" }
            val drift  = stratRuns.joinToString(" / ") { "%.2f".format(it.thermalDriftRatio) }
            sb.appendLine("  $id   | $tFirst | $maxP | $tBet | $drift")
        }

        sb.appendLine()
        sb.appendLine("Columns show R1 / R2 values.")
        sb.appendLine()

        sb.appendLine("═══════════════════════════════════════════════════════════")
        sb.appendLine("  WEIGHTED SCORE  (lower = better)")
        sb.appendLine("  PRIORITY — MaxPause weight: 10   T_first weight: 1   T_between weight: 1")
        sb.appendLine("  Strategies with MaxPause=0 rank purely by T_first.")
        sb.appendLine("═══════════════════════════════════════════════════════════")
        sb.appendLine()

        data class Score(val id: String, val score: Float, val tFirst: Float, val maxPause: Float, val tBetween: Float)
        val scores = mutableListOf<Score>()

        for (id in stratIds) {
            val stratRuns = runs.filter { it.strategy.id == id }
            if (stratRuns.isEmpty()) continue
            val avgTFirst   = stratRuns.mapNotNull { it.narrations.firstOrNull()?.timeToFirstAudioMs?.toFloat() }.average().toFloat() / 1000f
            val avgMaxPause = stratRuns.mapNotNull { it.narrations.maxOfOrNull { n -> n.maxPauseMs.toFloat() } }.average().toFloat() / 1000f
            val avgTBetween = stratRuns.mapNotNull { it.narrations.drop(1).firstOrNull()?.timeBetweenPreviousMs?.toFloat() }.average().toFloat() / 1000f
            val weighted = avgMaxPause * 10f + avgTFirst * 1f + avgTBetween * 1f
            scores += Score(id, weighted, avgTFirst, avgMaxPause, avgTBetween)
        }

        scores.sortBy { it.score }
        for ((rank, s) in scores.withIndex()) {
            val pauseLabel = if (s.maxPause == 0f) "✓ ZERO pauses" else "⚠ MaxPause=${\"%.1f\".format(s.maxPause)}s"
            val label = if (rank == 0) "★ BEST" else "  ${rank + 1}   "
            sb.appendLine("$label  ${s.id}: score=${"%.1f".format(s.score)}  $pauseLabel  T_first=${"%.1f".format(s.tFirst)}s  T_between=${"%.1f".format(s.tBetween)}s")
        }

        sb.appendLine()
        sb.appendLine("═══════════════════════════════════════════════════════════")
        sb.appendLine("END OF REPORT")
        sb.appendLine("═══════════════════════════════════════════════════════════")

        return sb.toString()
    }
}
