package com.travelguide.anywhere.data.model

data class PlaceOfInterest(
    val osmId: String,        // e.g. "node/123456"
    val name: String,
    val lat: Double,
    val lon: Double,
    val type: PoiType,
    val tags: Map<String, String>,
    val distanceMeters: Float = 0f
) {
    val distanceMiles: Float get() = distanceMeters / 1609.34f

    // Higher score = more notable. wikipedia/wikidata presence is the strongest signal.
    val fameScore: Int get() {
        var score = type.interestScore
        if (tags.containsKey("wikipedia")) score += 1000
        if (tags.containsKey("wikidata")) score += 500
        if (tags["tourism"] == "attraction") score += 200
        if (tags["tourism"] == "museum") score += 150
        return score
    }

    val shortDescription: String get() {
        val historic = tags["historic"]
        val tourism = tags["tourism"]
        val amenity = tags["amenity"]
        val building = tags["building"]
        val leisure = tags["leisure"]
        return when {
            historic != null -> historic.replace("_", " ").replaceFirstChar { it.uppercase() }
            tourism != null -> tourism.replace("_", " ").replaceFirstChar { it.uppercase() }
            amenity != null -> amenity.replace("_", " ").replaceFirstChar { it.uppercase() }
            building != null -> building.replace("_", " ").replaceFirstChar { it.uppercase() }
            leisure != null -> leisure.replace("_", " ").replaceFirstChar { it.uppercase() }
            else -> type.displayName
        }
    }
}

enum class PoiType(val displayName: String, val interestScore: Int) {
    HISTORIC("Historic Site", 100),
    MUSEUM("Museum", 90),
    ATTRACTION("Attraction", 80),
    ARTWORK("Artwork", 70),
    VIEWPOINT("Viewpoint", 70),
    PLACE_OF_WORSHIP("Place of Worship", 60),
    PARK("Park", 50),
    OTHER("Point of Interest", 30);
}
