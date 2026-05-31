package com.travelguide.anywhere.repository

import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Calendar
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Diagnostic harness for the famous-POI ranking pipeline. Triggered from
 * Settings → NERD STUFF → "Run POI API Experiment".
 *
 * It makes raw calls to OpenTripMap, Overpass, Wikidata, and the Wikipedia Pageviews API and logs
 * full request/response shapes + timings under the tag [PoiExperiment]. The goal is to empirically
 * answer two questions so we can rebuild the queries correctly:
 *
 *   1. Which OpenTripMap `kinds` identifiers are actually valid? (OTM 400s the WHOLE request if any
 *      single kind is invalid, which silently disables OTM.) We dump the authoritative
 *      `/places/kinds` catalog AND probe each candidate kind individually.
 *   2. Which Overpass famous-query branch is responsible for the timeout? We run each branch as its
 *      own query and time it, plus measure the cost of proposed constrained replacements.
 *
 * Read the results afterwards with Settings → Export Full Log File. This module is for testing only
 * and is never called from the normal tour flow.
 */
@Singleton
class PoiExperiment @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val gson: Gson,
    private val prefs: SharedPreferences,
) {
    suspend fun run(lat: Double, lon: Double, radiusMeters: Int = 64373) = withContext(Dispatchers.IO) {
        log("══════════════ POI EXPERIMENT START ══════════════")
        log("when=${Date()}  location=($lat, $lon)  radius=${radiusMeters}m (~${radiusMeters / 1609} mi)")
        val otmKey = prefs.getString(PoiRepository.PREF_OPENTRIPMAP_KEY, "") ?: ""
        log("otmKeyPresent=${otmKey.isNotBlank()}")

        if (otmKey.isNotBlank()) {
            dumpOtmKindsCatalog(otmKey)
            testOtmKindsIndividually(lat, lon, radiusMeters, otmKey)
            testOtmCombinedStrings(lat, lon, radiusMeters, otmKey)
        } else {
            log("[OTM] skipped — no OpenTripMap API key set in Settings")
        }

        testOverpassBranches(lat, lon, radiusMeters)
        testWikiEnrichment()

        log("══════════════ POI EXPERIMENT DONE ══════════════")
    }

    // ── OpenTripMap ──────────────────────────────────────────────────────────

    private fun dumpOtmKindsCatalog(otmKey: String) {
        log("──── OTM /places/kinds (authoritative taxonomy) ────")
        val url = "https://api.opentripmap.com/0.1/en/places/kinds?format=json&apikey=$otmKey"
        val (code, ms, body) = httpGet(url)
        log("GET /places/kinds -> HTTP $code in ${ms}ms")
        logLong("kinds", body, max = 12000)
    }

    private suspend fun testOtmKindsIndividually(lat: Double, lon: Double, radius: Int, otmKey: String) {
        log("──── OTM single-kind validity probe (one request per candidate) ────")
        for (kind in CANDIDATE_KINDS) {
            val url = "https://api.opentripmap.com/0.1/en/places/radius?radius=$radius&lon=$lon&lat=$lat" +
                "&kinds=$kind&rate=1&format=json&limit=5&apikey=$otmKey"
            var (code, ms, body) = httpGet(url)
            if (code == 429) {          // rate-limited: back off and retry once
                delay(7000)
                val retry = httpGet(url); code = retry.first; ms = retry.second; body = retry.third
            }
            val verdict = when (code) {
                200 -> "VALID   (count≈${jsonArraySize(body)})"
                400 -> "INVALID (HTTP 400)"
                429 -> "RATE_LIMITED"
                else -> "HTTP $code"
            }
            log("  kind=%-30s %s  (${ms}ms)".format(kind, verdict))
            if (code != 200 && code != 429) logLong("    body", body, max = 240)
            delay(500)
        }
    }

    private suspend fun testOtmCombinedStrings(lat: Double, lon: Double, radius: Int, otmKey: String) {
        log("──── OTM combined-kinds-string probe ────")
        val strings = listOf(
            "v3.3.8_known_good" to "interesting_places,museums,historic,architecture",
            "v3.3.9_combined"   to "interesting_places,museums,historic,architecture,stadiums,zoos,aquariums,amusements,beaches,natural,gardens",
        )
        for ((label, kinds) in strings) {
            val url = "https://api.opentripmap.com/0.1/en/places/radius?radius=$radius&lon=$lon&lat=$lat" +
                "&kinds=$kinds&rate=1&orderby=rate&format=json&limit=20&apikey=$otmKey"
            val (code, ms, body) = httpGet(url)
            log("  $label -> HTTP $code (${ms}ms)  count≈${jsonArraySize(body)}")
            log("    kinds=$kinds")
            if (code != 200) logLong("    body", body, max = 300)
            delay(700)
        }
    }

    // ── Overpass ───────────────────────────────────────────────────────────

    private fun testOverpassBranches(lat: Double, lon: Double, radius: Int) {
        log("──── Overpass per-branch timing ([timeout:180] each, isolated) ────")
        val branches = famousBranches(lat, lon, radius)
        for ((name, branchBody) in branches) {
            val query = "[out:json][timeout:180];\n(\n$branchBody\n);\nout body center 300;"
            val (code, ms, body) = overpassPost(query)
            log("  branch=%-22s HTTP %3d  %7dms  elements=%-5s  remark=%s"
                .format(name, code, ms, overpassElementCount(body), overpassRemark(body) ?: "none"))
        }

        // Reproduce the full production famous query (production branches only, no alt_ probes).
        log("── full production famous query ([timeout:300]) ──")
        val prod = branches.filterNot { it.first.startsWith("alt_") }.joinToString("\n") { it.second }
        val full = "[out:json][timeout:300];\n(\n$prod\n);\nout body center 300;"
        val (code, ms, body) = overpassPost(full)
        log("  FULL -> HTTP $code  ${ms}ms  elements=${overpassElementCount(body)}  remark=${overpassRemark(body) ?: "none"}")
    }

    /** Each production famous-query branch plus proposed constrained replacements, coords baked in. */
    private fun famousBranches(lat: Double, lon: Double, r: Int): List<Pair<String, String>> {
        val a = "around:$r,$lat,$lon"
        return listOf(
            // ── current production branches ──
            "wikipedia_catchall" to
                "  node[\"name\"][\"wikipedia\"][!\"shop\"][\"place\"!~\"city|town|village|hamlet|suburb|county|state|country|region|district|municipality|borough\"]($a);",
            "tourism" to
                "  node[\"name\"][\"tourism\"~\"attraction|museum|zoo|theme_park|aquarium|gallery|artwork\"]($a);\n" +
                "  way[\"name\"][\"tourism\"~\"attraction|museum|zoo|theme_park|aquarium|gallery|artwork\"]($a);",
            "heritage" to
                "  node[\"name\"][\"heritage\"]($a);\n  way[\"name\"][\"heritage\"]($a);",
            "historic" to
                "  node[\"name\"][\"historic\"~\"castle|monument|archaeological_site|ruins|memorial|palace|city_wall|city_gate|pagoda|temple\"]($a);\n" +
                "  way[\"name\"][\"historic\"~\"castle|monument|archaeological_site|ruins|memorial|palace|city_wall|city_gate|pagoda|temple\"]($a);",
            "stadium" to
                "  node[\"name\"][\"leisure\"=\"stadium\"]($a);\n  way[\"name\"][\"leisure\"=\"stadium\"]($a);",
            "square_wiki" to
                "  node[\"name\"][\"place\"=\"square\"][\"wikipedia\"]($a);\n  way[\"name\"][\"place\"=\"square\"][\"wikipedia\"]($a);",
            "garden_wiki" to
                "  node[\"name\"][\"leisure\"=\"garden\"][\"wikipedia\"]($a);\n  way[\"name\"][\"leisure\"=\"garden\"][\"wikipedia\"]($a);",
            "marketplace_wiki" to
                "  node[\"name\"][\"amenity\"=\"marketplace\"][\"wikipedia\"]($a);\n  way[\"name\"][\"amenity\"=\"marketplace\"][\"wikipedia\"]($a);",
            "beach_wiki" to
                "  node[\"name\"][\"natural\"=\"beach\"][\"wikipedia\"]($a);\n  way[\"name\"][\"natural\"=\"beach\"][\"wikipedia\"]($a);",
            "peak_wiki" to
                "  node[\"name\"][\"natural\"=\"peak\"][\"wikipedia\"]($a);",
            "nature_reserve_wiki" to
                "  node[\"name\"][\"leisure\"=\"nature_reserve\"][\"wikipedia\"]($a);\n  way[\"name\"][\"leisure\"=\"nature_reserve\"][\"wikipedia\"]($a);",
            "aerialway" to
                "  node[\"name\"][\"aerialway\"~\"gondola|cable_car|funicular\"]($a);\n  way[\"name\"][\"aerialway\"~\"gondola|cable_car|funicular\"]($a);",
            "cemetery_wiki" to
                "  way[\"name\"][\"landuse\"=\"cemetery\"][\"wikipedia\"]($a);",
            // ── proposed constrained replacements for the catch-all (measure their cost) ──
            "alt_man_made_wiki" to
                "  node[\"name\"][\"man_made\"~\"tower|bridge|lighthouse|obelisk|dam\"][\"wikipedia\"]($a);\n" +
                "  way[\"name\"][\"man_made\"~\"tower|bridge|lighthouse|obelisk|dam\"][\"wikipedia\"]($a);",
            "alt_worship_uni_wiki" to
                "  node[\"name\"][\"amenity\"~\"place_of_worship|university\"][\"wikipedia\"]($a);\n" +
                "  way[\"name\"][\"amenity\"~\"place_of_worship|university\"][\"wikipedia\"]($a);",
            "alt_viewpoint_wiki" to
                "  node[\"name\"][\"tourism\"=\"viewpoint\"][\"wikipedia\"]($a);",
            "alt_historic_any_wiki" to
                "  node[\"name\"][\"historic\"][\"wikipedia\"]($a);\n  way[\"name\"][\"historic\"][\"wikipedia\"]($a);",
            "alt_building_wiki" to
                "  node[\"name\"][\"building\"][\"wikipedia\"]($a);\n  way[\"name\"][\"building\"][\"wikipedia\"]($a);",
        )
    }

    // ── Wikidata + Wikipedia ─────────────────────────────────────────────────

    private fun testWikiEnrichment() {
        log("──── Wikidata sitelinks response shape ────")
        val qids = "Q243,Q9202" // Eiffel Tower, Empire State Building — stable references
        val wdUrl = "https://www.wikidata.org/w/api.php?action=wbgetentities&ids=$qids&props=sitelinks&format=json"
        val (c1, ms1, b1) = httpGet(wdUrl)
        log("wbgetentities $qids -> HTTP $c1 (${ms1}ms)")
        logLong("  wd", b1, max = 1500)

        log("──── Wikipedia Pageviews response shape ────")
        val (start, end) = pageviewsRange()
        val pvUrl = "https://wikimedia.org/api/rest_v1/metrics/pageviews/per-article/" +
            "en.wikipedia/all-access/all-agents/Eiffel_Tower/monthly/$start/$end"
        val (c2, ms2, b2) = httpGet(pvUrl)
        log("pageviews Eiffel_Tower $start..$end -> HTTP $c2 (${ms2}ms)")
        logLong("  pv", b2, max = 1500)
    }

    private fun pageviewsRange(): Pair<String, String> {
        val cal = Calendar.getInstance()
        cal.add(Calendar.MONTH, -1)
        val ey = cal.get(Calendar.YEAR); val em = cal.get(Calendar.MONTH) + 1
        cal.add(Calendar.MONTH, -2)
        val sy = cal.get(Calendar.YEAR); val sm = cal.get(Calendar.MONTH) + 1
        return "%04d%02d0100".format(sy, sm) to "%04d%02d0100".format(ey, em)
    }

    // ── HTTP + parsing helpers ───────────────────────────────────────────────

    private fun httpGet(url: String): Triple<Int, Long, String> {
        val t0 = System.currentTimeMillis()
        return try {
            val req = Request.Builder().url(url)
                .header("User-Agent", "TravelGuideAnywhere/2.0 (Android; travisneedham@gmail.com)")
                .build()
            okHttpClient.newCall(req).execute().use { r ->
                Triple(r.code, System.currentTimeMillis() - t0, r.body?.string() ?: "")
            }
        } catch (e: Exception) {
            Triple(-1, System.currentTimeMillis() - t0, "EXCEPTION: ${e.message}")
        }
    }

    private fun overpassPost(query: String): Triple<Int, Long, String> {
        val t0 = System.currentTimeMillis()
        return try {
            val body = FormBody.Builder().add("data", query).build()
            val req = Request.Builder()
                .url("https://overpass-api.de/api/interpreter")
                .post(body)
                .header("Accept", "*/*")
                .header("User-Agent", "TravelGuideAnywhere/2.0 (Android)")
                .build()
            okHttpClient.newCall(req).execute().use { r ->
                Triple(r.code, System.currentTimeMillis() - t0, r.body?.string() ?: "")
            }
        } catch (e: Exception) {
            Triple(-1, System.currentTimeMillis() - t0, "EXCEPTION: ${e.message}")
        }
    }

    private fun jsonArraySize(body: String): String = try {
        gson.fromJson(body, JsonArray::class.java)?.size()?.toString() ?: "?"
    } catch (e: Exception) { "?" }

    private fun overpassElementCount(body: String): String = try {
        gson.fromJson(body, JsonObject::class.java)?.getAsJsonArray("elements")?.size()?.toString() ?: "?"
    } catch (e: Exception) { "?" }

    private fun overpassRemark(body: String): String? = try {
        gson.fromJson(body, JsonObject::class.java)?.get("remark")?.asString
    } catch (e: Exception) { null }

    private fun log(msg: String) = Log.i(TAG, msg)

    /** logcat caps each line near 4 KB — chunk long bodies so nothing is silently dropped. */
    private fun logLong(prefix: String, text: String, max: Int = 4000) {
        val t = if (text.length > max) text.take(max) + "…(+${text.length - max} more chars)" else text
        t.chunked(3000).forEachIndexed { i, c -> Log.i(TAG, "$prefix[$i] $c") }
    }

    companion object {
        private const val TAG = "PoiExperiment"

        /** Candidate OTM kinds to validate one-by-one. */
        private val CANDIDATE_KINDS = listOf(
            "interesting_places", "museums", "historic", "architecture", "cultural", "religion",
            "natural", "beaches", "water", "geological_formations", "nature_reserves",
            "amusements", "theme_parks", "water_parks", "zoos", "aquariums",
            "sport", "stadiums", "swimming_pools",
            "gardens", "gardens_and_parks", "urban_environment",
            "view_points", "monuments_and_memorials", "fortifications", "castles",
            "other_buildings_and_structures", "towers", "bridges", "skyscrapers",
        )
    }
}
