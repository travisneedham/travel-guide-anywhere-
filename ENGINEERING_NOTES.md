# Engineering Notes

A persistent reference for decisions made and research conducted so that AI-assisted sessions
don't re-litigate the same ground after context compaction.

---

## Trip Mode: Geocoding the Wrong Country ("Parthenon Bug")

### What the bug looks like

User pastes a Google Maps link for a route **in Athens, Greece** (e.g. Parthenon → city centre,
~3.6 km). The app narrates a route in **Tennessee or Alabama** (~309 miles). Everything from
POI discovery to narration is wrong because the route waypoints are on the wrong continent.

---

### Google Maps URL anatomy — what data is actually available

A typical expanded URL looks like:

```
https://www.google.com/maps/dir/Parthenon,+Athens,+Greece/Athens,+Greece/@37.9757,23.7369,14z/data=!4m14!4m13!1m5!...!1sChIJ...!2m2!1d23.72!2d37.97!1m5!...!3e0
```

Fields we can reliably extract:

| Field | Source | Reliable? |
|---|---|---|
| Origin text | `/dir/{ORIGIN}/{DEST}/` path segments | Yes, but may be short ("Parthenon") or full ("Parthenon,+Athens,+Greece") |
| Dest text | same | same |
| `@lat,lon` viewport | path segment starting with `@` | **NOT the route location** — see below |
| `!2m2!1d{lon}!2d{lat}` pairs | `data=` segment | Only for coordinate-pinned waypoints |
| `!1s{placeId}` | `data=` segment | Google place IDs — **cannot resolve without Google Places API** |

#### Critical finding: `@lat,lon` is the user's device location, not the route

The `@lat,lon` in a `maps.app.goo.gl` short link reflects **where the user's map viewport was
at the time they shared the link** — which is often their current device location. When a
Nashville-based user shares a Greece route link, the `@` coords are Nashville. Using this as a
geocoding anchor sends all Nominatim lookups to Tennessee.

Confirmed from logcat: user in Nashville shared an Athens, Greece route →
`@36.1497159,-86.8133822` (Nashville) appeared in the expanded URL.

#### Critical finding: named-place routes don't have `!2m2` coordinate pairs

Google Maps `/dir/` routes created by searching for place names use `!1s{placeId}` (place ID
references) in the `data=` segment rather than `!2m2!1d{lon}!2d{lat}` coordinate pairs. The
`extractWaypointCoords()` regex correctly looks for `!2m2` patterns, but for named-place routes
it always returns an empty list. Direct coordinate extraction only works for routes where the
user pinned an exact map location (tap-and-hold a point).

#### Nominatim importance scores do NOT favour the globally famous version

Contrary to the assumption in commit 55983be, **Nominatim's raw importance scoring ranks
Nashville's Parthenon replica above the Athens Acropolis Parthenon** (user-verified via testing).
Similarly "Athens" without context may resolve to Athens, GA or Athens, AL before Athens, Greece.
This means `bounded=null` with a soft viewbox is also insufficient.

---

### All approaches tried (chronological)

#### Attempt 1 — `b321eb1` (v3.1.9): Geocode origin first, use result as dest anchor

Geocode origin with no context, pass resulting coords as a ±5° viewbox hint for destination.

**Why it fails:** 'Parthenon' (origin) geocodes with no context → Nashville Parthenon. The
viewbox hint for destination is now centred on Nashville → 'Athens' → Athens, Alabama.

---

#### Attempt 2 — `26c67e0` (v3.2.0): Anchor both lookups to the URL viewport

Extract `@lat,lon` from the URL and use it as the Nominatim viewbox anchor for BOTH origin
and destination.

**Why it fails:** `@lat,lon` is the user's device location (Nashville), not the route location.
With `bounded=1` around Nashville, every geocoding call is trapped in Tennessee.

---

#### Attempt 3 — `2670506` / `5061a7b` (reverted): Use device GPS as fallback anchor

Expose `TourGuideService.lastKnownLocation` and pass it to `resolveRoute()` as a hint when
the URL has no viewport.

**Why it was reverted:** The viewport was always Nashville (see above), so this anchor was the
same wrong information. Also introduced coupling between the location service and route parsing.

---

