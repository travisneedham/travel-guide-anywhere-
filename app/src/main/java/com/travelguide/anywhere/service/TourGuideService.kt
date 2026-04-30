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
import com.travelguide.anywhere.data.model.PlaceOfInterest
import com.travelguide.anywhere.repository.NarrationRepository
import com.travelguide.anywhere.repository.PoiRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class TourGuideService : LifecycleService() {

    @Inject lateinit var fusedLocation: FusedLocationProviderClient
    @Inject lateinit var poiRepository: PoiRepository
    @Inject lateinit var narrationRepository: NarrationRepository
    @Inject lateinit var mentionedPlacesStore: MentionedPlacesStore

    private var ttsEngine: TtsEngine? = null
    private var radiusMiles = 1f
    private var apiKey = ""
    private var lastLocation: Location? = null
    private var generationJob: Job? = null
    private var isSpeaking = false
    private var isGenerating = false
    private var savedTopicName = ""

    @Volatile private var currentNarrationPoi: PlaceOfInterest? = null
    @Volatile private var speakStartTime: Long = 0L

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { onLocationUpdate(it) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initTtsEngine()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START -> {
                // Re-init engine in case provider/keys changed since last run.
                initTtsEngine()
                mentionedPlacesStore.load()
                radiusMiles = intent.getFloatExtra(EXTRA_RADIUS_MILES, 1f)
                apiKey = intent.getStringExtra(EXTRA_API_KEY) ?: ""
                startForeground(NOTIFICATION_ID, buildNotification("Starting tour..."))
                requestLocationUpdates()
                emitState(TourState.LOCATING)
                mentionedPlaces.value = mentionedPlacesStore.recentFive()
            }
            ACTION_STOP -> stopTour()
            ACTION_PAUSE -> pauseTour()
            ACTION_RESUME -> resumeTour()
            ACTION_SKIP -> skipCurrent()
        }

        return START_STICKY
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
                updateNotification("Finding interesting places nearby...")

                val pois = poiRepository.fetchPois(location, radiusMiles)
                    .filterNot { poi -> mentionedPlacesStore.isNameMentioned(poi.name) }

                if (pois.isEmpty()) {
                    emitState(TourState.NO_NEW_POIS)
                    updateNotification("Exploring... waiting for new places")
                    isGenerating = false
                    return@launch
                }

                emitState(TourState.GENERATING)
                updateNotification("Writing your tour narration...")

                val poi = pois.first()
                mentionedPlacesStore.sessionNames.add(poi.name)
                currentNarrationPoi = poi
                emitCurrentPois(listOf(poi))

                val narration = narrationRepository.generateNarration(listOf(poi), location, radiusMiles, apiKey)
                isGenerating = false
                speak(narration, poi.name)

            } catch (e: CancellationException) {
                isGenerating = false
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Error in generation cycle", e)
                isGenerating = false
                emitError(e.message ?: "Unknown error")
                updateNotification("Error — retrying shortly...")
                delay(15_000L)
                generationJob = null
                lastLocation?.let { startGenerationCycle(it) }
            }
        }
    }

    private fun speak(text: String, topicName: String) {
        val engine = ttsEngine
        if (engine == null || !engine.isReady) {
            lifecycleScope.launch {
                delay(2_000L)
                speak(text, topicName)
            }
            return
        }

        savedTopicName = topicName
        speakStartTime = System.currentTimeMillis()
        isSpeaking = true
        emitState(TourState.SPEAKING)
        emitCurrentTopic(topicName)
        updateNotification("Now: $topicName")

        engine.speak(
            text = text,
            speechRate = sharedPrefs.getFloat(PREF_SPEECH_RATE, 0.95f),
            onDone = {
                val duration = System.currentTimeMillis() - speakStartTime
                val poi = currentNarrationPoi
                currentNarrationPoi = null
                isSpeaking = false
                lifecycleScope.launch {
                    emitCurrentTopic("")
                    if (poi != null && duration >= 10_000L) {
                        mentionedPlacesStore.commit(poi.osmId, poi.name, poi.lat, poi.lon)
                        mentionedPlaces.value = mentionedPlacesStore.recentFive()
                    }
                    lastLocation?.let { onLocationUpdate(it) }
                }
            },
            onError = {
                currentNarrationPoi = null
                isSpeaking = false
                lifecycleScope.launch {
                    emitCurrentTopic("")
                    lastLocation?.let { onLocationUpdate(it) }
                }
            }
        )
    }

    private fun pauseTour() {
        if (!isSpeaking) return
        ttsEngine?.pause()
        isSpeaking = false
        emitState(TourState.PAUSED)
        updateNotification("Paused — $savedTopicName")
    }

    private fun resumeTour() {
        if (tourState.value != TourState.PAUSED) return
        val engine = ttsEngine ?: return
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
        if (poi != null) {
            mentionedPlacesStore.commit(poi.osmId, poi.name, poi.lat, poi.lon)
            mentionedPlaces.value = mentionedPlacesStore.recentFive()
        }
        currentNarrationPoi = null
        emitCurrentTopic("")
        generationJob?.cancel()
        ttsEngine?.stop()
        isSpeaking = false
        isGenerating = false
        savedTopicName = ""
        lastLocation?.let { startGenerationCycle(it) }
    }

    private fun stopTour() {
        currentNarrationPoi = null
        generationJob?.cancel()
        fusedLocation.removeLocationUpdates(locationCallback)
        ttsEngine?.stop()
        isSpeaking = false
        isGenerating = false
        savedTopicName = ""
        emitState(TourState.IDLE)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun initTtsEngine() {
        ttsEngine?.shutdown()
        val provider = sharedPrefs.getString(PREF_TTS_PROVIDER, "android") ?: "android"
        ttsEngine = when (provider) {
            "openai" -> {
                val key = sharedPrefs.getString(PREF_OPENAI_TTS_KEY, "") ?: ""
                val model = sharedPrefs.getString(PREF_OPENAI_TTS_MODEL, "tts-1-hd") ?: "tts-1-hd"
                OpenAiTtsEngine(this, lifecycleScope, key, model)
            }
            "elevenlabs" -> {
                val key = sharedPrefs.getString(PREF_ELEVENLABS_KEY, "") ?: ""
                val voiceId = sharedPrefs.getString(PREF_ELEVENLABS_VOICE, ElevenLabsTtsEngine.DEFAULT_VOICE_ID)
                    ?: ElevenLabsTtsEngine.DEFAULT_VOICE_ID
                ElevenLabsTtsEngine(this, lifecycleScope, key, voiceId)
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

    private fun buildNotification(text: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Travel Guide Active")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_tour_guide)
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        try {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(text))
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
        const val EXTRA_RADIUS_MILES = "EXTRA_RADIUS_MILES"
        const val EXTRA_API_KEY = "EXTRA_API_KEY"
        const val CHANNEL_ID = "tour_guide_channel"
        const val NOTIFICATION_ID = 1001
        const val PREFS_NAME = "tour_prefs"
        const val PREF_SPEECH_RATE = "pref_speech_rate"
        const val PREF_TTS_PROVIDER = "pref_tts_provider"
        const val PREF_OPENAI_TTS_KEY = "pref_openai_tts_key"
        const val PREF_OPENAI_TTS_MODEL = "pref_openai_tts_model"
        const val PREF_ELEVENLABS_KEY = "pref_elevenlabs_key"
        const val PREF_ELEVENLABS_VOICE = "pref_elevenlabs_voice"
        private const val TAG = "TourGuideService"

        val tourState = MutableStateFlow(TourState.IDLE)
        val currentTopic = MutableStateFlow("")
        val currentPois = MutableStateFlow<List<PlaceOfInterest>>(emptyList())
        val mentionedPlaces = MutableStateFlow<List<MentionedPlacesStore.Entry>>(emptyList())
        val errorMessage = MutableStateFlow<String?>(null)

        private fun emitState(state: TourState) { tourState.value = state }
        private fun emitCurrentTopic(topic: String) { currentTopic.value = topic }
        private fun emitCurrentPois(pois: List<PlaceOfInterest>) { currentPois.value = pois }
        private fun emitError(msg: String) { errorMessage.value = msg }
    }
}

enum class TourState {
    IDLE, LOCATING, FETCHING, GENERATING, SPEAKING, PAUSED, NO_NEW_POIS, ERROR
}
