package com.travelguide.anywhere.repository

import android.content.SharedPreferences
import android.location.Location
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.travelguide.anywhere.data.local.SkippedEnrichmentStore
import com.travelguide.anywhere.data.model.PlaceOfInterest
import com.travelguide.anywhere.data.model.PoiType
import com.travelguide.anywhere.data.remote.dto.OverpassElement
import com.travelguide.anywhere.data.remote.dto.OverpassResponse
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PoiRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val gson: Gson,
    private val prefs: SharedPreferences,
    private val skippedEnrichmentStore: SkippedEnrichmentStore,
) {
    companion object {
        private const val TAG = "PoiRepository"

        const val PREF_FILTER_HISTORIC         = "filter_historic"
        const val PREF_FILTER_MUSEUM           = "filter_museum"
        const val PREF_FILTER_ATTRACTION       = "filter_attraction"
        const val PREF_FILTER_ARTWORK          = "filter_artwork"
        const val PREF_FILTER_VIEWPOINT        = "filter_viewpoint"
        const val PREF_FILTER_PARK             = "filter_park"
        const val PREF_FILTER_PLACE_OF_WORSHIP = "filter_place_of_worship"

        const val PREF_FILTER_SUB_STADIUM    = "filter_sub_stadium"
        const val PREF_FILTER_SUB_RIDE       = "filter_sub_ride"
        const val PREF_FILTER_SUB_ZOO        = "filter_sub_zoo"
        const val PREF_FILTER_SUB_THEME_PARK = "filter_sub_theme_park"
        const val PREF_FILTER_SUB_GARDEN     = "filter_sub_garden"
        const val PREF_FILTER_SUB_TOWER      = "filter_sub_tower"

        private val ADMIN_PLACE_TYPES = setOf(
            "city", "town", "village", "hamlet", "suburb", "neighbourhood",
            "county", "state", "country", "region", "district", "municipality", "borough"
        )

        private const val WIKI_CACHE_TTL_MS = 4L * 24 * 60 * 60 * 1000  // 4 days
        private const val PREF_WIKI_VIEWS_PREFIX = "wiki_v_"
        private const val PREF_WIKI_SLINKS_PREFIX = "wiki_s_"
        private const val ENRICH_LIMIT = 60
        private const val FAMOUS_SHARD_CAP = 1200  // was 300; raising the cap is free — Overpass already found the matches

        // Pre-warm / POI-list cache. The famous query is expensive (see ENGINEERING_NOTES); we cache
        // the fully-ranked result by coarse location so a tour started shortly after app launch is
        // an instant cache hit. Famous landmarks don't move, so a 6h TTL is safe.
        private const val POI_CACHE_TTL_MS = 6L * 60 * 60 * 1000  // 6 hours
        private const val PREF_POI_CACHE_PREFIX = "poi_cache_"
        private const val GEOHASH_PRECISION = 4  // ~20-40km cell, matches our 40-mile query radius

        // Last radius/mode the user ran a tour with — written by MainViewModel.startTour and read by
        // prewarm() so the launch-time fetch targets the parameters the user is most likely to reuse.
        const val PREF_LAST_RADIUS_MILES = "pref_last_radius_miles"
        const val PREF_LAST_FAMOUS_MODE = "pref_last_famous_mode"

        private const val OVERPASS_ENDPOINT = "https://overpass-api.de/api/interpreter"
        private const val OVERPASS_MIRROR_ENDPOINT = "https://overpass.kumi.systems/api/interpreter"
        // Per-shard server-side budget. overpass-api.de grants 2 slots per IP so 2 shards run truly
        // concurrently. 180s lets shardB (rare-coverage branches) finish in dense metros instead of
        // timing out and wasting the slot; shardA is faster. OkHttp ceiling must exceed the server value.
        private const val OVERPASS_SHARD_TIMEOUT_SEC = 180
        private const val OVERPASS_CALL_TIMEOUT_SEC = 195L  // OkHttp ceiling = server timeout + overhead

        private const val GEOHASH_BASE32 = "0123456789bcdefghjkmnpqrstuvwxyz"
    }

    private data class WikidataResult(val sitelinkCount: Int, val enwikiTitle: String?)

    /** Persistent-cache envelope: timestamp + the ranked POI list (fameScore is recomputed, not stored). */
    private data class PoiCacheEnvelope(val ts: Long, val pois: List<PlaceOfInterest>)

    /** Repo-owned scope so prewarm() outlives the caller (Fragment/ViewModel) that triggered it. */
    private val repoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** De-dupes a prewarm fetch and the later "Start Tour" fetch into a single network round-trip. */
    private val inFlight = ConcurrentHashMap<String, Deferred<List<PlaceOfInterest>>>()

    /** Overpass needs a long ceiling; Wiki enrichment calls use the shared client. Shares the connection pool. */
    private val overpassClient: OkHttpClient by lazy {
        okHttpClient.newBuilder()
            .callTimeout(OVERPASS_CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(OVERPASS_CALL_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Kick off a POI fetch as soon as a coarse location is known (typically app launch), so the
     * result is cached/in-flight by the time the user taps "Start Tour". Uses the radius/mode the
     * user last ran with. Fire-and-forget: safe to call repeatedly — duplicate keys are ignored.
     */
    fun prewarm(location: Location) {
        val famousMode = prefs.getBoolean(PREF_LAST_FAMOUS_MODE, false)
        val radiusMiles = prefs.getFloat(PREF_LAST_RADIUS_MILES, 5f)
        val key = cacheKey(location, radiusMiles, famousMode)
        if (inFlight.containsKey(key) || cachedPois(key) != null) {
            Log.d(TAG, "[prewarm] already warm for $key — skipping")
            return
        }
        Log.d(TAG, "[prewarm] starting fetch for $key (radius=${radiusMiles}mi, famous=$famousMode)")
        val job = repoScope.async {
            try { fetchPoisInternal(location, radiusMiles, famousMode, key) }
            finally { inFlight.remove(key) }
        }
        inFlight[key] = job
    }

    suspend fun fetchPois(
        location: Location,
        radiusMiles: Float,
        famousMode: Boolean = false
    ): List<PlaceOfInterest> = withContext(Dispatchers.IO) {
        val key = cacheKey(location, radiusMiles, famousMode)

        // 0a. Join an in-flight prewarm for the same cell/params instead of issuing a 2nd request.
        inFlight[key]?.let { pending ->
            try {
                Log.d(TAG, "[fetchPois] joining in-flight prewarm for $key")
                return@withContext rerankForLocation(pending.await(), location, famousMode)
            } catch (e: Exception) {
                Log.w(TAG, "[fetchPois] in-flight prewarm failed (${e.message}) — fetching fresh")
                inFlight.remove(key)
            }
        }
        // 0b. Serve a fresh persistent-cache hit (distances recomputed for the real location).
        cachedPois(key)?.let { cached ->
            Log.d(TAG, "[fetchPois] cache HIT for $key (${cached.size} POIs)")
            if (famousMode) {
                Log.d(TAG, "[cache/fame] Top 50 (from cache):")
                cached.take(50).forEachIndexed { i, p ->
                    Log.d(TAG, "  #${i + 1} ${p.name} — fame=${p.fameScore} " +
                        "views=${p.tags["wiki_views"] ?: "-"} " +
                        "sitelinks=${p.tags["wiki_sitelinks"] ?: "-"} " +
                        "operator=${p.tags["operator"] ?: "-"} type=${p.type} " +
                        "dist=${"%.1f".format(p.distanceMiles)}mi")
                }
            }
            return@withContext rerankForLocation(cached, location, famousMode)
        }

        fetchPoisInternal(location, radiusMiles, famousMode, key)
    }

    private suspend fun fetchPoisInternal(
        location: Location,
        radiusMiles: Float,
        famousMode: Boolean,
        cacheKey: String,
    ): List<PlaceOfInterest> = withContext(Dispatchers.IO) {
        val radiusMeters = (radiusMiles * 1609.34).toInt()

        // 1. Overpass is the sole POI source for both modes (OTM removed in v3.4.4 — its rate-ranked
        //    results were geographically clustered and missed famous landmarks at the edge of the radius).
        val source = "overpass"
        val rawPois: List<PlaceOfInterest> = fetchFromOverpass(location, radiusMeters, famousMode)

        // 2. Enrich with Wikipedia pageviews + Wikidata sitelinks so the fame-ranking algorithm applies.
        val enriched = try {
            enrichWithWikiData(rawPois)
        } catch (e: Exception) {
            Log.w(TAG, "[enrich] Failed: ${e.message}"); rawPois
        }

        // 3. Final ranking.
        val sorted = if (famousMode) enriched.sortedByDescending { it.fameScore }
                     else enriched.sortedBy { it.distanceMeters }

        if (sorted.isNotEmpty()) {
            val label = if (famousMode) "fame" else "dist"
            Log.d(TAG, "[$source/$label] ${sorted.size} POIs ranked. Top 50:")
            sorted.take(50).forEachIndexed { i, p ->
                Log.d(TAG, "  #${i + 1} ${p.name} — fame=${p.fameScore} " +
                    "views=${p.tags["wiki_views"] ?: "-"} " +
                    "sitelinks=${p.tags["wiki_sitelinks"] ?: "-"} " +
                    "operator=${p.tags["operator"] ?: "-"} type=${p.type} " +
                    "dist=${"%.1f".format(p.distanceMiles)}mi")
            }
            cachePois(cacheKey, sorted)
        }
        sorted
    }

    // ── Overpass (sole POI source) ────────────────────────────────────────────

    private suspend fun fetchFromOverpass(
        location: Location,
        radiusMeters: Int,
        famousMode: Boolean,
    ): List<PlaceOfInterest> {
        val mode = if (famousMode) "famous" else "nearby"
        Log.d(TAG, "[$mode] Querying Overpass: radius=${radiusMeters}m")

        // Famous mode runs as parallel shards: overpass-api.de executes union branches sequentially
        // server-side, so one 18-branch query took ~171s. Splitting across the 2 slots overpass-api.de
        // grants per IP lets the shards run concurrently — wall time ≈ the slowest shard. See
        // ENGINEERING_NOTES "Famous POI Coverage & Ranking" for the research behind this.
        val queries = if (famousMode)
            buildFamousQueryShards(location.latitude, location.longitude, radiusMeters)
        else
            listOf(buildNearbyQuery(location.latitude, location.longitude, radiusMeters))

        val t0 = System.currentTimeMillis()
        // supervisorScope lets one shard fail without cancelling its sibling — we use whatever
        // results we got rather than discarding everything when (e.g.) shardB times out.
        val shardResults: List<Result<List<OverpassElement>>> = supervisorScope {
            queries.mapIndexed { i, q ->
                async { runCatching { postOverpass(q, "$mode#${i + 1}") } }
            }.awaitAll()
        }
        Log.d(TAG, "[$mode] ${queries.size} shard(s) completed in ${System.currentTimeMillis() - t0}ms")

        val succeeded = shardResults.filter { it.isSuccess }
        if (succeeded.isEmpty()) throw shardResults.first { it.isFailure }.exceptionOrNull()!!
        shardResults.forEachIndexed { i, r ->
            if (r.isFailure) Log.w(TAG, "[$mode#${i + 1}] shard failed — partial results used: ${r.exceptionOrNull()?.message}")
        }
        val elements = succeeded.flatMap { it.getOrThrow() }
        if (elements.isEmpty()) {
            throw Exception("Overpass query returned no elements for all ${queries.size} shard(s)")
        }

        val pois = elements
            .filter { it.tags.containsKey("name") }
            .filter { element ->
                element.tags["shop"] == null || element.tags["historic"] != null
            }
            .filter { element ->
                element.tags["place"]?.let { it !in ADMIN_PLACE_TYPES } ?: true
            }
            .distinctBy { it.osmId }  // merge overlap between shards before mapping
            .map { element -> element.toPlaceOfInterest(location) }
            .filter { poi -> isTypeEnabled(poi.type) && isSubCategoryEnabled(poi) }
            .distinctBy { it.name }
            .sortedByDescending { it.fameScore }  // pre-sort so ENRICH_LIMIT targets most promising

        Log.d(TAG, "[$mode] Overpass returned ${elements.size} elements → ${pois.size} named POIs")
        return pois
    }

    /** Try the primary endpoint first; on 504/503/429, retry on the kumi.systems mirror. */
    private fun postOverpass(query: String, tag: String): List<OverpassElement> {
        var lastEx: Exception? = null
        for (endpoint in listOf(OVERPASS_ENDPOINT, OVERPASS_MIRROR_ENDPOINT)) {
            try {
                return postOverpassToEndpoint(endpoint, query, tag)
            } catch (e: Exception) {
                lastEx = e
                val msg = e.message ?: ""
                if ("HTTP 504" !in msg && "HTTP 503" !in msg && "HTTP 429" !in msg) throw e
                Log.w(TAG, "[$tag] $endpoint → ${e.message}, retrying on mirror…")
            }
        }
        throw lastEx!!
    }

    /** POST one Overpass query to a specific endpoint. Tolerates partial (timeout-remark) results. */
    private fun postOverpassToEndpoint(endpoint: String, query: String, tag: String): List<OverpassElement> {
        val body = FormBody.Builder().add("data", query).build()
        val request = Request.Builder()
            .url(endpoint)
            .post(body)
            .header("Accept", "*/*")
            .header("User-Agent", "TravelGuideAnywhere/2.0 (Android)")
            .build()

        val responseJson = overpassClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Overpass HTTP ${response.code}: ${response.body?.string()?.take(200)}")
            }
            response.body?.string() ?: throw Exception("Empty response from Overpass")
        }

        val parsed = gson.fromJson(responseJson, OverpassResponse::class.java)
        // Overpass signals a server-side timeout/memory abort with HTTP 200 + a `remark`, not an
        // error code. Keep whatever partial elements came back; only fail if nothing did.
        if (!parsed.remark.isNullOrBlank()) {
            Log.w(TAG, "[$tag] Overpass remark: ${parsed.remark} (got ${parsed.elements.size} elements)")
            if (parsed.elements.isEmpty()) throw Exception("Overpass shard failed: ${parsed.remark}")
        }
        return parsed.elements
    }

    private fun isTypeEnabled(type: PoiType): Boolean = when (type) {
        PoiType.HISTORIC         -> prefs.getBoolean(PREF_FILTER_HISTORIC, true)
        PoiType.MUSEUM           -> prefs.getBoolean(PREF_FILTER_MUSEUM, true)
        PoiType.ATTRACTION       -> prefs.getBoolean(PREF_FILTER_ATTRACTION, true)
        PoiType.ARTWORK          -> prefs.getBoolean(PREF_FILTER_ARTWORK, true)
        PoiType.VIEWPOINT        -> prefs.getBoolean(PREF_FILTER_VIEWPOINT, true)
        PoiType.PARK             -> prefs.getBoolean(PREF_FILTER_PARK, true)
        PoiType.PLACE_OF_WORSHIP -> prefs.getBoolean(PREF_FILTER_PLACE_OF_WORSHIP, true)
        PoiType.OTHER            -> true
    }

    private fun isSubCategoryEnabled(poi: PlaceOfInterest): Boolean {
        if (poi.type != PoiType.ATTRACTION) return true
        return when (poi.diversityKey) {
            "stadium"              -> prefs.getBoolean(PREF_FILTER_SUB_STADIUM, true)
            "ride"                 -> prefs.getBoolean(PREF_FILTER_SUB_RIDE, true)
            "zoo", "aquarium"      -> prefs.getBoolean(PREF_FILTER_SUB_ZOO, true)
            "theme_park"           -> prefs.getBoolean(PREF_FILTER_SUB_THEME_PARK, true)
            "garden"               -> prefs.getBoolean(PREF_FILTER_SUB_GARDEN, true)
            "tower", "bridge", "aerialway" -> prefs.getBoolean(PREF_FILTER_SUB_TOWER, true)
            else                   -> true
        }
    }

    private fun buildNearbyQuery(lat: Double, lon: Double, radiusMeters: Int): String {
        val cap = if (radiusMeters > 30_000) 500 else 300
        return "[out:json][timeout:$OVERPASS_SHARD_TIMEOUT_SEC];\n" +
        "(\n" +
        "  node[\"name\"][\"historic\"](around:$radiusMeters,$lat,$lon);\n" +
        "  way[\"name\"][\"historic\"](around:$radiusMeters,$lat,$lon);\n" +
        "  node[\"name\"][\"tourism\"~\"attraction|museum|artwork|viewpoint|theme_park|zoo|aquarium|gallery\"](around:$radiusMeters,$lat,$lon);\n" +
        "  way[\"name\"][\"tourism\"~\"attraction|museum|artwork|viewpoint|theme_park|zoo|aquarium|gallery\"](around:$radiusMeters,$lat,$lon);\n" +
        "  node[\"name\"][\"leisure\"~\"park|garden|stadium\"](around:$radiusMeters,$lat,$lon);\n" +
        "  way[\"name\"][\"leisure\"~\"park|garden|stadium\"](around:$radiusMeters,$lat,$lon);\n" +
        "  node[\"name\"][\"amenity\"=\"place_of_worship\"](around:$radiusMeters,$lat,$lon);\n" +
        "  node[\"name\"][\"amenity\"=\"marketplace\"](around:$radiusMeters,$lat,$lon);\n" +
        "  way[\"name\"][\"amenity\"=\"marketplace\"](around:$radiusMeters,$lat,$lon);\n" +
        "  node[\"name\"][\"place\"=\"square\"](around:$radiusMeters,$lat,$lon);\n" +
        "  way[\"name\"][\"place\"=\"square\"](around:$radiusMeters,$lat,$lon);\n" +
        ");\n" +
        "out body center $cap;"
    }

    /**
     * The famous query split into concurrent shards. See ENGINEERING_NOTES "Famous POI Coverage &
     * Ranking" for the per-branch timing data and rationale.
     *
     * Shard A = fast, high-yield branches (cheap, contain most headline POIs). Shard B = the slow
     * coverage branches (squares/gardens/markets/beaches/peaks/reserves) that return 0 in Dallas but
     * catch globally-famous area-mapped landmarks elsewhere (Tiananmen Square, Butchart Gardens, …).
     *
     * Two changes vs the old single query:
     *  - `tourism` and `stadium` now require `["wikipedia"]`. In famous mode only wiki-notable POIs
     *    earn a non-zero fameScore anyway, and this cuts those branches from ~16-22s to ~6-7s.
     *  - Added way-inclusive `building`/`man_made`+wikipedia branches. The old catch-all was
     *    node-only, so area-mapped famous buildings/towers (the Reunion-Tower class) were invisible.
     */
    private fun buildFamousQueryShards(lat: Double, lon: Double, radiusMeters: Int): List<String> {
        val a = "around:$radiusMeters,$lat,$lon"
        fun shard(vararg branches: String): String =
            "[out:json][timeout:$OVERPASS_SHARD_TIMEOUT_SEC];\n(\n" +
                branches.joinToString("\n") + "\n);\nout body center $FAMOUS_SHARD_CAP;"

        val shardA = shard(
            // node-only wikipedia catch-all + way-inclusive building/man_made closes the area gap
            "  node[\"name\"][\"wikipedia\"][!\"shop\"][\"place\"!~\"city|town|village|hamlet|suburb|county|state|country|region|district|municipality|borough\"]($a);",
            "  way[\"name\"][\"building\"][\"wikipedia\"]($a);",
            "  node[\"name\"][\"man_made\"~\"tower|bridge|lighthouse|obelisk\"][\"wikipedia\"]($a);\n" +
            "  way[\"name\"][\"man_made\"~\"tower|bridge|lighthouse|obelisk\"][\"wikipedia\"]($a);",
            "  node[\"name\"][\"tourism\"~\"attraction|museum|zoo|theme_park|aquarium|gallery|artwork\"][\"wikipedia\"]($a);\n" +
            "  way[\"name\"][\"tourism\"~\"attraction|museum|zoo|theme_park|aquarium|gallery|artwork\"][\"wikipedia\"]($a);",
            "  node[\"name\"][\"heritage\"]($a);\n  way[\"name\"][\"heritage\"]($a);",
            "  node[\"name\"][\"historic\"~\"castle|monument|archaeological_site|ruins|memorial|palace|city_wall|city_gate|pagoda|temple\"]($a);\n" +
            "  way[\"name\"][\"historic\"~\"castle|monument|archaeological_site|ruins|memorial|palace|city_wall|city_gate|pagoda|temple\"]($a);",
            "  node[\"name\"][\"leisure\"=\"stadium\"][\"wikipedia\"]($a);\n  way[\"name\"][\"leisure\"=\"stadium\"][\"wikipedia\"]($a);",
            "  node[\"name\"][\"aerialway\"~\"gondola|cable_car|funicular\"]($a);\n  way[\"name\"][\"aerialway\"~\"gondola|cable_car|funicular\"]($a);",
        )

        val shardB = shard(
            "  node[\"name\"][\"place\"=\"square\"][\"wikipedia\"]($a);\n  way[\"name\"][\"place\"=\"square\"][\"wikipedia\"]($a);",
            "  node[\"name\"][\"leisure\"=\"garden\"][\"wikipedia\"]($a);\n  way[\"name\"][\"leisure\"=\"garden\"][\"wikipedia\"]($a);",
            "  node[\"name\"][\"amenity\"=\"marketplace\"][\"wikipedia\"]($a);\n  way[\"name\"][\"amenity\"=\"marketplace\"][\"wikipedia\"]($a);",
            "  node[\"name\"][\"natural\"=\"beach\"][\"wikipedia\"]($a);\n  way[\"name\"][\"natural\"=\"beach\"][\"wikipedia\"]($a);",
            "  node[\"name\"][\"natural\"=\"peak\"][\"wikipedia\"]($a);",
            "  node[\"name\"][\"leisure\"=\"nature_reserve\"][\"wikipedia\"]($a);\n  way[\"name\"][\"leisure\"=\"nature_reserve\"][\"wikipedia\"]($a);",
            "  way[\"name\"][\"landuse\"=\"cemetery\"][\"wikipedia\"]($a);",
        )

        return listOf(shardA, shardB)
    }

    private fun OverpassElement.toPlaceOfInterest(userLocation: Location): PlaceOfInterest {
        val results = FloatArray(1)
        Location.distanceBetween(
            userLocation.latitude, userLocation.longitude,
            effectiveLat, effectiveLon,
            results
        )
        return PlaceOfInterest(
            osmId = osmId,
            name = tags["name:en"] ?: tags["name"] ?: "Unknown",
            lat = effectiveLat,
            lon = effectiveLon,
            type = resolveType(tags),
            tags = tags,
            distanceMeters = results[0]
        )
    }

    private fun resolveType(tags: Map<String, String>): PoiType = when {
        tags["historic"] != null -> PoiType.HISTORIC
        tags["tourism"] == "museum" -> PoiType.MUSEUM
        tags["tourism"] == "attraction" -> PoiType.ATTRACTION
        tags["tourism"] == "artwork" -> PoiType.ARTWORK
        tags["tourism"] == "viewpoint" -> PoiType.VIEWPOINT
        tags["tourism"] == "theme_park" -> PoiType.ATTRACTION
        tags["tourism"] == "zoo" -> PoiType.ATTRACTION
        tags["tourism"] == "aquarium" -> PoiType.ATTRACTION
        tags["tourism"] == "gallery" -> PoiType.MUSEUM
        tags["amenity"] == "place_of_worship" -> PoiType.PLACE_OF_WORSHIP
        tags["amenity"] == "marketplace" -> PoiType.ATTRACTION
        tags["leisure"] == "park" -> PoiType.PARK
        tags["leisure"] == "garden" -> PoiType.PARK
        tags["leisure"] == "stadium" -> PoiType.ATTRACTION
        tags["leisure"] == "nature_reserve" -> PoiType.PARK
        tags["place"] == "square" -> PoiType.ATTRACTION
        tags["natural"] == "beach" -> PoiType.PARK
        tags["natural"] == "peak" -> PoiType.VIEWPOINT
        tags["landuse"] == "cemetery" -> PoiType.HISTORIC
        tags["man_made"] == "bridge" -> PoiType.ATTRACTION
        tags["aerialway"] != null -> PoiType.ATTRACTION
        tags.containsKey("heritage") -> PoiType.HISTORIC
        else -> PoiType.OTHER
    }

    // ── Wiki Enrichment ──────────────────────────────────────────────────────

    private fun wordsOverlap(poiName: String, wikiTitle: String): Boolean {
        val aWords = poiName.lowercase().split(Regex("\\W+")).filter { it.length > 3 }.toSet()
        return wikiTitle.lowercase().split(Regex("\\W+")).filter { it.length > 3 }.any { it in aWords }
    }

    private suspend fun enrichWithWikiData(pois: List<PlaceOfInterest>): List<PlaceOfInterest> =
        coroutineScope {
        if (pois.isEmpty()) return@coroutineScope pois
        val limit = minOf(pois.size, ENRICH_LIMIT)
        val toEnrich = pois.take(limit)
        val qids = toEnrich.mapNotNull { it.tags["wikidata"] }.distinct()
        val sitelinkMap = if (qids.isNotEmpty()) fetchSitelinksBatch(qids) else emptyMap()
        val enrichedTop = toEnrich.map { poi ->
            async(Dispatchers.IO) {
                val qid = poi.tags["wikidata"]
                val sitelinkResult = qid?.let { sitelinkMap[it] }
                val sitelinks = sitelinkResult?.sitelinkCount ?: 0
                val rawTitle = poi.tags["wikipedia"]?.let { tag ->
                    val colon = tag.indexOf(':')
                    if (colon >= 0) tag.substring(colon + 1).trim() else null
                } ?: sitelinkResult?.enwikiTitle
                val wikiTitle = if (rawTitle != null) {
                    val generic = rawTitle.lowercase().trim() in PoiImageRepository.GENERIC_WIKIPEDIA_TITLES
                    val matched = wordsOverlap(poi.name, rawTitle)
                    if (generic || !matched) {
                        val reason = if (generic) "denylist" else "name_mismatch"
                        Log.d(TAG, "${poi.name}: wikidata(${qid}) enrich skipped — $reason '$rawTitle'")
                        skippedEnrichmentStore.record("enrich", poi.name, poi.osmId, qid, rawTitle, reason)
                        null
                    } else rawTitle
                } else null
                val enrichedSitelinks = if (wikiTitle != null) sitelinks else 0
                val views = if (wikiTitle != null) fetchPageviews(qid ?: wikiTitle, wikiTitle) else 0L
                if (enrichedSitelinks == 0 && views == 0L) poi
                else poi.copy(tags = poi.tags + buildMap {
                    if (views > 0L) put("wiki_views", views.toString())
                    if (enrichedSitelinks > 0) put("wiki_sitelinks", enrichedSitelinks.toString())
                })
            }
        }.awaitAll()
        if (limit < pois.size) enrichedTop + pois.drop(limit) else enrichedTop
    }

    private suspend fun fetchSitelinksBatch(qids: List<String>): Map<String, WikidataResult> =
        withContext(Dispatchers.IO) {
        val results = mutableMapOf<String, WikidataResult>()
        val uncached = mutableListOf<String>()
        for (qid in qids.distinct()) {
            val cached = cachedSitelinks(qid)
            if (cached != null) results[qid] = cached else uncached.add(qid)
        }
        for (chunk in uncached.chunked(50)) {
            try {
                // wbgetentities requires PIPE-separated ids. Comma was silently treated as one
                // literal id ("Q1,Q2…" → no-such-entity), so sitelinks enrichment returned nothing
                // for every multi-id batch since v3.3.9. Confirmed by the PoiExperiment log.
                val url = "https://www.wikidata.org/w/api.php?action=wbgetentities" +
                    "&ids=${chunk.joinToString("|")}&props=sitelinks&format=json"
                val request = Request.Builder().url(url)
                    .header("User-Agent", "TravelGuideAnywhere/2.0 (Android)").build()
                val body = okHttpClient.newCall(request).execute().use { r ->
                    if (!r.isSuccessful) return@use null
                    r.body?.string()
                } ?: continue
                val entities = gson.fromJson(body, JsonObject::class.java)
                    ?.getAsJsonObject("entities") ?: continue
                for (qid in chunk) {
                    val entity = entities.getAsJsonObject(qid) ?: continue
                    val sitelinks = entity.getAsJsonObject("sitelinks") ?: continue
                    val result = WikidataResult(
                        sitelinks.size(),
                        sitelinks.getAsJsonObject("enwiki")?.get("title")?.asString
                    )
                    results[qid] = result
                    cacheSitelinks(qid, result)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Sitelinks batch fetch failed: ${e.message}")
            }
        }
        results
    }

    private suspend fun fetchPageviews(cacheKey: String, articleTitle: String): Long =
        withContext(Dispatchers.IO) {
        cachedViews(cacheKey)?.let { return@withContext it }
        try {
            val (start, end) = pageviewsDateRange()
            val encoded = Uri.encode(articleTitle.replace(' ', '_'))
            val url = "https://wikimedia.org/api/rest_v1/metrics/pageviews/per-article/" +
                "en.wikipedia/all-access/all-agents/$encoded/monthly/$start/$end"
            val request = Request.Builder().url(url)
                .header("User-Agent", "TravelGuideAnywhere/2.0 (Android; travisneedham@gmail.com)")
                .build()
            val body = okHttpClient.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return@withContext 0L
                r.body?.string()
            } ?: return@withContext 0L
            val items = gson.fromJson(body, JsonObject::class.java)
                ?.getAsJsonArray("items") ?: return@withContext 0L
            val total = items.sumOf { it.asJsonObject?.get("views")?.asLong ?: 0L }
            if (total > 0L) cacheViews(cacheKey, total)
            total
        } catch (e: Exception) {
            Log.w(TAG, "Pageviews fetch failed for '$articleTitle': ${e.message}")
            0L
        }
    }

    private fun pageviewsDateRange(): Pair<String, String> {
        val cal = Calendar.getInstance()
        cal.add(Calendar.MONTH, -1)
        val endYear = cal.get(Calendar.YEAR)
        val endMonth = cal.get(Calendar.MONTH) + 1
        cal.add(Calendar.MONTH, -2)
        val startYear = cal.get(Calendar.YEAR)
        val startMonth = cal.get(Calendar.MONTH) + 1
        return "%04d%02d0100".format(startYear, startMonth) to "%04d%02d0100".format(endYear, endMonth)
    }

    private fun cachedViews(key: String): Long? {
        val safe = key.take(80).replace('/', '_')
        val raw = prefs.getString("$PREF_WIKI_VIEWS_PREFIX$safe", null) ?: return null
        val sep = raw.lastIndexOf('|')
        if (sep < 0) return null
        val ts = raw.substring(sep + 1).toLongOrNull() ?: return null
        if (System.currentTimeMillis() - ts > WIKI_CACHE_TTL_MS) return null
        return raw.substring(0, sep).toLongOrNull()
    }

    private fun cacheViews(key: String, views: Long) {
        val safe = key.take(80).replace('/', '_')
        prefs.edit().putString(
            "$PREF_WIKI_VIEWS_PREFIX$safe",
            "$views|${System.currentTimeMillis()}"
        ).apply()
    }

    private fun cachedSitelinks(qid: String): WikidataResult? {
        val raw = prefs.getString("$PREF_WIKI_SLINKS_PREFIX$qid", null) ?: return null
        val parts = raw.split("|", limit = 3)
        if (parts.size < 2) return null
        val ts = parts.last().toLongOrNull() ?: return null
        if (System.currentTimeMillis() - ts > WIKI_CACHE_TTL_MS) return null
        val count = parts[0].toIntOrNull() ?: return null
        return WikidataResult(count, parts[1].ifEmpty { null })
    }

    private fun cacheSitelinks(qid: String, result: WikidataResult) {
        prefs.edit().putString(
            "$PREF_WIKI_SLINKS_PREFIX$qid",
            "${result.sitelinkCount}|${result.enwikiTitle ?: ""}|${System.currentTimeMillis()}"
        ).apply()
    }

    // ── POI-list cache + geohash (pre-warm support) ───────────────────────────

    private fun cacheKey(location: Location, radiusMiles: Float, famousMode: Boolean): String {
        val cell = geohash(location.latitude, location.longitude, GEOHASH_PRECISION)
        val mode = if (famousMode) "f" else "n"
        return "$PREF_POI_CACHE_PREFIX${cell}_${mode}_%.2f".format(radiusMiles)
    }

    private fun cachedPois(key: String): List<PlaceOfInterest>? {
        val raw = prefs.getString(key, null) ?: return null
        return try {
            val type = object : TypeToken<PoiCacheEnvelope>() {}.type
            val env = gson.fromJson<PoiCacheEnvelope>(raw, type) ?: return null
            if (System.currentTimeMillis() - env.ts > POI_CACHE_TTL_MS) {
                prefs.edit().remove(key).apply(); null
            } else env.pois
        } catch (e: Exception) {
            Log.w(TAG, "[cache] read failed for $key: ${e.message}"); null
        }
    }

    private fun cachePois(key: String, pois: List<PlaceOfInterest>) {
        try {
            val json = gson.toJson(PoiCacheEnvelope(System.currentTimeMillis(), pois))
            prefs.edit().putString(key, json).apply()
        } catch (e: Exception) {
            Log.w(TAG, "[cache] write failed for $key: ${e.message}")
        }
    }

    /** Returns the most recently cached ranked POI list across all locations/modes, or null if nothing cached. */
    fun mostRecentCachedPois(): List<PlaceOfInterest>? {
        val envelopeType = object : TypeToken<PoiCacheEnvelope>() {}.type
        return prefs.all
            .filterKeys { it.startsWith(PREF_POI_CACHE_PREFIX) }
            .mapNotNull { (_, v) ->
                try { gson.fromJson<PoiCacheEnvelope>(v as? String ?: return@mapNotNull null, envelopeType) }
                catch (e: Exception) { null }
            }
            .maxByOrNull { it.ts }
            ?.pois
    }

    /**
     * Manually wipe every cached POI list and Wikipedia/Wikidata enrichment entry. Exposed for the
     * Settings "Clear Cached Places" button (testing/troubleshooting). There is intentionally NO
     * automatic version-based wipe — the cache persists across app updates so production users don't
     * lose it on upgrade; the 6h TTL handles staleness on its own.
     */
    fun clearPoiCaches() {
        // Cancel orphaned prewarm coroutines before clearing the map so they release their
        // Overpass slots. Blocking HTTP calls can't be interrupted mid-flight but will
        // discard results once the coroutine is marked cancelled.
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
        val editor = prefs.edit()
        // Snapshot the keys first — don't mutate the map backing prefs.all while iterating it.
        prefs.all.keys.toList()
            .filter {
                it.startsWith(PREF_POI_CACHE_PREFIX) ||
                it.startsWith(PREF_WIKI_VIEWS_PREFIX) ||
                it.startsWith(PREF_WIKI_SLINKS_PREFIX)
            }
            .forEach { editor.remove(it) }
        editor.apply()
        Log.d(TAG, "[cache] cleared all POI + wiki enrichment caches")
    }

    /** Recompute each POI's distance from the *actual* tour location (cache holds coarse distances)
     *  and re-sort for the requested mode. Famous mode sorts by fame; nearby by distance. */
    private fun rerankForLocation(
        pois: List<PlaceOfInterest>,
        location: Location,
        famousMode: Boolean,
    ): List<PlaceOfInterest> {
        val results = FloatArray(1)
        val withDistance = pois.map { p ->
            Location.distanceBetween(location.latitude, location.longitude, p.lat, p.lon, results)
            p.copy(distanceMeters = results[0])
        }
        return if (famousMode) withDistance.sortedByDescending { it.fameScore }
               else withDistance.sortedBy { it.distanceMeters }
    }

    /** Minimal geohash encoder (no extra dependency). Precision 4 ≈ a ~20-40 km cell. */
    private fun geohash(lat: Double, lon: Double, precision: Int): String {
        var latMin = -90.0; var latMax = 90.0
        var lonMin = -180.0; var lonMax = 180.0
        val sb = StringBuilder()
        var bit = 0; var ch = 0; var even = true
        while (sb.length < precision) {
            if (even) {
                val mid = (lonMin + lonMax) / 2
                if (lon >= mid) { ch = ch or (1 shl (4 - bit)); lonMin = mid } else lonMax = mid
            } else {
                val mid = (latMin + latMax) / 2
                if (lat >= mid) { ch = ch or (1 shl (4 - bit)); latMin = mid } else latMax = mid
            }
            even = !even
            if (bit < 4) bit++ else { sb.append(GEOHASH_BASE32[ch]); bit = 0; ch = 0 }
        }
        return sb.toString()
    }
}
