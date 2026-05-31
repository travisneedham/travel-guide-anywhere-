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

## POI Discovery: Overpass Element-Ordering Problem and OpenTripMap Migration

> ⚠️ **Partially superseded** — OpenTripMap was removed in v3.4.4 (see "v3.4.4 — OpenTripMap removed").
> The Overpass element-ordering analysis below is still relevant; the OpenTripMap-migration parts are
> historical only.

### The Overpass element-ordering problem (v3.2.9–v3.3.3)

Overpass returns results in **element-type order**: all matching nodes first (sorted by OSM ID),
then all ways, then all relations. A global result cap (`out body center 200` or `500`) fills
entirely from nodes before a single way or relation gets a slot.

In Athens at 10 miles, there are hundreds of `node["wikipedia"]` entries — minor churches,
plaques, municipal buildings. The Acropolis of Athens is mapped as a **relation** (multipolygon)
in OSM. With cap=200, it was never returned at all.

### What was tried (v3.3.3)

- Required both `["wikipedia"]["wikidata"]` on generic node queries → dramatically fewer nodes
- Added `relation["heritage"]` to explicitly capture UNESCO World Heritage Sites
- Required `["wikipedia"]` on historic node/way queries to filter noise
- Raised cap to 500, timeout to 45s

This helped dense cities but **broke small towns**: requiring `["wikipedia"]` on historic
nodes filters out features like a 200-year-old courthouse that has `historic=building` but
no Wikipedia article. The Overpass approach creates a fundamental tension between dense-city
filtering and sparse-area coverage.

### APIs evaluated as alternatives

| API | Fame ranking | Max radius | API key? | Response time | Rural coverage |
|---|---|---|---|---|---|
| **Wikipedia GeoSearch** | None (distance only) | 10 km hard cap | No | Fast | Poor (no Wikipedia = no results) |
| **Wikidata SPARQL** | Sitelinks count (excellent proxy) | Unlimited | No | 9–27s (degraded in 2026) | Same as Wikipedia |
| **OpenTripMap** | `rate` field (3h/2h/1h/0) | Unlimited | Free key required | Fast | 10M+ global POIs, good |
| **Overpass (current)** | None (custom fameScore) | Unlimited | No | 5–30s | Excellent (all OSM data) |
| **Google Places** | Prominence score | 50 km | Paid ($$$) | Fast | Excellent |

### Why OpenTripMap was chosen

OpenTripMap is the only free API that provides a **built-in fame/notability ranking** (`rate`)
with good global coverage including rural areas:

- `rate=3h` — UNESCO/major world attraction (Acropolis, Colosseum)
- `rate=2h` — well-known regional attraction
- `rate=1h` — locally interesting (small-town museums, historic buildings)
- `rate=0` — basic POI

A single API call returns the top N most notable places within a radius, pre-ranked:
```
GET /0.1/en/places/radius?radius=16000&lon=23.7348&lat=37.9755
  &kinds=interesting_places&rate=1h&format=geojson&limit=20&apikey=...
```

In Athens this returns Parthenon, Acropolis, National Museum at the top. In a small Illinois
town it returns the 4–6 locally notable things without flooding with noise. The element-type
ordering problem is eliminated entirely because OpenTripMap is not backed by raw OSM queries.

**Free tier**: requires a free API key from `dev.opentripmap.org`. Same model as the existing
Anthropic API key in app settings.

### Architecture decision

- **Both modes (Famous/Closest, Live/Trip):** Use OpenTripMap for POI discovery
- **Overpass fallback:** If OpenTripMap returns < 3 results or the user hasn't set an
  OpenTripMap API key, fall back to the existing Overpass query
- **fameScore:** Still computed from OSM tags when using Overpass fallback; OpenTripMap results
  use the `rate` field directly for ordering

### Key files (after migration)

- `PoiRepository.kt` — add `fetchPoisFromOpenTripMap()`, keep `fetchPois()` (Overpass) as
  fallback, add orchestration logic
- `AppModule.kt` — add Retrofit service for OpenTripMap
- `MainFragment.kt` / settings UI — add OpenTripMap API key preference
- `PlaceOfInterest.kt` — map OpenTripMap `rate` field to fameScore-compatible value

### What stays the same

- Overpass is still used for nearby-mode fallback (distance-sorted, no fame ranking needed)
- `NarrationRepository` and Claude prompt pipeline are unchanged — they consume
  `PlaceOfInterest` regardless of source
