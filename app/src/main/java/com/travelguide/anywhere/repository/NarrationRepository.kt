package com.travelguide.anywhere.repository

import android.content.SharedPreferences
import android.location.Location
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.travelguide.anywhere.BuildConfig
import com.travelguide.anywhere.data.local.NarrationHistoryStore
import com.travelguide.anywhere.data.model.PlaceOfInterest
import com.travelguide.anywhere.data.remote.ClaudeApiService
import com.travelguide.anywhere.data.remote.dto.ClaudeMessage
import com.travelguide.anywhere.data.remote.dto.ClaudeRequest
import com.travelguide.anywhere.service.LocalLlmEngine
import com.travelguide.anywhere.service.LocalLlmModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
    private val localLlmEngine: LocalLlmEngine,
) {

    data class NarrationResult(
        val text: String,
        val summary: String = "",
        val title: String = "",
        /** Call this alongside mentionedPlacesStore.commitWithSummary() — never before. */
        val commitHistory: () -> Unit,
    )

    suspend fun generateNarration(
        pois: List<PlaceOfInterest>,
        location: Location,
        radiusMiles: Float,
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

        val wikiExtract = poi.tags["wikipedia"]?.let { fetchWikipediaIntro(it) }
            ?: poi.tags["wikidata"]?.let { fetchWikipediaIntroFromWikidata(it) }
        if (wikiExtract != null) {
            Log.d(TAG, "Wikipedia context for '${poi.name}': ${wikiExtract.length} chars")
        }

        val systemPrompt = prefs.getString(PREF_SYSTEM_PROMPT, "")
            ?.takeIf { it.isNotBlank() } ?: ClaudeApiService.SYSTEM_PROMPT

        val baseUserMessage = (prefs.getString(PREF_USER_PROMPT, "")?.takeIf { it.isNotBlank() }
            ?: DEFAULT_USER_PROMPT)
            .replace("{location}", locationStr)
            .replace("{poi}", poiLine)

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

        val provider = prefs.getString(PREF_NARRATION_PROVIDER, NARRATION_PROVIDER_ANTHROPIC)
            ?: NARRATION_PROVIDER_ANTHROPIC
        val savedModel = prefs.getString(PREF_NARRATION_MODEL, "") ?: ""

        Log.d(TAG, "generateNarration: provider=$provider, model=${savedModel.ifBlank { "(default)" }}, " +
            "${history.size / 2} history pairs, maxTokens=$maxTokens, wiki=${wikiExtract != null}")

        if (provider == NARRATION_PROVIDER_LOCAL) {
            val modelName = prefs.getString(PREF_LOCAL_LLM_MODEL, LocalLlmModelManager.LocalModel.PHI4_MINI.name) ?: ""
            val model = runCatching { LocalLlmModelManager.LocalModel.valueOf(modelName) }.getOrDefault(LocalLlmModelManager.LocalModel.PHI4_MINI)
            val text = localLlmEngine.generate(model, systemPrompt, history, userMessageText)
            if (text.isNotBlank()) {
                val (summary, narrationText) = parseSummaryAndNarration(text)
                return NarrationResult(text = narrationText, summary = summary, commitHistory = {
                    historyStore.append(userMessage = userMessageText, assistantMessage = text, lat = location.latitude, lon = location.longitude)
                })
            }
            return NarrationResult(text = "Unable to generate narration. Check that a model is downloaded.", commitHistory = {})
        }

        if (provider == NARRATION_PROVIDER_OPENAI) {
            val openAiKey = prefs.getString(PREF_OPENAI_NARRATION_KEY, "") ?: ""
            val openAiModel = savedModel.takeIf { it.isNotBlank() } ?: "gpt-4o-mini"
            repeat(3) { attempt ->
                try {
                    val text = callOpenAiChat(openAiKey, openAiModel, systemPrompt, history, userMessageText, maxTokens)
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
                } catch (e: Exception) {
                    Log.w(TAG, "OpenAI attempt ${attempt + 1} failed: ${e.message}")
                    if (attempt == 2) return NarrationResult(text = "OpenAI error: ${e.message}", commitHistory = {})
                }
                delay(3_000L * (attempt + 1))
            }
            return NarrationResult(text = "Unable to generate narration at this time.", commitHistory = {})
        }

        // Anthropic path
        val anthropicKey = prefs.getString(PREF_ANTHROPIC_KEY, BuildConfig.ANTHROPIC_API_KEY)
            ?: BuildConfig.ANTHROPIC_API_KEY
        val anthropicModel = savedModel.takeIf { it.isNotBlank() } ?: "claude-haiku-4-5-20251001"

        val request = ClaudeRequest(
            model = anthropicModel,
            system = systemPrompt,
            messages = messages,
            maxTokens = maxTokens,
        )

        repeat(3) { attempt ->
            try {
                val response = claudeApi.createMessage(apiKey = anthropicKey, request = request)
                response.usage?.let { addSpend(anthropicModel, it.inputTokens, it.outputTokens) }
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
     * Asks Claude Haiku whether a candidate POI belongs to the same category as any disliked
     * place summaries. Always uses Anthropic regardless of the user's narration provider setting.
     * Returns false on any error so errors never cause unwanted skips.
     */
    suspend fun isSimilarToDisliked(
        poiName: String,
        poiDesc: String,
        dislikedSummaries: List<String>,
    ): Boolean {
        if (dislikedSummaries.isEmpty()) return false
        val apiKey = prefs.getString(PREF_ANTHROPIC_KEY, BuildConfig.ANTHROPIC_API_KEY)
            ?: BuildConfig.ANTHROPIC_API_KEY
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
            val response = claudeApi.createMessage(apiKey = apiKey, request = request)
            response.usage?.let { addSpend("claude-haiku-4-5-20251001", it.inputTokens, it.outputTokens) }
            response.text.trim().startsWith("YES", ignoreCase = true)
        } catch (e: Exception) {
            Log.w(TAG, "Similarity check failed for '$poiName': ${e.message}")
            false
        }
    }

    /** Fetches chat-capable models from the OpenAI API, sorted with preferred models first. */
    suspend fun fetchOpenAiModels(apiKey: String): List<String> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.openai.com/v1/models")
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()
            val responseBody = okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext emptyList()
                response.body?.string() ?: return@withContext emptyList()
            }
            val json = gson.fromJson(responseBody, JsonObject::class.java)
            json.getAsJsonArray("data")
                ?.mapNotNull { it.asJsonObject?.get("id")?.asString }
                ?.filter { id ->
                    val isChatLike = id.startsWith("gpt-") ||
                        Regex("^o[134]-").containsMatchIn(id) || id == "o1-mini"
                    val isExcluded = id.contains("audio") || id.contains("realtime") ||
                        id.contains("tts") || id.contains("embedding") ||
                        id.contains("dall-e") || id.contains("instruct")
                    isChatLike && !isExcluded
                }
                ?.sortedWith(
                    compareByDescending<String> { it in PREFERRED_OPENAI_MODELS }
                        .thenBy { it }
                )
                ?.distinct()
                ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "fetchOpenAiModels failed: ${e.message}")
            emptyList()
        }
    }

    /** Attempts to fetch the OpenAI account balance; falls back to a link on any error. */
    suspend fun fetchOpenAiBalance(apiKey: String): String = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("https://api.openai.com/v1/organization/balance")
                .header("Authorization", "Bearer $apiKey")
                .get()
                .build()
            val responseBody = okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext "Balance: See platform.openai.com/usage"
                response.body?.string() ?: return@withContext "Balance: See platform.openai.com/usage"
            }
            val json = gson.fromJson(responseBody, JsonObject::class.java)
            val available = json.getAsJsonArray("available")
                ?.firstOrNull()?.asJsonObject
                ?.get("amount")?.asDouble
            if (available != null) "Balance: $${"%.2f".format(available)}"
            else "Balance: See platform.openai.com/usage"
        } catch (e: Exception) {
            Log.w(TAG, "fetchOpenAiBalance failed: ${e.message}")
            "Balance: See platform.openai.com/usage"
        }
    }

    private suspend fun callOpenAiChat(
        apiKey: String,
        model: String,
        systemPrompt: String,
        history: List<ClaudeMessage>,
        userMessage: String,
        maxTokens: Int,
    ): String = withContext(Dispatchers.IO) {
        val messages = buildList {
            add(mapOf("role" to "system", "content" to systemPrompt))
            addAll(history.map { mapOf("role" to it.role, "content" to it.content) })
            add(mapOf("role" to "user", "content" to userMessage))
        }
        val bodyJson = gson.toJson(mapOf(
            "model" to model,
            "messages" to messages,
            "max_tokens" to maxTokens,
        ))
        val requestBody = bodyJson.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://api.openai.com/v1/chat/completions")
            .header("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()
        val responseBody = okHttpClient.newCall(request).execute().use { response ->
            val b = response.body?.string() ?: throw Exception("Empty response from OpenAI")
            if (!response.isSuccessful) {
                val errMsg = gson.fromJson(b, JsonObject::class.java)
                    ?.getAsJsonObject("error")?.get("message")?.asString
                throw Exception(errMsg ?: "HTTP ${response.code}")
            }
            b
        }
        gson.fromJson(responseBody, JsonObject::class.java)
            ?.getAsJsonArray("choices")
            ?.firstOrNull()?.asJsonObject
            ?.getAsJsonObject("message")
            ?.get("content")?.asString
            ?: throw Exception("No content in OpenAI response")
    }

    suspend fun generateDeepDiveNarration(
        poiName: String,
        poiSummary: String,
        location: Location,
        radiusMiles: Float,
    ): NarrationResult {
        val systemPrompt = prefs.getString(PREF_SYSTEM_PROMPT, "")
            ?.takeIf { it.isNotBlank() } ?: ClaudeApiService.SYSTEM_PROMPT

        val template = prefs.getString(PREF_DEEP_DIVE_PROMPT, "")
            ?.takeIf { it.isNotBlank() } ?: DEFAULT_DEEP_DIVE_PROMPT
        val subject = if (poiSummary.isNotBlank()) "$poiName — $poiSummary" else poiName

        val summaryInstruction = "\n\nBefore your narration, format your response exactly like this:\n" +
            "Line 1: a 3-6 word title in Title Case naming the angle or aspect of this place you are exploring " +
            "(e.g. \"The Sniper on the Sixth Floor\", \"Ruby's Desperate Act\", \"A City Frozen in Grief\", " +
            "\"Warren Commission Secrets\"). Do not use the full location name as the entire title.\n" +
            "Line 2: blank.\n" +
            "Line 3: one sentence describing the specific angle or story you are telling about this place " +
            "(e.g. \"The overlooked vantage point that changed everything.\" or " +
            "\"How a small city became the center of a nation's grief.\"). Do not start with a person's full name.\n" +
            "Line 4: blank.\n" +
            "Then begin your narration."

        val userMessageText = template.replace("{subject}", subject) + summaryInstruction

        val expiryDays = prefs.getInt(NarrationHistoryStore.PREF_EXPIRY_DAYS, NarrationHistoryStore.DEFAULT_EXPIRY_DAYS)
        val history = historyStore.getMessages(
            currentLat = location.latitude,
            currentLon = location.longitude,
            radiusMiles = radiusMiles,
            expiryDays = expiryDays,
        )
        val messages = history + listOf(ClaudeMessage(role = "user", content = userMessageText))

        Log.d(TAG, "generateDeepDiveNarration: subject='$poiName', ${history.size / 2} history pairs")

        val provider = prefs.getString(PREF_NARRATION_PROVIDER, NARRATION_PROVIDER_ANTHROPIC)
            ?: NARRATION_PROVIDER_ANTHROPIC
        val savedModel = prefs.getString(PREF_NARRATION_MODEL, "") ?: ""

        if (provider == NARRATION_PROVIDER_LOCAL) {
            val modelName = prefs.getString(PREF_LOCAL_LLM_MODEL, LocalLlmModelManager.LocalModel.PHI4_MINI.name) ?: ""
            val model = runCatching { LocalLlmModelManager.LocalModel.valueOf(modelName) }.getOrDefault(LocalLlmModelManager.LocalModel.PHI4_MINI)
            val text = localLlmEngine.generate(model, systemPrompt, history, userMessageText)
            if (text.isNotBlank()) {
                val (title, summary, narrationText) = parseDeepDiveResponse(text)
                return NarrationResult(text = narrationText, summary = summary, title = title, commitHistory = {
                    historyStore.append(userMessage = userMessageText, assistantMessage = text, lat = location.latitude, lon = location.longitude)
                })
            }
            return NarrationResult(text = "Unable to continue deep dive.", commitHistory = {})
        }

        if (provider == NARRATION_PROVIDER_OPENAI) {
            val key = prefs.getString(PREF_OPENAI_NARRATION_KEY, "") ?: ""
            val model = savedModel.takeIf { it.isNotBlank() } ?: "gpt-4o-mini"
            repeat(3) { attempt ->
                try {
                    val text = callOpenAiChat(key, model, systemPrompt, history, userMessageText, 1500)
                    if (text.isNotBlank()) {
                        val (title, summary, narrationText) = parseDeepDiveResponse(text)
                        return NarrationResult(text = narrationText, summary = summary, title = title, commitHistory = {
                            historyStore.append(userMessage = userMessageText, assistantMessage = text,
                                lat = location.latitude, lon = location.longitude)
                        })
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Deep dive OpenAI attempt ${attempt + 1} failed: ${e.message}")
                    if (attempt == 2) return NarrationResult(text = "Deep dive error: ${e.message}", commitHistory = {})
                }
                delay(3_000L * (attempt + 1))
            }
            return NarrationResult(text = "Unable to continue deep dive.", commitHistory = {})
        }

        // Anthropic path
        val anthropicKey = prefs.getString(PREF_ANTHROPIC_KEY, BuildConfig.ANTHROPIC_API_KEY)
            ?: BuildConfig.ANTHROPIC_API_KEY
        val anthropicModel = savedModel.takeIf { it.isNotBlank() } ?: "claude-haiku-4-5-20251001"
        val request = ClaudeRequest(
            model = anthropicModel,
            system = systemPrompt,
            messages = messages,
            maxTokens = 1500,
        )

        repeat(3) { attempt ->
            try {
                val response = claudeApi.createMessage(apiKey = anthropicKey, request = request)
                response.usage?.let { addSpend(anthropicModel, it.inputTokens, it.outputTokens) }
                val text = response.text
                if (text.isNotBlank()) {
                    val (title, summary, narrationText) = parseDeepDiveResponse(text)
                    return NarrationResult(text = narrationText, summary = summary, title = title, commitHistory = {
                        historyStore.append(userMessage = userMessageText, assistantMessage = text,
                            lat = location.latitude, lon = location.longitude)
                    })
                }
                val errMsg = response.error?.message ?: ""
                if (errMsg.isNotBlank() && !errMsg.contains("timeout", ignoreCase = true)) {
                    return NarrationResult(text = errMsg, commitHistory = {})
                }
            } catch (e: Exception) {
                Log.w(TAG, "Deep dive attempt ${attempt + 1} failed: ${e.message}")
                if (attempt == 2) throw e
            }
            delay(3_000L * (attempt + 1))
        }
        return NarrationResult(text = "Unable to continue deep dive.", commitHistory = {})
    }

    private fun addSpend(model: String, inputTokens: Int, outputTokens: Int) {
        val cost = computeCostUsd(model, inputTokens, outputTokens)
        prefs.edit().putFloat(
            PREF_ANTHROPIC_SPEND_USD,
            prefs.getFloat(PREF_ANTHROPIC_SPEND_USD, 0f) + cost
        ).apply()
    }

    private fun computeCostUsd(model: String, inputTokens: Int, outputTokens: Int): Float {
        val (inputPer1M, outputPer1M) = when {
            model.contains("opus")   -> 15.00f to 75.00f
            model.contains("sonnet") ->  3.00f to 15.00f
            else                     ->  0.80f to  4.00f
        }
        return (inputTokens * inputPer1M + outputTokens * outputPer1M) / 1_000_000f
    }

    private fun parseSummaryAndNarration(text: String): Pair<String, String> {
        val idx = text.indexOf("\n\n")
        if (idx in 1..300) {
            val firstLine = text.substring(0, idx).trim()
            val rest = text.substring(idx + 2).trim()
            if (firstLine.isNotBlank() && rest.isNotBlank() && firstLine.length < rest.length / 2) {
                return firstLine to rest
            }
        }
        return "" to text
    }

    private fun parseDeepDiveResponse(text: String): Triple<String, String, String> {
        // Expect: title \n\n summary \n\n narration
        val parts = text.trim().split(Regex("\\n\\s*\\n"), limit = 3)
        if (parts.size == 3) {
            val title = parts[0].trim()
            val summary = parts[1].trim()
            val narration = parts[2].trim()
            if (title.length in 1..70 && summary.isNotBlank() && narration.length > summary.length) {
                return Triple(title, summary, narration)
            }
        }
        // Fallback: try the 2-line (summary, narration) layout used by regular narrations.
        val (summary, narration) = parseSummaryAndNarration(text)
        return Triple("", summary, narration)
    }

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

            extract.take(6000)
        } catch (e: Exception) {
            Log.w(TAG, "Wikipedia fetch failed for '$wikipediaTag': ${e.message}")
            null
        }
    }

    private suspend fun fetchWikipediaIntroFromWikidata(qid: String): String? = withContext(Dispatchers.IO) {
        try {
            val url = "https://www.wikidata.org/w/api.php" +
                "?action=wbgetentities&ids=$qid&props=sitelinks&sitefilter=enwiki&format=json"
            val httpRequest = Request.Builder()
                .url(url)
                .get()
                .header("User-Agent", "TravelGuideAnywhere/2.0 (Android)")
                .build()
            val body = okHttpClient.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                response.body?.string() ?: return@withContext null
            }
            val title = com.google.gson.JsonParser.parseString(body).asJsonObject
                ?.getAsJsonObject("entities")
                ?.getAsJsonObject(qid)
                ?.getAsJsonObject("sitelinks")
                ?.getAsJsonObject("enwiki")
                ?.get("title")?.asString
                ?: return@withContext null
            fetchWikipediaIntro("en:$title")
        } catch (e: Exception) {
            Log.w(TAG, "Wikidata→Wikipedia intro failed for $qid: ${e.message}")
            null
        }
    }

    private fun maxTokensFor(poi: PlaceOfInterest, hasWikiContext: Boolean): Int {
        val score = poi.fameScore
        return when {
            hasWikiContext && score >= 2000 -> 4000
            hasWikiContext && score >= 500  -> 2500
            hasWikiContext                  -> 1500
            score >= 1000                   -> 2000
            score >= 200                    -> 1200
            else                            -> 800
        }
    }

    companion object {
        private const val TAG = "NarrationRepository"
        const val PREF_ANTHROPIC_KEY = "pref_api_key"   // same string value as MainFragment.PREF_API_KEY
        const val PREF_NARRATION_PROVIDER = "pref_narration_provider"
        const val PREF_NARRATION_MODEL = "pref_narration_model"
        const val PREF_OPENAI_NARRATION_KEY = "pref_openai_narration_key"
        const val NARRATION_PROVIDER_ANTHROPIC = "anthropic"
        const val NARRATION_PROVIDER_OPENAI = "openai"
        const val NARRATION_PROVIDER_LOCAL = "local"
        const val PREF_LOCAL_LLM_MODEL = "pref_local_llm_model"
        const val PREF_SYSTEM_PROMPT = "pref_system_prompt"
        const val PREF_ANTHROPIC_SPEND_USD = "pref_anthropic_spend_usd"
        const val PREF_DEEP_DIVE_PROMPT = "pref_deep_dive_prompt"
        const val DEFAULT_DEEP_DIVE_PROMPT =
            "I'm standing near {subject}. Tell me something new and fascinating about this " +
            "specific place. Pick a different story, person, era, detail, or angle you haven't " +
            "covered yet. Stay focused on this location itself. Do not wander to other topics " +
            "or other places. Do not repeat any facts or stories already covered."
        const val PREF_USER_PROMPT = "pref_user_prompt"
        const val DEFAULT_USER_PROMPT =
            "I'm currently at coordinates {location}. " +
            "The closest interesting place to me that I haven't heard about yet is:\n\n" +
            "{poi}\n\n" +
            "Please give me an engaging audio narration about this place. " +
            "Start naturally, as if you're right here with me, continuing our tour together."

        val PREFERRED_OPENAI_MODELS = setOf(
            "gpt-4o-mini", "gpt-4o", "gpt-4.1-mini", "gpt-4.1",
            "gpt-4-turbo", "gpt-3.5-turbo", "o1-mini", "o4-mini",
        )
        val OPENAI_MODEL_PRICING = mapOf(
            "gpt-4o-mini"   to "~\$0.001",
            "gpt-4.1-mini"  to "~\$0.002",
            "gpt-4o"        to "~\$0.013",
            "gpt-4.1"       to "~\$0.010",
            "gpt-4-turbo"   to "~\$0.014",
            "gpt-3.5-turbo" to "~\$0.003",
            "o1-mini"       to "~\$0.006",
            "o4-mini"       to "~\$0.006",
        )
    }
}
