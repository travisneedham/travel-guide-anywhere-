package com.travelguide.anywhere.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.location.Location
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
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
import java.util.Locale
import javax.inject.Inject

@AndroidEntryPoint
class TourGuideService : LifecycleService() {

    @Inject lateinit var fusedLocation: FusedLocationProviderClient
    @Inject lateinit var poiRepository: PoiRepository
    @Inject lateinit var narrationRepository: NarrationRepository
    @Inject lateinit var mentionedPlacesStore: MentionedPlacesStore

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var radiusMiles = 1f
    private var apiKey = ""
    private var lastLocation: Location? = null
    private var generationJob: Job? = null
    private var isSpeaking = false
    private var isGenerating = false

    // Tracks which POI is currently being spoken, and when speaking started.
    @Volatile private var currentNarrationPoi: PlaceOfInterest? = null
    @Volatile private var speakStartTime: Long = 0L

    // Pause/resume state
    private var savedChunks: List<String> = emptyList()
    private var savedTopicName: String = ""
    private var currentChunkIndex: Int = 0

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { onLocationUpdate(it) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        initTts()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_START -> {
                // Load the persistent history file. This clears in-memory state and
                // repopulates from disk so already-heard POIs are excluded immediately.
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

        fusedLocation.lastLocation.addOnSuccessListener { location ->
            location?.let { onLocationUpdate(it) }
        }
    }

    private fun onLocationUpdate(location: Location) {
        lastLocation = location
        if (!isSpeaking && !isGenerating && tourState.value != TourState.PAUSED) {
            startGenerationCycle(location)
        }
    }

    private fun startGenerationCycle(location: Location) {
        // Guard: don't start a new cycle if one is already running.
        if (generationJob?.isActive == true) return

        generationJob = lifecycleScope.launch {
            try {
                isGenerating = true
                emitState(TourState.FETCHING)
                updateNotification("Finding interesting places nearby...")

                // Fetch all named POIs, then filter using the store's in-memory name set.
                // The store's sessionNames contains both disk-persisted names (loaded on start)
                // and current-session picks (added immediately on selection below).
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
                // Add to session names immediately — before the API call — so even a
                // cancellation can't bring this POI back in the same session.
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
        if (!ttsReady) {
            lifecycleScope.launch {
                delay(2_000L)
                speak(text, topicName)
            }
            return
        }

        val chunks = splitIntoChunks(text)
        savedChunks = chunks
        savedTopicName = topicName
        currentChunkIndex = 0
        speakStartTime = System.currentTimeMillis()

        tts?.setSpeechRate(sharedPrefs.getFloat(PREF_SPEECH_RATE, 0.95f))
        isSpeaking = true
        emitState(TourState.SPEAKING)
        emitCurrentTopic(topicName)
        updateNotification("Now: $topicName")

        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                utteranceId?.removePrefix("chunk_")?.toIntOrNull()?.let {
                    currentChunkIndex = it
                }
            }
            override fun onDone(utteranceId: String?) {
                if (utteranceId == LAST_UTTERANCE_ID) {
                    val duration = System.currentTimeMillis() - speakStartTime
                    val poi = currentNarrationPoi
                    currentNarrationPoi = null
                    isSpeaking = false
                    savedChunks = emptyList()
                    lifecycleScope.launch {
                        // Commit to disk only if the narration played for at least 10 seconds.
                        if (poi != null && duration >= 10_000L) {
                            mentionedPlacesStore.commit(poi.osmId, poi.name, poi.lat, poi.lon)
                            mentionedPlaces.value = mentionedPlacesStore.recentFive()
                        }
                        lastLocation?.let { onLocationUpdate(it) }
                    }
                }
            }
            override fun onError(utteranceId: String?) {
                currentNarrationPoi = null
                isSpeaking = false
                savedChunks = emptyList()
                lifecycleScope.launch { lastLocation?.let { onLocationUpdate(it) } }
            }
        })

        enqueueChunks(chunks, startIndex = 0)
    }

    private fun enqueueChunks(chunks: List<String>, startIndex: Int) {
        try {
            chunks.forEachIndexed { i, chunk ->
                val globalIndex = startIndex + i
                val utteranceId = if (globalIndex == savedChunks.lastIndex) LAST_UTTERANCE_ID else "chunk_$globalIndex"
                tts?.speak(chunk, TextToSpeech.QUEUE_ADD, null, utteranceId)
            }
        } catch (e: Exception) {
            Log.w(TAG, "TTS speak failed, reinitializing: ${e.message}")
            isSpeaking = false
            ttsReady = false
            tts?.shutdown()
            initTts()
            lifecycleScope.launch {
                delay(3_000L)
                speak(savedChunks.joinToString(" "), savedTopicName)
            }
        }
    }

    private fun pauseTour() {
        if (!isSpeaking) return
        tts?.setOnUtteranceProgressListener(null)
        tts?.stop()
        isSpeaking = false
        emitState(TourState.PAUSED)
        updateNotification("Paused — ${savedTopicName}")
    }

    private fun resumeTour() {
        if (tourState.value != TourState.PAUSED || savedChunks.isEmpty()) return
        val remaining = savedChunks.drop(currentChunkIndex)
        if (remaining.isEmpty()) {
            lastLocation?.let { startGenerationCycle(it) }
            return
        }
        tts?.setSpeechRate(sharedPrefs.getFloat(PREF_SPEECH_RATE, 0.95f))
        isSpeaking = true
        emitState(TourState.SPEAKING)
        updateNotification("Now: $savedTopicName")
        enqueueChunks(remaining, startIndex = currentChunkIndex)
    }

    private fun skipCurrent() {
        // Commit the skipped POI to disk so it's not repeated next session.
        val poi = currentNarrationPoi
        if (poi != null) {
            mentionedPlacesStore.commit(poi.osmId, poi.name, poi.lat, poi.lon)
            mentionedPlaces.value = mentionedPlacesStore.recentFive()
        }
        currentNarrationPoi = null
        generationJob?.cancel()
        tts?.setOnUtteranceProgressListener(null)
        tts?.stop()
        isSpeaking = false
        isGenerating = false
        savedChunks = emptyList()
        savedTopicName = ""
        currentChunkIndex = 0
        lastLocation?.let { startGenerationCycle(it) }
    }

    private fun splitIntoChunks(text: String, maxLength: Int = 3800): List<String> {
        if (text.length <= maxLength) return listOf(text)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val end = minOf(start + maxLength, text.length)
            val splitAt = if (end < text.length) {
                text.lastIndexOf(". ", end).takeIf { it > start } ?: end
            } else end
            chunks.add(text.substring(start, splitAt).trim())
            start = splitAt
        }
        return chunks.filter { it.isNotBlank() }
    }

    private fun stopTour() {
        currentNarrationPoi = null
        generationJob?.cancel()
        fusedLocation.removeLocationUpdates(locationCallback)
        tts?.stop()
        isSpeaking = false
        isGenerating = false
        savedChunks = emptyList()
        emitState(TourState.IDLE)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun initTts() {
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                tts?.setSpeechRate(sharedPrefs.getFloat(PREF_SPEECH_RATE, 0.95f))
                ttsReady = true
            }
        }
    }

    private val sharedPrefs by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Tour Guide", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Audio tour guide status" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
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
        tts?.shutdown()
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
        const val LAST_UTTERANCE_ID = "last_utterance"
        const val PREFS_NAME = "tour_prefs"
        const val PREF_SPEECH_RATE = "pref_speech_rate"
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