- `MentionedPlacesStore` deduplication works on POI name, which both sources provide

---

## OpenTripMap Integration: Bugs Found and Fixed (v3.3.7)

> ⚠️ **Superseded** — OpenTripMap was removed entirely in v3.4.4 (see "v3.4.4 — OpenTripMap removed").
> Kept for history.

### Bug 1: Results sorted by distance instead of fame

**Symptom:** From Denton TX at 40mi radius in famous mode, the app returned a local Denton
monument instead of JFK Museum / Reunion Tower in Dallas.

**Root cause:** OTM's `/places/radius` endpoint defaults to `orderby=dist` (nearest first). With
limit=50, the 50 results are the 50 closest notable places to the query center. At 40mi, this
means suburban Denton/Frisco POIs fill the window before Dallas is reached. JFK Museum (35mi
from Denton) may not appear at all in the first 50 distance-ordered results.

**Fix:** Add `orderby=rate` to the API call for famous mode. This makes OTM return the
highest-rated places within the radius first, regardless of distance. Limit raised to 100 to
give a larger candidate pool. For nearby mode, keep `orderby=dist`.

### Bug 2: Client-side sort by fameScore was meaningless for OTM results

**Symptom:** Even after OTM returned results, the final ordering was wrong.

**Root cause:** `fameScore` is computed from OSM tags like `wikipedia`, `heritage`, etc. OTM
results only have `wikidata`, `kinds`, and `otm_rate` in their tags — they never have
`wikipedia` or `heritage` because we don't fetch those from OTM. So every OTM result collapses
to nearly the same fameScore (≈ `type.interestScore + 500`), and the "sort by fameScore" is
effectively a no-op. Results come out in whatever order OTM returned them (distance order).

**Fix:** Sort OTM famous-mode results by `otm_rate` (the actual OTM rate value stored in tags).
`fameScore` continues to be used only for the Overpass fallback path, where full OSM tags are
available.

### Bug 3: kinds=interesting_places may exclude some museums

**Symptom:** Some OTM entries (e.g. museums) that are only tagged with a sub-kind may not be
returned when filtering by the parent kind alone.

**Fix:** Query with `kinds=interesting_places,museums,historic,architecture` to catch entries
that OTM has tagged only with a more specific category.

### Bug 4: rate=2 filter may exclude valid tourist destinations

**Symptom:** Not directly observed, but if JFK Museum has OTM rate=1, the `rate=2` filter
in famous mode would silently drop it.

**Fix:** Set `rate=1` (minimum) for both modes. The `orderby=rate` + client-side sort by
`otm_rate` ensures the highest-rated places still appear first; a strict minimum filter is not
needed when the data is already sorted by rate.

### OTM rate field: two different scales (important)

The OTM API uses `rate` in TWO different ways that can be confused:

| Context | Scale | Example |
|---|---|---|
| `rate` **query parameter** (filter) | `"1"`, `"2"`, `"3"` (or `"1h"`, `"2h"`, `"3h"`) | `rate=2` means return places with OTM importance ≥ 2 |
| `rate` **response field** (per place) | Integer 0–7, based on Wikipedia quality score | `rate=7` = well-documented Wikipedia article (10+ languages) |

These are different metrics. A local monument can have response `rate=7` (many Wikipedia
sitelinks) while also having query filter `rate=1` (locally interesting only). Do not confuse
them.

### Current OTM call parameters (v3.3.7, do not change without good reason)

```
kinds   = "interesting_places,museums,historic,architecture"
rate    = "1"           ← minimum filter; sorting handles priority
orderby = "rate"        ← famous mode: TOP-rated in radius first
          "dist"        ← nearby mode: nearest first
limit   = 100           ← larger pool at 40mi radius
```

---

## Famous POI Coverage & Ranking (v3.3.9 → present)

> **This section supersedes the "Frozen at v3.3.2" note below for everything from v3.3.9 onward.**
> The project owner has explicitly chosen to evolve the famous-POI pipeline (continuous fame
> scoring + Wikipedia signals) rather than keep it frozen. The frozen note is retained as history.

### Why v3.3.2's binary scoring was abandoned

