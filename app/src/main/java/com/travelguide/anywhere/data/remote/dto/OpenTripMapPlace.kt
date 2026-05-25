package com.travelguide.anywhere.data.remote.dto

data class OpenTripMapPlace(
    val xid: String,
    val name: String = "",
    val osm: String? = null,
    val wikidata: String? = null,
    val kinds: String? = null,
    val rate: Int = 0,
    val point: OpenTripMapPoint = OpenTripMapPoint(0.0, 0.0),
)

data class OpenTripMapPoint(
    val lat: Double,
    val lon: Double,
)
