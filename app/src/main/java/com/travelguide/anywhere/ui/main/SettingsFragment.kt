package com.travelguide.anywhere.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.net.Uri
import android.provider.MediaStore
import android.view.WindowManager
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.travelguide.anywhere.BuildConfig
import com.travelguide.anywhere.R
import com.travelguide.anywhere.data.remote.ClaudeApiService
import com.travelguide.anywhere.databinding.FragmentSettingsBinding
import com.travelguide.anywhere.data.local.NarrationHistoryStore
import com.travelguide.anywhere.repository.NarrationRepository
import com.travelguide.anywhere.service.KokoroDownloadService
import com.travelguide.anywhere.service.KokoroModelManager
import com.travelguide.anywhere.service.PiperModelManager
import com.travelguide.anywhere.service.PiperTtsEngine
import com.travelguide.anywhere.service.PiperVoice
import com.travelguide.anywhere.service.PiperVoices
import com.travelguide.anywhere.service.TourGuideService
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.travelguide.anywhere.experiment.TtsExperiment
import com.travelguide.anywhere.service.KokoroTtsEngine
import com.travelguide.anywhere.service.TourState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    @Inject lateinit var prefs: SharedPreferences
    @Inject lateinit var kokoroModelManager: KokoroModelManager
    @Inject lateinit var piperModelManager: PiperModelManager

    private val viewModel: MainViewModel by activityViewModels()

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    // Voice preview — single OfflineTts instance shared across all previews.
    private var previewTts: OfflineTts? = null
    private var previewJob: Job? = null
    private var previewPlayer: android.media.MediaPlayer? = null
    private var lastPreviewSid: Int = -1
    private var lastPreviewName: String = ""

    // Piper preview state
    private var piperPreviewTts: OfflineTts? = null
    private var piperPreviewTtsVoiceId: String? = null  // which voice piperPreviewTts is loaded for
    private var lastPiperPreviewVoice: PiperVoice? = null
    private var pendingPiperPreview: String? = null  // play preview once download for this voice finishes
    private var piperStateJob: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        setupCollapsibleSections()
        loadApiKey()
        loadVoiceSettings()
        loadPrompts()
        setupDiagnostics()
        setupExperiment()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }
    }

    private fun setupCollapsibleSections() {
        fun toggle(body: View, indicator: android.widget.TextView) {
            val expanding = body.visibility == View.GONE
            body.visibility = if (expanding) View.VISIBLE else View.GONE
            indicator.text = if (expanding) "▼" else "▶"
        }
        binding.headerApi.setOnClickListener { toggle(binding.bodyApi, binding.indApi) }
        binding.headerVoice.setOnClickListener { toggle(binding.bodyVoice, binding.indVoice) }
        binding.headerPrompts.setOnClickListener { toggle(binding.bodyPrompts, binding.indPrompts) }
        binding.headerDiag.setOnClickListener { toggle(binding.bodyDiag, binding.indDiag) }
    }

    private fun loadApiKey() {
        binding.etApiKey.setText(
            prefs.getString(MainFragment.PREF_API_KEY, BuildConfig.ANTHROPIC_API_KEY)
        )
    }

    private fun loadVoiceSettings() {
        binding.sliderSpeechRate.value = prefs.getFloat(MainFragment.PREF_SPEECH_RATE, 0.95f)

        when (prefs.getString(TourGuideService.PREF_TTS_PROVIDER, "android")) {
            "openai" -> binding.rbOpenai.isChecked = true
            "kokoro" -> binding.rbKokoro.isChecked = true
            "piper" -> binding.rbPiper.isChecked = true
            else -> binding.rbAndroid.isChecked = true
        }
        applyProviderVisibility()
        binding.rgTtsProvider.setOnCheckedChangeListener { _, _ -> applyProviderVisibility() }

        // OpenAI
        binding.etOpenaiKey.setText(prefs.getString(TourGuideService.PREF_OPENAI_TTS_KEY, ""))
        val models = listOf("tts-1  (faster, standard quality)", "tts-1-hd  (slower, higher quality)")
        binding.actvOpenaiModel.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, models)
        )
        val savedModel = prefs.getString(TourGuideService.PREF_OPENAI_TTS_MODEL, "tts-1-hd") ?: "tts-1-hd"
        binding.actvOpenaiModel.setText(if (savedModel == "tts-1") models[0] else models[1], false)
        binding.tvOpenaiBalance.text =
            if ((prefs.getString(TourGuideService.PREF_OPENAI_TTS_KEY, "") ?: "").isBlank())
                "No key saved" else "See platform.openai.com"

        // Kokoro voice picker
        binding.actvKokoroVoice.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line,
                MainFragment.KOKORO_VOICES.map { it.first })
        )
        val savedSid = prefs.getInt(MainFragment.PREF_KOKORO_VOICE_SID, MainFragment.DEFAULT_KOKORO_VOICE_SID)
        val savedEntry = MainFragment.KOKORO_VOICES.getOrElse(savedSid) { MainFragment.KOKORO_VOICES[0] }
        binding.actvKokoroVoice.setText(savedEntry.first, false)
        lastPreviewSid = savedSid
        lastPreviewName = savedEntry.first.substringBefore(" ")

        binding.actvKokoroVoice.setOnItemClickListener { _, _, position, _ ->
            val entry = MainFragment.KOKORO_VOICES.getOrNull(position) ?: return@setOnItemClickListener
            prefs.edit().putInt(MainFragment.PREF_KOKORO_VOICE_SID, entry.second).apply()
            lastPreviewSid = entry.second
            lastPreviewName = entry.first.substringBefore(" ")
            previewVoice(sid = entry.second, voiceName = entry.first.substringBefore(" "))
        }

        // Replay the last voice preview when the user drags the speed slider.
        binding.sliderSpeechRate.addOnChangeListener { _, _, fromUser ->
            if (fromUser && lastPreviewSid >= 0) {
                previewVoice(sid = lastPreviewSid, voiceName = lastPreviewName)
            }
        }

        // Kick off background preview caching if not already done.
        kokoroModelManager.ensureVoicePreviews(
            MainFragment.KOKORO_VOICES,
            KokoroTtsEngine.VOICE_PREVIEW_TEMPLATE
        )
        setupKokoroDownload()
        setupPiperPicker()
    }

    private fun setupPiperPicker() {
        val labels = PiperVoices.ALL.map { "${it.displayName}  (~${it.sizeMb} MB)" }
        binding.actvPiperVoice.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, labels)
        )
        val savedId = prefs.getString(TourGuideService.PREF_PIPER_VOICE_ID, PiperVoices.DEFAULT_VOICE_ID)
            ?: PiperVoices.DEFAULT_VOICE_ID
        val savedVoice = PiperVoices.byId(savedId) ?: PiperVoices.ALL.first()
        val savedIndex = PiperVoices.ALL.indexOf(savedVoice).coerceAtLeast(0)
        binding.actvPiperVoice.setText(labels[savedIndex], false)
        lastPiperPreviewVoice = savedVoice
        observePiperVoiceState(savedVoice)

        binding.actvPiperVoice.setOnItemClickListener { _, _, position, _ ->
            val voice = PiperVoices.ALL.getOrNull(position) ?: return@setOnItemClickListener
            prefs.edit().putString(TourGuideService.PREF_PIPER_VOICE_ID, voice.id).apply()
            lastPiperPreviewVoice = voice
            observePiperVoiceState(voice)
            if (piperModelManager.isVoiceReady(voice.id)) {
                previewPiperVoice(voice)
            } else {
                // Trigger silent download — preview will auto-play when ready.
                pendingPiperPreview = voice.id
                piperModelManager.downloadVoiceIfNeeded(voice)
            }
        }

        // Replay last Piper preview when user moves the speed slider.
        binding.sliderSpeechRate.addOnChangeListener { _, _, fromUser ->
            if (!fromUser) return@addOnChangeListener
            val v = lastPiperPreviewVoice
            if (binding.rbPiper.isChecked && v != null && piperModelManager.isVoiceReady(v.id)) {
                previewPiperVoice(v)
            }
        }
    }

    private fun observePiperVoiceState(voice: PiperVoice) {
        piperStateJob?.cancel()
        piperStateJob = viewLifecycleOwner.lifecycleScope.launch {
            piperModelManager.stateFor(voice.id).collect { state ->
                applyPiperState(voice, state)
            }
        }
    }

    private fun applyPiperState(voice: PiperVoice, state: PiperModelManager.VoiceState) {
        when (state) {
            is PiperModelManager.VoiceState.NotDownloaded -> {
                binding.tvPiperStatus.text = "Not downloaded — tap a voice to install it"
                binding.progressPiper.visibility = View.GONE
            }
            is PiperModelManager.VoiceState.Downloading -> {
                val pct = (state.progress * 100).toInt()
                binding.tvPiperStatus.text = "Downloading ${voice.displayName}… $pct%"
                binding.progressPiper.visibility = View.VISIBLE
                binding.progressPiper.isIndeterminate = false
                binding.progressPiper.progress = pct
            }
            is PiperModelManager.VoiceState.Extracting -> {
                binding.tvPiperStatus.text = "Extracting ${voice.displayName}…"
                binding.progressPiper.visibility = View.VISIBLE
                binding.progressPiper.isIndeterminate = true
            }
            is PiperModelManager.VoiceState.Ready -> {
                binding.tvPiperStatus.text = "Ready"
                binding.progressPiper.visibility = View.GONE
                if (pendingPiperPreview == voice.id) {
                    pendingPiperPreview = null
                    previewPiperVoice(voice)
                }
            }
            is PiperModelManager.VoiceState.Error -> {
                binding.tvPiperStatus.text = "Error: ${state.message}"
                binding.progressPiper.visibility = View.GONE
            }
        }
    }

    private fun previewPiperVoice(voice: PiperVoice) {
        if (!piperModelManager.isVoiceReady(voice.id)) return
        val speed = binding.sliderSpeechRate.value
        previewJob?.cancel()
        previewPlayer?.runCatching { stop() }
        previewPlayer?.release()
        previewPlayer = null

        previewJob = viewLifecycleOwner.lifecycleScope.launch {
            val cached = piperModelManager.voicePreviewFile(voice.id)
            val wav: File = if (cached.exists()) {
                cached
            } else {
                val voiceDir = piperModelManager.voiceDir(voice.id)
                if (piperPreviewTts == null || piperPreviewTtsVoiceId != voice.id) {
                    piperPreviewTts = withContext(Dispatchers.IO) {
                        OfflineTts(config = OfflineTtsConfig(
                            model = OfflineTtsModelConfig(
                                vits = OfflineTtsVitsModelConfig(
                                    model = File(voiceDir, "${voice.id}.onnx").absolutePath,
                                    tokens = File(voiceDir, "tokens.txt").absolutePath,
                                    dataDir = File(voiceDir, "espeak-ng-data").absolutePath,
                                ),
                                numThreads = 1,
                                debug = false,
                                provider = "cpu",
                            )
                        ))
                    }
                    piperPreviewTtsVoiceId = voice.id
                }
                val tts = piperPreviewTts ?: return@launch
                val speakerName = voice.displayName.substringBefore(" ")
                withContext(Dispatchers.IO) {
                    tts.generate(
                        text = PiperTtsEngine.VOICE_PREVIEW_TEMPLATE.format(speakerName),
                        sid = 0,
                        speed = 0.95f,
                    ).save(cached.absolutePath)
                }
                if (!isActive) { cached.delete(); return@launch }
                cached
            }

            val mp = android.media.MediaPlayer().apply {
                setDataSource(wav.absolutePath)
                prepare()
                setOnCompletionListener { previewPlayer = null }
            }
            previewPlayer = mp
            mp.start()
            try {
                mp.playbackParams = android.media.PlaybackParams().setSpeed(speed)
            } catch (_: Exception) { /* falls back to 1.0× */ }
        }
    }

    private fun applyProviderVisibility() {
        binding.sectionOpenai.visibility = if (binding.rbOpenai.isChecked) View.VISIBLE else View.GONE
        binding.sectionKokoro.visibility = if (binding.rbKokoro.isChecked) View.VISIBLE else View.GONE
        binding.sectionPiper.visibility = if (binding.rbPiper.isChecked) View.VISIBLE else View.GONE
    }

    private fun setupKokoroDownload() {
        applyKokoroState(kokoroModelManager.state.value)
        binding.btnKokoroDownload.setOnClickListener {
            binding.btnKokoroDownload.isEnabled = false
            KokoroDownloadService.start(requireContext())
        }
        viewLifecycleOwner.lifecycleScope.launch {
            kokoroModelManager.state.collect { applyKokoroState(it) }
        }
    }

    private fun applyKokoroState(state: KokoroModelManager.DownloadState) {
        when (state) {
            is KokoroModelManager.DownloadState.NotDownloaded -> {
                binding.tvKokoroStatus.text = "Not downloaded"
                binding.progressKokoro.visibility = View.GONE
                binding.btnKokoroDownload.visibility = View.VISIBLE
                binding.btnKokoroDownload.text = "Download model (~350 MB)"
                binding.btnKokoroDownload.isEnabled = true
                binding.tilKokoroVoice.visibility = View.GONE
            }
            is KokoroModelManager.DownloadState.Downloading -> {
                val pct = (state.progress * 100).toInt()
                binding.tvKokoroStatus.text = "Downloading… $pct%"
                binding.progressKokoro.visibility = View.VISIBLE
                binding.progressKokoro.isIndeterminate = false
                binding.progressKokoro.progress = pct
                binding.btnKokoroDownload.visibility = View.GONE
                binding.tilKokoroVoice.visibility = View.GONE
            }
            is KokoroModelManager.DownloadState.Extracting -> {
                binding.tvKokoroStatus.text = "Extracting files… (2–3 min)"
                binding.progressKokoro.visibility = View.VISIBLE
                binding.progressKokoro.isIndeterminate = true
                binding.btnKokoroDownload.visibility = View.GONE
                binding.tilKokoroVoice.visibility = View.GONE
            }
            is KokoroModelManager.DownloadState.Ready -> {
                binding.tvKokoroStatus.text = "Ready"
                binding.progressKokoro.visibility = View.GONE
                binding.btnKokoroDownload.visibility = View.GONE
                binding.tilKokoroVoice.visibility = View.VISIBLE
            }
            is KokoroModelManager.DownloadState.Error -> {
                binding.tvKokoroStatus.text = "Error: ${state.message}"
                binding.progressKokoro.visibility = View.GONE
                binding.btnKokoroDownload.visibility = View.VISIBLE
                binding.btnKokoroDownload.text = "Retry download"
                binding.btnKokoroDownload.isEnabled = true
                binding.tilKokoroVoice.visibility = View.GONE
            }
        }
    }

    private fun loadPrompts() {
        binding.etSystemPrompt.setText(
            prefs.getString(NarrationRepository.PREF_SYSTEM_PROMPT, "")
                ?.takeIf { it.isNotBlank() } ?: ClaudeApiService.SYSTEM_PROMPT
        )
        binding.etUserPrompt.setText(
            prefs.getString(NarrationRepository.PREF_USER_PROMPT, "")
                ?.takeIf { it.isNotBlank() } ?: NarrationRepository.DEFAULT_USER_PROMPT
        )
        binding.btnRestoreSystem.setOnClickListener {
            binding.etSystemPrompt.setText(ClaudeApiService.SYSTEM_PROMPT)
        }
        binding.btnRestoreUser.setOnClickListener {
            binding.etUserPrompt.setText(NarrationRepository.DEFAULT_USER_PROMPT)
        }

        // Prevent the outer ScrollView from swallowing vertical scroll in the prompt boxes.
        listOf(binding.etSystemPrompt, binding.etUserPrompt).forEach { et ->
            et.setOnTouchListener { v, event ->
                v.parent.requestDisallowInterceptTouchEvent(true)
                if (event.action == MotionEvent.ACTION_UP ||
                    event.action == MotionEvent.ACTION_CANCEL) {
                    v.parent.requestDisallowInterceptTouchEvent(false)
                }
                false
            }
        }
    }

    private fun setupExperiment() {
        binding.btnRunExperiment.setOnClickListener {
            if (!kokoroModelManager.isReady) {
                Toast.makeText(requireContext(), "Kokoro model not downloaded — download it first in the Voice section", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (viewModel.tourState.value != TourState.IDLE) {
                Toast.makeText(requireContext(), "Stop the tour before running the experiment", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            startExperiment()
        }
    }

    private fun startExperiment() {
        val ctx = requireContext()
        val window = requireActivity().window
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val pm = ctx.getSystemService(PowerManager::class.java)
        val wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK, "TravelGuide:ExperimentWakeLock"
        ).also { it.acquire(90 * 60 * 1000L) }

        val progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
        }
        val tvStatus = TextView(ctx).apply {
            text = "Initializing Kokoro TTS engine…"
            setPadding(0, 16, 0, 0)
        }
        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(64, 40, 64, 16)
            addView(progressBar)
            addView(tvStatus)
        }

        var experimentJob: kotlinx.coroutines.Job? = null
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle("Running TTS Experiment")
            .setMessage("This will take ~35 minutes. Keep the phone plugged in.")
            .setView(layout)
            .setCancelable(false)
            .setNegativeButton("Cancel") { _, _ ->
                experimentJob?.cancel()
                wakeLock.runCatching { if (isHeld) release() }
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            .show()

        val voiceSid = prefs.getInt(MainFragment.PREF_KOKORO_VOICE_SID, MainFragment.DEFAULT_KOKORO_VOICE_SID)
        val modelDir = kokoroModelManager.modelDir

        val experiment = TtsExperiment(ctx, modelDir, voiceSid) { percent, status ->
            lifecycleScope.launch(Dispatchers.Main) {
                if (dialog.isShowing) {
                    progressBar.progress = percent
                    tvStatus.text = status
                }
            }
        }

        experimentJob = lifecycleScope.launch {
            val results = try {
                experiment.run()
            } catch (e: Exception) {
                if (!isActive) {
                    wakeLock.runCatching { if (isHeld) release() }
                    window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    return@launch
                }
                wakeLock.runCatching { if (isHeld) release() }
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (dialog.isShowing) dialog.dismiss()
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("Experiment Failed")
                    .setMessage(e.message ?: "Unknown error")
                    .setPositiveButton("OK", null)
                    .show()
                return@launch
            }

            wakeLock.runCatching { if (isHeld) release() }
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

            val report = experiment.formatResults(results)
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            data class SaveResult(val displayPath: String, val uri: Uri)
            val saved = withContext(Dispatchers.IO) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resolver = ctx.contentResolver
                    val cv = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, "tts_experiment_$ts.txt")
                        put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
                    resolver.openOutputStream(uri)!!.use { it.write(report.toByteArray()) }
                    cv.clear()
                    cv.put(MediaStore.MediaColumns.IS_PENDING, 0)
                    resolver.update(uri, cv, null, null)
                    SaveResult("Downloads/tts_experiment_$ts.txt", uri)
                } else {
                    val outDir = ctx.getExternalFilesDir("Experiment") ?: ctx.filesDir
                    outDir.mkdirs()
                    val f = File(outDir, "tts_experiment_$ts.txt").also { it.writeText(report) }
                    SaveResult(f.absolutePath, FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f))
                }
            }

            if (dialog.isShowing) dialog.dismiss()

            MaterialAlertDialogBuilder(ctx)
                .setTitle("Experiment Complete!")
                .setMessage("Results saved to:\n${saved.displayPath}\n\nOpen the Files app → Downloads to find it.")
                .setPositiveButton("Share File") { _, _ ->
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_STREAM, saved.uri)
                        putExtra(Intent.EXTRA_SUBJECT, "TTS Buffer Experiment $ts")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    startActivity(Intent.createChooser(shareIntent, "Share Experiment Results"))
                }
                .setNegativeButton("Done", null)
                .show()
        }
    }

    private fun setupDiagnostics() {
        val expiryDays = prefs.getInt(NarrationHistoryStore.PREF_EXPIRY_DAYS, NarrationHistoryStore.DEFAULT_EXPIRY_DAYS)
        binding.sliderHistoryExpiry.value = expiryDays.toFloat().coerceIn(5f, 90f)
        binding.tvHistoryExpiryLabel.text = "Narration memory: $expiryDays days"
        binding.sliderHistoryExpiry.addOnChangeListener { _, value, _ ->
            binding.tvHistoryExpiryLabel.text = "Narration memory: ${value.toInt()} days"
        }

        binding.btnClearHistory.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Clear History?")
                .setMessage(
                    "This removes all places from your \"Places Covered\" list and clears " +
                    "narration memory so Claude won't repeat context from previous tours. " +
                    "Everything will be eligible to be narrated again on your next tour."
                )
                .setPositiveButton("Clear") { _, _ ->
                    viewModel.clearHistory()
                    Toast.makeText(requireContext(), "History cleared", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
        binding.btnCopyLogs.setOnClickListener {
            lifecycleScope.launch {
                val logs = readTtsLogs()
                val cm = requireContext().getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("travel_guide_logs", logs))
                Toast.makeText(requireContext(), "TTS logs copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnCopyLast200.setOnClickListener {
            lifecycleScope.launch {
                val logs = readLogcat(maxLines = 200)
                val cm = requireContext().getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("travel_guide_logs", logs))
                Toast.makeText(requireContext(), "Last 200 log lines copied to clipboard", Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnExportLogs.setOnClickListener {
            lifecycleScope.launch {
                val logs = readLogcat(maxLines = null)
                val logFile = File(requireContext().cacheDir, "travel_guide_log.txt")
                logFile.writeText(logs)
                val uri = FileProvider.getUriForFile(
                    requireContext(),
                    "${requireContext().packageName}.fileprovider",
                    logFile
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    putExtra(Intent.EXTRA_SUBJECT, "Travel Guide Log")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(Intent.createChooser(shareIntent, "Export Log File"))
            }
        }
    }

    private fun saveSettings() {
        val anthropicKey = binding.etApiKey.text?.toString()?.trim() ?: ""
        val rate = binding.sliderSpeechRate.value
        val provider = when (binding.rgTtsProvider.checkedRadioButtonId) {
            R.id.rb_openai -> "openai"
            R.id.rb_kokoro -> "kokoro"
            R.id.rb_piper -> "piper"
            else -> "android"
        }
        val openAiKey = binding.etOpenaiKey.text?.toString()?.trim() ?: ""
        val openAiModel = if (binding.actvOpenaiModel.text.toString().startsWith("tts-1-hd")) "tts-1-hd" else "tts-1"
        val kokoroVoiceSid = MainFragment.KOKORO_VOICES.indexOfFirst {
            it.first == binding.actvKokoroVoice.text.toString()
        }.coerceAtLeast(0)
        val systemPrompt = binding.etSystemPrompt.text?.toString() ?: ""
        val userPrompt = binding.etUserPrompt.text?.toString() ?: ""

        val expiryDays = binding.sliderHistoryExpiry.value.toInt()

        prefs.edit()
            .putString(MainFragment.PREF_API_KEY, anthropicKey)
            .putFloat(MainFragment.PREF_SPEECH_RATE, rate)
            .putString(TourGuideService.PREF_TTS_PROVIDER, provider)
            .putString(TourGuideService.PREF_OPENAI_TTS_KEY, openAiKey)
            .putString(TourGuideService.PREF_OPENAI_TTS_MODEL, openAiModel)
            .putInt(MainFragment.PREF_KOKORO_VOICE_SID, kokoroVoiceSid)
            .putString(NarrationRepository.PREF_SYSTEM_PROMPT, systemPrompt)
            .putString(NarrationRepository.PREF_USER_PROMPT, userPrompt)
            .putInt(NarrationHistoryStore.PREF_EXPIRY_DAYS, expiryDays)
            .apply()
    }

    private suspend fun readTtsLogs(): String = withContext(Dispatchers.IO) {
        val interestingTags = setOf(
            "KokoroTtsEngine", "TourGuideService", "NarrationRepository",
            "PoiRepository", "KokoroModelManager", "AndroidTtsEngine"
        )
        try {
            val pid = android.os.Process.myPid().toString()
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "--pid", pid, "-v", "time")
            )
            process.inputStream.bufferedReader()
                .lineSequence()
                .filter { line -> interestingTags.any { line.contains(it) } }
                .joinToString("\n")
                .ifBlank { "(no TTS logs found — run a tour first)" }
        } catch (e: Exception) {
            "Error reading logcat: ${e.message}"
        }
    }

    private suspend fun readLogcat(maxLines: Int?): String = withContext(Dispatchers.IO) {
        val pid = android.os.Process.myPid().toString()
        val args = if (maxLines != null) {
            arrayOf("logcat", "-d", "-t", maxLines.toString(), "--pid", pid)
        } else {
            arrayOf("logcat", "-d", "--pid", pid)
        }
        try {
            val process = Runtime.getRuntime().exec(args)
            process.inputStream.bufferedReader().readText().ifBlank { "(no log output found)" }
        } catch (e: Exception) {
            "Error reading logcat: ${e.message}"
        }
    }

    private fun previewVoice(sid: Int, voiceName: String) {
        if (!kokoroModelManager.isReady) return
        val speed = binding.sliderSpeechRate.value  // capture before coroutine
        previewJob?.cancel()
        previewPlayer?.runCatching { stop() }
        previewPlayer?.release()
        previewPlayer = null

        previewJob = viewLifecycleOwner.lifecycleScope.launch {
            val cached = kokoroModelManager.voicePreviewFile(sid)
            val wavToPlay: File

            if (cached.exists()) {
                // Zero-lag path: pre-generated WAV already on disk.
                wavToPlay = cached
            } else {
                // Fallback: generate on demand then play.
                val text = KokoroTtsEngine.VOICE_PREVIEW_TEMPLATE.format(voiceName)
                if (previewTts == null) {
                    val modelDir = kokoroModelManager.modelDir
                    previewTts = withContext(Dispatchers.IO) {
                        OfflineTts(config = OfflineTtsConfig(
                            model = OfflineTtsModelConfig(
                                kokoro = OfflineTtsKokoroModelConfig(
                                    model = File(modelDir, "model.onnx").absolutePath,
                                    voices = File(modelDir, "voices.bin").absolutePath,
                                    tokens = File(modelDir, "tokens.txt").absolutePath,
                                    dataDir = File(modelDir, "espeak-ng-data").absolutePath,
                                    lang = "en-us",
                                ),
                                numThreads = 1,
                                debug = false,
                                provider = "cpu",
                            )
                        ))
                    }
                }
                val tts = previewTts ?: return@launch
                val tmp = File(requireContext().cacheDir, "voice_preview_$sid.wav")
                withContext(Dispatchers.IO) {
                    tts.generate(text = text, sid = sid, speed = 0.95f).save(tmp.absolutePath)
                }
                if (!isActive) { tmp.delete(); return@launch }
                wavToPlay = tmp
            }

            val mp = android.media.MediaPlayer().apply {
                setDataSource(wavToPlay.absolutePath)
                prepare()
                setOnCompletionListener {
                    if (wavToPlay != cached) wavToPlay.delete()
                    previewPlayer = null
                }
            }
            previewPlayer = mp
            mp.start()
            // Apply speed after start() — most reliable across Android versions.
            try {
                mp.playbackParams = android.media.PlaybackParams().setSpeed(speed)
            } catch (_: Exception) { /* plays at normal speed if unsupported */ }
        }
    }

    override fun onDestroyView() {
        saveSettings()
        previewJob?.cancel()
        previewPlayer?.runCatching { stop() }
        previewPlayer?.release()
        previewPlayer = null
        piperStateJob?.cancel()
        piperPreviewTts = null
        piperPreviewTtsVoiceId = null
        super.onDestroyView()
        _binding = null
    }
}