The frozen `fameScore` gave a flat `+1000` for any `wikipedia` tag and `+500` for any `wikidata`
tag. That makes the JFK Museum and the Denton County Courthouse score nearly the same — fame
became "has a Wikipedia article: yes/no", with no notion of *how* famous. The goal of the overhaul
is a **continuous** fame signal so globally famous places clearly outrank locally notable ones.

### What v3.3.9 changed (and why)

1. **Continuous fame from Wikipedia pageviews.** Each POI's monthly English-Wikipedia pageviews are
   fetched and folded into `fameScore` as `views^0.57 × 8.06`. Calibrated so ~50k views ≈ 5000 pts
   and ~200 views ≈ 300 pts — i.e. JFK Museum ≫ Denton Courthouse instead of a tie. The exponent
   compresses the long tail so a mega-landmark doesn't drown everything else.
2. **Wikidata sitelinks as a second signal.** The number of language wikis linked to a POI's
   Wikidata item (`+35` each) is a strong, language-agnostic fame proxy that works outside the US.
3. **Enrichment pipeline.** `enrichWithWikiData()` runs the top `ENRICH_LIMIT` (25) candidates
   through one batched Wikidata sitelinks call + parallel pageviews calls, writing `wiki_views` and
   `wiki_sitelinks` back onto the POI tags. 4-day SharedPreferences cache (a trip tends to stay in
   one area) keyed by article/QID.
4. **Tag-presence fallback.** Until a POI is enriched (or if the calls fail), `wikipedia` → `+500`,
   `wikidata` → `+150`, preserving v3.3.x behaviour as a floor.
5. **Broader OTM kinds + Overpass branches** to actually surface the new categories (stadiums,
   squares, gardens, markets, beaches, peaks, cemeteries, cable cars, zoos/aquariums) and 14 new
   `resolveType` cases so they map to sensible `PoiType`s.

### The v3.4.0 regression (reverted)

v3.4.0 tried to fix two field-reported bugs and over-corrected on both:

- **OTM 400.** OTM echoes the *entire* kinds string in its error, so the log does **not** identify
  which kind is invalid. v3.4.0 guessed and removed four kinds. The likely real culprit is just
  `gardens` (the valid identifier is almost certainly `gardens_and_parks`). Critical fact:
  **a single invalid kind 400s the whole request, which silently disables OTM entirely** and forces
  the slow Overpass fallback on every cycle. So the kinds string must contain *only verified-valid*
  identifiers — the cost of one bad kind is a total OTM outage, the benefit of one extra kind is
  marginal (`interesting_places` already subsumes most notable POIs).
- **Overpass timeout.** v3.4.0 deleted the broad `node["wikipedia"]` catch-all branch. That branch
  *was* the timeout cause (an unconstrained key-existence scan over a 40-mi radius), but it was also
  the only branch that caught odd-but-famous POIs: `man_made=tower` (**Reunion Tower**), active
  `place_of_worship` + wikipedia, viewpoints, non-enumerated `historic=*`, notable buildings.
  Deleting it is a real coverage loss.

Both changes were reverted to the v3.3.9 state.

### Architectural fix in this build

- **Enrichment now runs for BOTH sources.** Previously the OTM path `return`ed before enrichment, so
  the entire pageviews/sitelinks algorithm only ever applied to the Overpass fallback — meaning in
  any city with a working OTM key, none of the v3.3.9 work was used (ranking was raw `otm_rate`).
  `fetchPois` now: pick source → enrich → rank by `fameScore`, uniformly.
- **`otm_rate` folded into `fameScore`** (`rate × 120`, rate is 0–7) so OTM-sourced POIs (which have
  no OSM `wikipedia`/`heritage` tags) still carry OTM's own importance signal and rank coherently
  alongside enriched values.

### Experiment results (resolved — `PoiExperiment` harness, three field runs)

The `PoiExperiment` harness (Settings → NERD STUFF → "Run POI API Experiment") ran against a real
device in Dallas. The clean run (`travel_guide_log_20260531_000430.txt`) completed end-to-end and
settled every open question:

- **OTM kinds.** Only `gardens`, `theme_parks`, `swimming_pools` are invalid (HTTP 400). The v3.3.9
  string failed **solely because of `gardens`** — the valid identifier is `gardens_and_parks`. All
  other v3.3.9 kinds (`stadiums`, `zoos`, `aquariums`, `natural`, `beaches`, `amusements`) are valid.
  Fix shipped in v3.4.2; the combined `v3.4.2_fixed` probe returns HTTP 200. Confirmed-valid extras
  available if ever needed: `cultural`, `religion`, `view_points`, `monuments_and_memorials`,
  `towers`, `bridges`, `other_buildings_and_structures`, `water`, `nature_reserves`.
