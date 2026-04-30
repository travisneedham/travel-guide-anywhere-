package com.travelguide.anywhere.ui.main

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.travelguide.anywhere.BuildConfig
import com.travelguide.anywhere.R
import com.travelguide.anywhere.data.local.MentionedPlacesStore
import com.travelguide.anywhere.databinding.FragmentMainBinding
import com.travelguide.anywhere.service.TourGuideService
import com.travelguide.anywhere.service.TourState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class MainFragment : Fragment() {

    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by viewModels()

    @Inject lateinit var prefs: SharedPreferences
    @Inject lateinit var okHttpClient: OkHttpClient

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupSlider()
        setupButtons()
        observeState()
    }

    private fun setupSlider() {
        binding.rangeSlider.value = 4f
        binding.tvRangeLabel.text = getString(R.string.range_label, "1.0")
        binding.rangeSlider.addOnChangeListener { _, value, _ ->
            val miles = value / 4f
            val formatted = if (miles < 1f) "%.2f".format(miles) else "%.1f".format(miles)
            binding.tvRangeLabel.text = getString(R.string.range_label, formatted)
        }
    }

    private fun setupButtons() {
        binding.btnGo.setOnClickListener {
            val apiKey = resolveApiKey()
            if (apiKey.isBlank() || apiKey == "YOUR_API_KEY_HERE") {
                showApiKeyDialog()
                return@setOnClickListener
            }
            viewModel.startTour(binding.rangeSlider.value / 4f, apiKey)
        }
        binding.btnStop.setOnClickListener { viewModel.stopTour() }
        binding.btnPause.setOnClickListener { viewModel.pauseOrResume() }
        binding.btnSkip.setOnClickListener { viewModel.skip() }
        binding.btnSettings.setOnClickListener { showSettingsDialog() }
    }

    private fun resolveApiKey(): String {
        val saved = prefs.getString(PREF_API_KEY, null)
        return if (!saved.isNullOrBlank()) saved else BuildConfig.ANTHROPIC_API_KEY
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.tourState.collect { updateUiForState(it) } }
                launch { viewModel.currentTopic.collect { updateCurrentTopic(it) } }
                launch { viewModel.mentionedPlaces.collect { updateMentionedList(it) } }
                launch { viewModel.errorMessage.collect { it?.let { msg ->
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                }}}
            }
        }
    }

    private fun updateUiForState(state: TourState) {
        val isActive = state != TourState.IDLE && state != TourState.ERROR
        binding.btnGo.visibility = if (isActive) View.GONE else View.VISIBLE
        binding.btnStop.visibility = if (isActive) View.VISIBLE else View.GONE
        binding.rangeSlider.isEnabled = !isActive
        binding.cardStatus.visibility = if (isActive) View.VISIBLE else View.GONE
        binding.tvIdle.visibility = if (isActive) View.GONE else View.VISIBLE

        val showControls = state == TourState.SPEAKING || state == TourState.PAUSED
        binding.layoutPlaybackControls.visibility = if (showControls) View.VISIBLE else View.GONE

        if (state == TourState.PAUSED) {
            binding.btnPause.text = getString(R.string.btn_resume)
            binding.btnPause.setIconResource(R.drawable.ic_play)
        } else {
            binding.btnPause.text = getString(R.string.btn_pause)
            binding.btnPause.setIconResource(R.drawable.ic_pause)
        }

        binding.tvStatus.text = when (state) {
            TourState.IDLE -> ""
            TourState.LOCATING -> getString(R.string.status_locating)
            TourState.FETCHING -> getString(R.string.status_fetching)
            TourState.GENERATING -> getString(R.string.status_generating)
            TourState.SPEAKING -> getString(R.string.status_speaking)
            TourState.PAUSED -> getString(R.string.status_paused)
            TourState.NO_NEW_POIS -> getString(R.string.status_no_new_pois)
            TourState.ERROR -> getString(R.string.status_error)
        }

        binding.progressIndicator.visibility = when (state) {
            TourState.FETCHING, TourState.GENERATING, TourState.LOCATING -> View.VISIBLE
            else -> View.GONE
        }
    }

    private fun updateCurrentTopic(topic: String) {
        if (topic.isNotBlank()) {
            binding.tvCurrentTopic.text = getString(R.string.narrating_label, topic)
            binding.tvCurrentTopic.visibility = View.VISIBLE
        } else {
            binding.tvCurrentTopic.visibility = View.GONE
        }
    }

    private fun updateMentionedList(entries: List<MentionedPlacesStore.Entry>) {
        binding.llMentionedPlaces.removeAllViews()
        if (entries.isEmpty()) {
            binding.tvMentionedLabel.visibility = View.GONE
            return
        }
        binding.tvMentionedLabel.visibility = View.VISIBLE
        val sdf = SimpleDateFormat("MMM d 'at' h:mm a", Locale.getDefault())
        entries.forEachIndexed { index, entry ->
            val row = LayoutInflater.from(requireContext())
                .inflate(R.layout.item_mentioned_place, binding.llMentionedPlaces, false)
            row.findViewById<TextView>(R.id.tv_place_name).text = entry.name
            row.findViewById<TextView>(R.id.tv_place_time).text = sdf.format(Date(entry.mentionedAt))
            binding.llMentionedPlaces.addView(row)
            if (index < entries.lastIndex) {
                val divider = View(requireContext()).apply {
                    layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                    setBackgroundColor(requireContext().getColor(R.color.stroke))
                }
                binding.llMentionedPlaces.addView(divider)
            }
        }
    }

    private fun showApiKeyDialog() {
        val input = TextInputEditText(requireContext()).apply {
            hint = "sk-ant-..."
            setText(prefs.getString(PREF_API_KEY, ""))
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Anthropic API Key Required")
            .setMessage("Enter your Anthropic API key to enable AI narration.")
            .setView(input)
            .setPositiveButton("Save & Start") { _, _ ->
                val key = input.text?.toString()?.trim() ?: ""
                prefs.edit().putString(PREF_API_KEY, key).apply()
                if (key.isNotBlank()) viewModel.startTour(binding.rangeSlider.value / 4f, key)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSettingsDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_settings, null)

        // ── Wire up existing fields ──────────────────────────────
        val apiKeyInput = dialogView.findViewById<TextInputEditText>(R.id.et_api_key)
        val speechRateSlider = dialogView.findViewById<Slider>(R.id.slider_speech_rate)
        apiKeyInput.setText(prefs.getString(PREF_API_KEY, BuildConfig.ANTHROPIC_API_KEY))
        speechRateSlider.value = prefs.getFloat(PREF_SPEECH_RATE, 0.95f)

        // ── TTS provider radio ────────────────────────────────────
        val rgProvider = dialogView.findViewById<RadioGroup>(R.id.rg_tts_provider)
        val rbAndroid = dialogView.findViewById<android.widget.RadioButton>(R.id.rb_android)
        val rbOpenAi = dialogView.findViewById<android.widget.RadioButton>(R.id.rb_openai)
        val rbElevenLabs = dialogView.findViewById<android.widget.RadioButton>(R.id.rb_elevenlabs)
        when (prefs.getString(TourGuideService.PREF_TTS_PROVIDER, "android")) {
            "openai" -> rbOpenAi.isChecked = true
            "elevenlabs" -> rbElevenLabs.isChecked = true
            else -> rbAndroid.isChecked = true
        }

        // ── OpenAI fields ─────────────────────────────────────────
        val openAiKeyInput = dialogView.findViewById<TextInputEditText>(R.id.et_openai_key)
        openAiKeyInput.setText(prefs.getString(TourGuideService.PREF_OPENAI_TTS_KEY, ""))

        val openAiModels = listOf("tts-1  (faster, standard quality)", "tts-1-hd  (slower, higher quality)")
        val modelAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, openAiModels)
        val actvModel = dialogView.findViewById<AutoCompleteTextView>(R.id.actv_openai_model)
        actvModel.setAdapter(modelAdapter)
        val savedModel = prefs.getString(TourGuideService.PREF_OPENAI_TTS_MODEL, "tts-1-hd") ?: "tts-1-hd"
        actvModel.setText(if (savedModel == "tts-1") openAiModels[0] else openAiModels[1], false)

        // ── ElevenLabs fields ─────────────────────────────────────
        val elevenLabsKeyInput = dialogView.findViewById<TextInputEditText>(R.id.et_elevenlabs_key)
        elevenLabsKeyInput.setText(prefs.getString(TourGuideService.PREF_ELEVENLABS_KEY, ""))

        // ── Balance labels (fetch async when dialog opens) ────────
        val tvOpenAiBalance = dialogView.findViewById<TextView>(R.id.tv_openai_balance)
        val tvElevenLabsBalance = dialogView.findViewById<TextView>(R.id.tv_elevenlabs_balance)

        val storedOpenAiKey = prefs.getString(TourGuideService.PREF_OPENAI_TTS_KEY, "") ?: ""
        val storedElKey = prefs.getString(TourGuideService.PREF_ELEVENLABS_KEY, "") ?: ""

        if (storedOpenAiKey.isBlank()) {
            tvOpenAiBalance.text = "No key saved"
        } else {
            tvOpenAiBalance.text = "Loading…"
            lifecycleScope.launch {
                tvOpenAiBalance.text = fetchOpenAiBalance(storedOpenAiKey)
            }
        }
        if (storedElKey.isBlank()) {
            tvElevenLabsBalance.text = "No key saved"
        } else {
            tvElevenLabsBalance.text = "Loading…"
            lifecycleScope.launch {
                tvElevenLabsBalance.text = fetchElevenLabsBalance(storedElKey)
            }
        }

        // ── Build dialog ──────────────────────────────────────────
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.settings_title))
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val anthropicKey = apiKeyInput.text?.toString()?.trim() ?: ""
                val rate = speechRateSlider.value
                val provider = when (rgProvider.checkedRadioButtonId) {
                    R.id.rb_openai -> "openai"
                    R.id.rb_elevenlabs -> "elevenlabs"
                    else -> "android"
                }
                val openAiKey = openAiKeyInput.text?.toString()?.trim() ?: ""
                val openAiModel = if (actvModel.text.toString().startsWith("tts-1-hd")) "tts-1-hd" else "tts-1"
                val elKey = elevenLabsKeyInput.text?.toString()?.trim() ?: ""

                prefs.edit()
                    .putString(PREF_API_KEY, anthropicKey)
                    .putFloat(PREF_SPEECH_RATE, rate)
                    .putString(TourGuideService.PREF_TTS_PROVIDER, provider)
                    .putString(TourGuideService.PREF_OPENAI_TTS_KEY, openAiKey)
                    .putString(TourGuideService.PREF_OPENAI_TTS_MODEL, openAiModel)
                    .putString(TourGuideService.PREF_ELEVENLABS_KEY, elKey)
                    .apply()
                Toast.makeText(requireContext(), "Settings saved", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Clear History") { _, _ ->
                viewModel.clearHistory()
                Toast.makeText(requireContext(), "History cleared", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private suspend fun fetchOpenAiBalance(apiKey: String): String = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.openai.com/v1/dashboard/billing/credit_grants")
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext "Balance unavailable"
                val body = response.body?.string() ?: return@withContext "Balance unavailable"
                val json = JSONObject(body)
                val available = json.optDouble("total_available", -1.0)
                if (available >= 0) "\$${"%.2f".format(available)} remaining"
                else "Balance unavailable"
            }
        } catch (e: Exception) {
            "Balance unavailable"
        }
    }

    private suspend fun fetchElevenLabsBalance(apiKey: String): String = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.elevenlabs.io/v1/user/subscription")
                .header("xi-api-key", apiKey)
                .get()
                .build()
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext "Balance unavailable"
                val body = response.body?.string() ?: return@withContext "Balance unavailable"
                val json = JSONObject(body)
                val used = json.optInt("character_count", -1)
                val limit = json.optInt("character_limit", -1)
                if (used >= 0 && limit >= 0) {
                    val remaining = limit - used
                    "%,d / %,d chars left".format(remaining, limit)
                } else "Balance unavailable"
            }
        } catch (e: Exception) {
            "Balance unavailable"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val PREF_API_KEY = "pref_api_key"
        const val PREF_SPEECH_RATE = "pref_speech_rate"
    }
}
