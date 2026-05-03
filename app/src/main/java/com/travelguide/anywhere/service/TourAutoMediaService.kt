package com.travelguide.anywhere.service

import android.content.Intent
import android.os.Bundle
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.media.MediaBrowserServiceCompat
import com.travelguide.anywhere.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class TourAutoMediaService : MediaBrowserServiceCompat() {

    private lateinit var session: MediaSessionCompat
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()

        session = MediaSessionCompat(this, TAG).apply {
            setCallback(SessionCallback())
            isActive = true
        }
        sessionToken = session.sessionToken

        pushMetadata(TourGuideService.currentTopic.value, TourGuideService.tourState.value)
        pushPlaybackState(TourGuideService.tourState.value)

        scope.launch {
            launch { TourGuideService.tourState.collect { pushPlaybackState(it) } }
            launch {
                TourGuideService.currentTopic.collect {
                    pushMetadata(it, TourGuideService.tourState.value)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        session.release()
    }

    override fun onGetRoot(
        clientPackageName: String,
        clientUid: Int,
        rootHints: Bundle?
    ): BrowserRoot = BrowserRoot(ROOT_ID, null)

    override fun onLoadChildren(
        parentId: String,
        result: Result<List<MediaBrowserCompat.MediaItem>>
    ) {
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
                PlaybackStateCompat.ACTION_STOP
            else ->
                PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID
        }
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(pbState, PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN, 1f)
                .setActions(actions)
                .build()
        )
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
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle)
                .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, getString(R.string.app_name))
                .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, -1L)
                .build()
        )
    }

    private inner class SessionCallback : MediaSessionCompat.Callback() {
        override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) = onPlay()

        override fun onPlay() {
            if (TourGuideService.tourState.value == TourState.PAUSED) {
                send(TourGuideService.ACTION_RESUME)
            } else {
                startTour()
            }
        }

        override fun onPause()      { send(TourGuideService.ACTION_PAUSE) }
        override fun onSkipToNext() { send(TourGuideService.ACTION_SKIP) }
        override fun onStop()       { send(TourGuideService.ACTION_STOP) }

        private fun startTour() {
            val prefs = getSharedPreferences(TourGuideService.PREFS_NAME, MODE_PRIVATE)
            val apiKey = prefs.getString(PREF_API_KEY, "") ?: ""
            val radius = prefs.getFloat(TourGuideService.PREF_LAST_RADIUS, 1f)
            startForegroundService(
                Intent(this@TourAutoMediaService, TourGuideService::class.java).apply {
                    action = TourGuideService.ACTION_START
                    putExtra(TourGuideService.EXTRA_RADIUS_MILES, radius)
                    putExtra(TourGuideService.EXTRA_API_KEY, apiKey)
                }
            )
        }

        private fun send(action: String) =
            startService(
                Intent(this@TourAutoMediaService, TourGuideService::class.java).apply {
                    this.action = action
                }
            )
    }

    companion object {
        private const val TAG = "TourAutoMediaService"
        private const val ROOT_ID = "root"
        private const val ITEM_ID = "tour_guide"
        private const val PREF_API_KEY = "pref_api_key"
    }
}