- **Pageviews parser correct.** Shape verified: `{"items":[{…,"views":N}]}`, summed across months.
  Caveat: the trailing month of the 3-month window is often partial (Eiffel Apr = 9 404 vs Mar =
  310 077) — a known minor skew, revisit the date range later.
- **🔴 Wikidata sitelinks were silently broken (fixed v3.4.3).** `wbgetentities` requires the **pipe**
  `|` id separator; the code used a comma, so `ids=Q1,Q2…` was read as one literal id →
  `no-such-entity` → **zero sitelinks for every multi-id batch since v3.3.9**. The `sitelinks × 35`
  term contributed nothing, and POIs with a `wikidata` tag but no `wikipedia` tag also got no
  pageviews (no enwiki title to query). Fixed: `joinToString(",")` → `joinToString("|")`.

### Famous-query architecture (v3.4.3 — parallel shards + pre-warm)

**The real bottleneck: 171s.** The full single famous query measured **171s** end-to-end. Overpass
executes union branches **sequentially server-side** (confirmed via its run-time-model docs), so an
18-branch union is the sum of its branches. overpass-api.de grants **2 request slots per IP**, so we
now split the query into **two shards POSTed concurrently** — wall time ≈ the slower shard (~80-90s
projected), roughly a 2× win. `buildFamousQueryShards()` returns the two shard queries;
`fetchFromOverpass()` runs them with `async { … }.awaitAll()` and merges/dedupes by `osmId`.

- **Shard A** (fast, high-yield): wikipedia catch-all, tourism+wiki, heritage, historic, stadium+wiki,
  aerialway, plus **new way-inclusive `building`+wiki and `man_made`+wiki branches**.
- **Shard B** (slow coverage, 0-in-Dallas but globally vital): square / garden / marketplace / beach /
  peak / nature_reserve / cemetery — all `[wikipedia]`-gated.

**Branch query optimisation.** `tourism` and `stadium` now require `["wikipedia"]`. In famous mode
only wiki-notable POIs earn a non-zero `fameScore` anyway, and this cut those branches from
~16s/~22s to ~6-7s each in testing.

**Node-only catch-all gap closed.** The `wikipedia` catch-all is `node`-only, and famous squares /
gardens / markets / buildings are mapped as **areas (ways/relations)**. That's why the dedicated
`way[...][wikipedia]` branches are **not** redundant and must stay (cutting them would re-hide the
Reunion-Tower class). v3.4.3 also adds `way["building"]["wikipedia"]` + `man_made`+wiki to catch
area-mapped famous structures the node-only catch-all missed.

**Why we did NOT cut the 0-result branches.** Zero results in Dallas ≠ useless globally —
`place=square`+wiki catches Tiananmen Square, `leisure=garden`+wiki catches Butchart Gardens, etc.
Per OSM convention these are polygons, invisible to the node-only catch-all. Coverage wins over speed.

### Pre-warm / cache (v3.4.3)

- **Pre-warm at launch.** `MainViewModel.init` reads `FusedLocationProvider.lastLocation` (OS-cached,
  no GPS wait) and calls `PoiRepository.prewarm()`, which fetches in the background using the
  **last radius/mode** the user ran (persisted by `startTour` to `PREF_LAST_RADIUS_MILES` /
  `PREF_LAST_FAMOUS_MODE`). By the time the user taps Start Tour, the result is usually ready.
- **In-flight de-dup.** A `ConcurrentHashMap<key, Deferred>` ensures a pre-warm and the later
  Start-Tour fetch collapse into one network round-trip; `fetchPois` awaits the pending job.
- **Geohash cache.** Results are cached in SharedPreferences keyed by `geohash(precision 4)` (~20-40km
  cell) + mode + radius, TTL **6h**. On a hit, per-POI distances are recomputed for the real location
  and re-ranked, so coarse pre-warm location never corrupts ordering.
- **OTM stays primary** (~300ms); the shards are the fallback. OTM is pre-warmed too via the same path.

### Production timeouts (v3.4.3 — reverted from the 300s test build)

