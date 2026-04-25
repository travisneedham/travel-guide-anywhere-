package com.travelguide.anywhere.repository

import android.location.Location
import com.google.gson.Gson
import com.travelguide.anywhere.data.local.MentionedPlaceDao
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
    private val gson: Gson,
    private val mentionedPlaceDao: MentionedPlaceDao
) {

    suspend fun fetchUnmentionedPois(
        location: Location,
        radiusMiles: Float,
        sessionId: String
    ): List<PlaceOfInterest> = withContext(Dispatchers.IO) {
        val radiusMeters = (radiusMiles * 1609.34).toInt()
        val query = buildQuery(location.latitude, location.longitude, radiusMeters)

        val body = FormBody.Builder()
            .add("data", query)
            .build()

        val request = Request.Builder()
            .url("https://overpass-api.de/api/interpreter")
            .post(body)
            .header("Accept", "*/*")
            .header("User-Agent", "TravelGuideAnywhere/1.0 (Android)")
            .build()

        val responseJson = okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw Exception("Overpass HTTP ${response.code}: ${response.body?.string()?.take(200)}")
            }
            response.body?.string() ?: throw Exception("Empty response from Overpass")
        }

        val overpassResponse = gson.fromJson(responseJson, OverpassResponse::class.java)
        val mentionedIds = mentionedPlaceDao.getOsmIdsBySession(sessionId).toSet()

        overpassResponse.elements
            .filter { it.tags.containsKey("name") }
            .filter { it.osmId !in mentionedIds }
            .map { element -> element.toPlaceOfInterest(location) }
            .sortedBy { it.distanceMeters }
            // deduplicate by name — keep the closest element when node + way exist for the same place
            .distinctBy { it.name }
    }

    private fun buildQuery(lat: Double, lon: Double, radiusMeters: Int): String =
        // nwr = node/way/relation; ["name"] co-filter drops unnamed clutter and removes
        // the need for a hard result cap (named POIs stay ~30-50 even in dense areas).
        "[out:json][timeout:25];\n" +
        "(\n" +
        "  nwr[\"name\"][\"historic\"](around:$radiusMeters,$lat,$lon);\n" +
        "  nwr[\"name\"][\"tourism\"~\"attraction|museum|artwork|viewpoint\"](around:$radiusMeters,$lat,$lon);\n" +
        "  nwr[\"name\"][\"leisure\"=\"park\"](around:$radiusMeters,$lat,$lon);\n" +
        "  node[\"name\"][\"amenity\"=\"place_of_worship\"](around:$radiusMeters,$lat,$lon);\n" +
        ");\n" +
        "out body center;"

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
