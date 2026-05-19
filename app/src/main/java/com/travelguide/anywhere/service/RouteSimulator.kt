package com.travelguide.anywhere.service

import android.location.Location
import com.travelguide.anywhere.data.model.LatLon
import com.travelguide.anywhere.data.model.RouteData
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class RouteSimulator(private val route: RouteData) {

    private val waypoints: List<LatLon> = route.waypoints
    private val cumulativeDistances: DoubleArray
    private val totalDistance: Double

    var isPaused: Boolean = false
        private set

    private var elapsedSeconds: Double = 0.0

    init {
        val dists = DoubleArray(waypoints.size)
        for (i in 1 until waypoints.size) {
            dists[i] = dists[i - 1] + haversineMeters(waypoints[i - 1], waypoints[i])
        }
        cumulativeDistances = dists
        totalDistance = if (waypoints.size > 1) dists.last() else 0.0
    }

    val isAtEnd: Boolean
        get() = route.totalDurationSeconds > 0 && elapsedSeconds >= route.totalDurationSeconds

    val progressFraction: Float
        get() = if (route.totalDurationSeconds > 0) {
            (elapsedSeconds / route.totalDurationSeconds).toFloat().coerceIn(0f, 1f)
        } else 0f

    fun advance(seconds: Double) {
        if (!isPaused && !isAtEnd) {
            elapsedSeconds = (elapsedSeconds + seconds)
                .coerceAtMost(route.totalDurationSeconds.toDouble())
        }
    }

    fun pause() { isPaused = true }
    fun resume() { isPaused = false }

    fun currentLocation(): Location {
        if (waypoints.isEmpty()) return Location("route")

        if (waypoints.size == 1) {
            return Location("route").apply {
                latitude = waypoints[0].lat
                longitude = waypoints[0].lon
                accuracy = 10f
                time = System.currentTimeMillis()
            }
        }

        val fraction = if (route.totalDurationSeconds > 0) {
            (elapsedSeconds / route.totalDurationSeconds).coerceIn(0.0, 1.0)
        } else 0.0
        val targetDist = fraction * totalDistance

        // Binary search for segment containing targetDist
        var lo = 0
        var hi = waypoints.size - 2
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (cumulativeDistances[mid + 1] < targetDist) lo = mid + 1 else hi = mid
        }

        val segStart = waypoints[lo]
        val segEnd = waypoints[lo + 1]
        val segLength = cumulativeDistances[lo + 1] - cumulativeDistances[lo]
        val segFrac = if (segLength > 0) (targetDist - cumulativeDistances[lo]) / segLength else 0.0

        return Location("route").apply {
            latitude = segStart.lat + (segEnd.lat - segStart.lat) * segFrac
            longitude = segStart.lon + (segEnd.lon - segStart.lon) * segFrac
            accuracy = 10f
            time = System.currentTimeMillis()
        }
    }

    private fun haversineMeters(a: LatLon, b: LatLon): Double {
        val R = 6_371_000.0
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val sinDLat = sin(dLat / 2)
        val sinDLon = sin(dLon / 2)
        val aVal = sinDLat * sinDLat +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sinDLon * sinDLon
        return 2 * R * asin(sqrt(aVal))
    }
}