| Setting | Value |
|---|---|
| Overpass `[timeout:N]` per shard | `120` |
| OkHttp Overpass client `callTimeout`/`readTimeout` | `125s` (derived client in `PoiRepository`) |
| Global OkHttp `connect`/`write`/`read`/`call` | `15` / `30` / `125` / `125` s |

The `PoiExperiment` harness itself still uses `[timeout:300]` — it's a diagnostic tool, left as-is.

### v3.4.4 — OpenTripMap removed entirely; Overpass is the sole POI source

OTM was originally adopted (v3.3.x) for its `rate` field, a crowd-sourced 0–7 notability signal.
The v3.4.x experiments proved it was the **wrong source for famous mode**: OTM's rate-ranked
top-100 within a 40-mile radius is dominated by the geographically-central cluster, so a famous-mode
tour started from Denton TX returned only Denton/UNT venues — JFK Museum, Reunion Tower, and Dealey
Plaza (~35 mi away in Dallas) never made the cutoff. The Overpass wikipedia-gated shards already
query the *full* radius correctly and surface globally-notable POIs regardless of clustering.

Beyond that bug, `otm_rate` was a strictly weaker signal than the Wikipedia-pageviews and
Wikidata-sitelinks values we already compute, and OTM cost us a user-facing API-key setting plus a
whole code/UI/diagnostic path to maintain. When no key was set, the app already fell through to
Overpass for 100% of cases. So OTM was removed:

- Deleted `OpenTripMapService.kt`, `OpenTripMapPlace.kt`, the Hilt provider, the `fetchFromOpenTripMap`/
  `resolveTypeFromKinds` code, the `otm_rate` fold in `fameScore`, the `otmRate` branches in
  `NarrationRepository.maxTokensFor`, the OTM probes in `PoiExperiment`, and the Settings API-key field.
- `fetchPoisInternal` now calls `fetchFromOverpass` unconditionally for both modes
  (`buildFamousQueryShards` for famous, `buildNearbyQuery` for nearby).
- This permanently closes the "OTM-as-wrong-source" class of bugs. The sections below
  ("OpenTripMap Integration…" and the OTM-specific parts of the v3.3.x notes) are **superseded** and
  kept only for history.

**Cache invalidation:** there is intentionally **no** automatic/version-based cache wipe — that would
erase users' caches on every app update. The POI cache persists across versions; its 6h TTL plus the
now-correct Overpass results clear the old v3.4.3 OTM entry naturally. A manual **"Clear Cached
Places"** button (Settings → NERD STUFF, calls `PoiRepository.clearPoiCaches()`) wipes the POI list
and Wikipedia/Wikidata enrichment caches on demand for testing/troubleshooting.

---

## OpenTripMap Integration & Migration (SUPERSEDED — removed in v3.4.4)

> ⚠️ **Superseded** — OpenTripMap was removed entirely in v3.4.4 (see "v3.4.4 — OpenTripMap removed"
> above). Everything from here to the next `##` heading is retained for historical context only.

## Overpass Fallback: Frozen at v3.3.2 Logic

> ⚠️ **Superseded for v3.3.9+** — see "Famous POI Coverage & Ranking" above. Kept for history.

### The problem this note prevents

Every session that touches famous-mode or nearby-mode ordering ends up modifying the Overpass
queries or `fameScore`. The result is a cycle of regressions: a fix for Athens breaks Denton, a
fix for Denton breaks Athens. **Stop.** The Overpass queries and `fameScore` values are frozen
at the v3.3.2 state, which is the last version where user-verified correct results were produced
(JFK Museum ranked above Denton County Courthouse, etc.).

### When Overpass runs

Overpass is the **fallback only**. It runs when:
- The user has not set an OpenTripMap API key, OR
- OpenTripMap returns fewer than 3 results for the given location/radius

When an OpenTripMap key is present and OTM returns ≥ 3 results, Overpass is never called.
All ordering/ranking problems at that point are an **OTM problem**, not an Overpass problem.

### Verified correct in v3.3.6

The Overpass fallback logic (queries + fameScore) was user-verified correct in v3.3.6 from Denton
TX at 40mi radius. JFK Museum ranked above Denton County Courthouse; nearby mode completed without
timeout. **Leave this logic alone.** If ordering is wrong, get an OTM key.

