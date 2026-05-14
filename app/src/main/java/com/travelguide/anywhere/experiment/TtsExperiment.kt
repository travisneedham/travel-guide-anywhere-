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

// ── Hard-coded test narration (real place, ~309 words, written as spoken prose) ────────────────────
// Source: The Sixth Floor Museum at Dealey Plaza, Dallas TX — ~35 miles from Denton TX.
// This text was crafted to match Claude's touring style: years written out, no markdown,
// no abbreviations that would confuse the sentence splitter or TTS phonemizer.
val EXPERIMENT_TEXT = """Standing here in north Texas, we are about thirty-five miles from one of the most significant and somber sites in American history. The Sixth Floor Museum at Dealey Plaza occupies the former Texas School Book Depository building in downtown Dallas, and it preserves the story of President John Fitzgerald Kennedy's assassination on November twenty-second, nineteen sixty-three.

That Friday afternoon, Kennedy's motorcade wound through cheering crowds along Elm Street below the building. Three shots shattered the air. The president slumped forward, fatally wounded. Within hours, the nation and the world would never be quite the same.

The building itself is a seven-story red brick warehouse built in nineteen oh one, completely ordinary by appearance. But the sixth floor corner window became the most scrutinized square footage in the world after investigators concluded that Lee Harvey Oswald fired from that position. The original window is preserved exactly as investigators documented it, with boxes stacked just as they were that day.

The museum opened in nineteen eighty-nine and draws over three hundred thousand visitors each year. The exhibits are remarkably balanced and thorough. You move through Kennedy's life and presidency, the climate of Cold War America, the motorcade route, and the chaotic hours afterward. Film footage, photographs, and artifacts from that era are presented alongside careful historical context.

What strikes most visitors is the view from the window itself. Looking down at the plaza, you can see the white X painted on Elm Street marking where Kennedy was struck. The geometry of the moment becomes viscerally real. Dealey Plaza below is smaller than most people imagine from photographs, almost intimate in scale.

Whether you accept the Warren Commission conclusions or find yourself drawn to the countless alternative theories, the museum respects your intelligence and presents the evidence without steering your conclusions. It is history at its most human and its most haunting."""

// ── Strategy definitions ─────────────────────────────────────────────────────────────────────────────────────────
data class TtsStrategy(
    val id: String,
    val chunkChars: Int,
    val preGenAll: Boolean,       // true = generate ALL chunks before playing (no streaming)
    val thermalBackoff: Boolean,  // sleep 5s between chunks when throttling detected
    val description: String
)

val STRATEGIES = listOf(
    TtsStrategy("A", 400, preGenAll = true,  thermalBackoff = false, "Pre-gen ALL  | 400-char chunks"),
    TtsStrategy("B", 200, preGenAll = true,  thermalBackoff = false, "Pre-gen ALL  | 200-char chunks"),
    TtsStrategy("C", 400, preGenAll = false, thermalBackoff = false, "Stream ch1+  | 400-char chunks"),
    TtsStrategy("D", 200, preGenAll = false, thermalBackoff = false, "Stream ch1+  | 200-char chunks"),
    TtsStrategy("E", 100, preGenAll = false, thermalBackoff = false, "Stream ch1+  | 100-char chunks"),
    TtsStrategy("F", 200, preGenAll = false, thermalBackoff = true,  "Stream+cool  | 200-char chunks"),
)

// Two rounds in different orders — lets us see thermal carry-over.
val ROUND_ORDERS = listOf(
    listOf("A", "B", "C", "D", "E", "F"),  // Round 1: sequential by ID
    listOf("D", "F", "B", "C", "A", "E"),  // Round 2: shuffled
)

// ── Result data classes ────────────────────────────────────────────────────────────────────────────────────────

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
    val timeToFirstAudioMs: Long,   // wall-clock from start to when first audio could play
    val maxPauseMs: Long,
    val totalPauseMs: Long,
    val timeBetweenPreviousMs: Long, // 0 for narration 1; gap after narration 1 ends before 2 starts
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

// ── Main experiment class ─────────────────────────────────────────────────────────────────────────────────────

