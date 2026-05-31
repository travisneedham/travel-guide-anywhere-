package com.travelguide.anywhere.repository

import android.content.SharedPreferences
import android.location.Location
import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.travelguide.anywhere.data.model.PlaceOfInterest
import com.travelguide.anywhere.data.model.PoiType
import com.travelguide.anywhere.data.remote.OpenTripMapService
import com.travelguide.anywhere.data.remote.dto.OpenTripMapPlace
import com.travelguide.anywhere.data.remote.dto.OverpassElement
import com.travelguide.anywhere.data.remote.dto.OverpassResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PoiRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val gson: Gson,
    private val prefs: SharedPreferences,
    private val openTripMapService: OpenTripMapService,
) {
    companion object {
        private const val TAG = "PoiRepository"
        const val PREF_OPENTRIPMAP_KEY = "pref_opentripmap_key"

        const val PREF_FILTER_HISTORIC         = "filter_historic"
        const val PREF_FILTER_MUSEUM           = "filter_museum"
        const val PREF_FILTER_ATTRACTION       = "filter_attraction"
        const val PREF_FILTER_ARTWORK          = "filter_artwork"
        const val PREF_FILTER_VIEWPOINT        = "filter_viewpoint"
        const val PREF_FILTER_PARK             = "filter_park"
        const val PREF_FILTER_PLACE_OF_WORSHIP = "filter_place_of_worship"

        private val ADMIN_PLACE_TYPES = setOf(
            "city", "town", "village", "hamlet", "suburb", "neighbourhood",
            "county", "state", "country", "region", "district", "municipality", "borough"
        )

        private const val OTM_FALLBACK_THRESHOLD = 3

        private const val WIKI_CACHE_TTL_MS = 4L * 24 * 60 * 60 * 1000  // 4 days
        private const val PREF_WIKI_VIEWS_PREFIX = "wiki_v_"
        private const val PREF_WIKI_SLINKS_PREFIX = "wiki_s_"
        private const val ENRICH_LIMIT = 25
    }

    private data class WikidataResult(val sitelinkCount: Int, val enwikiTitle: String?)

    suspend fun fetchPois(
        location: Location,
        radiusMiles: Float,
        famousMode: Boolean = false
    ): List<PlaceOfInterest> = withContext(Dispatchers.IO) {
        val radiusMeters = (radiusMiles * 1609.34).toInt()
        val otmKey = prefs.getString(PREF_OPENTRIPMAP_KEY, "") ?: ""

        // 1. Pick a source: OpenTripMap is primary, Overpass is the fallback.
        var source = "overpass"
        val rawPois: List<PlaceOfInterest> = run {
            if (otmKey.isNotBlank()) {
                try {
                    val otmPois = fetchFromOpenTripMap(location, radiusMeters, famousMode, otmKey)
                    if (otmPois.size >= OTM_FALLBACK_THRESHOLD) {
                        source = "otm"
                        Log.d(TAG, "[OTM] Using ${otmPois.size} POIs (famousMode=$famousMode)")
                        return@run otmPois
                    }
                    Log.d(TAG, "[OTM] Only ${otmPois.size} results — falling back to Overpass")
                } catch (e: Exception) {
                    Log.w(TAG, "[OTM] Failed: ${e.message} — falling back to Overpass")
                }
            }
            fetchFromOverpass(location, radiusMeters, famousMode)
        }

        // 2. Enrich with Wikipedia pageviews + Wikidata sitelinks. Runs for BOTH sources so the
        //    fame-ranking algorithm applies whether the POIs came from OTM or Overpass.
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
            Log.d(TAG, "[$source/$label] ${sorted.size} POIs ranked. Top 10:")
            sorted.take(10).forEachIndexed { i, p ->
                Log.d(TAG, "  #${i + 1} ${p.name} — fame=${p.fameScore} " +
                    "rate=${p.tags["otm_rate"] ?: "-"} views=${p.tags["wiki_views"] ?: "-"} " +
                    "sitelinks=${p.tags["wiki_sitelinks"] ?: "-"} type=${p.type} " +
                    "dist=${"%.1f".format(p.distanceMiles)}mi")
            }
        }
        sorted
    }

    // ── OpenTripMap ──────────────────────────────────────────────────────────

    private suspend fun fetchFromOpenTripMap(
        location: Location,
        radiusMeters: Int,
        famousMode: Boolean,
        apiKey: String,
    ): List<PlaceOfInterest> {
        // Famous mode: ask OTM to sort by rate so the TOP-rated places in the radius are
        // returned first (not the nearest ones). Without orderby=rate, OTM defaults to
        // distance order — at 40mi, that means local suburban POIs dominate the 100-result
        // window and globally famous places (e.g. JFK Museum in Dallas) are never reached.
        //
        // Rate filter set to "1" (lowest) for both modes so we don't accidentally exclude
        // famous places that OTM happens to rate low. Sorting handles priority instead.
        val places = openTripMapService.getPlacesInRadius(
            radius = radiusMeters,
            lon = location.longitude,
            lat = location.latitude,
            kinds = "interesting_places,museums,historic,architecture,stadiums,zoos,aquariums,amusements,beaches,natural,gardens_and_parks",
            rate = "1",
            orderby = if (famousMode) "rate" else "dist",
            limit = 100,
            apiKey = apiKey,
        )

        Log.d(TAG, "[OTM] Raw response: ${places.size} places (famousMode=$famousMode, orderby=${if (famousMode) "rate" else "dist"})")

        val pois = places
            .filter { it.name.isNotBlank() }
            .map { it.toPlaceOfInterest(location) }
            .filter { isTypeEnabled(it.type) }
            .distinctBy { it.name }
            .let { list ->
                if (famousMode) list.sortedByDescending { it.tags["otm_rate"]?.toIntOrNull() ?: 0 }
                else list.sortedBy { it.distanceMeters }
            }

        if (pois.isNotEmpty()) {
            Log.d(TAG, "[OTM] Top POIs: " +
                pois.take(5).joinToString { "${it.name}(otm_rate=${it.tags["otm_rate"]}, dist=${"%.1f".format(it.distanceMiles)}mi)" })
        }
        return pois
    }

    private fun OpenTripMapPlace.toPlaceOfInterest(userLocation: Location): PlaceOfInterest {
        val results = FloatArray(1)
        Location.distanceBetween(
            userLocation.latitude, userLocation.longitude,
            point.lat, point.lon,
            results
        )
        val tags = buildMap {
            wikidata?.let { put("wikidata", it) }
            osm?.let { put("osm", it) }
            kinds?.let { put("kinds", it) }
            put("otm_rate", rate.toString())
        }
        return PlaceOfInterest(
            osmId = osm ?: "otm/$xid",
            name = name,
            lat = point.lat,
            lon = point.lon,
            type = resolveTypeFromKinds(kinds),
            tags = tags,
            distanceMeters = results[0],
        )
    }

    private fun resolveTypeFromKinds(kinds: String?): PoiType {
        if (kinds == null) return PoiType.OTHER
        val k = kinds.lowercase()
        return when {
            "museum" in k -> PoiType.MUSEUM
            "archaeological" in k || "fortification" in k || "castle" in k -> PoiType.HISTORIC
            "historic" in k || "monument" in k || "memorial" in k -> PoiType.HISTORIC
            "religion" in k -> PoiType.PLACE_OF_WORSHIP
            "view_point" in k -> PoiType.VIEWPOINT
            "stadium" in k || "sport" in k -> PoiType.ATTRACTION
            "amusement" in k -> PoiType.ATTRACTION
            "beach" in k -> PoiType.PARK
            "marketplace" in k || "market" in k -> PoiType.ATTRACTION
            "garden" in k || "park" in k || "natural" in k -> PoiType.PARK
            "artwork" in k || "art_gallery" in k -> PoiType.ARTWORK
            "cultural" in k || "architecture" in k || "tourist" in k -> PoiType.ATTRACTION
            else -> PoiType.OTHER
        }
    }

    // ── Overpass (fallback) ──────────────────────────────────────────────────

    private suspend fun fetchFromOverpass(
        location: Location,
        radiusMeters: Int,
        famousMode: Boolean,
    ): List<PlaceOfInterest> {
        val mode = if (famousMode) "famous" else "nearby"
        Log.d(TAG, "[$mode] Querying Overpass: radius=${radiusMeters}m")

        val query = if (famousMode)
            buildFamousQuery(location.latitude, location.longitude, radiusMeters)
        else
            buildNearbyQuery(location.latitude, location.longitude, radiusMeters)

        val body = FormBody.Builder()
            .add("data", query)
            .build()

        val request = Request.Builder()
            .url("https://overpass-api.de/api/interpreter")
            .post(body)
            .header("Accept", "*/*")
            .header("User-Agent", "TravelGuideAnywhere/2.0 (Android)")
            .build()

        val responseJson = okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Overpass HTTP ${response.code}: ${response.body?.string()?.take(200)}")
            }
            response.body?.string() ?: throw Exception("Empty response from Overpass")
        }

        val overpassResponse = gson.fromJson(responseJson, OverpassResponse::class.java)

        if (!overpassResponse.remark.isNullOrBlank()) {
            Log.w(TAG, "[$mode] Overpass remark: ${overpassResponse.remark}")
            if (overpassResponse.elements.isEmpty()) {
                throw Exception("Overpass query failed: ${overpassResponse.remark}")
            }
        }

        val raw = overpassResponse.elements.size
        val pois = overpassResponse.elements
            .filter { it.tags.containsKey("name") }
            .filter { element ->
                element.tags["shop"] == null || element.tags["historic"] != null
            }
            .filter { element ->
                element.tags["place"]?.let { it !in ADMIN_PLACE_TYPES } ?: true
            }
            .map { element -> element.toPlaceOfInterest(location) }
            .filter { poi -> isTypeEnabled(poi.type) }
            .distinctBy { it.name }
            .sortedByDescending { it.fameScore }  // pre-sort so ENRICH_LIMIT targets most promising

        Log.d(TAG, "[$mode] Overpass returned $raw elements → ${pois.size} named POIs")
        return pois
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

    private fun buildNearbyQuery(lat: Double, lon: Double, radiusMeters: Int): String {
        val cap = if (radiusMeters > 30_000) 500 else 300
        // TEST BUILD: server-side timeout raised to 300s while we profile query cost.
        return "[out:json][timeout:300];\n" +
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

    private fun buildFamousQuery(lat: Double, lon: Double, radiusMeters: Int): String =
        // TEST BUILD: server-side timeout raised to 300s while we profile query cost. The broad
        // wikipedia catch-all (line below) is the suspected timeout source — kept in for now so the
        // PoiExperiment harness can measure it. See ENGINEERING_NOTES "Famous POI Coverage & Ranking".
        "[out:json][timeout:300];\n" +
        "(\n" +
        "  node[\"name\"][\"wikipedia\"][!\"shop\"][\"place\"!~\"city|town|village|hamlet|suburb|county|state|country|region|district|municipality|borough\"](around:$radiusMeters,$lat,$lon);\n" +
        "  node[\"name\"][\"tourism\"~\"attraction|museum|zoo|theme_park|aquarium|gallery|artwork\"](around:$radiusMeters,$lat,$lon);\n" +
        "  way[\"name\"][\"tourism\"~\"attraction|museum|zoo|theme_park|aquarium|gallery|artwork\"](around:$radiusMeters,$lat,$lon);\n" +
        "  node[\"name\"][\"heritage\"](around:$radiusMeters,$lat,$lon);\n" +
        "  way[\"name\"][\"heritage\"](around:$radiusMeters,$lat,$lon);\n" +
        "  node[\"name\"][\"historic\"~\"castle|monument|archaeological_site|ruins|memorial|palace|city_wall|city_gate|pagoda|temple\"](around:$radiusMeters,$lat,$lon);\n" +
        "  way[\"name\"][\"historic\"~\"castle|monument|archaeological_site|ruins|memorial|palace|city_wall|city_gate|pagoda|temple\"](around:$radiusMeters,$lat,$lon);\n" +
        "  node[\"name\"][\"leisure\"=\"stadium\"](around:$radiusMeters,$lat,$lon);\n" +
        "  way[\"name\"][\"leisure\"=\"stadium\"](around:$radiusMeters,$lat,$lon);\n" +
        "  node[\"name\"][\"place\"=\"square\"][\"wikipedia\"](around:$radiusMeters,$lat,$lon);\n" +
        "  way[\"name\"][\"place\"=\"square\"][\"wikipedia\"](around:$radiusMeters,$lat,$lon);\n" +
        "  node[\"name\"][\"leisure\"=\"garden\"][\"wikipedia\"](around:$radiusMeters,$lat,$lon);\n" +
        "  way[\"name\"][\"leisure\"=\"garden\"][\"wikipedia\"](around:$radiusMeters,$lat,$lon);\n" +
        "  node[\"name\"][\"amenity\"=\"marketplace\"][\"wikipedia\"](around:$radiusMeters,$lat,$lon);\n" +
        "  way[\"name\"][\"amenity\"=\"marketplace\"][\"wikipedia\"](around:$radiusMeters,$lat,$lon);\n" +
        "  node[\"name\"][\"natural\"=\"beach\"][\"wikipedia\"](around:$radiusMeters,$lat,$lon);\n" +
        "  way[\"name\"][\"natural\"=\"beach\"][\"wikipedia\"](around:$radiusMeters,$lat,$lon);\n" +
        "  node[\"name\"][\"natural\"=\"peak\"][\"wikipedia\"](around:$radiusMeters,$lat,$lon);\n" +
        "  node[\"name\"][\"leisure\"=\"nature_reserve\"][\"wikipedia\"](around:$radiusMeters,$lat,$lon);\n" +
        "  way[\"name\"][\"leisure\"=\"nature_reserve\"][\"wikipedia\"](around:$radiusMeters,$lat,$lon);\n" +
        "  node[\"name\"][\"aerialway\"~\"gondola|cable_car|funicular\"](around:$radiusMeters,$lat,$lon);\n" +
        "  way[\"name\"][\"aerialway\"~\"gondola|cable_car|funicular\"](around:$radiusMeters,$lat,$lon);\n" +
        "  way[\"name\"][\"landuse\"=\"cemetery\"][\"wikipedia\"](around:$radiusMeters,$lat,$lon);\n" +
        ");\n" +
        "out body center 300;"

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
        else -> PoiType.OTHER
    }

    // ── Wiki Enrichment ──────────────────────────────────────────────────────

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
                val wikiTitle = poi.tags["wikipedia"]?.let { tag ->
                    val colon = tag.indexOf(':')
                    if (colon >= 0) tag.substring(colon + 1).trim() else null
                } ?: sitelinkResult?.enwikiTitle
                val views = if (wikiTitle != null) fetchPageviews(qid ?: wikiTitle, wikiTitle) else 0L
                if (sitelinks == 0 && views == 0L) poi
                else poi.copy(tags = poi.tags + buildMap {
                    if (views > 0L) put("wiki_views", views.toString())
                    if (sitelinks > 0) put("wiki_sitelinks", sitelinks.toString())
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
                val url = "https://www.wikidata.org/w/api.php?action=wbgetentities" +
                    "&ids=${chunk.joinToString(",")}&props=sitelinks&format=json"
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
}
