package com.travelguide.anywhere.data.model

import kotlin.math.pow

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

    // Higher score = more notable. Enriched with Wikipedia pageviews (wiki_views tag) and
    // Wikidata sitelinks (wiki_sitelinks tag) for a continuous signal. Heritage and wiki
    // enrichment are the strongest signals.
    val fameScore: Int get() {
        var score = type.interestScore
        when (tags["heritage"]) {
            "1" -> score += 1000
            "2" -> score += 400
            "3" -> score += 200
        }
        if (tags["tourism"] == "attraction") score += 100
        if (tags["tourism"] == "museum") score += 75
        if (tags.containsKey("wikimedia_commons")) score += 40
        if (tags["fee"] == "yes") score += 35
        if (tags.containsKey("opening_hours")) score += 25
        if (tags.containsKey("image")) score += 20
        if (tags["historic"] in setOf("castle", "archaeological_site")) score += 50
        val views = tags["wiki_views"]?.toLongOrNull() ?: 0L
        val viewsContribution = if (views > 0L) (views.toDouble().pow(0.57) * 8.06).toInt() else 0
        // Infrastructure/agency types (transit, telecom, etc.) get half credit for pageviews —
        // they're notable but not visitable landmarks.
        score += if (type == PoiType.OTHER) viewsContribution / 2 else viewsContribution
        // Cap sitelinks: mis-tagged Wikidata QIDs can carry 20+ sitelinks for a different entity.
        val sitelinks = tags["wiki_sitelinks"]?.toIntOrNull() ?: 0
        score += minOf(sitelinks, 15) * 20
        // Fallback for un-enriched POIs: weak signal so real typed POIs rank above infrastructure.
        if (views == 0L && sitelinks == 0) {
            if (tags.containsKey("wikipedia")) score += 120
            else if (tags.containsKey("wikidata")) score += 40
        }
        return score
    }

    val shortDescription: String get() {
        val historic = tags["historic"]
        val tourism = tags["tourism"]
        val amenity = tags["amenity"]
        val building = tags["building"]
        val leisure = tags["leisure"]
        val natural = tags["natural"]
        val place = tags["place"]
        return when {
            historic != null -> historic.replace("_", " ").replaceFirstChar { it.uppercase() }
            tourism != null -> tourism.replace("_", " ").replaceFirstChar { it.uppercase() }
            amenity != null -> amenity.replace("_", " ").replaceFirstChar { it.uppercase() }
            building != null -> building.replace("_", " ").replaceFirstChar { it.uppercase() }
            leisure != null -> leisure.replace("_", " ").replaceFirstChar { it.uppercase() }
            natural != null -> natural.replace("_", " ").replaceFirstChar { it.uppercase() }
            place != null -> place.replace("_", " ").replaceFirstChar { it.uppercase() }
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
    OTHER("Point of Interest", 10);
}
