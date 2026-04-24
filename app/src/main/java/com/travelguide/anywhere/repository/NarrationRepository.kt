package com.travelguide.anywhere.repository

import android.location.Location
import com.travelguide.anywhere.data.model.PlaceOfInterest
import com.travelguide.anywhere.data.remote.ClaudeApiService
import com.travelguide.anywhere.data.remote.dto.ClaudeMessage
import com.travelguide.anywhere.data.remote.dto.ClaudeRequest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NarrationRepository @Inject constructor(
    private val claudeApi: ClaudeApiService
) {

    suspend fun generateNarration(
        pois: List<PlaceOfInterest>,
        location: Location,
        radiusMiles: Float,
        apiKey: String
    ): String {
        val poi = pois.first()
        val distStr = "%.2f".format(poi.distanceMiles)
        val extraTags = poi.tags
            .filterKeys { it !in listOf("name", "source", "source:date", "wikidata", "wikipedia") }
            .entries.take(4)
            .joinToString(", ") { (k, v) -> "$k=$v" }
        val poiLine = "- ${poi.name} (${poi.shortDescription}): ${distStr} miles away${if (extraTags.isNotEmpty()) " — $extraTags" else ""}"

        val locationStr = "%.4f°N, %.4f°W".format(location.latitude, Math.abs(location.longitude))

        val userMessage = buildString {
            append("I'm currently at coordinates $locationStr. ")
            append("The closest interesting place to me that I haven't heard about yet is:\n\n")
            append(poiLine)
            append("\n\nPlease give me an engaging audio narration about this specific place. ")
            append("Start naturally, as if you're right here with me, continuing our tour together.")
        }

        val response = claudeApi.createMessage(
            apiKey = apiKey,
            request = ClaudeRequest(
                system = ClaudeApiService.SYSTEM_PROMPT,
                messages = listOf(
                    ClaudeMessage(role = "user", content = userMessage)
                )
            )
        )

        return response.text.ifBlank {
            response.error?.message ?: "Unable to generate narration at this time."
        }
    }
}
