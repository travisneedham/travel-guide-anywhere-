package com.travelguide.anywhere.repository

import android.net.Uri
import android.util.Log
import com.travelguide.anywhere.data.model.LatLon
import com.travelguide.anywhere.data.model.RouteData
import com.travelguide.anywhere.data.model.TravelMode
import com.travelguide.anywhere.data.remote.NominatimService
import com.travelguide.anywhere.data.remote.OsrmService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RouteRepository @Inject constructor(
    private val osrmService: OsrmService,
    private val nominatimService: NominatimService
) {

    sealed class Result {
        data class Success(val route: RouteData) : Result()
        data class Failure(val message: String) : Result()
    }

    // Lightweight client just for following Google Maps short-URL redirects.
    private val expandClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "TravelGuideAnywhere/2.0 (Android)")
                    .build()
            )
        }
        .build()

    suspend fun resolveRoute(url: String): Result = withContext(Dispatchers.IO) {
        try {
            val fullUrl = expandUrl(url)
            Log.d(TAG, "Expanded URL: $fullUrl")

            val parsed = parseGoogleMapsUrl(fullUrl)
                ?: return@withContext Result.Failure("Could not find an origin and destination in that link.")

            val (originStr, destStr, travelMode) = parsed
            Log.d(TAG, "Parsed: origin=$originStr  dest=$destStr  mode=$travelMode")

            // Geocode origin first, then bias the destination search with the origin's
            // coordinates. This prevents "Parthenon" (for example) from resolving to a
            // replica in another country when the route is clearly set in Greece.
            val origin = geocodeIfNeeded(originStr)
                ?: return@withContext Result.Failure("Could not locate: $originStr")
            val dest = geocodeIfNeeded(destStr, proximityHint = origin)
                ?: return@withContext Result.Failure("Could not locate: $destStr")

            val coordsStr = "${origin.lon},${origin.lat};${dest.lon},${dest.lat}"
            val osrmResponse = osrmService.getRoute(
                profile = travelMode.osrmProfile,
                coordinates = coordsStr,
                geometries = "geojson",
                overview = "full"
            )

            val route = osrmResponse.routes.firstOrNull()
                ?: return@withContext Result.Failure("No route found between those locations.")

            val distanceMiles = route.distance / 1609.34
            if (distanceMiles > 500) {
                return@withContext Result.Failure(
                    "Route is ${distanceMiles.toInt()} miles — maximum is 500 miles."
                )
            }

            val waypoints = route.geometry.coordinates.map { coord ->
                LatLon(lat = coord[1], lon = coord[0])
            }
            if (waypoints.isEmpty()) return@withContext Result.Failure("Route has no waypoints.")

            Result.Success(
                RouteData(
                    waypoints = waypoints,
                    totalDistanceMeters = route.distance,
                    totalDurationSeconds = route.duration.toLong(),
                    travelMode = travelMode,
                    originLabel = originStr,
                    destinationLabel = destStr
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Route resolution failed", e)
            Result.Failure("Route lookup failed: ${e.message}")
        }
    }

    private fun expandUrl(url: String): String {
        if (!url.contains("goo.gl") && !url.contains("maps.app")) return url
        return try {
            val request = Request.Builder().url(url).build()
            expandClient.newCall(request).execute().use { response ->
                response.body?.close()
                response.request.url.toString()
            }
        } catch (e: Exception) {
            Log.w(TAG, "URL expansion failed, using original: ${e.message}")
            url
        }
    }

    private fun parseGoogleMapsUrl(url: String): Triple<String, String, TravelMode>? {
        val uri = Uri.parse(url)
        val travelMode = detectTravelMode(uri)

        // ?api=1 format: origin= and destination= query params
        val originParam = uri.getQueryParameter("origin")
        val destParam = uri.getQueryParameter("destination")
        if (!originParam.isNullOrBlank() && !destParam.isNullOrBlank() &&
            !isCurrentLocation(originParam)
        ) {
            return Triple(originParam, destParam, travelMode)
        }

        // Older ?saddr= / ?daddr= format (common in maps.app.goo.gl expansions)
        val saddr = uri.getQueryParameter("saddr")
        val daddr = uri.getQueryParameter("daddr")
        if (!saddr.isNullOrBlank() && !daddr.isNullOrBlank() && !isCurrentLocation(saddr)) {
            return Triple(saddr, daddr, travelMode)
        }

        // /dir/ORIGIN/DESTINATION/ path format
        val segments = uri.pathSegments
        val dirIndex = segments.indexOf("dir")
        if (dirIndex >= 0 && segments.size > dirIndex + 2) {
            val originSeg = segments[dirIndex + 1]
            val destSeg = segments[dirIndex + 2]
            if (originSeg.isNotBlank() && !originSeg.startsWith("@") &&
                destSeg.isNotBlank() && !destSeg.startsWith("@") &&
                !isCurrentLocation(originSeg)
            ) {
                return Triple(originSeg, destSeg, travelMode)
            }
        }

        return null
    }

    private fun isCurrentLocation(s: String): Boolean {
        val normalized = s.replace('+', ' ').trim()
        return normalized.equals("current location", ignoreCase = true) ||
            normalized.equals("my location", ignoreCase = true) ||
            normalized.equals("here", ignoreCase = true) ||
            normalized.equals("your location", ignoreCase = true)
    }

    private fun detectTravelMode(uri: Uri): TravelMode {
        val data = uri.getQueryParameter("data") ?: ""
        return when {
            data.contains("!3e2") -> TravelMode.WALKING
            data.contains("!3e1") -> TravelMode.CYCLING
            data.contains("!3e3") -> TravelMode.TRANSIT
            else -> TravelMode.DRIVING
        }
    }

    private suspend fun geocodeIfNeeded(locationStr: String, proximityHint: LatLon? = null): LatLon? {
        val coordRegex = Regex("""^(-?\d+\.?\d*),\s*(-?\d+\.?\d*)$""")
        val match = coordRegex.find(locationStr.trim())
        if (match != null) {
            return LatLon(
                lat = match.groupValues[1].toDouble(),
                lon = match.groupValues[2].toDouble()
            )
        }
        // Build a ±5-degree viewbox around the hint so Nominatim ranks geographically
        // nearby results first. This prevents e.g. "Parthenon" from returning the
        // Nashville replica when the route is anchored in Greece.
        val viewbox = proximityHint?.let { h ->
            val d = 5.0
            "${h.lon - d},${h.lat + d},${h.lon + d},${h.lat - d}"
        }
        return try {
            nominatimService.search(query = locationStr, format = "json", limit = 1, viewbox = viewbox)
                .firstOrNull()
                ?.let { LatLon(lat = it.lat.toDouble(), lon = it.lon.toDouble()) }
        } catch (e: Exception) {
            Log.e(TAG, "Geocoding failed for '$locationStr': ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "RouteRepository"
    }
}
