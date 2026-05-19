package com.travelguide.anywhere.repository

import android.content.SharedPreferences
import android.location.Location
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.travelguide.anywhere.data.local.NarrationHistoryStore
import com.travelguide.anywhere.data.model.PlaceOfInterest
import com.travelguide.anywhere.data.remote.ClaudeApiService
import com.travelguide.anywhere.data.remote.dto.ClaudeMessage
import com.travelguide.anywhere.data.remote.dto.ClaudeRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NarrationRepository @Inject constructor(
    private val claudeApi: ClaudeApiService,
    private val prefs: SharedPreferences,
    private val historyStore: NarrationHistoryStore,
    private val okHttpClient: OkHttpClient,
    private val gson: Gson,
) {

    data class NarrationResult(
        val text: String,
        val summary: String = "",
        /** Call this alongside mentionedPlacesStore.commitWithSummary() — never before. */
        val commitHistory: () -> Unit,
    )

    suspend fun generateNarration(
        pois: List<PlaceOfInterest>,
        location: Location,
        radiusMiles: Float,
        apiKey: String,
    ): NarrationResult {
        val poi = pois.first()
        val distStr = "%.2f".format(poi.distanceMiles)
        val extraTags = poi.tags
            .filterKeys { it !in listOf("name", "source", "source:date", "wikidata", "wikipedia") }
            .entries.take(4)
            .joinToString(", ") { (k, v) -> "$k=$v" }
        val poiLine = "- ${poi.name} (${poi.shortDescription}): ${distStr} miles away" +
            if (extraTags.isNotEmpty()) " — $extraTags" else ""

        val locationStr = "%.4f°N, %.4f°W".format(location.latitude, Math.abs(location.longitude))

        // Fetch Wikipedia intro for enrichment — never blocks narration if it fails.
        val wikiExtract = poi.tags["wikipedia"]?.let { fetchWikipediaIntro(it) }
        if (wikiExtract != null) {
            Log.d(TAG, "Wikipedia context for '${poi.name}': ${wikiExtract.length} chars")
        }

        val systemPrompt = prefs.getString(PREF_SYSTEM_PROMPT, "")
            ?.takeIf { it.isNotBlank() } ?: ClaudeApiService.SYSTEM_PROMPT

        val baseUserMessage = (prefs.getString(PREF_USER_PROMPT, "")?.takeIf { it.isNotBlank() }
            ?: DEFAULT_USER_PROMPT)
            .replace("{location}", locationStr)
            .replace("{poi}", poiLine)

        // Always appended — needed to populate the Places Covered summary.
        val summaryInstruction = "\n\nBefore your narration, write one sentence on the very first line " +
            "that describes what this place is — for example: \"A mid-nineteenth-century lighthouse " +
            "perched on a rocky headland\" or \"The childhood home of a notorious outlaw.\" " +
            "Do not start with the place name. Follow it with exactly one blank line, then begin your narration."

        val userMessageText = buildString {
            append(baseUserMessage)
            if (wikiExtract != null) {
                append("\n\n---\nBackground context from Wikipedia:\n\n$wikiExtract")
            }
            append(summaryInstruction)
        }

        val expiryDays = prefs.getInt(NarrationHistoryStore.PREF_EXPIRY_DAYS, NarrationHistoryStore.DEFAULT_EXPIRY_DAYS)
        val history = historyStore.getMessages(
            currentLat = location.latitude,
            currentLon = location.longitude,
            radiusMiles = radiusMiles,
            expiryDays = expiryDays,
        )

        val maxTokens = maxTokensFor(poi, wikiExtract != null)
        val messages = history + listOf(ClaudeMessage(role = "user", content = userMessageText))

        val request = ClaudeRequest(
            system = systemPrompt,
            messages = messages,
            maxTokens = maxTokens,
        )

        Log.d(TAG, "Claude request: ${history.size / 2} history pairs, maxTokens=$maxTokens, wiki=${wikiExtract != null}")

        // Retry up to 3 times on transient errors.
        repeat(3) { attempt ->
            try {
                val response = claudeApi.createMessage(apiKey = apiKey, request = request)
                val text = response.text
                if (text.isNotBlank()) {
                    val (summary, narrationText) = parseSummaryAndNarration(text)
                    return NarrationResult(text = narrationText, summary = summary, commitHistory = {
                        historyStore.append(
                            userMessage = userMessageText,
                            assistantMessage = text,
                            lat = location.latitude,
                            lon = location.longitude,
                        )
                    })
                }
                val errMsg = response.error?.message ?: ""
                if (errMsg.isNotBlank() && !errMsg.contains("timeout", ignoreCase = true)) {
                    return NarrationResult(text = errMsg, commitHistory = {})
                }
                Log.w(TAG, "Attempt ${attempt + 1}: blank/timeout response — $errMsg")
            } catch (e: Exception) {
                Log.w(TAG, "Attempt ${attempt + 1} failed: ${e.message}")
                if (attempt == 2) throw e
            }
            delay(3_000L * (attempt + 1))
        }

        return NarrationResult(text = "Unable to generate narration at this time.", commitHistory = {})
    }

    /**
     * Asks Claude (Haiku, cheapest model) whether a candidate POI belongs to the same
     * category as any of the user's disliked place summaries. Returns false on any error
     * so errors never cause unwanted skips.
     */
    suspend fun isSimilarToDisliked(
        poiName: String,
        poiDesc: String,
        dislikedSummaries: List<String>,
        apiKey: String,
    ): Boolean {
        if (dislikedSummaries.isEmpty()) return false
        val list = dislikedSummaries.take(20).joinToString("\n") { "- $it" }
        val request = ClaudeRequest(
            model = "claude-haiku-4-5-20251001",
            system = "Answer YES or NO only, nothing else.",
            messages = listOf(ClaudeMessage(
                role = "user",
                content = "The user disliked these types of places:\n$list\n\n" +
                    "Is \"$poiName\" ($poiDesc) the same type or category as any of them? " +
                    "Answer YES if it is the same kind of place. Answer NO otherwise."
            )),
            maxTokens = 5,
        )
        return try {
            claudeApi.createMessage(apiKey = apiKey, request = request)
                .text.trim().startsWith("YES", ignoreCase = true)
        } catch (e: Exception) {
            Log.w(TAG, "Similarity check failed for '$poiName': ${e.message}")
            false
        }
    }

    /**
     * Splits Claude's response into a one-sentence summary (first line) and the narration body.
     * Falls back to ("", fullText) if the response doesn't follow the expected format.
     */
    private fun parseSummaryAndNarration(text: String): Pair<String, String> {
        val idx = text.indexOf("\n\n")
        if (idx in 1..300) {
            val firstLine = text.substring(0, idx).trim()
            val rest = text.substring(idx + 2).trim()
            // The summary should be a single sentence, much shorter than the narration.
            if (firstLine.isNotBlank() && rest.isNotBlank() && firstLine.length < rest.length / 2) {
                return firstLine to rest
            }
        }
        return "" to text
    }

    // Fetch the intro section of a Wikipedia article. Returns null on any failure.
    // The Wikipedia tag in OSM is formatted as "en:Article Title".
    private suspend fun fetchWikipediaIntro(wikipediaTag: String): String? = withContext(Dispatchers.IO) {
        try {
            val colon = wikipediaTag.indexOf(':')
            if (colon < 0) return@withContext null
            val lang = wikipediaTag.substring(0, colon)
            val title = wikipediaTag.substring(colon + 1)

            val url = "https://$lang.wikipedia.org/w/api.php?" +
                "action=query&prop=extracts&exintro=true&explaintext=true" +
                "&titles=${URLEncoder.encode(title, "UTF-8")}&format=json"

            val httpRequest = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", "TravelGuideAnywhere/2.0 (Android)")
                .build()

            val responseBody = okHttpClient.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.string() ?: return@withContext null
            }

            val json = gson.fromJson(responseBody, JsonObject::class.java)
            val pages = json.getAsJsonObject("query")?.getAsJsonObject("pages")
                ?: return@withContext null
            val page = pages.entrySet().firstOrNull()?.value?.asJsonObject
                ?: return@withContext null

            if (page.get("pageid")?.asInt == -1) return@withContext null

            val extract = page.get("extract")?.asString?.trim() ?: return@withContext null
            if (extract.isBlank()) return@withContext null

            // Cap at 6000 chars — Wikipedia intro sections are typically 500–3000 chars;
            // this prevents extreme cases from ballooning token cost.
            extract.take(6000)
        } catch (e: Exception) {
            Log.w(TAG, "Wikipedia fetch failed for '$wikipediaTag': ${e.message}")
            null
        }
    }

    private fun maxTokensFor(poi: PlaceOfInterest, hasWikiContext: Boolean): Int {
        val score = poi.fameScore
        return when {
            hasWikiContext && score >= 2000 -> 4000  // World-famous + rich context
            hasWikiContext && score >= 500  -> 2500  // Notable with good Wikipedia context
            hasWikiContext                  -> 1500  // Has Wikipedia but lower fame
            score >= 1000                   -> 2000  // Very famous, no Wikipedia tag
            score >= 200                    -> 1200  // Moderately notable
            else                            -> 800   // Simple/small place
        }
    }

    companion object {
        private const val TAG = "NarrationRepository"
        const val PREF_SYSTEM_PROMPT = "pref_system_prompt"
        const val PREF_USER_PROMPT = "pref_user_prompt"
        const val DEFAULT_USER_PROMPT =
            "I'm currently at coordinates {location}. " +
            "The closest interesting place to me that I haven't heard about yet is:\n\n" +
            "{poi}\n\n" +
            "Please give me an engaging audio narration about this place. " +
            "Start naturally, as if you're right here with me, continuing our tour together."
    }
}
