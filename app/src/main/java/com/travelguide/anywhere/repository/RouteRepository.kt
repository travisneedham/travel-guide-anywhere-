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
            Log.i(TAG, "resolveRoute — expanded: $fullUrl")

            val parsed = parseGoogleMapsUrl(fullUrl)
                ?: return@withContext Result.Failure("Could not find an origin and destination in that link.")

            Log.i(TAG, "resolveRoute — origin='${parsed.origin}' dest='${parsed.dest}' " +
                "mode=${parsed.travelMode} viewport=${parsed.viewportHint} " +
                "originCoord=${parsed.originCoord} destCoord=${parsed.destCoord}")

            // Level 1: use coordinates extracted directly from the data= segment.
            // These are Google's own resolved coordinates — no geocoding ambiguity possible.
            // Level 2: Nominatim with a soft ±2° viewbox hint around the viewport (bounded=0).
            //   Global importance scores still govern — famous places in Greece beat local US namesakes.
            // Level 3: unbiased Nominatim only if we have no geographic anchor at all.
            val viewport = parsed.viewportHint
            val origin = parsed.originCoord
                ?: geocodeWithContext(parsed.origin, anchor = viewport)
                ?: return@withContext Result.Failure("Could not locate: ${parsed.origin}")
            val dest = parsed.destCoord
                ?: geocodeWithContext(parsed.dest, anchor = viewport)
                ?: return@withContext Result.Failure("Could not locate: ${parsed.dest}")

            Log.i(TAG, "resolveRoute — resolved origin=$origin dest=$dest")

            val coordsStr = "${origin.lon},${origin.lat};${dest.lon},${dest.lat}"
            val osrmResponse = osrmService.getRoute(
                profile = parsed.travelMode.osrmProfile,
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
                    travelMode = parsed.travelMode,
                    originLabel = parsed.origin,
                    destinationLabel = parsed.dest
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
                // Log every URL in the redirect chain for debugging.
                val chain = mutableListOf<String>()
                var r: okhttp3.Response? = response
                while (r != null) {
                    chain.add(r.request.url.toString())
                    r = r.priorResponse
                }
                chain.reverse()  // oldest first
                Log.i(TAG, "expandUrl — redirect chain (${chain.size} hops):")
                chain.forEachIndexed { i, u -> Log.i(TAG, "  [$i] $u") }

                // Pick the best URL: prefer one with @lat,lon or data= coordinates.
                val best = chain.firstOrNull { it.contains("/@") || it.contains("data=") }
                if (best != null) {
                    Log.i(TAG, "expandUrl — using chain URL with coords: $best")
                    response.body?.close()
                    return@use best
                }

                // If no redirect URL had coordinates, scan the HTML response body
                // for an og:url or canonical link that contains the full Maps URL.
                val body = try {
                    response.body?.source()?.readUtf8(100_000) ?: ""
                } catch (_: Exception) { "" }
                Log.i(TAG, "expandUrl — HTML body length=${body.length}, first 500 chars: ${body.take(500)}")

                val fullUrlFromHtml = extractUrlFromHtml(body)
                if (fullUrlFromHtml != null) {
                    Log.i(TAG, "expandUrl — extracted from HTML: $fullUrlFromHtml")
                } else {
                    Log.w(TAG, "expandUrl — no coords in redirect chain or HTML, using final: ${chain.lastOrNull()}")
                }
                fullUrlFromHtml ?: response.request.url.toString()
            }
        } catch (e: Exception) {
            Log.w(TAG, "URL expansion failed, using original: ${e.message}")
            url
        }
    }

    private fun extractUrlFromHtml(html: String): String? {
        if (html.isEmpty()) return null
        // og:url meta tag — Google embeds the full canonical Maps URL here.
        val ogRegex = Regex("""<meta[^>]+property=["']og:url["'][^>]+content=["']([^"']+)""")
        ogRegex.find(html)?.groupValues?.get(1)?.let { if (it.contains("/maps/")) return it }
        // Reversed attribute order variant.
        val ogAlt = Regex("""<meta[^>]+content=["']([^"']+)[^>]+property=["']og:url["']""")
        ogAlt.find(html)?.groupValues?.get(1)?.let { if (it.contains("/maps/")) return it }
        // Canonical link.
        val canonical = Regex("""<link[^>]+rel=["']canonical["'][^>]+href=["']([^"']+)""")
        canonical.find(html)?.groupValues?.get(1)?.let { if (it.contains("/maps/")) return it }
        // Last resort: any Google Maps /dir/ URL with an @ in it.
        val dirUrl = Regex("""https://(?:www\.)?google\.\w+/maps/dir/[^"'\s]*@-?\d+\.\d+[^"'\s]*""")
        dirUrl.find(html)?.value?.let { return it }
        return null
    }

    private data class ParsedRoute(
        val origin: String,
        val dest: String,
        val travelMode: TravelMode,
        val viewportHint: LatLon?,
        // Exact coordinates extracted from the data= segment, bypassing Nominatim entirely.
        val originCoord: LatLon?,
        val destCoord: LatLon?,
    )

    private fun parseGoogleMapsUrl(url: String): ParsedRoute? {
        val uri = Uri.parse(url)

        // The data= payload is a path segment in /dir/ URLs, not a query parameter.
        // Check both locations so we handle all URL formats.
        val dataSegment = uri.pathSegments.firstOrNull { it.startsWith("data=") }
            ?.removePrefix("data=")
            ?: uri.getQueryParameter("data")
            ?: ""

        val travelMode = detectTravelMode(dataSegment)

        // Extract the @lat,lon,zoomz viewport — present in virtually all expanded URLs.
        // e.g. "@37.9757,23.7369,14z"
        val viewportHint = uri.pathSegments
            .firstOrNull { it.startsWith("@") }
            ?.removePrefix("@")
            ?.split(",")
            ?.let { parts ->
                val lat = parts.getOrNull(0)?.toDoubleOrNull()
                val lon = parts.getOrNull(1)?.toDoubleOrNull()
                if (lat != null && lon != null) LatLon(lat, lon) else null
            }

        // Extract exact waypoint coordinates from the data= segment.
        // Google encodes each named place as !2m2!1d{longitude}!2d{latitude}.
        // The pairs appear in route order: first = origin, second = destination.
        val waypointCoords = extractWaypointCoords(dataSegment)
        val originCoord = waypointCoords.getOrNull(0)
        val destCoord = waypointCoords.getOrNull(1)

        // ?api=1 format: origin= and destination= query params
        val originParam = uri.getQueryParameter("origin")
        val destParam = uri.getQueryParameter("destination")
        if (!originParam.isNullOrBlank() && !destParam.isNullOrBlank() &&
            !isCurrentLocation(originParam)
        ) {
            return ParsedRoute(originParam, destParam, travelMode, viewportHint, originCoord, destCoord)
        }

        // Older ?saddr= / ?daddr= format (common in maps.app.goo.gl expansions)
        val saddr = uri.getQueryParameter("saddr")
        val daddr = uri.getQueryParameter("daddr")
        if (!saddr.isNullOrBlank() && !daddr.isNullOrBlank() && !isCurrentLocation(saddr)) {
            return ParsedRoute(saddr, daddr, travelMode, viewportHint, originCoord, destCoord)
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
                return ParsedRoute(originSeg, destSeg, travelMode, viewportHint, originCoord, destCoord)
            }
        }

        return null
    }

    /**
     * Extracts waypoint (lon, lat) pairs from the Google Maps data= segment.
     *
     * In a directions URL the data payload is Protocol-Buffer-lite encoded.
     * Each named waypoint is stored as a coordinate message with the pattern:
     *   !2m2!1d{longitude}!2d{latitude}
     * Pairs appear in route order so index 0 = origin, index 1 = destination.
     */
    private fun extractWaypointCoords(data: String): List<LatLon> {
        if (data.isEmpty()) return emptyList()
        val regex = Regex("""!2m2!1d(-?\d+\.\d+)!2d(-?\d+\.\d+)""")
        return regex.findAll(data).map { m ->
            LatLon(lat = m.groupValues[2].toDouble(), lon = m.groupValues[1].toDouble())
        }.toList()
    }

    private fun isCurrentLocation(s: String): Boolean {
        val normalized = s.replace('+', ' ').trim()
        return normalized.equals("current location", ignoreCase = true) ||
            normalized.equals("my location", ignoreCase = true) ||
            normalized.equals("here", ignoreCase = true) ||
            normalized.equals("your location", ignoreCase = true)
    }

    private fun detectTravelMode(dataSegment: String): TravelMode = when {
        dataSegment.contains("!3e2") -> TravelMode.WALKING
        dataSegment.contains("!3e1") -> TravelMode.CYCLING
        dataSegment.contains("!3e3") -> TravelMode.TRANSIT
        else -> TravelMode.DRIVING
    }

    /**
     * Resolves a location string to coordinates.
     *
     * If [locationStr] is already a "lat,lon" pair, returns it directly.
     * Otherwise calls Nominatim. When [anchor] is provided, a ±2° viewbox is
     * passed as a soft preference (bounded=0) so globally-important places
     * (Athens, Greece; the Parthenon) still win over local namesakes even when
     * the URL's viewport happens to be centred on the wrong continent.
     */
    private suspend fun geocodeWithContext(locationStr: String, anchor: LatLon? = null): LatLon? {
        val coordRegex = Regex("""^(-?\d+\.?\d*),\s*(-?\d+\.?\d*)$""")
        val match = coordRegex.find(locationStr.trim())
        if (match != null) {
            return LatLon(
                lat = match.groupValues[1].toDouble(),
                lon = match.groupValues[2].toDouble()
            )
        }

        val viewbox = if (anchor != null) {
            val d = 2.0
            "${anchor.lon - d},${anchor.lat + d},${anchor.lon + d},${anchor.lat - d}"
        } else null

        return try {
            val results = nominatimService.search(
                query = locationStr,
                format = "json",
                limit = 1,
                viewbox = viewbox,
                bounded = null,
            )
            results.firstOrNull()?.let { LatLon(lat = it.lat.toDouble(), lon = it.lon.toDouble()) }
        } catch (e: Exception) {
            Log.e(TAG, "Geocoding failed for '$locationStr': ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "RouteRepository"
    }
}
