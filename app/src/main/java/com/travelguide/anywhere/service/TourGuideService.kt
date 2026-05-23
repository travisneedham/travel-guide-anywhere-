package com.travelguide.anywhere.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.location.Location
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.Priority
import com.travelguide.anywhere.MainActivity
import com.travelguide.anywhere.R
import com.travelguide.anywhere.data.local.MentionedPlacesStore
import com.travelguide.anywhere.data.local.NarrationHistoryStore
import com.travelguide.anywhere.data.model.PlaceOfInterest
import com.travelguide.anywhere.data.model.PoiType
import com.travelguide.anywhere.data.model.RouteData
import com.travelguide.anywhere.repository.NarrationRepository
import com.travelguide.anywhere.repository.PoiImageRepository
import com.travelguide.anywhere.repository.PoiRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import com.travelguide.anywhere.ui.main.MainFragment
import javax.inject.Inject

@AndroidEntryPoint
class TourGuideService : LifecycleService() {

    @Inject lateinit var fusedLocation: FusedLocationProviderClient
    @Inject lateinit var poiRepository: PoiRepository
    @Inject lateinit var narrationRepository: NarrationRepository
    @Inject lateinit var mentionedPlacesStore: MentionedPlacesStore
    @Inject lateinit var narrationHistoryStore: NarrationHistoryStore
    @Inject lateinit var kokoroModelManager: KokoroModelManager
    @Inject lateinit var piperModelManager: PiperModelManager
    @Inject lateinit var poiImageRepository: PoiImageRepository

    private var ttsEngine: TtsEngine? = null
    private var radiusMiles = 1f
    private var apiKey = ""
    private var famousMode = false
    private var lastLocation: Location? = null
        set(value) {
            field = value
            if (value != null) lastKnownLocation.value = value
        }
    private var generationJob: Job? = null
    private var prefetchJob: Job? = null
    private var imageFetchJob: Job? = null
    private var routeSimulator: RouteSimulator? = null
    private var routeAdvanceJob: Job? = null
    private var isSpeaking = false
    private var isGenerating = false
    private var isReplayMode = false
    private var savedTopicName = ""
    @Volatile private var deepDivePoiName: String = ""
    @Volatile private var deepDivePoiSummary: String = ""
    @Volatile private var deepDiveOriginalPoi: PlaceOfInterest? = null
    @Volatile private var lastNarratedPoi: PlaceOfInterest? = null
    @Volatile private var deepDiveCount: Int = 0
    @Volatile private var currentDeepDiveTitle: String = ""
    @Volatile private var prefetchedDeepDiveResult: NarrationRepository.NarrationResult? = null
    private var deepDivePrefetchJob: Job? = null
    private val deepDiveMaxIterations = 10
    // Incremented on every skip/stop so onDone callbacks from prior narrations are discarded.
    private var speakGeneration = 0

    @Volatile private var currentNarrationPoi: PlaceOfInterest? = null
    @Volatile private var currentNarrationCommit: (() -> Unit)? = null
    @Volatile private var currentNarrationSummary: String = ""
    @Volatile private var currentNarrationWikipediaUrl: String? = null
    @Volatile private var speakStartTime: Long = 0L
    @Volatile private var prefetchedNarration: Pair<PlaceOfInterest, String>? = null
    @Volatile private var prefetchedNarrationCommit: (() -> Unit)? = null
    @Volatile private var prefetchedNarrationSummary: String = ""
    @Volatile private var prefetchedWikipediaUrl: String? = null

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { onLocationUpdate(it) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START -> {
                initTtsEngine()
                mentionedPlacesStore.load()
                narrationHistoryStore.load()
                radiusMiles = intent.getFloatExtra(EXTRA_RADIUS_MILES, 1f)
                apiKey = intent.getStringExtra(EXTRA_API_KEY) ?: ""
                famousMode = intent.getBooleanExtra(EXTRA_FAMOUS_MODE, false)
                sharedPrefs.edit()
                    .putFloat(PREF_LAST_RADIUS, radiusMiles)
                    .putBoolean(PREF_FAMOUS_MODE, famousMode)
                    .apply()
                startForeground(NOTIFICATION_ID, buildNotification("Starting tour...", true))
                val route = pendingRoute.also { pendingRoute = null }
                if (route != null) startRouteSimulation(route) else requestLocationUpdates()
                emitState(TourState.LOCATING)
                mentionedPlaces.value = mentionedPlacesStore.recentFive()
            }
            ACTION_STOP -> stopTour()
            ACTION_PAUSE -> pauseTour()
            ACTION_RESUME -> resumeTour()
            ACTION_SKIP -> skipCurrent()
            ACTION_SET_SPEED -> {
                ttsEngine?.setSpeed(intent.getFloatExtra(EXTRA_SPEECH_RATE, 1.0f))
                if (tourState.value == TourState.PAUSED) {
                    isSpeaking = true
                    emitState(TourState.SPEAKING)
                    updateNotification("Now: $savedTopicName")
                }
            }
            ACTION_REPLAY_POI -> handleReplayPoi(intent)
            ACTION_TOGGLE_DEEP_DIVE -> toggleDeepDive()
        }

