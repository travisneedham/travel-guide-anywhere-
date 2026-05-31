package com.travelguide.anywhere.ui.main

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.provider.MediaStore
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
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
import com.travelguide.anywhere.data.local.MentionedPlacesStore
import com.travelguide.anywhere.data.remote.ClaudeApiService
import com.travelguide.anywhere.databinding.FragmentSettingsBinding
import com.travelguide.anywhere.data.local.NarrationHistoryStore
import com.travelguide.anywhere.repository.NarrationRepository
import com.travelguide.anywhere.repository.PoiExperiment
import com.travelguide.anywhere.repository.PoiRepository
import com.google.android.gms.location.FusedLocationProviderClient
import com.travelguide.anywhere.service.KokoroDownloadService
import com.travelguide.anywhere.service.KokoroModelManager
import com.travelguide.anywhere.service.LocalLlmModelManager
import com.travelguide.anywhere.service.PiperModelManager
import com.travelguide.anywhere.service.PiperTtsEngine
import com.travelguide.anywhere.service.PiperVoice
import com.travelguide.anywhere.service.PiperVoices
import com.travelguide.anywhere.service.TourAutoMediaService
import com.travelguide.anywhere.service.TourGuideService
import com.travelguide.anywhere.service.TourState
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsKokoroModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import com.travelguide.anywhere.service.KokoroTtsEngine
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
    @Inject lateinit var mentionedPlacesStore: MentionedPlacesStore
    @Inject lateinit var narrationRepository: NarrationRepository
    @Inject lateinit var localLlmModelManager: LocalLlmModelManager
    @Inject lateinit var poiExperiment: PoiExperiment
    @Inject lateinit var fusedLocation: FusedLocationProviderClient

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
        setupFeedback()
        loadAuthorshipEngine()
        loadVoiceSettings()
        loadInterestFilters()
        loadPrompts()
        loadLocalLlm()
        setupDiagnostics()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }
    }

    private fun setupFeedback() {
        binding.etFeedback.setOnTouchListener { v, event ->
            v.parent.requestDisallowInterceptTouchEvent(true)
            if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
                v.parent.requestDisallowInterceptTouchEvent(false)
            }
            false
        }

        binding.btnSendFeedback.setOnClickListener {
            val feedbackText = binding.etFeedback.text?.toString()?.trim() ?: ""
            if (feedbackText.isBlank()) {
                Toast.makeText(requireContext(), "Please describe what happened first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.btnSendFeedback.isEnabled = false
            binding.btnSendFeedback.text = "Preparing…"

            viewLifecycleOwner.lifecycleScope.launch {
                val ctx = requireContext()

                val ttsProvider = prefs.getString(TourGuideService.PREF_TTS_PROVIDER, "android") ?: "android"
                val isTripMode = prefs.getBoolean(MainFragment.PREF_TRIP_MODE, false)
                val isFamousSort = prefs.getBoolean(MainFragment.PREF_FAMOUS_SORT, false)
                val radiusIndex = prefs.getInt(MainFragment.PREF_RADIUS_INDEX, MainFragment.DEFAULT_RADIUS_INDEX)
                val radiusMiles = MainFragment.SLIDER_MILES.getOrElse(radiusIndex) { 5f }
                val apiKeySet = !prefs.getString(MainFragment.PREF_API_KEY, "").isNullOrBlank()

                val emailBody = buildString {
                    appendLine("FEEDBACK")
                    appendLine("========")
                    appendLine(feedbackText)
                    appendLine()
                    appendLine("DEVICE & APP")
                    appendLine("------------")
                    appendLine("Version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                    appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}")
                    appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                    appendLine()
                    appendLine("SETTINGS")
                    appendLine("--------")
                    appendLine("TTS Provider: $ttsProvider")
                    appendLine("Kokoro model: ${if (kokoroModelManager.isReady) "downloaded" else "not downloaded"}")
                    appendLine("Mode: ${if (isTripMode) "Trip" else "Live"}")
                    appendLine("Sort: ${if (isFamousSort) "Most Famous First" else "Closest First"}")
                    appendLine("Radius: ${"%.2f".format(radiusMiles)} miles")
                    appendLine("API key configured: $apiKeySet")
                    appendLine()
                    appendLine("Full app log attached.")
                }

                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val logFile = File(ctx.cacheDir, "feedback_log_$ts.txt")
                withContext(Dispatchers.IO) { logFile.writeText(readLogcat(maxLines = null)) }
                val logUri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", logFile)

                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "message/rfc822"
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(FEEDBACK_EMAIL))
                    putExtra(Intent.EXTRA_SUBJECT, "[Travel Guide] v${BuildConfig.VERSION_NAME} — ${Build.MODEL}")
                    putExtra(Intent.EXTRA_TEXT, emailBody)
                    putExtra(Intent.EXTRA_STREAM, logUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                binding.btnSendFeedback.isEnabled = true
                binding.btnSendFeedback.text = "Send Feedback via Email"

                try {
                    startActivity(Intent.createChooser(intent, "Send Feedback"))
                    binding.etFeedback.setText("")
                } catch (_: android.content.ActivityNotFoundException) {
                    Toast.makeText(ctx, "No email app found", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setupCollapsibleSections() {
        fun toggle(body: View, indicator: android.widget.TextView) {
            val expanding = body.visibility == View.GONE
            body.visibility = if (expanding) View.VISIBLE else View.GONE
            indicator.text = if (expanding) "▼" else "▶"
        }
        binding.headerFeedback.setOnClickListener { toggle(binding.bodyFeedback, binding.indFeedback) }
        binding.headerApi.setOnClickListener { toggle(binding.bodyApi, binding.indApi) }
        binding.headerVoice.setOnClickListener { toggle(binding.bodyVoice, binding.indVoice) }
        binding.headerInterests.setOnClickListener { toggle(binding.bodyInterests, binding.indInterests) }
        binding.headerHistory.setOnClickListener { toggle(binding.bodyHistory, binding.indHistory) }
        binding.headerPrompts.setOnClickListener { toggle(binding.bodyPrompts, binding.indPrompts) }
        binding.headerDiag.setOnClickListener { toggle(binding.bodyDiag, binding.indDiag) }
    }

    private fun loadAuthorshipEngine() {
        binding.etApiKey.setText(
            prefs.getString(MainFragment.PREF_API_KEY, BuildConfig.ANTHROPIC_API_KEY)
        )
        when (prefs.getString(NarrationRepository.PREF_NARRATION_PROVIDER, NarrationRepository.NARRATION_PROVIDER_ANTHROPIC)) {
            NarrationRepository.NARRATION_PROVIDER_OPENAI -> binding.rbNarrationOpenai.isChecked = true
            NarrationRepository.NARRATION_PROVIDER_LOCAL -> binding.rbNarrationLocal.isChecked = true
            else -> binding.rbNarrationAnthropic.isChecked = true
        }
        applyNarrationProviderVisibility()
        binding.rgNarrationProvider.setOnCheckedChangeListener { _, _ -> applyNarrationProviderVisibility() }

        setupAnthropicModels()
        loadAnthropicSpend()

        binding.etOpenaiNarrationKey.setText(prefs.getString(NarrationRepository.PREF_OPENAI_NARRATION_KEY, ""))
        binding.btnFetchOpenaiNarration.setOnClickListener { fetchOpenAiNarrationData() }

        val savedModel = prefs.getString(NarrationRepository.PREF_NARRATION_MODEL, "") ?: ""
        if (binding.rbNarrationOpenai.isChecked && savedModel.isNotBlank()) {
            populateOpenAiModelDropdown(listOf(savedModel), savedModel)
        }
    }

    private fun loadAnthropicSpend() {
        val spend = prefs.getFloat(NarrationRepository.PREF_ANTHROPIC_SPEND_USD, 0f)
        binding.tvAnthropicSpend.text = "Estimated spend: ${"$%.4f".format(spend)}"
        binding.btnResetAnthropicSpend.setOnClickListener {
            prefs.edit().putFloat(NarrationRepository.PREF_ANTHROPIC_SPEND_USD, 0f).apply()
            binding.tvAnthropicSpend.text = "Estimated spend: $0.0000"
        }
    }

    private fun applyNarrationProviderVisibility() {
        val isOpenAI = binding.rbNarrationOpenai.isChecked
        val isLocal = binding.rbNarrationLocal.isChecked
        binding.sectionAuthorshipAnthropic.visibility = if (!isOpenAI && !isLocal) View.VISIBLE else View.GONE
        binding.sectionAuthorshipOpenai.visibility = if (isOpenAI) View.VISIBLE else View.GONE
        binding.sectionAuthorshipLocal.visibility = if (isLocal) View.VISIBLE else View.GONE
    }

    private fun setupAnthropicModels() {
        binding.actvNarrationModel.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, ANTHROPIC_MODEL_LABELS)
        )
        val savedModel = prefs.getString(NarrationRepository.PREF_NARRATION_MODEL, "") ?: ""
        val savedIdx = ANTHROPIC_MODEL_IDS.indexOf(savedModel).let { if (it < 0) 0 else it }
        binding.actvNarrationModel.setText(ANTHROPIC_MODEL_LABELS[savedIdx], false)
        binding.tvNarrationPricing.text = ANTHROPIC_MODEL_PRICING[savedIdx]
        binding.actvNarrationModel.setOnItemClickListener { _, _, position, _ ->
            binding.tvNarrationPricing.text = ANTHROPIC_MODEL_PRICING.getOrElse(position) { "" }
        }
    }

    private fun populateOpenAiModelDropdown(models: List<String>, selectedModel: String) {
        if (models.isEmpty()) return
        binding.actvOpenaiNarrationModel.setAdapter(
            ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, models)
        )
        val selected = if (selectedModel in models) selectedModel else models.first()
        binding.actvOpenaiNarrationModel.setText(selected, false)
        binding.tvOpenaiNarrationPricing.text =
            NarrationRepository.OPENAI_MODEL_PRICING[selected] ?: "~unknown"
        binding.actvOpenaiNarrationModel.setOnItemClickListener { _, _, position, _ ->
            val model = models.getOrElse(position) { "" }
            binding.tvOpenaiNarrationPricing.text =
                NarrationRepository.OPENAI_MODEL_PRICING[model] ?: "~unknown"
        }
    }

    private fun fetchOpenAiNarrationData() {
        val key = binding.etOpenaiNarrationKey.text?.toString()?.trim() ?: ""
        if (key.isBlank()) {
            Toast.makeText(requireContext(), "Enter your OpenAI API key first", Toast.LENGTH_SHORT).show()
            return
        }
        binding.btnFetchOpenaiNarration.isEnabled = false
        binding.btnFetchOpenaiNarration.text = "Fetching…"
        binding.tvOpenaiNarrationBalance.text = "Fetching…"
        viewLifecycleOwner.lifecycleScope.launch {
            val savedModel = prefs.getString(NarrationRepository.PREF_NARRATION_MODEL, "") ?: ""
            val models = narrationRepository.fetchOpenAiModels(key)
            val balance = narrationRepository.fetchOpenAiBalance(key)
            binding.tvOpenaiNarrationBalance.text = balance
            if (models.isNotEmpty()) {
                val preferred = savedModel.takeIf { it in models } ?: models.first()
                populateOpenAiModelDropdown(models, preferred)
            } else {
                binding.tvOpenaiNarrationBalance.text = "$balance  (model fetch failed — check key)"
            }
            binding.btnFetchOpenaiNarration.isEnabled = true
            binding.btnFetchOpenaiNarration.text = "Fetch Models & Balance"
        }
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

    private fun loadInterestFilters() {
        binding.etOpentripmapKey.setText(prefs.getString(PoiRepository.PREF_OPENTRIPMAP_KEY, ""))
        binding.cbFilterHistoric.isChecked = prefs.getBoolean(PoiRepository.PREF_FILTER_HISTORIC, true)
        binding.cbFilterMuseum.isChecked = prefs.getBoolean(PoiRepository.PREF_FILTER_MUSEUM, true)
        binding.cbFilterAttraction.isChecked = prefs.getBoolean(PoiRepository.PREF_FILTER_ATTRACTION, true)
        binding.cbFilterArtwork.isChecked = prefs.getBoolean(PoiRepository.PREF_FILTER_ARTWORK, true)
        binding.cbFilterViewpoint.isChecked = prefs.getBoolean(PoiRepository.PREF_FILTER_VIEWPOINT, true)
        binding.cbFilterPark.isChecked = prefs.getBoolean(PoiRepository.PREF_FILTER_PARK, true)
        binding.cbFilterPlaceOfWorship.isChecked = prefs.getBoolean(PoiRepository.PREF_FILTER_PLACE_OF_WORSHIP, true)
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
        binding.etDeepDivePrompt.setText(
            prefs.getString(NarrationRepository.PREF_DEEP_DIVE_PROMPT, "")
                ?.takeIf { it.isNotBlank() } ?: NarrationRepository.DEFAULT_DEEP_DIVE_PROMPT
        )
        binding.btnRestoreSystem.setOnClickListener {
            binding.etSystemPrompt.setText(ClaudeApiService.SYSTEM_PROMPT)
        }
        binding.btnRestoreUser.setOnClickListener {
            binding.etUserPrompt.setText(NarrationRepository.DEFAULT_USER_PROMPT)
        }
        binding.btnRestoreDeepDive.setOnClickListener {
            binding.etDeepDivePrompt.setText(NarrationRepository.DEFAULT_DEEP_DIVE_PROMPT)
        }

        // Prevent the outer ScrollView from swallowing vertical scroll in the prompt boxes.
        listOf(binding.etSystemPrompt, binding.etUserPrompt, binding.etDeepDivePrompt).forEach { et ->
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

    private fun loadLocalLlm() {
        // Restore selected model
        val savedModel = prefs.getString(NarrationRepository.PREF_LOCAL_LLM_MODEL,
            LocalLlmModelManager.LocalModel.PHI4_MINI.name) ?: ""
        if (savedModel == LocalLlmModelManager.LocalModel.SMOLLM2.name) {
            binding.rbLocalSmollm2.isChecked = true
        } else {
            binding.rbLocalPhi4.isChecked = true
        }

        // Download buttons
        binding.btnDownloadPhi4.setOnClickListener {
            localLlmModelManager.downloadIfNeeded(LocalLlmModelManager.LocalModel.PHI4_MINI)
        }
        binding.btnDownloadSmollm2.setOnClickListener {
            localLlmModelManager.downloadIfNeeded(LocalLlmModelManager.LocalModel.SMOLLM2)
        }

        // Observe state flows
        viewLifecycleOwner.lifecycleScope.launch {
            localLlmModelManager.stateFlowFor(LocalLlmModelManager.LocalModel.PHI4_MINI).collect { state ->
                updateLocalModelUi(
                    state,
                    binding.btnDownloadPhi4,
                    binding.pbPhi4,
                    binding.tvPhi4Status,
                )
                updateLocalModelSelectVisibility()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            localLlmModelManager.stateFlowFor(LocalLlmModelManager.LocalModel.SMOLLM2).collect { state ->
                updateLocalModelUi(
                    state,
                    binding.btnDownloadSmollm2,
                    binding.pbSmollm2,
                    binding.tvSmollm2Status,
                )
                updateLocalModelSelectVisibility()
            }
        }
    }

    private fun updateLocalModelUi(
        state: LocalLlmModelManager.DownloadState,
        btn: com.google.android.material.button.MaterialButton,
        progressBar: android.widget.ProgressBar,
        statusTv: android.widget.TextView,
    ) {
        when (state) {
            is LocalLlmModelManager.DownloadState.NotDownloaded -> {
                btn.visibility = View.VISIBLE
                btn.isEnabled = true
                btn.text = "Download"
                progressBar.visibility = View.GONE
                statusTv.visibility = View.GONE
            }
            is LocalLlmModelManager.DownloadState.Downloading -> {
                btn.visibility = View.GONE
                progressBar.visibility = View.VISIBLE
                progressBar.progress = (state.progress * 100).toInt()
                statusTv.visibility = View.VISIBLE
                statusTv.text = "${(state.progress * 100).toInt()}%"
                statusTv.setTextColor(requireContext().getColor(R.color.text_secondary))
            }
            is LocalLlmModelManager.DownloadState.Ready -> {
                btn.visibility = View.GONE
                progressBar.visibility = View.GONE
                statusTv.visibility = View.VISIBLE
                statusTv.text = "Downloaded"
                statusTv.setTextColor(requireContext().getColor(R.color.accent))
            }
            is LocalLlmModelManager.DownloadState.Error -> {
                btn.visibility = View.VISIBLE
                btn.isEnabled = true
                btn.text = "Retry"
                progressBar.visibility = View.GONE
                statusTv.visibility = View.GONE
                Toast.makeText(requireContext(), "Download failed: ${state.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateLocalModelSelectVisibility() {
        val anyReady = localLlmModelManager.isReady(LocalLlmModelManager.LocalModel.PHI4_MINI) ||
            localLlmModelManager.isReady(LocalLlmModelManager.LocalModel.SMOLLM2)
        binding.layoutLocalModelSelect.visibility = if (anyReady) View.VISIBLE else View.GONE
        // Disable radio buttons for models not yet downloaded
        binding.rbLocalPhi4.isEnabled = localLlmModelManager.isReady(LocalLlmModelManager.LocalModel.PHI4_MINI)
        binding.rbLocalSmollm2.isEnabled = localLlmModelManager.isReady(LocalLlmModelManager.LocalModel.SMOLLM2)
        // If selected model got deleted somehow, switch to available one
        if (binding.rbLocalPhi4.isChecked && !binding.rbLocalPhi4.isEnabled && binding.rbLocalSmollm2.isEnabled) {
            binding.rbLocalSmollm2.isChecked = true
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
        binding.btnPoiExperiment.setOnClickListener {
            launchPoiExperiment()
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
                val ctx = requireContext()
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                data class SaveResult(val displayPath: String, val uri: Uri)
                val saved = withContext(Dispatchers.IO) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val resolver = ctx.contentResolver
                        val cv = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, "travel_guide_log_$ts.txt")
                            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
                        resolver.openOutputStream(uri)!!.use { it.write(logs.toByteArray()) }
                        cv.clear()
                        cv.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        resolver.update(uri, cv, null, null)
                        SaveResult("Downloads/travel_guide_log_$ts.txt", uri)
                    } else {
                        val outDir = ctx.getExternalFilesDir("Logs") ?: ctx.filesDir
                        outDir.mkdirs()
                        val f = File(outDir, "travel_guide_log_$ts.txt").also { it.writeText(logs) }
                        SaveResult(
                            f.absolutePath,
                            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
                        )
                    }
                }

                MaterialAlertDialogBuilder(ctx)
                    .setTitle("Log Saved")
                    .setMessage("Saved to:\n${saved.displayPath}\n\nOpen the Files app → Downloads to find it.")
                    .setPositiveButton("Share File") { _, _ ->
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, saved.uri)
                            putExtra(Intent.EXTRA_SUBJECT, "Travel Guide Log $ts")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(shareIntent, "Share Log File"))
                    }
                    .setNegativeButton("Done", null)
                    .show()
            }
        }
        binding.btnExportAutoLogs.setOnClickListener {
            lifecycleScope.launch {
                val logs = readAutoLogs()
                val ctx = requireContext()
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                data class SaveResult(val displayPath: String, val uri: Uri)
                val saved = withContext(Dispatchers.IO) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val resolver = ctx.contentResolver
                        val cv = ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, "auto_log_$ts.txt")
                            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)!!
                        resolver.openOutputStream(uri)!!.use { it.write(logs.toByteArray()) }
                        cv.clear()
                        cv.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        resolver.update(uri, cv, null, null)
                        SaveResult("Downloads/auto_log_$ts.txt", uri)
                    } else {
                        val outDir = ctx.getExternalFilesDir("Logs") ?: ctx.filesDir
                        outDir.mkdirs()
                        val f = File(outDir, "auto_log_$ts.txt").also { it.writeText(logs) }
                        SaveResult(
                            f.absolutePath,
                            FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
                        )
                    }
                }
                MaterialAlertDialogBuilder(ctx)
                    .setTitle("Android Auto Log Saved")
                    .setMessage("Saved to:\n${saved.displayPath}\n\nOpen the Files app → Downloads to find it.")
                    .setPositiveButton("Share File") { _, _ ->
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_STREAM, saved.uri)
                            putExtra(Intent.EXTRA_SUBJECT, "Android Auto Log $ts")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        startActivity(Intent.createChooser(shareIntent, "Share Auto Log File"))
                    }
                    .setNegativeButton("Done", null)
                    .show()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun launchPoiExperiment() {
        val defaultLat = 33.1789543
        val defaultLon = -97.1118095
        val runWith = { lat: Double, lon: Double ->
            binding.btnPoiExperiment.isEnabled = false
            binding.progressExperiment.visibility = View.VISIBLE
            binding.tvExperimentStep.visibility = View.VISIBLE
            binding.tvExperimentStep.text = "Starting…"
            viewLifecycleOwner.lifecycleScope.launch {
                try {
                    poiExperiment.run(lat, lon) { step ->
                        binding.tvExperimentStep.text = step
                    }
                    binding.tvExperimentStep.text = "Done — export the log file."
                    Toast.makeText(
                        requireContext(),
                        "POI experiment complete — Export Full Log File and send it back.",
                        Toast.LENGTH_LONG
                    ).show()
                } catch (e: Exception) {
                    binding.tvExperimentStep.text = "Error: ${e.message}"
                    Toast.makeText(requireContext(), "Experiment error: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    binding.btnPoiExperiment.isEnabled = true
                    binding.progressExperiment.visibility = View.GONE
                }
            }
            Unit
        }
        try {
            fusedLocation.lastLocation
                .addOnSuccessListener { loc -> runWith(loc?.latitude ?: defaultLat, loc?.longitude ?: defaultLon) }
                .addOnFailureListener { runWith(defaultLat, defaultLon) }
        } catch (e: SecurityException) {
            runWith(defaultLat, defaultLon)
        }
    }

    private fun saveSettings() {
        val anthropicKey = binding.etApiKey.text?.toString()?.trim() ?: ""

        val narrationProvider = when {
            binding.rbNarrationLocal.isChecked -> NarrationRepository.NARRATION_PROVIDER_LOCAL
            binding.rbNarrationOpenai.isChecked -> NarrationRepository.NARRATION_PROVIDER_OPENAI
            else -> NarrationRepository.NARRATION_PROVIDER_ANTHROPIC
        }
        val anthropicModelIdx = ANTHROPIC_MODEL_LABELS.indexOf(
            binding.actvNarrationModel.text.toString()
        ).let { if (it < 0) 0 else it }
        val narrationModel = if (binding.rbNarrationOpenai.isChecked)
            binding.actvOpenaiNarrationModel.text.toString().trim()
        else
            ANTHROPIC_MODEL_IDS[anthropicModelIdx]
        val openAiNarrationKey = binding.etOpenaiNarrationKey.text?.toString()?.trim() ?: ""

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
            .putString(NarrationRepository.PREF_NARRATION_PROVIDER, narrationProvider)
            .putString(NarrationRepository.PREF_NARRATION_MODEL, narrationModel)
            .putString(NarrationRepository.PREF_OPENAI_NARRATION_KEY, openAiNarrationKey)
            .putFloat(MainFragment.PREF_SPEECH_RATE, rate)
            .putString(TourGuideService.PREF_TTS_PROVIDER, provider)
            .putString(TourGuideService.PREF_OPENAI_TTS_KEY, openAiKey)
            .putString(TourGuideService.PREF_OPENAI_TTS_MODEL, openAiModel)
            .putInt(MainFragment.PREF_KOKORO_VOICE_SID, kokoroVoiceSid)
            .putString(NarrationRepository.PREF_SYSTEM_PROMPT, systemPrompt)
            .putString(NarrationRepository.PREF_USER_PROMPT, userPrompt)
            .putInt(NarrationHistoryStore.PREF_EXPIRY_DAYS, expiryDays)
            .putString(PoiRepository.PREF_OPENTRIPMAP_KEY, binding.etOpentripmapKey.text?.toString()?.trim() ?: "")
            .putBoolean(PoiRepository.PREF_FILTER_HISTORIC, binding.cbFilterHistoric.isChecked)
            .putBoolean(PoiRepository.PREF_FILTER_MUSEUM, binding.cbFilterMuseum.isChecked)
            .putBoolean(PoiRepository.PREF_FILTER_ATTRACTION, binding.cbFilterAttraction.isChecked)
            .putBoolean(PoiRepository.PREF_FILTER_ARTWORK, binding.cbFilterArtwork.isChecked)
            .putBoolean(PoiRepository.PREF_FILTER_VIEWPOINT, binding.cbFilterViewpoint.isChecked)
            .putBoolean(PoiRepository.PREF_FILTER_PARK, binding.cbFilterPark.isChecked)
            .putBoolean(PoiRepository.PREF_FILTER_PLACE_OF_WORSHIP, binding.cbFilterPlaceOfWorship.isChecked)
            .putString(NarrationRepository.PREF_DEEP_DIVE_PROMPT, binding.etDeepDivePrompt.text?.toString() ?: "")
            .putString(NarrationRepository.PREF_LOCAL_LLM_MODEL,
                if (binding.rbLocalSmollm2.isChecked) LocalLlmModelManager.LocalModel.SMOLLM2.name
                else LocalLlmModelManager.LocalModel.PHI4_MINI.name)
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

    private suspend fun readAutoLogs(): String = withContext(Dispatchers.IO) {
        val autoTags = setOf(
            TourAutoMediaService.TAG,
            "MediaSessionCompat", "MediaSession", "AudioFocus",
            "AudioManager", "TourGuideService", "TourAutoMedia"
        )
        try {
            val pid = android.os.Process.myPid().toString()
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "--pid", pid, "-v", "time")
            )
            val lines = process.inputStream.bufferedReader()
                .lineSequence()
                .filter { line -> autoTags.any { line.contains(it) } }
                .joinToString("\n")
            lines.ifBlank { "(no Android Auto logs found — connect to Android Auto and run a tour first)" }
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
        previewTts = null
        piperStateJob?.cancel()
        piperPreviewTts = null
        piperPreviewTtsVoiceId = null
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val FEEDBACK_EMAIL = "travisneedham@gmail.com"

        val ANTHROPIC_MODEL_IDS = listOf(
            "claude-haiku-4-5-20251001",
            "claude-sonnet-4-6",
            "claude-opus-4-7",
        )
        val ANTHROPIC_MODEL_LABELS = listOf(
            "Haiku 4.5 — fastest, cheapest",
            "Sonnet 4.6 — balanced",
            "Opus 4.7 — most capable",
        )
        val ANTHROPIC_MODEL_PRICING = listOf("~\$0.005", "~\$0.018", "~\$0.090")
    }
}