### The reference queries (v3.3.2, do not modify)

#### Nearby mode

```
[out:json][timeout:45];    ← timeout raised from 25s (latent bug fix, not a logic change)
(
  node["name"]["historic"](around:R,LAT,LON);
  way["name"]["historic"](around:R,LAT,LON);
  node["name"]["tourism"~"attraction|museum|artwork|viewpoint"](around:R,LAT,LON);
  way["name"]["tourism"~"attraction|museum|artwork|viewpoint"](around:R,LAT,LON);
  node["name"]["leisure"="park"](around:R,LAT,LON);
  way["name"]["leisure"="park"](around:R,LAT,LON);
  node["name"]["amenity"="place_of_worship"](around:R,LAT,LON);
);
out body center CAP;   ← cap added (latent bug fix): 300 for ≤30km, 500 for larger
```

**Why the cap is a bug fix, not a logic change:** v3.3.2 had `out body center;` with no cap.
At 40 miles in DFW, this returns tens of thousands of elements and exceeds Overpass's timeout,
producing "Overpass query failed" errors in a retry loop. Adding the cap restores the same
behaviour that smaller radii already had — it does not change what POIs are selected or how
they are ordered.

#### Famous mode

```
[out:json][timeout:30];
(
  node["name"]["wikipedia"][!"shop"]["place"!~"city|town|village|hamlet|suburb|county|state|
      country|region|district|municipality|borough"](around:R,LAT,LON);
  node["name"]["tourism"~"attraction|museum|zoo|theme_park|aquarium|gallery"](around:R,LAT,LON);
  way["name"]["tourism"~"attraction|museum|zoo|theme_park|aquarium|gallery"](around:R,LAT,LON);
  node["name"]["heritage"](around:R,LAT,LON);
  way["name"]["heritage"](around:R,LAT,LON);
  node["name"]["historic"~"castle|monument|archaeological_site|ruins|memorial"](around:R,LAT,LON);
  way["name"]["historic"~"castle|monument|archaeological_site|ruins|memorial"](around:R,LAT,LON);
);
out body center 200;
```

**What was tried and regressed (do not repeat):**

- **v3.3.3: Added `["wikidata"]` to the first branch** — silently dropped tourist destinations
  that have a Wikipedia article but no Wikidata link in OSM. JFK Museum was likely lost here.
  **Reverted.**
- **v3.3.3: Added `["wikipedia"]` requirement to historic nodes** — broke small-town content
  (e.g. 200-year-old courthouse that has `historic=building` but no Wikipedia article).
  **Reverted.**
- **v3.3.3: Added `relation["heritage"]`** — intended to capture the Acropolis (an OSM
  relation). But the Acropolis problem is solved by OTM; the Overpass fallback doesn't need to
  handle this edge case. **Reverted.**
- **v3.3.5 (incorrect): Changed fameScore** — raised museum/attraction bonuses, cut heritage=2
  bonus, added courthouse penalty. This was an attempt to fix ranking without understanding why
  v3.3.2 ranked correctly. **Reverted to v3.3.2 values.**

### Reference fameScore values (v3.3.2, do not modify)

```kotlin
type.interestScore      // HISTORIC=100, MUSEUM=90, ATTRACTION=80, ARTWORK=70, VIEWPOINT=70,
                        // PLACE_OF_WORSHIP=60, PARK=50, OTHER=30
wikipedia tag:   +1000
wikidata tag:    +500
heritage=1:      +2000  (UNESCO World Heritage)
heritage=2:      +800   (national designation)
heritage=3:      +400   (regional designation)
tourism=attraction: +200
tourism=museum:  +150
wikimedia_commons: +100
fee=yes:         +75
opening_hours:   +50
historic=castle or archaeological_site: +80
```

With these values:
- JFK Museum (MUSEUM=90 + wikipedia=1000 + wikidata=500 + museum=150 + fee=75 + hours=50) = **1865**
- Denton Courthouse (HISTORIC=100 + wikipedia=1000 + wikidata=500) = **1600** ← loses correctly

### Do not add complexity to the Overpass fallback

The Overpass fallback is a degraded-mode safety net, not a replacement for OpenTripMap. If
Overpass returns suboptimal ordering for a specific location (e.g. Athens), the correct fix is
for the user to set an OpenTripMap API key — not to redesign the Overpass queries or fameScore.

---



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