#### Attempt 4 — `59e246e` (v3.2.3): Three-level strategy with bounded=1

- Level 1: Extract exact coords from `data=` segment (bypasses Nominatim entirely)
- Level 2: `bounded=1` Nominatim inside ±2° box around `@lat,lon` viewport; retry unbiased if empty
- Level 3: Unbiased Nominatim

**Why it fails:** Level 1 returns empty for named-place routes (no `!2m2` pairs). Level 2 has
`bounded=1` around Nashville → trapped in Tennessee. Level 3 is never reached because Level 2
always returns *something* (Nashville Parthenon) rather than nothing.

Also introduced dest anchor fallback: `anchor = viewport ?: origin`. When viewport is Nashville
and origin resolves to Nashville, dest is also anchored to Nashville.

---

#### Attempt 5 — `55983be` (v3.2.8, current): Remove bounded=1, soft viewbox only

Changed `bounded=1` → `bounded=null`. Viewbox passed as a preference only, not a restriction.

**Why it also fails (per user testing):** Nominatim's global importance scores actually rank the
Nashville Parthenon higher than the Greek Parthenon. Without any hard restriction the wrong
result still wins. The assumption that "famous global landmarks outrank US replicas" is false for
this case.

---

### What has NOT been tried yet

#### Option A — Minimum-pair-distance heuristic (most promising)

Query Nominatim with `limit=3` (or 5) for each of origin and destination. Enumerate all
(origin_candidate × dest_candidate) pairs, compute haversine distance for each, pick the pair
with the shortest distance.

**Rationale:** Nashville Parthenon + Athens, GA ≈ 435 km. Athens Acropolis + Athens, Greece
≈ 3.6 km. The correct pair will almost always be far closer together than any wrong-continent
pairing. Works regardless of the URL viewport.

**Limitation:** Would fail for intentionally very long routes where a closer wrong pair exists.
But the 500-mile app cap already limits route length, and for famous cross-continental routes
both place names are usually unambiguous enough that limit=1 gets them right.

**NominatimResult** currently only deserialises `lat`, `lon`, `display_name`. No other changes
needed — just call with `limit = 3` instead of `1`, return all candidates, then pick the pair.

#### Option B — Parse country context from path segments

The `/dir/{ORIGIN}/{DEST}/` URL path may already contain the disambiguated full name, e.g.
`Parthenon,+Athens,+Greece` rather than just `Parthenon`. This would geocode correctly with
`limit=1` and no anchor. Worth logging the raw decoded path segments to see if Google always
encodes the country in the path, or only sometimes.

To verify: add a log line in `parseGoogleMapsUrl()` that prints the raw decoded path segments
before splitting into origin/dest. If they contain country names, the simplest fix is to
pass the full segment text to Nominatim rather than stripping it to just the first part.

Currently `parseGoogleMapsUrl` uses `segments[dirIndex + 1]` and `segments[dirIndex + 2]`
which should already give the full URL-decoded text. The question is whether Google includes the
country in short-link expansions.

#### Option C — Nominatim `countrycodes` parameter

Nominatim supports `countrycodes=gr` to restrict results to Greece. But we'd need to know the
country first, which is the problem we're trying to solve.

#### Option D — Use Photon or another geocoder

