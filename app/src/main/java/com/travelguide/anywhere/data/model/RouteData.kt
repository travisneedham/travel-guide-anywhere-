package com.travelguide.anywhere.data.model

import kotlin.math.roundToInt

data class RouteData(
    val waypoints: List<LatLon>,
    val totalDistanceMeters: Double,
    val totalDurationSeconds: Long,
    val travelMode: TravelMode,
    val originLabel: String,
    val destinationLabel: String
) {
    val summaryText: String get() {
        val miles = totalDistanceMeters / 1609.34
        val hours = totalDurationSeconds / 3600
        val mins = (totalDurationSeconds % 3600) / 60
        val timeStr = if (hours >= 1) "${hours}h ${mins}m" else "${mins}m"
        val distStr = if (miles >= 10) "${miles.roundToInt()} mi" else "%.1f mi".format(miles)
        return "$distStr, ~$timeStr"
    }
}

enum class TravelMode(val osrmProfile: String) {
    DRIVING("driving"),
    WALKING("walking"),
    CYCLING("cycling"),
    TRANSIT("driving");  // Falls back to driving for OSRM
}
