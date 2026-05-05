package com.travelguide.anywhere.ui.main

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.travelguide.anywhere.BuildConfig
import com.travelguide.anywhere.R
import com.travelguide.anywhere.data.local.MentionedPlacesStore
import com.travelguide.anywhere.databinding.FragmentMainBinding
import com.travelguide.anywhere.service.KokoroDownloadService
import com.travelguide.anywhere.service.KokoroModelManager
import com.travelguide.anywhere.service.TourGuideService
import com.travelguide.anywhere.service.TourState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class MainFragment : Fragment() {

    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by viewModels()

    private var sliderInSpeedMode = false
    private var savedRangeSliderValue = 4f

    @Inject lateinit var prefs: SharedPreferences
    @Inject lateinit var kokoroModelManager: KokoroModelManager

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
        checkKokoroOnStartup()
    }

    private fun setupSlider() {
        binding.rangeSlider.value = savedRangeSliderValue
        updateRangeLabel(savedRangeSliderValue)
        binding.rangeSlider.addOnChangeListener { _, value, fromUser ->
            if (sliderInSpeedMode) {
                updateSpeedLabel(value)
                if (fromUser) {
                    prefs.edit().putFloat(PREF_SPEECH_RATE, value).apply()
                    viewModel.setPlaybackSpeed(value)
                }
            } else {
                updateRangeLabel(value)
            }
        }
    }

    private fun switchSliderToSpeedMode() {
        if (sliderInSpeedMode) return
        savedRangeSliderValue = binding.rangeSlider.value
        sliderInSpeedMode = true
        val rate = prefs.getFloat(PREF_SPEECH_RATE, 0.95f).coerceIn(0.5f, 1.5f)
        binding.rangeSlider.apply {
            valueFrom = 0.5f
            valueTo = 1.5f
            stepSize = 0.05f
            value = rate
        }
        updateSpeedLabel(rate)
    }

    private fun switchSliderToRangeMode() {
        if (!sliderInSpeedMode) return
        sliderInSpeedMode = false
        binding.rangeSlider.apply {
            valueFrom = 1f
            valueTo = 40f
            stepSize = 1f
            value = savedRangeSliderValue
        }
        updateRangeLabel(savedRangeSliderValue)
    }

    private fun updateRangeLabel(value: Float) {
        val miles = value / 4f
        val formatted = if (miles < 1f) "%.2f".format(miles) else "%.1f".format(miles)
        binding.tvRangeLabel.text = getString(R.string.range_label, formatted)
    }

    private fun updateSpeedLabel(rate: Float) {
        binding.tvRangeLabel.text = getString(R.string.speed_label, "%.2f".format(rate))
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
        binding.btnSettings.setOnClickListener {
            parentFragmentManager.commit {
                replace(R.id.fragment_container, SettingsFragment())
                addToBackStack(null)
            }
        }
    }

    private fun checkKokoroOnStartup() {
        // If already ready, ensure kokoro is selected and previews are cached.
        if (kokoroModelManager.isReady) {
            if (!prefs.getBoolean(PREF_KOKORO_AUTO_SELECTED, false)) {
                prefs.edit()
                    .putString(TourGuideService.PREF_TTS_PROVIDER, "kokoro")
                    .putBoolean(PREF_KOKORO_AUTO_SELECTED, true)
                    .apply()
            }
            kokoroModelManager.ensureVoicePreviews(
                KOKORO_VOICES,
                com.travelguide.anywhere.service.KokoroTtsEngine.VOICE_PREVIEW_TEMPLATE
            )
            return
        }
        // Don't stack a prompt on top of an already-running download.
        val s = kokoroModelManager.state.value
        if (s is KokoroModelManager.DownloadState.Downloading ||
            s is KokoroModelManager.DownloadState.Extracting) return

        val hasPartial = File(requireContext().cacheDir, "kokoro-model.tar.bz2")
            .let { it.exists() && it.length() > 0 }
        val buttonLabel = if (hasPartial) "Resume Download" else "Download (~350 MB)"

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Download AI Voice?")
            .setMessage(
                "Kokoro provides high-quality, on-device narration — no internet needed once installed. The model is ~350 MB."
            )
            .setPositiveButton(buttonLabel) { _, _ -> KokoroDownloadService.start(requireContext()) }
            .setNegativeButton("Later", null)
            .show()
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
                // Auto-select Kokoro + kick off voice preview caching when model is ready.
                launch {
                    kokoroModelManager.state.collect { state ->
                        if (state is KokoroModelManager.DownloadState.Ready) {
                            if (!prefs.getBoolean(PREF_KOKORO_AUTO_SELECTED, false)) {
                                prefs.edit()
                                    .putString(TourGuideService.PREF_TTS_PROVIDER, "kokoro")
                                    .putBoolean(PREF_KOKORO_AUTO_SELECTED, true)
                                    .apply()
                            }
                            kokoroModelManager.ensureVoicePreviews(
                                KOKORO_VOICES,
                                com.travelguide.anywhere.service.KokoroTtsEngine.VOICE_PREVIEW_TEMPLATE
                            )
                        }
                    }
                }
            }
        }
    }

    private fun updateUiForState(state: TourState) {
        val isActive = state != TourState.IDLE && state != TourState.ERROR
        binding.btnGo.visibility = if (isActive) View.GONE else View.VISIBLE
        binding.btnStop.visibility = if (isActive) View.VISIBLE else View.GONE
        binding.cardStatus.visibility = if (isActive) View.VISIBLE else View.GONE
        binding.tvIdle.visibility = if (isActive) View.GONE else View.VISIBLE
        if (isActive) switchSliderToSpeedMode() else switchSliderToRangeMode()

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
            TourState.LOADING_AUDIO -> getString(R.string.status_loading_audio)
            TourState.SPEAKING -> getString(R.string.status_speaking)
            TourState.PAUSED -> getString(R.string.status_paused)
            TourState.NO_NEW_POIS -> getString(R.string.status_no_new_pois)
            TourState.ERROR -> getString(R.string.status_error)
        }

        binding.progressIndicator.visibility = when (state) {
            TourState.LOCATING, TourState.FETCHING, TourState.GENERATING,
            TourState.LOADING_AUDIO, TourState.NO_NEW_POIS -> View.VISIBLE
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val PREF_API_KEY = "pref_api_key"
        const val PREF_SPEECH_RATE = "pref_speech_rate"
        const val PREF_KOKORO_VOICE_SID = "pref_kokoro_voice_sid"
        const val DEFAULT_KOKORO_VOICE_SID = 17 // Onyx (American Male)
        private const val PREF_KOKORO_AUTO_SELECTED = "pref_kokoro_auto_selected"

        // kokoro-multi-lang-v1_0 — speaker IDs 0-52
        val KOKORO_VOICES = listOf(
            "Alloy (American Female)" to 0,
            "Aoede (American Female)" to 1,
            "Bella (American Female)" to 2,
            "Heart (American Female)" to 3,
            "Jessica (American Female)" to 4,
            "Kore (American Female)" to 5,
            "Nicole (American Female)" to 6,
            "Nova (American Female)" to 7,
            "River (American Female)" to 8,
            "Sarah (American Female)" to 9,
            "Sky (American Female)" to 10,
            "Adam (American Male)" to 11,
            "Echo (American Male)" to 12,
            "Eric (American Male)" to 13,
            "Fenrir (American Male)" to 14,
            "Liam (American Male)" to 15,
            "Michael (American Male)" to 16,
            "Onyx (American Male)" to 17,
            "Puck (American Male)" to 18,
            "Santa (American Male)" to 19,
            "Alice (British Female)" to 20,
            "Emma (British Female)" to 21,
            "Isabella (British Female)" to 22,
            "Lily (British Female)" to 23,
            "Daniel (British Male)" to 24,
            "Fable (British Male)" to 25,
            "George (British Male)" to 26,
            "Lewis (British Male)" to 27,
            "Dora (Spanish Female)" to 28,
            "Alex (Spanish Male)" to 29,
            "Siwis (French Female)" to 30,
            "Alpha (Hindi Female)" to 31,
            "Beta (Hindi Female)" to 32,
            "Omega (Hindi Male)" to 33,
            "Psi (Hindi Male)" to 34,
            "Sara (Italian Female)" to 35,
            "Nicola (Italian Male)" to 36,
            "Alpha (Japanese Female)" to 37,
            "Gongitsune (Japanese Female)" to 38,
            "Nezumi (Japanese Female)" to 39,
            "Tebukuro (Japanese Female)" to 40,
            "Kumo (Japanese Male)" to 41,
            "Dora (Portuguese Female)" to 42,
            "Alex (Portuguese Male)" to 43,
            "Santa (Portuguese Male)" to 44,
            "Xiaobei (Chinese Female)" to 45,
            "Xiaoni (Chinese Female)" to 46,
            "Xiaoxiao (Chinese Female)" to 47,
            "Xiaoyi (Chinese Female)" to 48,
            "Yunjian (Chinese Male)" to 49,
            "Yunxi (Chinese Male)" to 50,
            "Yunxia (Chinese Male)" to 51,
            "Yunyang (Chinese Male)" to 52,
        )

        fun langForVoiceSid(sid: Int): String = when (sid) {
            in 0..27  -> "en-us"
            28, 29    -> "es"
            30        -> "fr-fr"
            in 31..34 -> "hi"
            in 35..36 -> "it"
            in 37..41 -> "ja"
            in 42..44 -> "pt-br"
            in 45..52 -> "zh-cn"
            else      -> "en-us"
        }
    }
}