Photon (Komoot's geocoder, also OSM-based) may rank results differently. Not worth switching
until we understand why Nominatim's importance scores are inverted for Parthenon.

#### Option E — Parse `!1s` place IDs from `data=`

Google place IDs like `ChIJabc123` appear in the `!1s` tokens. These could theoretically be
resolved via the Google Places API. **Blocked** — requires a Google Places API key (paid) and
adds a hard external dependency.

---

### Recommended next step

Try **Option A** (minimum-pair-distance) first — it's purely a change to `geocodeWithContext`
and doesn't require any new API keys or services. The logic:

1. Add a `limit` parameter to `NominatimService.search()` (already there, just set to 3 or 5)
2. In `resolveRoute`, call a new `geocodeCandidates(text, limit=3)` for both origin and dest
3. For all (o, d) pairs compute haversine distance
4. Return the pair with minimum distance

If the logcat shows the full disambiguated place names in the path segments (Option B), that's
an even simpler fix — just verify the raw text before attempting Option A.

---

## Trip Mode: Location Search + Auto-Route (replaces Google Maps URL parsing)

### Why URL parsing was abandoned

All five attempts to correctly geocode Google Maps share-links failed (see "Parthenon Bug"
section above). The root problem is unsolvable without a Google Places API key: short-link
expansions give us only human-readable place names and the user's device viewport — not the
actual route coordinates.

### New approach (v3.2.9+)

- **Location search box** replaces the URL paste field. User types any place name; the app
  calls Nominatim with `limit=5` and shows a labelled list (e.g. "Parthenon, Athens, Attica,
  Greece" vs "Parthenon, Nashville, Tennessee, US"). User taps the correct one — disambiguation
  is now done by the human, not an algorithm.
- **Auto-route generation**: Once a location is selected, the app picks one of 8 cardinal
  directions (N/NE/E/SE/S/SW/W/NW) at random, computes a destination 1 mile away using the
  haversine `destinationPoint` formula, then calls the OSRM public walking-profile server to
  get real footpath waypoints between the two coordinates.
- **Key files:** `RouteRepository.kt` (search + OSRM), `LocationResult.kt` (data model),
  `item_location_result.xml` (list item layout), `MainViewModel.kt` (`searchLocations` /
  `selectLocation`), `MainFragment.kt` (`updateLocationResults` / debounced TextWatcher).

---

## Loading Spinner Implementation

### Architecture

`TourGuideService.loadingProgress: MutableStateFlow<Float>(-1f)` is the single source of truth.

- **`-1f`** → spinner is indeterminate (spinning circle, no fill)
- **`0.0–1.0`** → spinner is determinate (arc fills to that fraction)

`MainFragment` observes `combine(tourState, loadingProgress)` and toggles
`CircularProgressIndicator.isIndeterminate` accordingly.

When `speakNarration()` starts, TourGuideService sets `loadingProgress = -1f` and passes
`onProgress = { fraction -> loadingProgress.value = fraction }` to `engine.speak()`. Each
engine decides whether/how to call `onProgress`.

---

### Per-engine spinner behaviour

#### Kokoro TTS — determinate, chunk-based (accurate)

Kokoro uses an adaptive chunking pipeline: it splits narration into ~200-char chunks and
generates them in sequence while playing. After each chunk it runs `computeAdaptiveBufN()` to
project whether enough chunks are buffered for gapless playback.

- While buffering: `onProgress((i+1).toFloat() / needed.coerceAtLeast(i+2))` — arc grows chunk by chunk
- When buffer threshold is met: `onProgress(1.0f)` — arc completes; `onStart()` fires, playback begins
- If prewarmed: `onProgress(1.0f)` immediately (all chunks were already generated)

This is the original v2.4.5 implementation and still works correctly.

#### OpenAI TTS — determinate, byte-streaming (accurate)

`fetchAudio()` reads the HTTP response in 8 KB chunks and reports
`onProgress(bytesRead / contentLength)` on each read. The `Content-Length` response header from
OpenAI's TTS endpoint is reliable, so the arc tracks real download progress closely.

- If no `Content-Length` header: reads all bytes at once, calls `onProgress(1.0f)` when done
- If prewarmed: `onProgress(1.0f)` immediately

This is the original v2.4.5 implementation and is already in the current codebase.

#### Piper TTS — indeterminate (v3.3.0+)

Piper uses sherpa-onnx's `OfflineTts.generate()`, which is a single blocking call — no
callbacks, no incremental output. The sherpa-onnx API does have a `generateWithCallback`
variant that fires per-sample, but **this is broken on Kotlin 2.x**: the JNI callback from the
C++ thread is incompatible with Kotlin 2.x coroutine dispatchers and causes crashes.

**History of failed progress estimators for Piper:**

- **v3.1.7 (time-based):** Estimated total duration as `text.length × 25ms / speechRate`,
  ran a 150ms ticker coroutine that reported `elapsed/estimated`. Worked on the calibration
  device (Samsung SM-F766U1) but over-estimated on faster hardware.
- **v3.2.3–v3.2.9 (25ms/char ticker):** Same ticker with slightly different constants. On the
  user's device, synthesis completes well before the estimate — circle stalls at 3–20% and
  then audio starts playing while the circle is still partially filled.

**Resolution (v3.3.0):** Remove the ticker entirely. `loadingProgress` stays at `-1f` throughout
Piper synthesis → spinner is a plain spinning circle. No fake progress is better than wrong
progress.

Exception: when prewarmed audio is used (synthesis already done), `onProgress(1.0f)` still fires
immediately in `speak()` before `onStart()` — this is accurate and kept.

#### Android TTS — always indeterminate

`AndroidTtsEngine` uses the system TTS engine, which provides no synthesis progress callbacks.
`onProgress` is never called → always indeterminate. This is correct and intentional.

---

## Android Auto: Sideloaded APK Setup

### One-time setup on the phone

Android Auto normally only shows apps installed from the Play Store. To allow sideloaded/debug
APKs you must unlock developer mode:

1. Open the **Android Auto** app on the phone
2. Hamburger menu → **About**
3. Tap the version number **10 times** — developer mode unlocks
4. A **Developer options** section appears in the main settings
5. Enable **"Allow apps from unknown sources"**

### Each time a new APK is installed

1. Install the APK — via ADB (`adb install -r app-debug.apk`) or download the artifact from
   the GitHub Actions tab and tap to install on the phone
2. Force-stop the Android Auto app and relaunch it (or restart the phone — Android Auto caches
   its app list and won't pick up a new install until it rescans)
3. Connect to the car or open the Android Auto phone screen — Travel Guide Anywhere should
   appear under media apps

The debug keystore is committed to the repo (`app/debug.keystore`, password `android`) so all
APKs built by Android Studio or GitHub Actions are signed identically. Reinstalls never cause
a signature conflict.

### If the app doesn't appear

- Confirm the APK installed: `adb shell pm list packages | grep travelguide`
- Check for a startup crash: `adb logcat -s TourAutoMediaService`

### How Android Auto integration works

The app uses `MediaBrowserServiceCompat` (not the newer Car App Library). Android Auto discovers
it via the `android.media.browse.MediaBrowserService` intent filter on `TourAutoMediaService`
and the `automotive_app_desc.xml` resource declaring `<uses name="media"/>`.

`TourAutoMediaService` bridges Android Auto controls (play/pause/skip/stop) to
`TourGuideService` via its static StateFlows, and pushes playback state, track metadata, and
POI artwork back to the car's head unit.

---

## App Icon Update (pending)

The current icon is a blue location pin with a microphone (from v3.0.0). The user wants to
update it to an orange C-shaped pin with a brain inside and black sound waves.

**Current state:** Reverted to the original microphone icon in `e3360fa`.

**Why it stalled:** Inline images pasted in Claude Code chat are displayed visually to the AI
but are NOT saved to disk — they cannot be used as file input for image processing tools.

**How to provide the file:** On a desktop/laptop, copy the icon file to a known path:
```bash
cp ~/Downloads/icon_source.png /tmp/icon_source.png
```
Then tell Claude the path. Claude can then read the file and generate the adaptive-icon PNGs.

**Icon spec (for when the file arrives):**
- Adaptive icon foreground: 5 densities (mdpi/hdpi/xhdpi/xxhdpi/xxxhdpi), 108dp canvas,
  66.67% safe zone (icon art fits inside 72dp), transparent background PNG
- Legacy square + round launchers: same 5 densities
- `launcher_bg` in `colors.xml`: update from `#0D2A6E` (dark blue) to match the new design's
  background color (white `#FFFFFF` or whatever the source file uses)
- Files to update: all `mipmap-*/ic_launcher.png`, `mipmap-*/ic_launcher_foreground.png`,
  `mipmap-*/ic_launcher_round.png`, and `colors.xml`

---

## Toolchain Compatibility (do not change without re-reading CLAUDE.md)

Current working combo (as of v3.2.8):
- AGP `8.10.0` + Gradle `8.14.1` + compileSdk `36` + targetSdk `35`
- Kotlin `2.3.20` + KSP `2.3.8` (new standalone semver, not `{kotlin}-1.0.N` format)
- Hilt `2.58` (last version before `POST_COMPILATION_CLASSES` which requires AGP 9.0+)
- `androidx.core:core-ktx` `1.18.0`

See `CLAUDE.md` for the full constraint list.
