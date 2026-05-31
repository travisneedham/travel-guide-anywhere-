package com.travelguide.anywhere.repository

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
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
 * Runs in this order so the most fragile calls (Wikidata + Pageviews) land while the
 * network is fresh, before the long Overpass branch probes have a chance to tire it out:
 *
 *   1. Wikidata sitelinks response shape
 *   2. Wikipedia Pageviews response shape
 *   3. OTM /places/kinds catalog (404 probe)
 *   4. OTM single-kind validity probe
 *   5. OTM combined-kinds-string probe (including the v3.4.2 fixed string)
 *   6. Overpass per-branch timing (each branch isolated)
 *   7. Full production famous query
 *
 * Read the results afterwards with Settings → Export Full Log File.
 */
@Singleton
class PoiExperiment @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val gson: Gson,
    private val prefs: SharedPreferences,
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    suspend fun run(
        lat: Double,
        lon: Double,
        radiusMeters: Int = 64373,
        onProgress: (String) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        fun progress(step: String) {
            log(step)
            mainHandler.post { onProgress(step) }
        }

        log("══════════════ POI EXPERIMENT START ══════════════")
        log("when=${Date()}  location=($lat, $lon)  radius=${radiusMeters}m (~${radiusMeters / 1609} mi)")
        val otmKey = prefs.getString(PoiRepository.PREF_OPENTRIPMAP_KEY, "") ?: ""
        log("otmKeyPresent=${otmKey.isNotBlank()}")

        // ── 1 & 2: Wiki enrichment FIRST while the network is fresh ──────────
        progress("Wiki: Wikidata sitelinks…")
        testWikidata()

        progress("Wiki: Wikipedia Pageviews…")
        testPageviews()

        // ── 3–5: OpenTripMap probes ───────────────────────────────────────────
        if (otmKey.isNotBlank()) {
            progress("OTM: kinds catalog…")
            dumpOtmKindsCatalog(otmKey)

            testOtmKindsIndividually(lat, lon, radiusMeters, otmKey) { idx, total, kind ->
                mainHandler.post { onProgress("OTM: kind $idx/$total ($kind)…") }
            }

            progress("OTM: combined strings…")
            testOtmCombinedStrings(lat, lon, radiusMeters, otmKey)
        } else {
            log("[OTM] skipped — no OpenTripMap API key set in Settings")
        }

        // ── 6 & 7: Overpass branch timing ────────────────────────────────────
        testOverpassBranches(lat, lon, radiusMeters) { idx, total, name ->
            mainHandler.post { onProgress("Overpass: branch $idx/$total ($name)…") }
        }

        log("══════════════ POI EXPERIMENT DONE ══════════════")
    }

    // ── Wikidata ─────────────────────────────────────────────────────────────

    private fun testWikidata() {
        log("──── Wikidata sitelinks response shape ────")
        val qids = "Q243,Q9202" // Eiffel Tower, Empire State Building — stable references
        val url = "https://www.wikidata.org/w/api.php?action=wbgetentities&ids=$qids&props=sitelinks&format=json"
        val (code, ms, body) = httpGet(url)
        log("wbgetentities $qids -> HTTP $code (${ms}ms)")
        logLong("  wd", body, max = 2000)
    }

    // ── Wikipedia Pageviews ──────────────────────────────────────────────────

    private fun testPageviews() {
        log("──── Wikipedia Pageviews response shape ────")
        val (start, end) = pageviewsRange()
        val url = "https://wikimedia.org/api/rest_v1/metrics/pageviews/per-article/" +
            "en.wikipedia/all-access/all-agents/Eiffel_Tower/monthly/$start/$end"
        val (code, ms, body) = httpGet(url)
        log("pageviews Eiffel_Tower $start..$end -> HTTP $code (${ms}ms)")
        logLong("  pv", body, max = 2000)
    }

    private fun pageviewsRange(): Pair<String, String> {
        val cal = Calendar.getInstance()
        cal.add(Calendar.MONTH, -1)
        val ey = cal.get(Calendar.YEAR); val em = cal.get(Calendar.MONTH) + 1
        cal.add(Calendar.MONTH, -2)
        val sy = cal.get(Calendar.YEAR); val sm = cal.get(Calendar.MONTH) + 1
        return "%04d%02d0100".format(sy, sm) to "%04d%02d0100".format(ey, em)
    }

    // ── OpenTripMap ──────────────────────────────────────────────────────────

    private fun dumpOtmKindsCatalog(otmKey: String) {
        log("──── OTM /places/kinds (authoritative taxonomy) ────")
        val url = "https://api.opentripmap.com/0.1/en/places/kinds?format=json&apikey=$otmKey"
        val (code, ms, body) = httpGet(url)
        log("GET /places/kinds -> HTTP $code in ${ms}ms")
        logLong("kinds", body, max = 12000)
    }

    private suspend fun testOtmKindsIndividually(
        lat: Double, lon: Double, radius: Int, otmKey: String,
        onKind: (idx: Int, total: Int, kind: String) -> Unit,
    ) {
        log("──── OTM single-kind validity probe (one request per candidate) ────")
        val total = CANDIDATE_KINDS.size
        for ((idx, kind) in CANDIDATE_KINDS.withIndex()) {
            onKind(idx + 1, total, kind)
            val url = "https://api.opentripmap.com/0.1/en/places/radius?radius=$radius&lon=$lon&lat=$lat" +
                "&kinds=$kind&rate=1&format=json&limit=5&apikey=$otmKey"
            var (code, ms, body) = httpGet(url)
            if (code == 429) {
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
            "v3.3.9_broken"     to "interesting_places,museums,historic,architecture,stadiums,zoos,aquariums,amusements,beaches,natural,gardens",
            "v3.4.2_fixed"      to "interesting_places,museums,historic,architecture,stadiums,zoos,aquariums,amusements,beaches,natural,gardens_and_parks",
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

    private fun testOverpassBranches(
        lat: Double, lon: Double, radius: Int,
        onBranch: (idx: Int, total: Int, name: String) -> Unit,
    ) {
        log("──── Overpass per-branch timing ([timeout:300] each, isolated) ────")
        val branches = famousBranches(lat, lon, radius)
        val total = branches.size
        for ((idx, pair) in branches.withIndex()) {
            val (name, branchBody) = pair
            onBranch(idx + 1, total, name)
            val query = "[out:json][timeout:300];\n(\n$branchBody\n);\nout body center 300;"
            val (code, ms, body) = overpassPost(query)
            log("  branch=%-24s HTTP %3d  %7dms  elements=%-5s  remark=%s"
                .format(name, code, ms, overpassElementCount(body), overpassRemark(body) ?: "none"))
        }

        // Full production query — all non-alt branches combined.
        log("── full production famous query ([timeout:300]) ──")
        val prod = branches.filterNot { it.first.startsWith("alt_") }.joinToString("\n") { it.second }
        val full = "[out:json][timeout:300];\n(\n$prod\n);\nout body center 300;"
        onBranch(total, total, "FULL")
        val (code, ms, body) = overpassPost(full)
        log("  FULL -> HTTP $code  ${ms}ms  elements=${overpassElementCount(body)}  remark=${overpassRemark(body) ?: "none"}")
    }

    /**
     * Each production famous-query branch plus proposed constrained alt branches to measure
     * their individual cost — especially square_wiki/garden_wiki/marketplace_wiki which
     * returned 0 results in Dallas but may be vital in other cities (Tiananmen Square,
     * Butchart Gardens, Borough Market, etc).
     */
    private fun famousBranches(lat: Double, lon: Double, r: Int): List<Pair<String, String>> {
        val a = "around:$r,$lat,$lon"
        return listOf(
            // ── current production branches ──────────────────────────────────
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
            // ── globally-relevant branches: 0 results in Dallas but potentially vital elsewhere ──
            // (Tiananmen Square, Butchart Gardens, Borough Market, Bondi Beach, K2, etc.)
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
            // ── proposed additional branches (measure cost + yield) ─────────
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

    private fun logLong(prefix: String, text: String, max: Int = 4000) {
        val t = if (text.length > max) text.take(max) + "…(+${text.length - max} more chars)" else text
        t.chunked(3000).forEachIndexed { i, c -> Log.i(TAG, "$prefix[$i] $c") }
    }

    companion object {
        private const val TAG = "PoiExperiment"

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
