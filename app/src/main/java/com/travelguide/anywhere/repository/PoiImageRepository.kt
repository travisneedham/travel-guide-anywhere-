package com.travelguide.anywhere.repository

import android.net.Uri
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.travelguide.anywhere.data.model.PlaceOfInterest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PoiImageRepository @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val gson: Gson
) {
    /**
     * Returns a displayable image URL for the POI, or null if none is found.
     * Priority: wikipedia tag → wikidata P18 → wikimedia_commons File tag
     */
    suspend fun fetchImageUrl(poi: PlaceOfInterest): String? = withContext(Dispatchers.IO) {
        try {
            poi.tags["wikipedia"]?.let { tag ->
                fetchWikipediaImage(tag)?.let { return@withContext it }
            }
            poi.tags["wikidata"]?.let { qid ->
                fetchWikidataImage(qid)?.let { return@withContext it }
            }
            poi.tags["wikimedia_commons"]
                ?.takeIf { it.startsWith("File:") }
                ?.removePrefix("File:")
                ?.let { filename ->
                    fetchCommonsImageUrl(filename)?.let { return@withContext it }
                }
        } catch (e: Exception) {
            Log.w(TAG, "Image fetch failed for ${poi.name}: ${e.message}")
        }
        null
    }

    private fun fetchWikipediaImage(tag: String): String? {
        val colon = tag.indexOf(':')
        if (colon < 0) return null
        val lang = tag.substring(0, colon)
        val title = tag.substring(colon + 1)
        // OkHttp's addPathSegment handles URL encoding of the title correctly.
        val url = okhttp3.HttpUrl.Builder()
            .scheme("https")
            .host("$lang.wikipedia.org")
            .addPathSegments("api/rest_v1/page/summary")
            .addPathSegment(title)
            .build()
        return get(url.toString())?.let { body ->
            gson.fromJson(body, JsonObject::class.java)
                ?.getAsJsonObject("thumbnail")
                ?.get("source")?.asString
        }
    }

    private fun fetchWikidataImage(qid: String): String? {
        val url = "https://www.wikidata.org/w/api.php" +
                "?action=wbgetclaims&entity=$qid&property=P18&format=json"
        val filename = get(url)?.let { body ->
            gson.fromJson(body, JsonObject::class.java)
                ?.getAsJsonObject("claims")
                ?.getAsJsonArray("P18")
                ?.firstOrNull()?.asJsonObject
                ?.getAsJsonObject("mainsnak")
                ?.getAsJsonObject("datavalue")
                ?.get("value")?.asString
        } ?: return null
        return fetchCommonsImageUrl(filename)
    }

    private fun fetchCommonsImageUrl(filename: String): String? {
        val encoded = Uri.encode(filename.replace(" ", "_"))
        val url = "https://commons.wikimedia.org/w/api.php" +
                "?action=query&titles=File:$encoded" +
                "&prop=imageinfo&iiprop=url&iiurlwidth=640&format=json"
        return get(url)?.let { body ->
            val pages = gson.fromJson(body, JsonObject::class.java)
                ?.getAsJsonObject("query")
                ?.getAsJsonObject("pages")
            pages?.entrySet()?.firstOrNull()?.value?.asJsonObject
                ?.getAsJsonArray("imageinfo")
                ?.firstOrNull()?.asJsonObject
                ?.get("thumburl")?.asString
        }
    }

    private fun get(url: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "TravelGuideAnywhere/2.0 (Android)")
            .build()
        return try {
            okHttpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        } catch (e: Exception) {
            Log.w(TAG, "HTTP GET failed: $url — ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "PoiImageRepository"
    }
}
