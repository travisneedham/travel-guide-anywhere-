package com.travelguide.anywhere

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.travelguide.anywhere.service.PiperVoices
import com.travelguide.anywhere.service.TourGuideService
import com.travelguide.anywhere.ui.main.MainFragment
import dagger.hilt.android.HiltAndroidApp
import okhttp3.OkHttpClient
import javax.inject.Inject

@HiltAndroidApp
class TravelGuideApp : Application(), ImageLoaderFactory {

    @Inject lateinit var okHttpClient: OkHttpClient

    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
        applyInstallDefaults()
    }

    /**
     * Seed the fresh-install defaults once. Writing them into prefs (rather than relying on each
     * read-site fallback) makes "the default on install" explicit and consistent everywhere:
     * live mode, famous-first sort, 30-mile range, 1X narration speed, and the Piper LibriTTS-R
     * voice. Guarded by a flag so it never overrides choices the user later changes.
     */
    private fun applyInstallDefaults() {
        val prefs = getSharedPreferences(TourGuideService.PREFS_NAME, Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREF_INSTALL_DEFAULTS_APPLIED, false)) return
        prefs.edit()
            .putBoolean(MainFragment.PREF_TRIP_MODE, false)            // live (not trip) mode
            .putBoolean(MainFragment.PREF_FAMOUS_SORT, true)          // famous first
            .putInt(MainFragment.PREF_RADIUS_INDEX, RADIUS_INDEX_30_MI) // 30 miles
            .putFloat(MainFragment.PREF_SPEECH_RATE, 1.0f)            // 1X narration speed
            .putString(TourGuideService.PREF_TTS_PROVIDER, "piper")   // Piper engine
            .putString(TourGuideService.PREF_PIPER_VOICE_ID, PiperVoices.DEFAULT_VOICE_ID) // LibriTTS-R
            .putBoolean(PREF_INSTALL_DEFAULTS_APPLIED, true)
            .apply()
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
            .build()

    companion object {
        private const val PREF_INSTALL_DEFAULTS_APPLIED = "pref_install_defaults_applied"
        // Index of 30f in MainFragment.SLIDER_MILES.
        private const val RADIUS_INDEX_30_MI = 29
    }
}