class TtsExperiment(
    private val context: Context,
    private val modelDir: File,
    private val voiceSid: Int,
    private val onProgress: (percent: Int, status: String) -> Unit
) {

    // Thermal sensor: probe multiple paths, remember the one that gives reasonable readings.
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

    // Parse WAV header to get play duration in ms — avoids actual audio playback.
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

    // Better sentence splitter using Android's BreakIterator — handles "Dr.", "J.F.K.", etc.
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

    // ── Core runner ─────────────────────────────────────────────────────────────────────────────────────

    suspend fun run(): List<StrategyRun> = withContext(Dispatchers.Default) {
        val tempFiles = mutableListOf<File>()
        val tts = initTts()

        // Count total chunk generations to drive progress bar.
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

                    // Run 2 narrations: narration 1 is fresh; narration 2 simulates prewarm.
                    var narrationPlayWindowMs = 0L  // set after narration 1

                    for (narNum in 1..2) {
                        val thermalStart = readThermalC()
                        val chunkResults = mutableListOf<ChunkMeasurement>()
                        val tempFilesThisNar = mutableListOf<File>()

                        // ── Generate all chunks sequentially (measuring real gen times) ──
                        val genMs = LongArray(chunks.size)
                        val playMs = LongArray(chunks.size)
                        val thermals = arrayOfNulls<Float>(chunks.size)

                        var firstChunkGenMs = 0L

                        for (i in chunks.indices) {
                            if (!coroutineContext.isActive) return@withContext results

                            // Thermal backoff for strategy F: if throttling detected, sleep 5s.
                            if (strat.thermalBackoff && i >= 2 && firstChunkGenMs > 0) {
                                val recent = genMs.slice(maxOf(0, i - 2) until i).average()
                                if (recent > firstChunkGenMs * 1.30) {
                                    Log.d(TAG, "Thermal backoff triggered at chunk $i (gen ${recent.roundToInt()}ms vs first ${firstChunkGenMs}ms)")
                                    delay(5_000L)
                                }
                            }

                            val wavFile = File(context.cacheDir, "exp_${UUID.randomUUID()}.wav")
                            tempFilesThisNar += wavFile
                            tempFiles += wavFile

                            val t0 = SystemClock.elapsedRealtime()
                            tts.generate(text = chunks[i], sid = voiceSid, speed = 1.0f)
                                .save(wavFile.absolutePath)
                            genMs[i] = SystemClock.elapsedRealtime() - t0
                            playMs[i] = wavDurationMs(wavFile)
                            thermals[i] = readThermalC()

                            if (i == 0) firstChunkGenMs = genMs[i]

                            completedChunks++
                            progress("Round $round · ${strat.id} · Nar $narNum · Chunk ${i + 1}/${chunks.size} (${genMs[i]}ms)")
                        }

                        // ── Compute timing model ─────────────────────────────────────────────────────
                        // genReady[i] = wall-clock time (relative to gen start) when chunk i is ready.
                        // For streaming: generation of chunks 1..N starts when chunk 0 starts playing
                        //   → genReady[i] = genMs[0] + genMs[1] + ... + genMs[i]
                        // For pre-gen: all chunks generated before any playback.
                        //   → genReady[i] = genMs[0] + ... + genMs[i]   (same formula, different T_first)

                        val genReady = LongArray(chunks.size)
                        genReady[0] = genMs[0]
                        var backoffAccumulated = 0L
                        for (i in 1 until chunks.size) {
                            // For strategy F: if we added backoff before generating chunk i,
                            // it's already reflected in elapsed wall-clock time from our delay() calls.
                            // But since we measured genMs sequentially, we need to add back the
                            // conceptual "parallel" offset: backoff delays shift later chunks' ready times.
                            genReady[i] = genReady[i - 1] + genMs[i]
                        }

                        val timeToFirstAudio: Long
                        val pauses = LongArray(chunks.size)  // pauses[0] always 0
                        val playStart = LongArray(chunks.size)
                        val playEnd = LongArray(chunks.size)

                        if (strat.preGenAll) {
                            // T_first = all chunks generated.
                            timeToFirstAudio = genReady.last()
                            playStart[0] = timeToFirstAudio
                            playEnd[0] = playStart[0] + playMs[0]
                            for (i in 1 until chunks.size) {
                                pauses[i] = 0L  // all pre-generated, no stalls
                                playStart[i] = playEnd[i - 1]
                                playEnd[i] = playStart[i] + playMs[i]
                            }
                        } else {
                            // Streaming: T_first = chunk 0 gen time only.
                            // Concurrent gen of chunks 1..N starts at T_first.
                            // genReady[i] (from T=0) = genMs[0] + genMs[1] + ... + genMs[i]
                            timeToFirstAudio = genMs[0]
                            playStart[0] = timeToFirstAudio
                            playEnd[0] = playStart[0] + playMs[0]
                            for (i in 1 until chunks.size) {
                                // Chunk i is ready at genReady[i] (absolute from T=0).
                                val chunkReadyAbsolute = genReady[i]
                                pauses[i] = maxOf(0L, chunkReadyAbsolute - playEnd[i - 1])
                                playStart[i] = maxOf(playEnd[i - 1], chunkReadyAbsolute)
                                playEnd[i] = playStart[i] + playMs[i]
                            }
                        }

                        val narPlayWindowMs = if (chunks.isEmpty()) 0L else playEnd.last() - playStart[0]
                        val totalPauseMs = pauses.sum()
                        val maxPauseMs = pauses.max()

                        // T_between: how long we'd wait between narration 1 ending and narration 2 starting.
                        // Prewarm starts when narration 1 begins playing. Window = narPlayWindowMs.
                        val timeBetween = if (narNum == 1) {
                            narrationPlayWindowMs = narPlayWindowMs
                            0L
                        } else {
                            val nar2TotalGen = genMs.sum()
                            maxOf(0L, nar2TotalGen - narrationPlayWindowMs)
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
                            timeToFirstAudioMs = timeToFirstAudio,
                            maxPauseMs = maxPauseMs,
                            totalPauseMs = totalPauseMs,
                            timeBetweenPreviousMs = timeBetween,
                            thermalStartC = thermalStart,
                            thermalEndC = thermalEnd
                        )

                        // Clean up WAV files immediately to free storage.
                        tempFilesThisNar.forEach { it.delete() }
                    }

                    // Thermal drift: ratio of last-3 avg gen time to first chunk gen time.
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

    // ── Results formatter ────────────────────────────────────────────────────────────────────────────────────

    fun formatResults(runs: List<StrategyRun>): String {
        val sb = StringBuilder()
        val ts = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

        sb.appendLine("═══════════════════════════════════════════════════════════")
        sb.appendLine("  TRAVEL GUIDE — TTS STRATEGY EXPERIMENT RESULTS")
        sb.appendLine("═══════════════════════════════════════════════════════════")
        sb.appendLine("Date          : $ts")
        sb.appendLine("Android       : ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        sb.appendLine("Device        : ${Build.MANUFACTURER} ${Build.MODEL}")
        sb.appendLine("Kokoro voice  : SID $voiceSid")
        sb.appendLine("Test text     : ${EXPERIMENT_TEXT.split("\\s+".toRegex()).size} words / ${EXPERIMENT_TEXT.length} chars")
        sb.appendLine()
        sb.appendLine("STRATEGIES")
        STRATEGIES.forEach { sb.appendLine("  ${it.id}: ${it.description}") }
        sb.appendLine()
        sb.appendLine("NOTE: Timing model simulates concurrent generation mathematically.")
        sb.appendLine("      'Pause' = how long playback would stall waiting for next chunk.")
        sb.appendLine("      'T_between' does NOT include Claude API latency (~5-15s real overhead).")
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
                    sb.appendLine("│  ── $label ──")
                    nar.thermalStartC?.let { sb.appendLine("│     Thermal: ${"%.1f".format(it)}°C → ${nar.thermalEndC?.let { e -> "%.1f".format(e) + "°C" } ?: "?"}") }
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

        // Summary table
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

        // Scoring
        sb.appendLine("═══════════════════════════════════════════════════════════")
        sb.appendLine("  WEIGHTED SCORE  (lower = better)")
        sb.appendLine("  T_first weight: 1   MaxPause weight: 3   T_between weight: 2")
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
            val weighted = avgTFirst * 1f + avgMaxPause * 3f + avgTBetween * 2f
            scores += Score(id, weighted, avgTFirst, avgMaxPause, avgTBetween)
        }

        scores.sortBy { it.score }
        for ((rank, s) in scores.withIndex()) {
            val label = if (rank == 0) "★ BEST" else "  ${rank + 1}   "
            sb.appendLine("$label  ${s.id}: score=${"%.1f".format(s.score)}  (T_first=${"%.1f".format(s.tFirst)}s  MaxPause=${"%.1f".format(s.maxPause)}s  T_between=${"%.1f".format(s.tBetween)}s)")
        }

        sb.appendLine()
        sb.appendLine("═══════════════════════════════════════════════════════════")
        sb.appendLine("END OF REPORT")
        sb.appendLine("═══════════════════════════════════════════════════════════")

        return sb.toString()
    }
}
