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

    // Higher score = more notable. wikipedia/wikidata and heritage tags are the strongest signals.
    val fameScore: Int get() {
        var score = type.interestScore
        // Cross-wiki presence
        if (tags.containsKey("wikipedia")) score += 1000
        if (tags.containsKey("wikidata")) score += 500
        // UNESCO World Heritage (heritage=1) and national/regional designations.
        // heritage=2 is national-register level — locally significant, not globally notable, so moderate bonus.
        when (tags["heritage"]) {
            "1" -> score += 2000
            "2" -> score += 500
            "3" -> score += 200
        }
        // Tourism sites are purpose-built attractions and outrank incidental civic landmarks
        if (tags["tourism"] == "museum") score += 350
        if (tags["tourism"] == "attraction") score += 300
        if (tags["tourism"] == "zoo" || tags["tourism"] == "theme_park") score += 250
        // Documentation richness signals
        if (tags.containsKey("wikimedia_commons")) score += 100
        if (tags.containsKey("image")) score += 50
        if (tags["fee"] == "yes") score += 75
        if (tags.containsKey("opening_hours")) score += 50
        // Historic type specificity — archaeological/castle sites are major draws
        if (tags["historic"] in setOf("castle", "archaeological_site", "ruins")) score += 150
        if (tags["historic"] in setOf("monument", "memorial")) score += 80
        // Civic/administrative historic types are locally notable but rarely tourist destinations
        if (tags["historic"] in setOf("courthouse", "building", "manor", "farm", "mill", "bridge")) score -= 200
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
