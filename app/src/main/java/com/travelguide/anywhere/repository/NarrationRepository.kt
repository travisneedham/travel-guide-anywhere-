package com.travelguide.anywhere.repository

import android.content.SharedPreferences
import android.location.Location
import android.util.Log
import com.travelguide.anywhere.data.model.PlaceOfInterest
import com.travelguide.anywhere.data.remote.ClaudeApiService
import com.travelguide.anywhere.data.remote.dto.ClaudeMessage
import com.travelguide.anywhere.data.remote.dto.ClaudeRequest
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NarrationRepository @Inject constructor(
    private val claudeApi: ClaudeApiService,
    private val prefs: SharedPreferences
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

        val systemPrompt = prefs.getString(PREF_SYSTEM_PROMPT, "")
            ?.takeIf { it.isNotBlank() } ?: ClaudeApiService.SYSTEM_PROMPT

        val userMessage = (prefs.getString(PREF_USER_PROMPT, "")?.takeIf { it.isNotBlank() } ?: DEFAULT_USER_PROMPT)
            .replace("{location}", locationStr)
            .replace("{poi}", poiLine)

        val request = ClaudeRequest(
            system = systemPrompt,
            messages = listOf(ClaudeMessage(role = "user", content = userMessage))
        )

        // Retry up to 3 times on transient errors (stream idle timeout, socket timeout, etc.)
        repeat(3) { attempt ->
            try {
                val response = claudeApi.createMessage(apiKey = apiKey, request = request)
                val text = response.text
                if (text.isNotBlank()) return text
                val errMsg = response.error?.message ?: ""
                // Don't retry hard API errors (bad key, quota, etc.)
                if (errMsg.isNotBlank() && !errMsg.contains("timeout", ignoreCase = true)) {
                    return errMsg
                }
                Log.w(TAG, "Attempt ${attempt + 1}: blank/timeout response — $errMsg")
            } catch (e: Exception) {
                Log.w(TAG, "Attempt ${attempt + 1} failed: ${e.message}")
                if (attempt == 2) throw e
            }
            delay(3_000L * (attempt + 1)) // 3s, 6s before retries 2 and 3
        }

        return "Unable to generate narration at this time."
    }

    companion object {
        private const val TAG = "NarrationRepository"
        const val PREF_SYSTEM_PROMPT = "pref_system_prompt"
        const val PREF_USER_PROMPT = "pref_user_prompt"
        const val DEFAULT_USER_PROMPT =
            "I'm currently at coordinates {location}. " +
            "The closest interesting place to me that I haven't heard about yet is:\n\n" +
            "{poi}\n\n" +
            "Please give me an engaging audio narration about this specific place. " +
            "Start naturally, as if you're right here with me, continuing our tour together."
    }
}
