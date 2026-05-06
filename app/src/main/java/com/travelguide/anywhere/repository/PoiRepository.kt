package com.travelguide.anywhere.repository

import android.location.Location
import android.util.Log
import com.google.gson.Gson
import com.travelguide.anywhere.data.model.PlaceOfInterest
import com.travelguide.anywhere.data.model.PoiType
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
    private val gson: Gson
) {
    companion object {
        private const val TAG = "PoiRepository"
    }

    suspend fun fetchPois(
        location: Location,
        radiusMiles: Float,
        famousMode: Boolean = false
    ): List<PlaceOfInterest> = withContext(Dispatchers.IO) {
        val radiusMeters = (radiusMiles * 1609.34).toInt()
        val mode = if (famousMode) "famous" else "nearby"
        Log.d(TAG, "[$mode] Querying Overpass: radius=${radiusMiles}mi (${radiusMeters}m)")

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

        // Overpass signals a timeout/error via the "remark" field while returning empty elements.
        if (!overpassResponse.remark.isNullOrBlank()) {
            Log.w(TAG, "[$mode] Overpass remark: ${overpassResponse.remark}")
            if (overpassResponse.elements.isEmpty()) {
                throw Exception("Overpass query failed: ${overpassResponse.remark}")
            }
        }

        val raw = overpassResponse.elements.size
        val pois = overpassResponse.elements
            .filter { it.tags.containsKey("name") }
            .map { element -> element.toPlaceOfInterest(location) }
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

        pois
    }

    // Nearby mode: all interesting POI types sorted by distance (closest first).
    private fun buildNearbyQuery(lat: Double, lon: Double, radiusMeters: Int): String =
        "[out:json][timeout:25];\n" +
        "(\n" +
        "  nwr[\"name\"][\"historic\"](around:$radiusMeters,$lat,$lon);\n" +
        "  nwr[\"name\"][\"tourism\"~\"attraction|museum|artwork|viewpoint\"](around:$radiusMeters,$lat,$lon);\n" +
        "  nwr[\"name\"][\"leisure\"=\"park\"](around:$radiusMeters,$lat,$lon);\n" +
        "  node[\"name\"][\"amenity\"=\"place_of_worship\"](around:$radiusMeters,$lat,$lon);\n" +
        ");\n" +
        "out body center;"

    // Famous mode: only node+way types, no relations.
    // - wikipedia: node only (way["wikipedia"] is slow — the tag has millions of OSM entries
    //   and evaluating way bounding boxes against all of them is expensive even at small radii)
    // - tourism/historic/heritage: node + way only (skip relations — large multipolygons like
    //   university campuses or lakes mapped as relations cause query timeouts)
    private fun buildFamousQuery(lat: Double, lon: Double, radiusMeters: Int): String =
        "[out:json][timeout:30];\n" +
        "(\n" +
        "  node[\"name\"][\"wikipedia\"](around:$radiusMeters,$lat,$lon);\n" +
        "  node[\"name\"][\"tourism\"~\"attraction|museum|zoo|theme_park|aquarium|gallery\"](around:$radiusMeters,$lat,$lon);\n" +
        "  way[\"name\"][\"tourism\"~\"attraction|museum|zoo|theme_park|aquarium|gallery\"](around:$radiusMeters,$lat,$lon);\n" +
        "  node[\"name\"][\"heritage\"](around:$radiusMeters,$lat,$lon);\n" +
        "  way[\"name\"][\"heritage\"](around:$radiusMeters,$lat,$lon);\n" +
        "  node[\"name\"][\"historic\"~\"castle|monument|archaeological_site|ruins|memorial\"](around:$radiusMeters,$lat,$lon);\n" +
        "  way[\"name\"][\"historic\"~\"castle|monument|archaeological_site|ruins|memorial\"](around:$radiusMeters,$lat,$lon);\n" +
        ");\n" +
        "out body center 80;"

    private fun OverpassElement.toPlaceOfInterest(userLocation: Location): PlaceOfInterest {
        val results = FloatArray(1)
        Location.distanceBetween(
            userLocation.latitude, userLocation.longitude,
            effectiveLat, effectiveLon,
            results
        )
        return PlaceOfInterest(
            osmId = osmId,
            name = tags["name"] ?: "Unknown",
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
