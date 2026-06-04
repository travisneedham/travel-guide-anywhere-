package com.travelguide.anywhere.repository

import android.util.Log
import com.travelguide.anywhere.data.model.LatLon
import com.travelguide.anywhere.data.model.LocationResult
import com.travelguide.anywhere.data.model.RouteData
import com.travelguide.anywhere.data.model.TravelMode
import com.travelguide.anywhere.data.remote.NominatimService
import com.travelguide.anywhere.data.remote.OsrmService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

@Singleton
class RouteRepository @Inject constructor(
    private val osrmService: OsrmService,
    private val nominatimService: NominatimService,
) {

    sealed class Result {
        data class Success(val route: RouteData) : Result()
        data class Failure(val message: String) : Result()
    }

    private data class Direction(val label: String, val bearingDeg: Double)

    suspend fun searchLocations(query: String): List<LocationResult> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        try {
            nominatimService.search(
                query = query,
                format = "json",
                limit = 5,
            ).map { r ->
                LocationResult(
                    displayName = r.displayName,
                    shortName = r.displayName.split(",").first().trim(),
                    lat = r.lat.toDouble(),
                    lon = r.lon.toDouble(),
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Location search failed: ${e.message}")
            emptyList()
        }
    }

    suspend fun resolveRouteFromLocation(location: LocationResult): Result = withContext(Dispatchers.IO) {
        buildWalkingRoute(location.lat, location.lon, location.shortName)
    }

    /**
     * Builds a walking route from an arbitrary map point (the dragged pin). Reverse-geocodes the
     * point first so the narration has a meaningful place name; falls back to a generic label.
     */
    suspend fun resolveRouteFromPoint(lat: Double, lon: Double): Result = withContext(Dispatchers.IO) {
        val shortName = try {
            nominatimService.reverse(lat = lat, lon = lon).displayName.split(",").first().trim()
        } catch (e: Exception) {
            Log.w(TAG, "Reverse geocode failed for ($lat,$lon): ${e.message}")
            ""
        }.ifBlank { "this area" }
        buildWalkingRoute(lat, lon, shortName)
    }

    private suspend fun buildWalkingRoute(lat: Double, lon: Double, shortName: String): Result {
        return try {
            val direction = CARDINAL_DIRECTIONS.random()
            val dest = destinationPoint(lat, lon, direction.bearingDeg, WALK_DISTANCE_METERS)
            Log.i(TAG, "resolveRoute — origin=($lat,$lon) dir=${direction.label} dest=(${dest.lat},${dest.lon})")

            val coordsStr = "$lon,$lat;${dest.lon},${dest.lat}"
            val osrmResponse = osrmService.getRoute(
                profile = TravelMode.WALKING.osrmProfile,
                coordinates = coordsStr,
                geometries = "geojson",
                overview = "full",
            )

            val route = osrmResponse.routes.firstOrNull()
                ?: return Result.Failure("No walking route found — try a different location.")

            val waypoints = route.geometry.coordinates.map { coord ->
                LatLon(lat = coord[1], lon = coord[0])
            }
            if (waypoints.isEmpty()) return Result.Failure("Route has no waypoints.")

            Result.Success(
                RouteData(
                    waypoints = waypoints,
                    totalDistanceMeters = route.distance,
                    totalDurationSeconds = route.duration.toLong(),
                    travelMode = TravelMode.WALKING,
                    originLabel = shortName,
                    destinationLabel = "1 mi ${direction.label} of $shortName",
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Route resolution failed", e)
            Result.Failure("Route lookup failed: ${e.message}")
        }
    }

    private fun destinationPoint(lat: Double, lon: Double, bearingDeg: Double, distanceM: Double): LatLon {
        val R = 6371000.0
        val d = distanceM / R
        val brng = Math.toRadians(bearingDeg)
        val lat1 = Math.toRadians(lat)
        val lon1 = Math.toRadians(lon)
        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(brng))
        val lon2 = lon1 + atan2(sin(brng) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
        return LatLon(Math.toDegrees(lat2), Math.toDegrees(lon2))
    }

    companion object {
        private const val TAG = "RouteRepository"
        private const val WALK_DISTANCE_METERS = 1609.34

        private val CARDINAL_DIRECTIONS = listOf(
            Direction("north", 0.0),
            Direction("northeast", 45.0),
            Direction("east", 90.0),
            Direction("southeast", 135.0),
            Direction("south", 180.0),
            Direction("southwest", 225.0),
            Direction("west", 270.0),
            Direction("northwest", 315.0),
        )
    }
}
