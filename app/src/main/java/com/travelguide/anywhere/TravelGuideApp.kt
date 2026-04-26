// v1.2 — Stable save point
// Fixed: nwr Overpass query (Lincoln Memorial now found), fame-based POI sorting,
// single-POI narration per cycle, TTS DeadObjectException recovery.
package com.travelguide.anywhere

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class TravelGuideApp : Application()
