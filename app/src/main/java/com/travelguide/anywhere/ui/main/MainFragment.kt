package com.travelguide.anywhere.ui.main

import android.content.SharedPreferences
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import coil.load
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
@AndroidEntryPoint
class MainFragment : Fragment() {

    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainViewModel by viewModels()

    private var sliderInSpeedMode = false
    private var isTripMode = false
    private var isFamousFirst = false
    private var tourIsActive = false
    private var blockModeChange = false
    private var urlDebounceJob: Job? = null
    private var savedRadiusIndex = DEFAULT_RADIUS_INDEX

    @Inject lateinit var prefs: SharedPreferences
    @Inject lateinit var kokoroModelManager: KokoroModelManager
    @Inject lateinit var mentionedPlacesStore: MentionedPlacesStore

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Restore mode and sort preferences before setting up slider.
        isTripMode = prefs.getBoolean(PREF_TRIP_MODE, false)
        isFamousFirst = prefs.getBoolean(PREF_FAMOUS_SORT, false)
        savedRadiusIndex = prefs.getInt(PREF_RADIUS_INDEX, DEFAULT_RADIUS_INDEX)

        setupModeToggle()
        setupSortToggle()
        setupSlider()
        setupButtons()
        setupRouteCard()
        setupNowPlayingActions()
        observeState()
        checkKokoroOnStartup()

