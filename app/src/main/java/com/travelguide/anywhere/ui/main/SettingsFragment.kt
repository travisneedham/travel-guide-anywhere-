package com.travelguide.anywhere.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
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
import com.travelguide.anywhere.BuildConfig
import com.travelguide.anywhere.R
import com.travelguide.anywhere.data.remote.ClaudeApiService
import com.travelguide.anywhere.databinding.FragmentSettingsBinding
import com.travelguide.anywhere.repository.NarrationRepository
import com.travelguide.anywhere.service.KokoroDownloadService
import com.travelguide.anywhere.service.KokoroModelManager
import com.travelguide.anywhere.service.TourGuideService
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class SettingsFragment : Fragment() {

    @Inject lateinit var prefs: SharedPreferences
    @Inject lateinit var kokoroModelManager: KokoroModelManager

    private val viewModel: MainViewModel by activityViewModels()

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

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
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            parentFragmentManager.popBackStack()
        }
        binding.toolbar.setOnMenuItemClickListener { item ->
            if (item.itemId == R.id.action_save) {
                saveSettings()
                parentFragmentManager.popBackStack()
                true
            } else false
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
        val savedSid = prefs.getInt(MainFragment.PREF_KOKORO_VOICE_SID, 0)
        binding.actvKokoroVoice.setText(
            MainFragment.KOKORO_VOICES.getOrElse(savedSid) { MainFragment.KOKORO_VOICES[0] }.first, false
        )
        setupKokoroDownload()
    }

    private fun applyProviderVisibility() {
        binding.sectionOpenai.visibility = if (binding.rbOpenai.isChecked) View.VISIBLE else View.GONE
        binding.sectionKokoro.visibility = if (binding.rbKokoro.isChecked) View.VISIBLE else View.GONE
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

    private fun setupDiagnostics() {
        binding.btnClearHistory.setOnClickListener {
            viewModel.clearHistory()
            Toast.makeText(requireContext(), "History cleared", Toast.LENGTH_SHORT).show()
        }
        binding.btnCopyLogs.setOnClickListener {
            lifecycleScope.launch {
                val logs = readTtsLogs()
                val cm = requireContext().getSystemService(ClipboardManager::class.java)
                cm.setPrimaryClip(ClipData.newPlainText("travel_guide_logs", logs))
                Toast.makeText(requireContext(), "TTS logs copied to clipboard", Toast.LENGTH_SHORT).show()
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
            else -> "android"
        }
        val openAiKey = binding.etOpenaiKey.text?.toString()?.trim() ?: ""
        val openAiModel = if (binding.actvOpenaiModel.text.toString().startsWith("tts-1-hd")) "tts-1-hd" else "tts-1"
        val kokoroVoiceSid = MainFragment.KOKORO_VOICES.indexOfFirst {
            it.first == binding.actvKokoroVoice.text.toString()
        }.coerceAtLeast(0)
        val systemPrompt = binding.etSystemPrompt.text?.toString() ?: ""
        val userPrompt = binding.etUserPrompt.text?.toString() ?: ""

        prefs.edit()
            .putString(MainFragment.PREF_API_KEY, anthropicKey)
            .putFloat(MainFragment.PREF_SPEECH_RATE, rate)
            .putString(TourGuideService.PREF_TTS_PROVIDER, provider)
            .putString(TourGuideService.PREF_OPENAI_TTS_KEY, openAiKey)
            .putString(TourGuideService.PREF_OPENAI_TTS_MODEL, openAiModel)
            .putInt(MainFragment.PREF_KOKORO_VOICE_SID, kokoroVoiceSid)
            .putString(NarrationRepository.PREF_SYSTEM_PROMPT, systemPrompt)
            .putString(NarrationRepository.PREF_USER_PROMPT, userPrompt)
            .apply()
        Toast.makeText(requireContext(), "Settings saved", Toast.LENGTH_SHORT).show()
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
