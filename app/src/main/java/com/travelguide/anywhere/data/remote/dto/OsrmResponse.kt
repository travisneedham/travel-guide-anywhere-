package com.travelguide.anywhere.data.remote.dto

data class OsrmResponse(
    val routes: List<OsrmRoute>
)

data class OsrmRoute(
    val distance: Double,
    val duration: Double,
    val geometry: OsrmGeometry
)

data class OsrmGeometry(
    val coordinates: List<List<Double>>  // [[lon, lat], ...]
)
