package com.travelguide.anywhere.repository

import android.location.Location
import com.travelguide.anywhere.data.local.MentionedPlaceDao
import com.travelguide.anywhere.data.model.PlaceOfInterest
import com.travelguide.anywhere.data.model.PoiType
import com.travelguide.anywhere.data.remote.OverpassApiService
import com.travelguide.anywhere.data.remote.dto.OverpassElement
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PoiRepository @Inject constructor(
    private val overpassApi: OverpassApiService,
    private val mentionedPlaceDao: MentionedPlaceDao
) {

    suspend fun fetchUnmentionedPois(
        location: Location,
        radiusMiles: Float,
        sessionId: String
    ): List<PlaceOfInterest> {
        val radiusMeters = (radiusMiles * 1609.34).toInt()
        val query = OverpassApiService.buildQuery(location.latitude, location.longitude, radiusMeters)

        val response = overpassApi.queryPois(query)
        val mentionedIds = mentionedPlaceDao.getOsmIdsBySession(sessionId).toSet()

        return response.elements
            .filter { it.tags.containsKey("name") }
            .filter { it.osmId !in mentionedIds }
            .map { element -> element.toPlaceOfInterest(location) }
            .sortedByDescending { it.type.interestScore }
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
            name = tags["name"] ?: "Unknown",
            lat = effectiveLat,
            lon = effectiveLon,
            type = resolveType(tags),
            tags = tags,
            distanceMeters = results[0]
        )
    }

    private fun resolveType(tags: Map<String, String>): PoiType {
        val historic = tags["historic"]
        val tourism = tags["tourism"]
        val amenity = tags["amenity"]
        val building = tags["building"]
        val leisure = tags["leisure"]
        return when {
            historic != null -> PoiType.HISTORIC
            tourism == "museum" -> PoiType.MUSEUM
            tourism == "attraction" -> PoiType.ATTRACTION
            tourism == "artwork" -> PoiType.ARTWORK
            tourism == "viewpoint" -> PoiType.VIEWPOINT
            amenity == "place_of_worship" -> PoiType.PLACE_OF_WORSHIP
            leisure == "park" -> PoiType.PARK
            else -> PoiType.OTHER
        }
    }
}
