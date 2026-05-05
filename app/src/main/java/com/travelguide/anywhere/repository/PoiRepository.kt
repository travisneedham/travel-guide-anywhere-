package com.travelguide.anywhere.repository

import android.location.Location
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

    suspend fun fetchPois(
        location: Location,
        radiusMiles: Float,
        famousMode: Boolean = false
    ): List<PlaceOfInterest> = withContext(Dispatchers.IO) {
        val radiusMeters = (radiusMiles * 1609.34).toInt()
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

        overpassResponse.elements
            .filter { it.tags.containsKey("name") }
            .map { element -> element.toPlaceOfInterest(location) }
            .distinctBy { it.name }
            .let { pois ->
                if (famousMode) pois.sortedByDescending { it.fameScore }
                else pois.sortedBy { it.distanceMeters }
            }
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

    // Famous mode: filter to Wikipedia/Wikidata/heritage-tagged places and top tourism
    // types so we get notable landmarks even at large radii. Sorted by fameScore.
    private fun buildFamousQuery(lat: Double, lon: Double, radiusMeters: Int): String =
        "[out:json][timeout:40];\n" +
        "(\n" +
        "  nwr[\"name\"][\"wikipedia\"](around:$radiusMeters,$lat,$lon);\n" +
        "  nwr[\"name\"][\"wikidata\"](around:$radiusMeters,$lat,$lon);\n" +
        "  nwr[\"name\"][\"heritage\"](around:$radiusMeters,$lat,$lon);\n" +
        "  nwr[\"name\"][\"tourism\"=\"attraction\"](around:$radiusMeters,$lat,$lon);\n" +
        "  nwr[\"name\"][\"tourism\"=\"museum\"](around:$radiusMeters,$lat,$lon);\n" +
        "  nwr[\"name\"][\"historic\"~\"castle|monument|archaeological_site|ruins\"](around:$radiusMeters,$lat,$lon);\n" +
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