        // Show Places Covered immediately on launch if previous-session history exists.
        if (TourGuideService.tourState.value == TourState.IDLE) {
            mentionedPlacesStore.load()
        }
        updatePlacesButton(mentionedPlacesStore.allSorted())
    }

    // ── Mode toggle ────────────────────────────────────────────────────────────

    private fun setupModeToggle() {
        binding.toggleMode.check(if (isTripMode) R.id.btn_mode_trip else R.id.btn_mode_live)
        binding.cardRoute.visibility = if (isTripMode) View.VISIBLE else View.GONE

        binding.toggleMode.addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            if (blockModeChange) return@addOnButtonCheckedListener
            if (tourIsActive) {
                blockModeChange = true
                group.check(if (isTripMode) R.id.btn_mode_trip else R.id.btn_mode_live)
                blockModeChange = false
                return@addOnButtonCheckedListener
            }
            val newTrip = checkedId == R.id.btn_mode_trip
            if (newTrip == isTripMode) return@addOnButtonCheckedListener
            val wasTrip = isTripMode
            isTripMode = newTrip
            if (wasTrip) viewModel.resetRoute()
            prefs.edit().putBoolean(PREF_TRIP_MODE, isTripMode).apply()
            binding.cardRoute.visibility = if (isTripMode) View.VISIBLE else View.GONE
        }
    }

    private fun setupSortToggle() {
        binding.toggleSort.check(if (isFamousFirst) R.id.btn_sort_famous else R.id.btn_sort_closest)

        binding.toggleSort.addOnButtonCheckedListener { group, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            if (blockModeChange) return@addOnButtonCheckedListener
            if (tourIsActive) {
                blockModeChange = true
                group.check(if (isFamousFirst) R.id.btn_sort_famous else R.id.btn_sort_closest)
                blockModeChange = false
                return@addOnButtonCheckedListener
            }
            isFamousFirst = checkedId == R.id.btn_sort_famous
            prefs.edit().putBoolean(PREF_FAMOUS_SORT, isFamousFirst).apply()
        }
    }

    // ── Slider ─────────────────────────────────────────────────────────────────

    private fun setupSlider() {
        applyModeSliderConfig()
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

    private fun applyModeSliderConfig() {
        binding.rangeSlider.apply {
            valueFrom = 0f
            valueTo = (SLIDER_MILES.size - 1).toFloat()
            stepSize = 1f
            value = savedRadiusIndex.toFloat()
        }
        updateRangeLabel(savedRadiusIndex.toFloat())
    }

    private fun switchSliderToSpeedMode() {
        if (sliderInSpeedMode) return
        savedRadiusIndex = binding.rangeSlider.value.toInt()
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
        applyModeSliderConfig()
    }

    private fun updateRangeLabel(value: Float) {
        val miles = SLIDER_MILES.getOrElse(value.toInt()) { SLIDER_MILES[DEFAULT_RADIUS_INDEX] }
        val formatted = when {
            miles < 1f  -> "%.2f".format(miles)
            miles < 10f -> "%.1f".format(miles)
            else        -> "%.0f".format(miles)
        }
        binding.tvRangeLabel.text = getString(R.string.range_label, formatted)
    }

    private fun updateSpeedLabel(rate: Float) {
        binding.tvRangeLabel.text = getString(R.string.speed_label, "%.2f".format(rate))
    }

    private fun currentRadiusMiles(): Float {
        val index = if (sliderInSpeedMode) savedRadiusIndex else binding.rangeSlider.value.toInt()
        return SLIDER_MILES.getOrElse(index) { SLIDER_MILES[DEFAULT_RADIUS_INDEX] }
    }

    // ── Buttons ────────────────────────────────────────────────────────────────

    private fun setupButtons() {
        binding.btnGo.setOnClickListener {
            val apiKey = resolveApiKey()
            if (apiKey.isBlank() || apiKey == "YOUR_API_KEY_HERE") {
                showApiKeyDialog()
                return@setOnClickListener
            }
            if (isTripMode) {
                val routeState = viewModel.routeParseState.value
                if (routeState !is MainViewModel.RouteParseState.Ready) {
                    Toast.makeText(requireContext(),
                        "Paste a valid Google Maps directions link first",
                        Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                viewModel.startRouteTour(currentRadiusMiles(), apiKey)
            } else {
                viewModel.startTour(currentRadiusMiles(), apiKey, isFamousFirst)
            }
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
        binding.ivPoiImage.setOnClickListener { showFullScreenImage() }
        binding.btnPlacesCovered.setOnClickListener { PlacesBottomSheetFragment().show(childFragmentManager, "places") }
    }

    // ── Route card ─────────────────────────────────────────────────────────────

    private fun setupRouteCard() {
        binding.etRouteUrl.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                urlDebounceJob?.cancel()
                urlDebounceJob = viewLifecycleOwner.lifecycleScope.launch {
                    delay(800L)
                    viewModel.parseRoute(s?.toString()?.trim() ?: "")
                }
            }
        })
    }

    private fun updateRouteStatus(state: MainViewModel.RouteParseState) {
        val tv = binding.tvRouteStatus
        when (state) {
            is MainViewModel.RouteParseState.Idle -> tv.visibility = View.GONE
            is MainViewModel.RouteParseState.Loading -> {
                tv.visibility = View.VISIBLE
                tv.setTextColor(requireContext().getColor(R.color.text_secondary))
                tv.text = "Parsing route…"
            }
            is MainViewModel.RouteParseState.Ready -> {
                tv.visibility = View.VISIBLE
                tv.setTextColor(requireContext().getColor(R.color.accent))
                tv.text = "Route ready: ${state.route.summaryText}"
            }
            is MainViewModel.RouteParseState.Error -> {
                tv.visibility = View.VISIBLE
                tv.setTextColor(0xFFFF5252.toInt())
                tv.text = state.message
            }
        }
    }

    // ── Kokoro startup ─────────────────────────────────────────────────────────

    private fun checkKokoroOnStartup() {
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

    // ── API key ────────────────────────────────────────────────────────────────

    private fun resolveApiKey(): String {
        val saved = prefs.getString(PREF_API_KEY, null)
        return if (!saved.isNullOrBlank()) saved else BuildConfig.ANTHROPIC_API_KEY
    }

    // ── State observation ──────────────────────────────────────────────────────

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { viewModel.tourState.collect { updateUiForState(it) } }
                launch {
                    combine(viewModel.tourState, viewModel.loadingProgress) { state, progress ->
                        state to progress
                    }.collect { (state, progress) ->
                        val ind = binding.progressIndicator
                        if (state == TourState.LOADING_AUDIO && progress >= 0f) {
                            if (ind.isIndeterminate) ind.isIndeterminate = false
                            ind.setProgressCompat((progress * 100).toInt(), false)
                        } else if (!ind.isIndeterminate) {
                            ind.isIndeterminate = true
                        }
                    }
                }
                launch { viewModel.currentTopic.collect { updateCurrentTopic(it) } }
                launch { viewModel.mentionedPlaces.collect { updatePlacesButton(it) } }
                launch { TourGuideService.currentPoiMeta.collect { meta ->
                    binding.btnPoiWikipedia.visibility = if (meta.wikipediaUrl != null) View.VISIBLE else View.GONE
                } }
                launch { viewModel.errorMessage.collect { it?.let { msg ->
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                    TourGuideService.errorMessage.value = null  // consume — prevents re-toast on lifecycle restart
                }}}
                launch { viewModel.currentPoiImage.collect { imageUrl ->
                    if (imageUrl != null) {
                        android.util.Log.d("MainFragment", "Loading POI image: $imageUrl")
                        binding.ivPoiImage.load(imageUrl) {
                            crossfade(300)
                            listener(
                                onSuccess = { _, _ ->
                                    binding.ivPoiImage.visibility = View.VISIBLE
                                    binding.tvImageCredit.visibility = View.VISIBLE
                                },
                                onError = { _, result ->
                                    android.util.Log.w("MainFragment",
                                        "Coil failed to load $imageUrl — ${result.throwable}")
                                    binding.ivPoiImage.visibility = View.GONE
                                    binding.tvImageCredit.visibility = View.GONE
                                }
                            )
                        }
                        // Show image+credit immediately so there's no flash if load succeeds.
                        // onError above will hide them if loading fails.
                        binding.ivPoiImage.visibility = View.VISIBLE
                        binding.tvImageCredit.visibility = View.VISIBLE
                    } else {
                        binding.ivPoiImage.visibility = View.GONE
                        binding.tvImageCredit.visibility = View.GONE
                    }
                }}
                launch { viewModel.routeParseState.collect { updateRouteStatus(it) } }
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
        tourIsActive = isActive
        binding.toggleMode.alpha = if (isActive) 0.78f else 1.0f
        binding.toggleSort.alpha = if (isActive) 0.78f else 1.0f
        binding.cardRoute.visibility = when {
            isTripMode && !isActive -> View.VISIBLE
            isTripMode && isActive -> View.GONE
            else -> View.GONE
        }
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
            TourState.FETCHING -> when {
                isFamousFirst -> "Finding famous landmarks…"
                isTripMode -> "Finding places along route…"
                else -> getString(R.string.status_fetching)
            }
            TourState.GENERATING -> getString(R.string.status_generating)
            TourState.LOADING_AUDIO -> getString(R.string.status_loading_audio)
            TourState.SPEAKING -> {
                val provider = prefs.getString(TourGuideService.PREF_TTS_PROVIDER, "android") ?: "android"
                val voiceName = when (provider) {
                    "kokoro" -> {
                        val sid = prefs.getInt(PREF_KOKORO_VOICE_SID, DEFAULT_KOKORO_VOICE_SID)
                        KOKORO_VOICES.getOrNull(sid)?.first?.substringBefore(" ") ?: ""
                    }
                    "piper" -> {
                        val voiceId = prefs.getString(TourGuideService.PREF_PIPER_VOICE_ID,
                            com.travelguide.anywhere.service.PiperVoices.DEFAULT_VOICE_ID) ?: ""
                        com.travelguide.anywhere.service.PiperVoices.byId(voiceId)
                            ?.displayName?.substringBefore(" ") ?: ""
                    }
                    "openai" -> "OpenAI"
                    else -> ""
                }
                if (voiceName.isNotBlank()) "${getString(R.string.status_speaking)} ($voiceName)"
                else getString(R.string.status_speaking)
            }
            TourState.PAUSED -> getString(R.string.status_paused)
            TourState.NO_NEW_POIS -> if (isFamousFirst) getString(R.string.status_no_new_pois_famous)
                                     else getString(R.string.status_no_new_pois)
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
            binding.layoutPoiActions.visibility = View.VISIBLE
            refreshActionBarState()
        } else {
            binding.tvCurrentTopic.visibility = View.GONE
            binding.layoutPoiActions.visibility = View.GONE
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
                if (key.isNotBlank()) {
                    viewModel.startTour(currentRadiusMiles(), key, isFamousFirst)
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun setupNowPlayingActions() {
        val secondary = android.content.res.ColorStateList.valueOf(requireContext().getColor(R.color.text_secondary))
        binding.btnPoiNavigate.imageTintList = secondary
        binding.btnPoiWikipedia.imageTintList = secondary
        binding.btnPoiWantToVisit.imageTintList = secondary
        binding.btnPoiThumbsDown.imageTintList = secondary
        binding.btnPoiThumbsDown.alpha = 0.5f

        binding.btnPoiNavigate.setOnClickListener {
            val poi = TourGuideService.currentPois.value.firstOrNull() ?: return@setOnClickListener
            val uri = android.net.Uri.parse("geo:${poi.lat},${poi.lon}?q=${android.net.Uri.encode(poi.name)}")
            try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri)) }
            catch (_: Exception) { Toast.makeText(requireContext(), "No maps app found", Toast.LENGTH_SHORT).show() }
        }
        binding.btnPoiWikipedia.setOnClickListener {
            val url = TourGuideService.currentPoiMeta.value.wikipediaUrl ?: return@setOnClickListener
            try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))) }
            catch (_: Exception) { Toast.makeText(requireContext(), "No browser found", Toast.LENGTH_SHORT).show() }
        }
        binding.btnPoiWantToVisit.setOnClickListener {
            val poi = TourGuideService.currentPois.value.firstOrNull() ?: return@setOnClickListener
            val meta = TourGuideService.currentPoiMeta.value
            mentionedPlacesStore.commitEarly(poi.osmId, poi.name, poi.lat, poi.lon, meta.summary, meta.wikipediaUrl, poi.tags)
            mentionedPlacesStore.markWantToVisit(poi.osmId)
            TourGuideService.mentionedPlaces.value = mentionedPlacesStore.recentFive()
            refreshActionBarState()
        }
        binding.btnPoiThumbsDown.setOnClickListener {
            val poi = TourGuideService.currentPois.value.firstOrNull() ?: return@setOnClickListener
            val meta = TourGuideService.currentPoiMeta.value
            mentionedPlacesStore.commitEarly(poi.osmId, poi.name, poi.lat, poi.lon, meta.summary, meta.wikipediaUrl, poi.tags)
            mentionedPlacesStore.markThumbsDown(poi.osmId)
            TourGuideService.mentionedPlaces.value = mentionedPlacesStore.recentFive()
            refreshActionBarState()
        }
    }

    private fun refreshActionBarState() {
        val poi = TourGuideService.currentPois.value.firstOrNull() ?: return
        val entry = mentionedPlacesStore.allSorted().find { it.osmId == poi.osmId }
        val wantToVisit = entry?.wantToVisit ?: false
        val thumbsDown = entry?.thumbsDown ?: false
        val ctx = requireContext()
        val accentColor = ctx.getColor(R.color.accent)
        val secondaryColor = ctx.getColor(R.color.text_secondary)
        val redColor = ctx.getColor(R.color.stop_color)
        binding.btnPoiWantToVisit.setImageResource(if (wantToVisit) R.drawable.ic_favorite else R.drawable.ic_favorite_border)
        binding.btnPoiWantToVisit.imageTintList = android.content.res.ColorStateList.valueOf(if (wantToVisit) accentColor else secondaryColor)
        binding.btnPoiThumbsDown.imageTintList = android.content.res.ColorStateList.valueOf(if (thumbsDown) redColor else secondaryColor)
        binding.btnPoiThumbsDown.alpha = if (thumbsDown) 1f else 0.5f
    }

    private fun updatePlacesButton(entries: List<MentionedPlacesStore.Entry>) {
        val nonSkipped = entries.count { !it.autoSkipped }
        if (nonSkipped == 0 && mentionedPlacesStore.allSorted().isEmpty()) {
            binding.btnPlacesCovered.visibility = View.GONE
        } else {
            val total = mentionedPlacesStore.allSorted().size
            binding.btnPlacesCovered.text = "Places Covered ($total)"
            binding.btnPlacesCovered.visibility = View.VISIBLE
        }
    }

    private fun showFullScreenImage() {
        val url = viewModel.currentPoiImage.value ?: return
        val ctx = requireContext()
        val dialog = android.app.Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen)

        val zoomView = ZoomableImageView(ctx).apply {
            load(url)
        }

        val btnClose = android.widget.TextView(ctx).apply {
            text = "✕"
            textSize = 22f
            setTextColor(0xCCFFFFFF.toInt())
            setPadding(32, 48, 32, 32)
            setOnClickListener { dialog.dismiss() }
        }

        val frame = android.widget.FrameLayout(ctx).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(zoomView, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT
            ))
            addView(btnClose, android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP or android.view.Gravity.END
            ))
        }
        dialog.setContentView(frame)
        dialog.show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        if (!sliderInSpeedMode) savedRadiusIndex = binding.rangeSlider.value.toInt()
        prefs.edit().putInt(PREF_RADIUS_INDEX, savedRadiusIndex).apply()
        _binding = null
    }

    companion object {
        const val PREF_API_KEY = "pref_api_key"
        const val PREF_SPEECH_RATE = "pref_speech_rate"
        const val PREF_KOKORO_VOICE_SID = "pref_kokoro_voice_sid"
        const val DEFAULT_KOKORO_VOICE_SID = 17 // Onyx (American Male)
        const val PREF_TRIP_MODE = "pref_route_mode"
        const val PREF_FAMOUS_SORT = "pref_famous_mode"
        const val PREF_RADIUS_INDEX = "pref_radius_index"
        const val DEFAULT_RADIUS_INDEX = 7  // 5.0 miles
        private const val PREF_KOKORO_AUTO_SELECTED = "pref_kokoro_auto_selected"

        val SLIDER_MILES = floatArrayOf(
            0.25f, 0.5f, 0.75f, 1.0f, 1.5f, 2.0f, 3.0f, 5.0f,
            10f, 20f, 30f, 40f, 50f, 60f, 70f, 80f, 90f, 100f
        )

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
