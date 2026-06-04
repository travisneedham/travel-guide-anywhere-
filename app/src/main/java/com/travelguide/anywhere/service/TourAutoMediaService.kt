package com.travelguide.anywhere.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.media.MediaBrowserServiceCompat
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.travelguide.anywhere.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TourAutoMediaService : MediaBrowserServiceCompat() {

    private lateinit var session: MediaSessionCompat
    private lateinit var audioManager: AudioManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var currentArtwork: Bitmap? = null
    private var audioFocusGranted = false
    private var audioFocusRequest: AudioFocusRequest? = null

    private val audioAttrs = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        Log.i(TAG, "Audio focus changed: ${focusChangeName(focusChange)} ($focusChange) — current tourState=${TourGuideService.tourState.value}")
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.i(TAG, "Audio focus GAINED — resuming if paused")
                audioFocusGranted = true
                if (TourGuideService.tourState.value == TourState.PAUSED) {
                    send(TourGuideService.ACTION_RESUME)
                }
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.w(TAG, "Audio focus LOST permanently — pausing tour")
                audioFocusGranted = false
                send(TourGuideService.ACTION_PAUSE)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.i(TAG, "Audio focus LOSS_TRANSIENT — pausing tour")
                audioFocusGranted = false
                send(TourGuideService.ACTION_PAUSE)
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.i(TAG, "Audio focus LOSS_TRANSIENT_CAN_DUCK — pausing tour")
                audioFocusGranted = false
                send(TourGuideService.ACTION_PAUSE)
            }
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                Log.i(TAG, "ACTION_AUDIO_BECOMING_NOISY (headphones/BT disconnected) — pausing tour")
                send(TourGuideService.ACTION_PAUSE)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate — TourAutoMediaService starting")

        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        Log.d(TAG, "AudioManager acquired")

        session = MediaSessionCompat(this, TAG).apply {
            setPlaybackToLocal(AudioManager.STREAM_MUSIC)
            setCallback(SessionCallback())
            isActive = true
        }
        sessionToken = session.sessionToken
        sharedToken = session.sessionToken
        Log.i(TAG, "MediaSession created and active, token=$sessionToken")

        registerReceiver(noisyReceiver, IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY))
        Log.d(TAG, "BECOMING_NOISY receiver registered")

        val initialState = TourGuideService.tourState.value
        val initialTopic = TourGuideService.currentTopic.value
        Log.i(TAG, "Initial state: tourState=$initialState topic=\"$initialTopic\"")

        pushMetadata(initialTopic, initialState)
        pushPlaybackState(initialState)

        scope.launch {
            launch {
                TourGuideService.tourState.collect { state ->
                    Log.d(TAG, "Tour state → $state")
                    // Proactively grab audio focus when a tour starts from the phone app (not just
                    // via the media session's onPlay), so narration routes to the car immediately.
                    if (state == TourState.LOCATING || state == TourState.SPEAKING) {
                        requestAudioFocus()
                    }
                    pushPlaybackState(state)
                    pushMetadata(TourGuideService.currentTopic.value, state)
                }
            }
            launch {
                TourGuideService.currentTopic.collect { topic ->
                    Log.d(TAG, "Current topic → \"$topic\"")
                    pushMetadata(topic, TourGuideService.tourState.value)
                }
            }
            launch {
                TourGuideService.currentPoiImage.collect { url ->
                    if (url != null) {
                        Log.d(TAG, "POI image URL: $url — loading bitmap")
                        val bitmap = loadBitmap(url)
                        if (bitmap != null) {
                            currentArtwork = bitmap
                            Log.d(TAG, "Artwork loaded (${bitmap.width}×${bitmap.height})")
                            pushMetadata(TourGuideService.currentTopic.value, TourGuideService.tourState.value)
                        } else {
                            Log.w(TAG, "Artwork load FAILED for: $url")
                        }
                    } else {
                        Log.d(TAG, "POI image cleared")
                        currentArtwork = null
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "onDestroy — TourAutoMediaService stopping")
        super.onDestroy()
        abandonAudioFocus()
        try { unregisterReceiver(noisyReceiver) } catch (e: Exception) {
            Log.w(TAG, "Error unregistering noisyReceiver: ${e.message}")
        }
        scope.cancel()
        session.release()
        sharedToken = null
        Log.d(TAG, "MediaSession released, scope cancelled")
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot {
        Log.i(TAG, "onGetRoot — client=$clientPackageName uid=$clientUid hints=$rootHints")
        return BrowserRoot(ROOT_ID, null)
    }

    override fun onLoadChildren(
        parentId: String,
        result: Result<List<MediaBrowserCompat.MediaItem>>
    ) {
        Log.d(TAG, "onLoadChildren — parentId=$parentId")
        if (parentId != ROOT_ID) { result.sendResult(emptyList()); return }
        result.sendResult(
            listOf(
                MediaBrowserCompat.MediaItem(
                    MediaDescriptionCompat.Builder()
                        .setMediaId(ITEM_ID)
                        .setTitle(getString(R.string.app_name))
                        .setSubtitle("Audio tour guide")
                        .build(),
                    MediaBrowserCompat.MediaItem.FLAG_PLAYABLE
                )
            )
        )
    }

    private fun requestAudioFocus(): Boolean {
        if (audioFocusGranted) {
            Log.d(TAG, "requestAudioFocus — already granted, skipping")
            return true
        }
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(audioAttrs)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(focusChangeListener)
                .build()
            audioFocusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                focusChangeListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
        }
        val granted = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        audioFocusGranted = granted
        Log.i(TAG, "requestAudioFocus result=${if (granted) "GRANTED" else "DENIED"} (code=$result)")
        return granted
    }

    private fun abandonAudioFocus() {
        if (!audioFocusGranted) {
            Log.d(TAG, "abandonAudioFocus — not held, skipping")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let {
                audioManager.abandonAudioFocusRequest(it)
                audioFocusRequest = null
            }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusChangeListener)
        }
        audioFocusGranted = false
        Log.i(TAG, "abandonAudioFocus — released")
    }

    private fun pushPlaybackState(state: TourState) {
        val pbState = when (state) {
            TourState.SPEAKING                                                   -> PlaybackStateCompat.STATE_PLAYING
            TourState.PAUSED                                                     -> PlaybackStateCompat.STATE_PAUSED
            TourState.LOCATING, TourState.FETCHING,
            TourState.GENERATING, TourState.LOADING_AUDIO,
            TourState.NO_NEW_POIS                                                -> PlaybackStateCompat.STATE_BUFFERING
            TourState.ERROR                                                      -> PlaybackStateCompat.STATE_ERROR
            TourState.IDLE                                                       -> PlaybackStateCompat.STATE_STOPPED
        }
        val actions = when (state) {
            TourState.SPEAKING ->
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_STOP
            TourState.PAUSED ->
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_STOP
            else ->
                PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT
        }
        Log.d(TAG, "pushPlaybackState: $state → pbState=$pbState")
        val builder = PlaybackStateCompat.Builder()
            .setState(pbState, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
            .setActions(actions)
        // Thumbs-down + Maps are surfaced as custom Android Auto actions while narrating.
        if (state == TourState.SPEAKING) {
            builder.addCustomAction(
                PlaybackStateCompat.CustomAction.Builder(
                    TourGuideService.ACTION_THUMBS_DOWN, "Not Interested", R.drawable.ic_thumb_down
                ).build()
            )
            builder.addCustomAction(
                PlaybackStateCompat.CustomAction.Builder(
                    TourGuideService.ACTION_NAVIGATE, "Maps", R.drawable.ic_maps
                ).build()
            )
        }
        session.setPlaybackState(builder.build())
    }

    private fun pushMetadata(topic: String, state: TourState) {
        val title = if (topic.isNotBlank()) topic else getString(R.string.app_name)
        val subtitle = when (state) {
            TourState.IDLE          -> "Ready to explore"
            TourState.LOCATING      -> "Getting your location…"
            TourState.FETCHING      -> "Finding interesting places…"
            TourState.GENERATING    -> "Writing narration…"
            TourState.LOADING_AUDIO -> "Loading audio…"
            TourState.SPEAKING      -> "Narrating"
            TourState.PAUSED        -> "Paused"
            TourState.NO_NEW_POIS   -> "Looking for new places…"
            TourState.ERROR         -> "Error — will retry"
        }
        Log.d(TAG, "pushMetadata: title=\"$title\" subtitle=\"$subtitle\" artwork=${currentArtwork != null}")
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, getString(R.string.app_name))
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, -1L)
        currentArtwork?.let { builder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it) }
        session.setMetadata(builder.build())
    }

    private suspend fun loadBitmap(url: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            val request = ImageRequest.Builder(this@TourAutoMediaService)
                .data(url)
                .size(800, 800)
                .allowHardware(false)
                .build()
            val result = imageLoader.execute(request)
            ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
        } catch (e: Exception) {
            Log.w(TAG, "loadBitmap exception: ${e.message}")
            null
        }
    }

    private inner class SessionCallback : MediaSessionCompat.Callback() {
        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
            Log.i(TAG, "SessionCallback.onPlayFromMediaId mediaId=$mediaId extras=$extras")
            onPlay()
        }

        override fun onPlay() {
            val currentState = TourGuideService.tourState.value
            Log.i(TAG, "SessionCallback.onPlay — currentState=$currentState")
            val focusGranted = requestAudioFocus()
            if (!focusGranted) {
                Log.w(TAG, "onPlay — audio focus NOT granted, aborting")
                return
            }
            if (currentState == TourState.PAUSED) {
                Log.i(TAG, "onPlay — resuming paused tour")
                send(TourGuideService.ACTION_RESUME)
            } else {
                Log.i(TAG, "onPlay — starting new tour")
                startTour()
            }
        }

        override fun onPause() {
            Log.i(TAG, "SessionCallback.onPause")
            send(TourGuideService.ACTION_PAUSE)
        }

        override fun onSkipToNext() {
            Log.i(TAG, "SessionCallback.onSkipToNext")
            send(TourGuideService.ACTION_SKIP)
        }

        override fun onCustomAction(action: String?, extras: Bundle?) {
            Log.i(TAG, "SessionCallback.onCustomAction action=$action")
            when (action) {
                TourGuideService.ACTION_THUMBS_DOWN -> send(TourGuideService.ACTION_THUMBS_DOWN)
                TourGuideService.ACTION_NAVIGATE    -> send(TourGuideService.ACTION_NAVIGATE)
            }
        }

        override fun onStop() {
            Log.i(TAG, "SessionCallback.onStop — abandoning audio focus")
            abandonAudioFocus()
            send(TourGuideService.ACTION_STOP)
        }

        private fun startTour() {
            val prefs = getSharedPreferences(TourGuideService.PREFS_NAME, MODE_PRIVATE)
            val apiKey = prefs.getString(PREF_API_KEY, "") ?: ""
            val radius = prefs.getFloat(TourGuideService.PREF_LAST_RADIUS, 1f)
            val famousMode = prefs.getBoolean(TourGuideService.PREF_FAMOUS_MODE, false)
            Log.i(TAG, "startTour — radius=${radius}mi famousMode=$famousMode apiKey=${if (apiKey.isNotBlank()) "present (${apiKey.take(8)}…)" else "MISSING"}")
            startForegroundService(
                Intent(this@TourAutoMediaService, TourGuideService::class.java).apply {
                    action = TourGuideService.ACTION_START
                    putExtra(TourGuideService.EXTRA_RADIUS_MILES, radius)
                    putExtra(TourGuideService.EXTRA_API_KEY, apiKey)
                    putExtra(TourGuideService.EXTRA_FAMOUS_MODE, famousMode)
                }
            )
        }

    }

    private fun send(action: String) {
        Log.d(TAG, "send → TourGuideService action=$action")
        startService(
            Intent(this, TourGuideService::class.java).apply {
                this.action = action
            }
        )
    }

    private fun focusChangeName(focusChange: Int) = when (focusChange) {
        AudioManager.AUDIOFOCUS_GAIN                     -> "GAIN"
        AudioManager.AUDIOFOCUS_LOSS                     -> "LOSS"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT           -> "LOSS_TRANSIENT"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "LOSS_TRANSIENT_CAN_DUCK"
        else                                             -> "UNKNOWN"
    }

    companion object {
        const val TAG = "TourAutoMediaService"
        private const val ROOT_ID = "root"
        private const val ITEM_ID = "tour_guide"
        private const val PREF_API_KEY = "pref_api_key"

        @Volatile var sharedToken: android.support.v4.media.session.MediaSessionCompat.Token? = null
    }
}
