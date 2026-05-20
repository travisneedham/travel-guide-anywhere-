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
    private var generationJob: Job? = null
    private var prefetchJob: Job? = null
    private var imageFetchJob: Job? = null
    private var routeSimulator: RouteSimulator? = null
    private var routeAdvanceJob: Job? = null
    private var isSpeaking = false
    private var isGenerating = false
    private var isReplayMode = false
    private var savedTopicName = ""

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
                ttsEngine?.setSpeed(intent.getFloatExtra(EXTRA_SPEECH_RATE, 0.95f))
                if (tourState.value == TourState.PAUSED) {
                    isSpeaking = true
                    emitState(TourState.SPEAKING)
                    updateNotification("Now: $savedTopicName")
                }
            }
            ACTION_REPLAY_POI -> handleReplayPoi(intent)
        }

        return START_STICKY
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
                val wikiUrl = storedWikiUrl ?: buildWikiUrl(storedTags["wikipedia"])
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

                val wikiUrl = buildWikiUrl(poi.tags["wikipedia"])
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
                prefetchedWikipediaUrl = buildWikiUrl(poi.tags["wikipedia"])
                Log.i(TAG, "PREFETCH: STORED '${poi.name}' — total ${System.currentTimeMillis() - prefetchStart}ms")
                val speechRate = sharedPrefs.getFloat(PREF_SPEECH_RATE, 0.95f)
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

        savedTopicName = topicName
        speakStartTime = System.currentTimeMillis()
        isSpeaking = true
        emitState(TourState.LOADING_AUDIO)
        emitCurrentTopic(topicName)
        updateNotification("Loading audio: $topicName", true)

        val speechRate = sharedPrefs.getFloat(PREF_SPEECH_RATE, 0.95f)
        loadingProgress.value = -1f
        engine.speak(
            text = text,
            speechRate = speechRate,
            onProgress = { fraction -> loadingProgress.value = fraction },
            onStart = {
                loadingProgress.value = -1f
                emitState(TourState.SPEAKING)
                updateNotification("Now: $topicName")
                if (!isReplayMode) {
                    Log.i(TAG, "TTS onStart '$topicName' — triggering prefetch")
                    lastLocation?.let { prefetchNextNarration(it) }
                }
            },
            onEnqueued = {},
            onDone = {
                val duration = System.currentTimeMillis() - speakStartTime
                val poi = currentNarrationPoi
                val commit = currentNarrationCommit.also { currentNarrationCommit = null }
                val summary = currentNarrationSummary.also { currentNarrationSummary = "" }
                val wikiUrl = currentNarrationWikipediaUrl.also { currentNarrationWikipediaUrl = null }
                currentNarrationPoi = null
                isSpeaking = false
                Log.i(TAG, "TTS onDone '${poi?.name}' — played ${duration}ms, prefetchReady=${prefetchedNarration != null}")
                lifecycleScope.launch {
                    emitCurrentTopic("")
                    currentPoiMeta.value = CurrentPoiMeta()
                    if (poi != null && duration >= 10_000L) {
                        mentionedPlacesStore.commitWithSummary(poi.osmId, poi.name, poi.lat, poi.lon, summary, wikiUrl, poi.tags)
                        commit?.invoke()
                        mentionedPlaces.value = mentionedPlacesStore.recentFive()
                    }

                    if (isReplayMode) {
                        isReplayMode = false
                        stopTour()
                        return@launch
                    }

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
                currentNarrationPoi = null
                currentNarrationCommit = null
                currentNarrationSummary = ""
                currentNarrationWikipediaUrl = null
                isSpeaking = false
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
        val poi = currentNarrationPoi
        val commit = currentNarrationCommit
        if (poi != null) {
            mentionedPlacesStore.commit(poi.osmId, poi.name, poi.lat, poi.lon, poi.tags)
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
        loadingProgress.value = -1f
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
        currentNarrationPoi = null
        currentNarrationCommit = null
        currentNarrationSummary = ""
        currentNarrationWikipediaUrl = null
        isReplayMode = false
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
        loadingProgress.value = -1f
        emitCurrentTopic("")
        emitCurrentPoiImage(null)
        currentPoiMeta.value = CurrentPoiMeta()
        emitState(TourState.IDLE)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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

    private fun buildWikiUrl(tag: String?): String? {
        if (tag == null) return null
        val colon = tag.indexOf(':')
        if (colon < 0) return null
        val lang = tag.substring(0, colon)
        val title = tag.substring(colon + 1).replace(' ', '_')
        return "https://$lang.wikipedia.org/wiki/$title"
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
