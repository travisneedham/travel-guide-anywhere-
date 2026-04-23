package com.travelguide.anywhere.data.remote.dto

import com.google.gson.annotations.SerializedName

data class OverpassResponse(
    val elements: List<OverpassElement> = emptyList()
)

data class OverpassElement(
    val type: String,                          // "node", "way", "relation"
    val id: Long,
    val lat: Double? = null,                   // present on nodes
    val lon: Double? = null,
    val center: OverpassCenter? = null,        // present on ways/relations
    val tags: Map<String, String> = emptyMap()
) {
    val effectiveLat: Double get() = lat ?: center?.lat ?: 0.0
    val effectiveLon: Double get() = lon ?: center?.lon ?: 0.0
    val osmId: String get() = "$type/$id"
}

data class OverpassCenter(
    val lat: Double,
    val lon: Double
)
