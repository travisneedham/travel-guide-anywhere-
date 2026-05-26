package com.travelguide.anywhere.repository

import android.content.SharedPreferences
import android.location.Location
import android.util.Log
import com.google.gson.Gson
import com.travelguide.anywhere.data.model.PlaceOfInterest
import com.travelguide.anywhere.data.model.PoiType
import com.travelguide.anywhere.data.remote.OpenTripMapService
import com.travelguide.anywhere.data.remote.dto.OpenTripMapPlace
import com.travelguide.anywhere.data.remote.dto.OverpassElement
import com.travelguide.anywhere.data.remote.dto.OverpassResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
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
    }

    suspend fun fetchPois(
        location: Location,
        radiusMiles: Float,
        famousMode: Boolean = false
    ): List<PlaceOfInterest> = withContext(Dispatchers.IO) {
        val radiusMeters = (radiusMiles * 1609.34).toInt()
        val otmKey = prefs.getString(PREF_OPENTRIPMAP_KEY, "") ?: ""

        if (otmKey.isNotBlank()) {
            try {
                val otmPois = fetchFromOpenTripMap(location, radiusMeters, famousMode, otmKey)
                if (otmPois.size >= OTM_FALLBACK_THRESHOLD) {
                    Log.d(TAG, "[OTM] Returning ${otmPois.size} POIs (famousMode=$famousMode)")
                    return@withContext otmPois
                }
                Log.d(TAG, "[OTM] Only ${otmPois.size} results — falling back to Overpass")
            } catch (e: Exception) {
                Log.w(TAG, "[OTM] Failed: ${e.message} — falling back to Overpass")
            }
        }

        fetchFromOverpass(location, radiusMeters, famousMode)
    }

    // ── OpenTripMap ──────────────────────────────────────────────────────────

    private suspend fun fetchFromOpenTripMap(
        location: Location,
        radiusMeters: Int,
        famousMode: Boolean,
        apiKey: String,
    ): List<PlaceOfInterest> {
        val rate = if (famousMode) "2" else "1"
        val places = openTripMapService.getPlacesInRadius(
            radius = radiusMeters,
            lon = location.longitude,
            lat = location.latitude,
            kinds = "interesting_places",
            rate = rate,
            limit = 50,
            apiKey = apiKey,
        )

        Log.d(TAG, "[OTM] Raw response: ${places.size} places (rate>=$rate)")

        val pois = places
            .filter { it.name.isNotBlank() }
            .map { it.toPlaceOfInterest(location) }
            .filter { isTypeEnabled(it.type) }
            .distinctBy { it.name }
            .let { list ->
                if (famousMode) list.sortedByDescending { it.fameScore }
                else list.sortedBy { it.distanceMeters }
            }

        if (pois.isNotEmpty()) {
            Log.d(TAG, "[OTM] Top POIs: " +
                pois.take(5).joinToString { "${it.name}(rate=${it.tags["otm_rate"]})" })
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
            .let { list ->
                if (famousMode) list.sortedByDescending { it.fameScore }
                else list.sortedBy { it.distanceMeters }
            }

        Log.d(TAG, "[$mode] Overpass returned $raw elements → ${pois.size} named POIs")
        if (famousMode && pois.isNotEmpty()) {
            Log.d(TAG, "[famous] Top POIs by fame score: " +
                pois.take(5).joinToString { "${it.name}(${it.fameScore})" })
        }
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

    // v3.3.2 reference query — do not modify. See ENGINEERING_NOTES.md.
    // Cap added (not in v3.3.2) to fix latent timeout/OOM at large radii (e.g. 40mi metro areas).
    private fun buildNearbyQuery(lat: Double, lon: Double, radiusMeters: Int): String {
        val cap = if (radiusMeters > 30_000) 500 else 300
        return "[out:json][timeout:45];\n" +
        "(\n" +
        "  node[\"name\"][\"historic\"](around:$radiusMeters,$lat,$lon);\n" +
        "  way[\"name\"][\"historic\"](around:$radiusMeters,$lat,$lon);\n" +
        "  node[\"name\"][\"tourism\"~\"attraction|museum|artwork|viewpoint\"](around:$radiusMeters,$lat,$lon);\n" +
        "  way[\"name\"][\"tourism\"~\"attraction|museum|artwork|viewpoint\"](around:$radiusMeters,$lat,$lon);\n" +
        "  node[\"name\"][\"leisure\"=\"park\"](around:$radiusMeters,$lat,$lon);\n" +
        "  way[\"name\"][\"leisure\"=\"park\"](around:$radiusMeters,$lat,$lon);\n" +
        "  node[\"name\"][\"amenity\"=\"place_of_worship\"](around:$radiusMeters,$lat,$lon);\n" +
        ");\n" +
        "out body center $cap;"
    }

    // v3.3.2 reference query — do not modify. See ENGINEERING_NOTES.md.
    private fun buildFamousQuery(lat: Double, lon: Double, radiusMeters: Int): String =
        "[out:json][timeout:30];\n" +
        "(\n" +
        "  node[\"name\"][\"wikipedia\"][!\"shop\"][\"place\"!~\"city|town|village|hamlet|suburb|county|state|country|region|district|municipality|borough\"](around:$radiusMeters,$lat,$lon);\n" +
        "  node[\"name\"][\"tourism\"~\"attraction|museum|zoo|theme_park|aquarium|gallery\"](around:$radiusMeters,$lat,$lon);\n" +
        "  way[\"name\"][\"tourism\"~\"attraction|museum|zoo|theme_park|aquarium|gallery\"](around:$radiusMeters,$lat,$lon);\n" +
        "  node[\"name\"][\"heritage\"](around:$radiusMeters,$lat,$lon);\n" +
        "  way[\"name\"][\"heritage\"](around:$radiusMeters,$lat,$lon);\n" +
        "  node[\"name\"][\"historic\"~\"castle|monument|archaeological_site|ruins|memorial\"](around:$radiusMeters,$lat,$lon);\n" +
        "  way[\"name\"][\"historic\"~\"castle|monument|archaeological_site|ruins|memorial\"](around:$radiusMeters,$lat,$lon);\n" +
        ");\n" +
        "out body center 200;"

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
        tags["amenity"] == "place_of_worship" -> PoiType.PLACE_OF_WORSHIP
        tags["leisure"] == "park" -> PoiType.PARK
        else -> PoiType.OTHER
    }
}