        return START_STICKY
    }

    private fun toggleDeepDive() {
        if (isDeepDive.value) {
            // User-initiated exit. Don't clear segments — the original card stays
            // visible (with whatever's already in the list) until the next POI starts.
            isDeepDive.value = false
            deepDivePrefetchJob?.cancel(); deepDivePrefetchJob = null
            prefetchedDeepDiveResult = null
        } else {
            val originalPoi = currentNarrationPoi ?: lastNarratedPoi
            if (originalPoi == null) {
                Log.w(TAG, "Deep dive: no current/last POI — ignoring toggle")
                return
            }
            isDeepDive.value = true
            deepDiveOriginalPoi = originalPoi
            deepDivePoiName = originalPoi.name
            deepDivePoiSummary = currentNarrationSummary
            deepDiveCount = 0
            currentDeepDiveTitle = ""
            deepDiveSegments.value = emptyList()
            // Discard any pending normal POI prefetch — we're branching into a dive.
            prefetchJob?.cancel(); prefetchJob = null
            prefetchedNarration = null
            prefetchedNarrationCommit = null
            prefetchedNarrationSummary = ""
            prefetchedWikipediaUrl = null
            if (isSpeaking) {
                // Mid-narration: pre-warm so the first dive is ready when current ends.
                startDeepDivePrefetch()
            } else {
                // Between narrations: cancel any in-flight next-POI fetch and start
                // the first dive on the just-finished subject immediately.
                generationJob?.cancel()
                startDeepDiveCycle()
            }
        }
        Log.i(TAG, "Deep dive toggled: ${isDeepDive.value}, subject='$deepDivePoiName'")
    }

    private fun startDeepDivePrefetch() {
        val location = lastLocation ?: return
        val name = deepDivePoiName
        val summary = deepDivePoiSummary
        deepDivePrefetchJob?.cancel()
        deepDivePrefetchJob = lifecycleScope.launch {
            try {
                val result = narrationRepository.generateDeepDiveNarration(name, summary, location, radiusMiles)
                prefetchedDeepDiveResult = result
                Log.i(TAG, "Deep dive prefetch complete for '$name'")
            } catch (_: CancellationException) { throw CancellationException() }
            catch (e: Exception) {
                Log.e(TAG, "Deep dive prefetch failed", e)
            }
        }
    }

    private fun clearDeepDiveContext() {
        deepDiveOriginalPoi = null
        deepDivePoiName = ""
        deepDivePoiSummary = ""
        deepDiveCount = 0
        currentDeepDiveTitle = ""
        deepDiveSegments.value = emptyList()
        deepDivePrefetchJob?.cancel(); deepDivePrefetchJob = null
        prefetchedDeepDiveResult = null
    }

    private fun startDeepDiveCycle() {
        // Cancel any background prefetch — generation happens inside this cycle now.
        deepDivePrefetchJob?.cancel(); deepDivePrefetchJob = null
        val ready = prefetchedDeepDiveResult.also { prefetchedDeepDiveResult = null }
        val location = lastLocation ?: run {
            Log.w(TAG, "Deep dive: no location — exiting mode")
            isDeepDive.value = false
            return
        }
        val name = deepDivePoiName
        val originalPoi = deepDiveOriginalPoi
        generationJob = lifecycleScope.launch {
            try {
                isGenerating = true
                emitState(TourState.GENERATING)
                updateNotification("Deep dive: $name…", true)
                // 3-second silence and generation run in parallel; audio only plays
                // once both are done so there's always a pause between narrations.
                val pauseJob = launch { delay(3_000L) }
                val result = ready ?: narrationRepository.generateDeepDiveNarration(
                    name, deepDivePoiSummary, location, radiusMiles
                )
                pauseJob.join()
                currentNarrationCommit = result.commitHistory
                currentNarrationSummary = result.summary
                isGenerating = false
                deepDiveCount += 1
                val title = result.title.ifBlank { result.summary.take(40) }
                currentDeepDiveTitle = title
                if (title.isNotBlank()) {
                    deepDiveSegments.value = deepDiveSegments.value + title
                }
                // 10-dive cap: turn the mode off on the final dive so the next narration
                // returns to the normal POI tour. The 10th dive still plays in full.
                if (deepDiveCount >= deepDiveMaxIterations) {
                    isDeepDive.value = false
                }
                val topicName = originalPoi?.name ?: name
                speak(result.text, topicName)
            } catch (e: CancellationException) {
                isGenerating = false
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error in deep dive cycle", e)
                isGenerating = false
                isDeepDive.value = false
                clearDeepDiveContext()
                lastLocation?.let { onLocationUpdate(it) }
            }
        }
    }

    private fun handleReplayPoi(intent: Intent) {
        val osmId = intent.getStringExtra(EXTRA_POI_OSM_ID) ?: return
        val name = intent.getStringExtra(EXTRA_POI_NAME) ?: return
        val lat = intent.getDoubleExtra(EXTRA_POI_LAT, 0.0)
        val lon = intent.getDoubleExtra(EXTRA_POI_LON, 0.0)
        apiKey = intent.getStringExtra(EXTRA_API_KEY) ?: ""
        initTtsEngine()
        mentionedPlacesStore.load()
        narrationHistoryStore.load()
        val storedEntry = mentionedPlacesStore.allSorted().find { it.osmId == osmId }
        val storedTags = storedEntry?.tags ?: emptyMap()
        val storedWikiUrl = storedEntry?.wikipediaUrl
        isReplayMode = true
        startForeground(NOTIFICATION_ID, buildNotification("Replaying: $name", true))
        startReplayCycle(osmId, name, lat, lon, storedTags, storedWikiUrl)
    }

    private fun startReplayCycle(
        osmId: String,
        name: String,
        lat: Double,
        lon: Double,
        storedTags: Map<String, String> = emptyMap(),
        storedWikiUrl: String? = null,
    ) {
        generationJob?.cancel()
        generationJob = lifecycleScope.launch {
            try {
                isGenerating = true
                emitState(TourState.GENERATING)
                updateNotification("Writing narration for $name...", true)

                val location = Location("replay").apply {
                    latitude = lat
                    longitude = lon
                }
                val poi = PlaceOfInterest(
                    osmId = osmId,
                    name = name,
                    lat = lat,
                    lon = lon,
                    type = PoiType.ATTRACTION,
                    tags = storedTags,
                )
                val wikiUrl = storedWikiUrl ?: poiImageRepository.fetchWikipediaUrl(poi)
                currentNarrationPoi = poi
                currentNarrationSummary = ""
                currentNarrationWikipediaUrl = wikiUrl
                emitCurrentPois(listOf(poi))
                emitCurrentPoiImage(null)
                imageFetchJob?.cancel()
                imageFetchJob = lifecycleScope.launch {
                    val url = try { poiImageRepository.fetchImageUrl(poi) } catch (_: Exception) { null }
                    if (isActive) emitCurrentPoiImage(url)
                }

                val result = narrationRepository.generateNarration(
                    listOf(poi), location, radiusMiles.coerceAtLeast(1f)
                )
                currentNarrationCommit = result.commitHistory
                currentNarrationSummary = result.summary
                currentPoiMeta.value = CurrentPoiMeta(osmId, result.summary, wikiUrl)
                isGenerating = false
                speak(result.text, name)
            } catch (e: CancellationException) {
                imageFetchJob?.cancel(); imageFetchJob = null
                emitCurrentPoiImage(null)
                isGenerating = false
                isReplayMode = false
                currentPoiMeta.value = CurrentPoiMeta()
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error in replay cycle", e)
                imageFetchJob?.cancel(); imageFetchJob = null
                emitCurrentPoiImage(null)
                isGenerating = false
                isReplayMode = false
                currentPoiMeta.value = CurrentPoiMeta()
                stopTour()
            }
        }
    }

    private fun startRouteSimulation(route: RouteData) {
        val sim = RouteSimulator(route)
        routeSimulator = sim
        routeAdvanceJob = lifecycleScope.launch {
            val firstLoc = sim.currentLocation()
            lastLocation = firstLoc
            onLocationUpdate(firstLoc)
            while (!sim.isAtEnd) {
                delay(30_000L)
                if (!sim.isPaused) sim.advance(30.0)
                val loc = sim.currentLocation()
                lastLocation = loc
                if (!isSpeaking && !isGenerating && tourState.value != TourState.PAUSED) {
                    onLocationUpdate(loc)
                }
            }
            Log.d(TAG, "Route simulation complete — holding at final position")
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 30_000L)
            .setMinUpdateDistanceMeters(50f)
            .build()
        fusedLocation.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        fusedLocation.lastLocation.addOnSuccessListener { it?.let { loc -> onLocationUpdate(loc) } }
    }

    private fun onLocationUpdate(location: Location) {
        lastLocation = location
        if (!isSpeaking && !isGenerating && tourState.value != TourState.PAUSED) {
            startGenerationCycle(location)
        }
    }

    private fun startGenerationCycle(location: Location) {
        if (generationJob?.isActive == true) return

        generationJob = lifecycleScope.launch {
            try {
                isGenerating = true
                emitState(TourState.FETCHING)
                val fetchMsg = if (famousMode) "Finding famous landmarks nearby..."
                               else "Finding interesting places nearby..."
                updateNotification(fetchMsg, true)

                val allPois = poiRepository.fetchPois(location, radiusMiles, famousMode)
                val pois = allPois.filterNot { poi -> mentionedPlacesStore.isNameMentioned(poi.name) }
                Log.d(TAG, "fetchPois: ${allPois.size} total, ${pois.size} unmentioned")

                if (pois.isEmpty()) {
                    emitState(TourState.NO_NEW_POIS)
                    updateNotification("Exploring... waiting for new places", true)
                    isGenerating = false
                    return@launch
                }

                val poi = selectPoi(pois)
                if (poi == null) {
                    emitState(TourState.NO_NEW_POIS)
                    updateNotification("Exploring... waiting for new places", true)
                    isGenerating = false
                    return@launch
                }

                emitState(TourState.GENERATING)
                updateNotification("Writing your tour narration...", true)

                mentionedPlacesStore.sessionNames.add(poi.name)
                currentNarrationPoi = poi
                currentNarrationSummary = ""
                currentNarrationWikipediaUrl = null
                emitCurrentPois(listOf(poi))

                emitCurrentPoiImage(null)
                imageFetchJob?.cancel()
                imageFetchJob = lifecycleScope.launch {
                    val url = try { poiImageRepository.fetchImageUrl(poi) } catch (_: Exception) { null }
                    if (isActive) emitCurrentPoiImage(url)
                }

                val wikiUrl = poiImageRepository.fetchWikipediaUrl(poi)
                val result = narrationRepository.generateNarration(listOf(poi), location, radiusMiles)
                currentNarrationCommit = result.commitHistory
                currentNarrationSummary = result.summary
                currentNarrationWikipediaUrl = wikiUrl
                currentPoiMeta.value = CurrentPoiMeta(poi.osmId, result.summary, wikiUrl)
                isGenerating = false
                speak(result.text, poi.name)

            } catch (e: CancellationException) {
                isGenerating = false
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error in generation cycle", e)
                isGenerating = false
                emitError(e.message ?: "Unknown error")
                updateNotification("Error — retrying shortly...", true)
                delay(15_000L)
                generationJob = null
                lastLocation?.let { startGenerationCycle(it) }
            }
        }
    }

    private suspend fun selectPoi(candidates: List<PlaceOfInterest>): PlaceOfInterest? {
        val disliked = mentionedPlacesStore.thumbsDownEntries()
        var skipped = 0
        for (candidate in candidates) {
            if (disliked.isNotEmpty() && skipped < 5) {
                val desc = "${candidate.name} — ${candidate.shortDescription}"
                val similar = narrationRepository.isSimilarToDisliked(
                    candidate.name, desc, disliked.map { it.summary }
                )
                if (similar) {
                    Log.d(TAG, "AUTO-SKIP: '${candidate.name}'")
                    mentionedPlacesStore.commitAutoSkipped(candidate.osmId, candidate.name, candidate.lat, candidate.lon, desc, candidate.tags)
                    mentionedPlacesStore.sessionNames.add(candidate.name)
                    mentionedPlaces.value = mentionedPlacesStore.recentFive()
                    skipped++
                    continue
                }
            }
            return candidate
        }
        return null
    }

    private fun prefetchNextNarration(location: Location) {
        if (prefetchJob?.isActive == true) {
            Log.d(TAG, "PREFETCH: already running — skipping duplicate call")
            return
        }
        Log.d(TAG, "PREFETCH: starting")
        val prefetchStart = System.currentTimeMillis()
        prefetchJob = lifecycleScope.launch {
            try {
                val t0 = System.currentTimeMillis()
                val pois = poiRepository.fetchPois(location, radiusMiles, famousMode)
                    .filterNot { poi -> mentionedPlacesStore.isNameMentioned(poi.name) }
                Log.d(TAG, "PREFETCH: POI fetch done in ${System.currentTimeMillis() - t0}ms — ${pois.size} unmentioned")
                if (pois.isEmpty()) {
                    Log.d(TAG, "PREFETCH: no unmentioned POIs — aborting")
                    return@launch
                }
                val poi = pois.first()
                Log.d(TAG, "PREFETCH: generating narration for '${poi.name}'")
                val t1 = System.currentTimeMillis()
                val result = narrationRepository.generateNarration(listOf(poi), location, radiusMiles)
                Log.d(TAG, "PREFETCH: Claude generation done in ${System.currentTimeMillis() - t1}ms")
                prefetchedNarration = poi to result.text
                prefetchedNarrationCommit = result.commitHistory
                prefetchedNarrationSummary = result.summary
                prefetchedWikipediaUrl = poiImageRepository.fetchWikipediaUrl(poi)
                Log.i(TAG, "PREFETCH: STORED '${poi.name}' — total ${System.currentTimeMillis() - prefetchStart}ms")
                val speechRate = sharedPrefs.getFloat(PREF_SPEECH_RATE, 1.0f)
                ttsEngine?.prewarm(result.text, speechRate)
                Log.d(TAG, "PREFETCH: prewarm complete for '${poi.name}'")
            } catch (e: CancellationException) {
                Log.d(TAG, "PREFETCH: job cancelled")
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "PREFETCH: failed — ${e.message}")
            }
        }
    }

    private fun speak(text: String, topicName: String, reinit: Boolean = true) {
        if (reinit) {
            isSpeaking = true  // close the onLocationUpdate window before engine init
            initTtsEngine()
        }
        val engine = ttsEngine
        if (engine == null || !engine.isReady) {
            lifecycleScope.launch {
                delay(2_000L)
                speak(text, topicName, reinit = false)
            }
            return
        }
        val myGeneration = speakGeneration

        savedTopicName = topicName
        speakStartTime = System.currentTimeMillis()
        isSpeaking = true
        currentNarrationPoi?.let { lastNarratedPoi = it }
        emitState(TourState.LOADING_AUDIO)
        emitCurrentTopic(topicName)
        updateNotification("Loading audio: $topicName", true)

        val speechRate = sharedPrefs.getFloat(PREF_SPEECH_RATE, 1.0f)
        startLoadingProgressEstimator(text)
        engine.speak(
            text = text,
            speechRate = speechRate,
            onProgress = { fraction ->
                // Engine-reported milestone acts as a floor — never moves the bar backwards.
                if (fraction > loadingProgress.value) loadingProgress.value = fraction
            },
            onStart = {
                stopLoadingProgressEstimator()
                loadingProgress.value = -1f
                emitState(TourState.SPEAKING)
                updateNotification("Now: $topicName")
                when {
                    isReplayMode -> { /* no prefetch */ }
                    isDeepDive.value && deepDiveCount < deepDiveMaxIterations -> {
                        Log.i(TAG, "TTS onStart '$topicName' — committing dive to history, triggering prefetch")
                        // Commit the current dive's narration to history NOW so the
                        // prefetch for the next dive sees it and won't repeat it.
                        currentNarrationCommit?.invoke()
                        currentNarrationCommit = null
                        startDeepDivePrefetch()
                    }
                    !isDeepDive.value -> {
                        Log.i(TAG, "TTS onStart '$topicName' — triggering prefetch")
                        lastLocation?.let { prefetchNextNarration(it) }
                    }
                }
            },
            onEnqueued = {},
            onDone = {
                if (speakGeneration != myGeneration) return@speak
                val duration = System.currentTimeMillis() - speakStartTime
                val poi = currentNarrationPoi
                val commit = currentNarrationCommit.also { currentNarrationCommit = null }
                val summary = currentNarrationSummary.also { currentNarrationSummary = "" }
                val wikiUrl = currentNarrationWikipediaUrl.also { currentNarrationWikipediaUrl = null }
                currentNarrationPoi = null
                isSpeaking = false
                Log.i(TAG, "TTS onDone '${poi?.name}' — played ${duration}ms, prefetchReady=${prefetchedNarration != null}")
                val finishedDiveTitle = currentDeepDiveTitle.also { currentDeepDiveTitle = "" }
                val finishedDiveOriginalPoi = deepDiveOriginalPoi
                lifecycleScope.launch {
                    // Keep the original-POI card visible during deep dive transitions.
                    val stayInDeepDive = isDeepDive.value
                    if (!stayInDeepDive) {
                        emitCurrentTopic("")
                        currentPoiMeta.value = CurrentPoiMeta()
                    }
                    if (poi != null && duration >= 10_000L) {
                        mentionedPlacesStore.commitWithSummary(poi.osmId, poi.name, poi.lat, poi.lon, summary, wikiUrl, poi.tags)
                        mentionedPlaces.value = mentionedPlacesStore.recentFive()
                    }
                    if (duration >= 10_000L) {
                        commit?.invoke()
                    }
                    // Persist the just-finished dive's title onto the original POI's entry.
                    if (finishedDiveTitle.isNotBlank() && duration >= 10_000L && finishedDiveOriginalPoi != null) {
                        mentionedPlacesStore.appendDeepDive(finishedDiveOriginalPoi.osmId, finishedDiveTitle)
                        mentionedPlaces.value = mentionedPlacesStore.recentFive()
                    }

                    if (isReplayMode) {
                        isReplayMode = false
                        stopTour()
                        return@launch
                    }

                    if (isDeepDive.value) {
                        if (deepDivePoiName.isBlank() && poi != null) {
                            deepDivePoiName = poi.name
                            deepDivePoiSummary = summary
                            if (deepDiveOriginalPoi == null) deepDiveOriginalPoi = poi
                        }
                        prefetchJob?.cancel(); prefetchJob = null
                        prefetchedNarration = null
                        prefetchedNarrationCommit = null
                        prefetchedNarrationSummary = ""
                        prefetchedWikipediaUrl = null
                        startDeepDiveCycle()
                        return@launch
                    }

                    // Leaving deep dive context — clear the segments list so the next
                    // POI's card starts clean. If toggled off mid-dive, the list stays
                    // visible until this point (the new POI is about to take over).
                    clearDeepDiveContext()

                    val prefetched = prefetchedNarration.also { prefetchedNarration = null }
                    val nextCommit = prefetchedNarrationCommit.also { prefetchedNarrationCommit = null }
                    val nextSummary = prefetchedNarrationSummary.also { prefetchedNarrationSummary = "" }
                    val nextWikiUrl = prefetchedWikipediaUrl.also { prefetchedWikipediaUrl = null }
                    prefetchJob?.cancel(); prefetchJob = null

                    val nextPoi = prefetched?.first
                    val nextNarration = prefetched?.second
                    if (nextPoi != null && nextNarration != null &&
                        !mentionedPlacesStore.isNameMentioned(nextPoi.name)) {
                        Log.i(TAG, "PREFETCH HIT: using prefetched narration for '${nextPoi.name}'")
                        mentionedPlacesStore.sessionNames.add(nextPoi.name)
                        currentNarrationCommit = nextCommit
                        currentNarrationPoi = nextPoi
                        currentNarrationSummary = nextSummary
                        currentNarrationWikipediaUrl = nextWikiUrl
                        currentPoiMeta.value = CurrentPoiMeta(nextPoi.osmId, nextSummary, nextWikiUrl)
                        emitCurrentPois(listOf(nextPoi))
                        emitCurrentPoiImage(null)
                        imageFetchJob?.cancel()
                        imageFetchJob = lifecycleScope.launch {
                            val url = try { poiImageRepository.fetchImageUrl(nextPoi) } catch (_: Exception) { null }
                            if (isActive) emitCurrentPoiImage(url)
                        }
                        isGenerating = true
                        delay(3_000L)
                        isGenerating = false
                        speak(nextNarration, nextPoi.name)
                    } else {
                        Log.i(TAG, "PREFETCH MISS: nextPoi=${nextPoi?.name} — falling back to full cycle")
                        lastLocation?.let { onLocationUpdate(it) }
                    }
                }
            },
            onError = {
                if (speakGeneration != myGeneration) return@speak
                currentNarrationPoi = null
                currentNarrationCommit = null
                currentNarrationSummary = ""
                currentNarrationWikipediaUrl = null
                isSpeaking = false
                stopLoadingProgressEstimator()
                loadingProgress.value = -1f
                lifecycleScope.launch {
                    emitCurrentTopic("")
                    currentPoiMeta.value = CurrentPoiMeta()
                    prefetchJob?.cancel(); prefetchJob = null
                    prefetchedNarration = null
                    prefetchedNarrationCommit = null
                    prefetchedNarrationSummary = ""
                    prefetchedWikipediaUrl = null
                    if (isReplayMode) {
                        isReplayMode = false
                        stopTour()
                    } else {
                        lastLocation?.let { onLocationUpdate(it) }
                    }
                }
            }
        )
    }

    private fun pauseTour() {
        if (!isSpeaking) return
        ttsEngine?.pause()
        routeSimulator?.pause()
        isSpeaking = false
        emitState(TourState.PAUSED)
        updateNotification("Paused — $savedTopicName")
    }

    private fun resumeTour() {
        if (tourState.value != TourState.PAUSED) return
        val engine = ttsEngine ?: return
        routeSimulator?.resume()
        if (!engine.canResume) {
            lastLocation?.let { startGenerationCycle(it) }
            return
        }
        isSpeaking = true
        emitState(TourState.SPEAKING)
        updateNotification("Now: $savedTopicName")
        engine.resume()
    }

    private fun skipCurrent() {
        speakGeneration++
        val poi = currentNarrationPoi
        val commit = currentNarrationCommit
        if (poi != null) {
            mentionedPlacesStore.commitWithSummary(
                poi.osmId, poi.name, poi.lat, poi.lon,
                currentNarrationSummary, currentNarrationWikipediaUrl, poi.tags
            )
            commit?.invoke()
            mentionedPlaces.value = mentionedPlacesStore.recentFive()
        }
        currentNarrationPoi = null
        currentNarrationCommit = null
        currentNarrationSummary = ""
        currentNarrationWikipediaUrl = null

        val prefetched = prefetchedNarration.also { prefetchedNarration = null }
        val nextCommit = prefetchedNarrationCommit.also { prefetchedNarrationCommit = null }
        val nextSummary = prefetchedNarrationSummary.also { prefetchedNarrationSummary = "" }
        val nextWikiUrl = prefetchedWikipediaUrl.also { prefetchedWikipediaUrl = null }

        generationJob?.cancel()
        prefetchJob?.cancel(); prefetchJob = null
        imageFetchJob?.cancel(); imageFetchJob = null
        ttsEngine?.stop()
        isSpeaking = false
        isGenerating = false
        savedTopicName = ""
        stopLoadingProgressEstimator()
        loadingProgress.value = -1f

        if (isDeepDive.value) {
            // Keep the original POI card intact (topic, image, wiki) so the UI
            // shows a seamless transition instead of collapsing and rebuilding.
            if (currentDeepDiveTitle.isNotBlank()) {
                deepDiveSegments.value = deepDiveSegments.value.dropLast(1)
                deepDiveCount = (deepDiveCount - 1).coerceAtLeast(0)
                currentDeepDiveTitle = ""
            }
            deepDivePrefetchJob?.cancel(); deepDivePrefetchJob = null
            prefetchedDeepDiveResult = null
            startDeepDiveCycle()
            return
        }

        emitCurrentTopic("")
        emitCurrentPoiImage(null)
        currentPoiMeta.value = CurrentPoiMeta()

        if (isReplayMode) {
            isReplayMode = false
            stopTour()
            return
        }

        val nextPoi = prefetched?.first
        val nextNarration = prefetched?.second
        if (nextPoi != null && nextNarration != null &&
            !mentionedPlacesStore.isNameMentioned(nextPoi.name)) {
            mentionedPlacesStore.sessionNames.add(nextPoi.name)
            currentNarrationCommit = nextCommit
            currentNarrationPoi = nextPoi
            currentNarrationSummary = nextSummary
            currentNarrationWikipediaUrl = nextWikiUrl
            currentPoiMeta.value = CurrentPoiMeta(nextPoi.osmId, nextSummary, nextWikiUrl)
            emitCurrentPois(listOf(nextPoi))
            imageFetchJob = lifecycleScope.launch {
                val url = try { poiImageRepository.fetchImageUrl(nextPoi) } catch (_: Exception) { null }
                if (isActive) emitCurrentPoiImage(url)
            }
            speak(nextNarration, nextPoi.name)
        } else {
            lastLocation?.let { startGenerationCycle(it) }
        }
    }

    private fun stopTour() {
        speakGeneration++
        currentNarrationPoi = null
        currentNarrationCommit = null
        currentNarrationSummary = ""
        currentNarrationWikipediaUrl = null
        isReplayMode = false
        isDeepDive.value = false
        clearDeepDiveContext()
        lastNarratedPoi = null
        generationJob?.cancel()
        prefetchJob?.cancel(); prefetchJob = null
        imageFetchJob?.cancel(); imageFetchJob = null
        routeAdvanceJob?.cancel(); routeAdvanceJob = null
        routeSimulator = null
        pendingRoute = null
        prefetchedNarration = null
        prefetchedNarrationCommit = null
        prefetchedNarrationSummary = ""
        prefetchedWikipediaUrl = null
        fusedLocation.removeLocationUpdates(locationCallback)
        ttsEngine?.stop()
        isSpeaking = false
        isGenerating = false
        savedTopicName = ""
        stopLoadingProgressEstimator()
        loadingProgress.value = -1f
        emitCurrentTopic("")
        emitCurrentPoiImage(null)
        currentPoiMeta.value = CurrentPoiMeta()
        emitState(TourState.IDLE)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── Loading-progress estimator ────────────────────────────────────────────
    // Drives the LOADING_AUDIO spinner forward at a rate calibrated to the active TTS
    // engine, so the user sees a smooth, roughly realistic fill instead of an infinite
    // spin. The engine's own onProgress callback acts as a floor — if it reports a
    // milestone ahead of where the estimator is, we jump forward.
    //
    // Per-engine constants come from observed field timings on mid-range Android
    // hardware (Samsung SM-F766U1). They overestimate slightly so the bar reaches ~95%
    // around the time audio actually starts; the bar is then snapped to 100% (briefly)
    // and reset to indeterminate on onStart.

    private var loadingProgressJob: Job? = null

    private fun estimateTtsGenerationMs(textLen: Int): Long {
        val (overheadMs, msPerChar) = when (ttsEngine) {
            is PiperTtsEngine -> 800L to 30L
            is KokoroTtsEngine -> 1500L to 70L
            is OpenAiTtsEngine -> 1200L to 12L
            else -> 400L to 5L  // AndroidTtsEngine and fallback
        }
        return overheadMs + textLen * msPerChar
    }

    private fun startLoadingProgressEstimator(text: String) {
        loadingProgressJob?.cancel()
        val estimatedMs = estimateTtsGenerationMs(text.length).coerceAtLeast(1L)
        val startTime = System.currentTimeMillis()
        loadingProgress.value = 0.02f  // small initial fill so the bar doesn't look empty
        loadingProgressJob = lifecycleScope.launch {
            try {
                while (isActive) {
                    val elapsed = System.currentTimeMillis() - startTime
                    // Asymptotic: linear toward 0.95, never reaches 1.0 from time alone —
                    // engine's onStart callback is what flips us out of LOADING_AUDIO.
                    val projected = (elapsed.toFloat() / estimatedMs).coerceAtMost(0.95f)
                    if (projected > loadingProgress.value) loadingProgress.value = projected
                    delay(120)
                }
            } catch (_: CancellationException) {}
        }
    }

    private fun stopLoadingProgressEstimator() {
        loadingProgressJob?.cancel()
        loadingProgressJob = null
    }

    private fun initTtsEngine() {
        ttsEngine?.shutdown()
        val provider = sharedPrefs.getString(PREF_TTS_PROVIDER, "piper") ?: "piper"
        ttsEngine = when (provider) {
            "openai" -> {
                val key = sharedPrefs.getString(PREF_OPENAI_TTS_KEY, "") ?: ""
                val model = sharedPrefs.getString(PREF_OPENAI_TTS_MODEL, "tts-1-hd") ?: "tts-1-hd"
                OpenAiTtsEngine(this, lifecycleScope, key, model)
            }
            "kokoro" -> {
                if (kokoroModelManager.isReady) {
                    val sid = sharedPrefs.getInt(MainFragment.PREF_KOKORO_VOICE_SID, MainFragment.DEFAULT_KOKORO_VOICE_SID)
                        .coerceIn(0, MainFragment.KOKORO_VOICES.size - 1)
                    val lang = MainFragment.langForVoiceSid(sid)
                    KokoroTtsEngine(this, kokoroModelManager.modelDir, voiceSid = sid, lang = lang)
                } else {
                    Log.w(TAG, "Kokoro model not downloaded — falling back to Android TTS")
                    AndroidTtsEngine(this)
                }
            }
            "piper" -> {
                val voiceId = sharedPrefs.getString(PREF_PIPER_VOICE_ID, PiperVoices.DEFAULT_VOICE_ID)
                    ?: PiperVoices.DEFAULT_VOICE_ID
                if (piperModelManager.isVoiceReady(voiceId)) {
                    PiperTtsEngine(this, piperModelManager.voiceDir(voiceId), voiceId)
                } else {
                    Log.w(TAG, "Piper voice $voiceId not downloaded — falling back to Android TTS")
                    AndroidTtsEngine(this)
                }
            }
            else -> AndroidTtsEngine(this)
        }
    }


    private val sharedPrefs by lazy { getSharedPreferences(PREFS_NAME, MODE_PRIVATE) }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Tour Guide", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Audio tour guide status" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String, showProgress: Boolean = false): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val pauseIntent = PendingIntent.getService(
            this, 1, Intent(this, TourGuideService::class.java).apply { action = ACTION_PAUSE }, flags
        )
        val resumeIntent = PendingIntent.getService(
            this, 2, Intent(this, TourGuideService::class.java).apply { action = ACTION_RESUME }, flags
        )
        val skipIntent = PendingIntent.getService(
            this, 3, Intent(this, TourGuideService::class.java).apply { action = ACTION_SKIP }, flags
        )

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Travel Guide Active")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_tour_guide)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setProgress(0, 0, showProgress)

        when (tourState.value) {
            TourState.SPEAKING -> {
                builder.addAction(R.drawable.ic_pause, "Pause", pauseIntent)
                builder.addAction(R.drawable.ic_skip, "Skip", skipIntent)
            }
            TourState.PAUSED -> {
                builder.addAction(R.drawable.ic_play, "Resume", resumeIntent)
                builder.addAction(R.drawable.ic_skip, "Skip", skipIntent)
            }
            else -> Unit
        }

        val token = TourAutoMediaService.sharedToken
        if (token != null) {
            builder.setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(token)
                    .setShowActionsInCompactView(0, 1)
            )
        }

        return builder.build()
    }

    private fun updateNotification(text: String, showProgress: Boolean = false) {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(text, showProgress))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update notification: ${e.message}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsEngine?.shutdown()
        fusedLocation.removeLocationUpdates(locationCallback)
    }

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_PAUSE = "ACTION_PAUSE"
        const val ACTION_RESUME = "ACTION_RESUME"
        const val ACTION_SKIP = "ACTION_SKIP"
        const val ACTION_REPLAY_POI = "ACTION_REPLAY_POI"
        const val ACTION_TOGGLE_DEEP_DIVE = "ACTION_TOGGLE_DEEP_DIVE"
        const val EXTRA_RADIUS_MILES = "EXTRA_RADIUS_MILES"
        const val EXTRA_API_KEY = "EXTRA_API_KEY"
        const val EXTRA_FAMOUS_MODE = "EXTRA_FAMOUS_MODE"
        const val EXTRA_POI_OSM_ID = "EXTRA_POI_OSM_ID"
        const val EXTRA_POI_NAME = "EXTRA_POI_NAME"
        const val EXTRA_POI_LAT = "EXTRA_POI_LAT"
        const val EXTRA_POI_LON = "EXTRA_POI_LON"
        const val CHANNEL_ID = "tour_guide_channel"
        const val NOTIFICATION_ID = 1001
        const val PREFS_NAME = "tour_prefs"
        const val PREF_SPEECH_RATE = "pref_speech_rate"
        const val PREF_TTS_PROVIDER = "pref_tts_provider"
        const val PREF_OPENAI_TTS_KEY = "pref_openai_tts_key"
        const val PREF_OPENAI_TTS_MODEL = "pref_openai_tts_model"
        const val PREF_PIPER_VOICE_ID = "pref_piper_voice_id"
        const val ACTION_SET_SPEED = "ACTION_SET_SPEED"
        const val EXTRA_SPEECH_RATE = "EXTRA_SPEECH_RATE"
        const val PREF_LAST_RADIUS = "pref_last_radius"
        const val PREF_FAMOUS_MODE = "pref_famous_mode"
        private const val TAG = "TourGuideService"

        @Volatile var pendingRoute: RouteData? = null

        data class CurrentPoiMeta(
            val osmId: String = "",
            val summary: String = "",
            val wikipediaUrl: String? = null,
        )

        val tourState = MutableStateFlow(TourState.IDLE)
        val currentTopic = MutableStateFlow("")
        val currentPois = MutableStateFlow<List<PlaceOfInterest>>(emptyList())
        val mentionedPlaces = MutableStateFlow<List<MentionedPlacesStore.Entry>>(emptyList())
        val errorMessage = MutableStateFlow<String?>(null)
        val currentPoiImage = MutableStateFlow<String?>(null)
        val currentPoiMeta = MutableStateFlow(CurrentPoiMeta())
        val loadingProgress = MutableStateFlow(-1f)
        val isDeepDive = MutableStateFlow(false)
        val deepDiveSegments = MutableStateFlow<List<String>>(emptyList())
        val lastKnownLocation = MutableStateFlow<android.location.Location?>(null)

        private fun emitState(state: TourState) { tourState.value = state }
        private fun emitCurrentTopic(topic: String) { currentTopic.value = topic }
        private fun emitCurrentPois(pois: List<PlaceOfInterest>) { currentPois.value = pois }
        private fun emitError(msg: String) { errorMessage.value = msg }
        private fun emitCurrentPoiImage(url: String?) { currentPoiImage.value = url }
    }
}

enum class TourState {
    IDLE, LOCATING, FETCHING, GENERATING, LOADING_AUDIO, SPEAKING, PAUSED, NO_NEW_POIS, ERROR
}
