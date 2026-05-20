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
     * Returns a Wikipedia article URL for the POI, or null if none can be resolved.
     * Tries the OSM `wikipedia` tag first (no network), then queries the Wikidata sitelinks
     * API when only a `wikidata` tag is present.
     */
    suspend fun fetchWikipediaUrl(poi: PlaceOfInterest): String? = withContext(Dispatchers.IO) {
        val wp = poi.tags["wikipedia"]
        if (wp != null) {
            val colon = wp.indexOf(':')
            if (colon >= 0) {
                val lang = wp.substring(0, colon)
                val title = wp.substring(colon + 1).replace(' ', '_')
                return@withContext "https://$lang.wikipedia.org/wiki/$title"
            }
        }
        val wd = poi.tags["wikidata"] ?: return@withContext null
        fetchWikidataWikiUrl(wd)
    }

    private fun fetchWikidataWikiUrl(qid: String): String? {
        val url = "https://www.wikidata.org/w/api.php" +
                "?action=wbgetentities&ids=$qid&props=sitelinks&sitefilter=enwiki&format=json"
        return get(url)?.let { body ->
            gson.fromJson(body, JsonObject::class.java)
                ?.getAsJsonObject("entities")
                ?.getAsJsonObject(qid)
                ?.getAsJsonObject("sitelinks")
                ?.getAsJsonObject("enwiki")
                ?.get("title")?.asString
                ?.replace(' ', '_')
                ?.let { title -> "https://en.wikipedia.org/wiki/$title" }
        }
    }

    /**
     * Returns a displayable image URL for the POI, or null if none is found.
     * Priority: wikipedia tag → wikidata P18 → wikimedia_commons File tag
     */
    suspend fun fetchImageUrl(poi: PlaceOfInterest): String? = withContext(Dispatchers.IO) {
        val wp = poi.tags["wikipedia"]
        val wd = poi.tags["wikidata"]
        val wc = poi.tags["wikimedia_commons"]
        Log.d(TAG, "${poi.name}: wikipedia=$wp  wikidata=$wd  wikimedia_commons=$wc")
        try {
            wp?.let { tag ->
                val url = fetchWikipediaImage(tag)
                Log.d(TAG, "${poi.name}: wikipedia→$url")
                url?.let { return@withContext it }
            }
            wd?.let { qid ->
                val url = fetchWikidataImage(qid)
                Log.d(TAG, "${poi.name}: wikidata($qid)→$url")
                url?.let { return@withContext it }
            }
            wc?.takeIf { it.startsWith("File:") }
                ?.removePrefix("File:")
                ?.let { filename ->
                    val url = fetchCommonsImageUrl(filename)
                    Log.d(TAG, "${poi.name}: wikimedia_commons($filename)→$url")
                    url?.let { return@withContext it }
                }
        } catch (e: Exception) {
            Log.w(TAG, "Image fetch failed for ${poi.name}: ${e.message}")
        }
        Log.d(TAG, "${poi.name}: no image found")
        null
    }

    private fun fetchWikipediaImage(tag: String): String? {
        val colon = tag.indexOf(':')
        if (colon < 0) return null
        val lang = tag.substring(0, colon)
        val title = tag.substring(colon + 1)
        // Use the MediaWiki pageimages API — more reliable than the REST summary endpoint,
        // which only includes "thumbnail" when a lead image is explicitly configured on the
        // article. pageimages returns the designated representative image for any article.
        val url = "https://$lang.wikipedia.org/w/api.php" +
                "?action=query&titles=${Uri.encode(title)}&prop=pageimages&pithumbsize=640&format=json"
        return get(url)?.let { body ->
            val pages = gson.fromJson(body, JsonObject::class.java)
                ?.getAsJsonObject("query")
                ?.getAsJsonObject("pages")
            pages?.entrySet()?.firstOrNull()?.value?.asJsonObject
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
