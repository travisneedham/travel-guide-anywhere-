package com.travelguide.anywhere.ui.main

import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.travelguide.anywhere.BuildConfig
import com.travelguide.anywhere.R
import com.travelguide.anywhere.databinding.FragmentMainBinding
import com.travelguide.anywhere.service.TourState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainFragment : Fragment() {

    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by viewModels()

    @Inject lateinit var prefs: SharedPreferences

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
        binding.rangeSlider.value = 1.0f
        binding.tvRangeLabel.text = getString(R.string.range_label, "1.0")
        binding.rangeSlider.addOnChangeListener { _, value, _ ->
            val formatted = if (value < 1f) "%.1f".format(value) else "%.0f".format(value)
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
            viewModel.startTour(binding.rangeSlider.value, apiKey)
        }

        binding.btnStop.setOnClickListener {
            viewModel.stopTour()
        }

        binding.btnSettings.setOnClickListener {
            showSettingsDialog()
        }
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
                launch { viewModel.mentionedPlaces.collect { updateMentionedChips(it.map { p -> p.name }) } }
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

        binding.tvStatus.text = when (state) {
            TourState.IDLE -> ""
            TourState.LOCATING -> getString(R.string.status_locating)
            TourState.FETCHING -> getString(R.string.status_fetching)
            TourState.GENERATING -> getString(R.string.status_generating)
            TourState.SPEAKING -> getString(R.string.status_speaking)
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

    private fun updateMentionedChips(names: List<String>) {
        binding.chipGroupMentioned.removeAllViews()
        names.forEach { name ->
            val chip = Chip(requireContext()).apply {
                text = name
                isClickable = false
            }
            binding.chipGroupMentioned.addView(chip)
        }
        binding.tvMentionedLabel.visibility = if (names.isEmpty()) View.GONE else View.VISIBLE
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
                if (key.isNotBlank()) viewModel.startTour(binding.rangeSlider.value, key)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showSettingsDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_settings, null)
        val apiKeyInput = dialogView.findViewById<TextInputEditText>(R.id.et_api_key)
        val speechRateSlider = dialogView.findViewById<com.google.android.material.slider.Slider>(R.id.slider_speech_rate)
        apiKeyInput.setText(prefs.getString(PREF_API_KEY, BuildConfig.ANTHROPIC_API_KEY))
        speechRateSlider.value = prefs.getFloat(PREF_SPEECH_RATE, 0.95f)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.settings_title))
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val key = apiKeyInput.text?.toString()?.trim() ?: ""
                val rate = speechRateSlider.value
                prefs.edit()
                    .putString(PREF_API_KEY, key)
                    .putFloat(PREF_SPEECH_RATE, rate)
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

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val PREF_API_KEY = "pref_api_key"
        const val PREF_SPEECH_RATE = "pref_speech_rate"
    }
}
