// v1.0 — Initial working release
// Features: GPS-triggered tour loop, Overpass POI discovery, Claude AI narration,
// Android TTS playback, foreground service, Room session deduplication.
package com.travelguide.anywhere

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class TravelGuideApp : Application()
