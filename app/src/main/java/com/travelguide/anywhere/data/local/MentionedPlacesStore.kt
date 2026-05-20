package com.travelguide.anywhere.data.local

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MentionedPlacesStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gson: Gson
) {
    data class Entry(
        val osmId: String,
        val name: String,
        val lat: Double,
        val lon: Double,
        val mentionedAt: Long = System.currentTimeMillis(),
        val summary: String = "",
        val thumbsDown: Boolean = false,
        val autoSkipped: Boolean = false,
        val wantToVisit: Boolean = false,
        val wikipediaUrl: String? = null,
        val tags: Map<String, String> = emptyMap(),
    )

    private val file: File get() = File(context.filesDir, "mentioned_places.json")
    private val _entries = mutableListOf<Entry>()

    val sessionNames = mutableSetOf<String>()

    fun load() {
        _entries.clear()
        sessionNames.clear()
        if (!file.exists()) return
        try {
            val type = object : TypeToken<List<Entry>>() {}.type
            val loaded: List<Entry> = gson.fromJson(file.readText(), type) ?: emptyList()
            _entries.addAll(loaded)
            loaded.forEach { sessionNames.add(it.name) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load mentioned places: ${e.message}")
        }
    }

    fun commitWithSummary(osmId: String, name: String, lat: Double, lon: Double, summary: String, wikipediaUrl: String? = null, tags: Map<String, String> = emptyMap()) {
        val idx = _entries.indexOfFirst { it.osmId == osmId }
        if (idx < 0) {
            _entries.add(Entry(osmId, name, lat, lon, System.currentTimeMillis(), summary, wikipediaUrl = wikipediaUrl, tags = tags))
            save()
        } else if (summary.isNotBlank() && _entries[idx].summary.isBlank()) {
            _entries[idx] = _entries[idx].copy(
                summary = summary,
                wikipediaUrl = wikipediaUrl ?: _entries[idx].wikipediaUrl,
                tags = if (tags.isNotEmpty()) tags else _entries[idx].tags,
            )
            save()
        }
    }

    // Commit immediately (e.g. when user taps a now-playing action before the 10s timer).
    // No-op if already committed.
    fun commitEarly(osmId: String, name: String, lat: Double, lon: Double, summary: String, wikipediaUrl: String? = null, tags: Map<String, String> = emptyMap()) {
        if (_entries.none { it.osmId == osmId }) {
            _entries.add(Entry(osmId, name, lat, lon, System.currentTimeMillis(), summary, wikipediaUrl = wikipediaUrl, tags = tags))
            save()
        }
    }

    fun commit(osmId: String, name: String, lat: Double, lon: Double, tags: Map<String, String> = emptyMap()) {
        if (_entries.none { it.osmId == osmId }) {
            _entries.add(Entry(osmId, name, lat, lon, tags = tags))
            save()
        }
    }

    fun commitAutoSkipped(osmId: String, name: String, lat: Double, lon: Double, summary: String, tags: Map<String, String> = emptyMap()) {
        if (_entries.none { it.osmId == osmId }) {
            _entries.add(Entry(osmId, name, lat, lon, System.currentTimeMillis(), summary, autoSkipped = true, tags = tags))
            save()
        }
    }

    fun markThumbsDown(osmId: String) {
        val idx = _entries.indexOfFirst { it.osmId == osmId }
        if (idx >= 0) {
            _entries[idx] = _entries[idx].copy(thumbsDown = !_entries[idx].thumbsDown)
            save()
        }
    }

    fun markWantToVisit(osmId: String) {
        val idx = _entries.indexOfFirst { it.osmId == osmId }
        if (idx >= 0) {
            _entries[idx] = _entries[idx].copy(wantToVisit = !_entries[idx].wantToVisit)
            save()
        }
    }

    fun thumbsDownEntries(): List<Entry> = _entries.filter { it.thumbsDown && it.summary.isNotBlank() }

    fun clear() {
        _entries.clear()
        sessionNames.clear()
        try { file.delete() } catch (e: Exception) {
            Log.w(TAG, "Failed to delete store file: ${e.message}")
        }
    }

    fun recentFive(): List<Entry> = _entries.sortedByDescending { it.mentionedAt }.take(5)

    fun allSorted(): List<Entry> = _entries.sortedByDescending { it.mentionedAt }

    fun isNameMentioned(name: String): Boolean = sessionNames.any { mentioned ->
        name.contains(mentioned, ignoreCase = true) ||
        mentioned.contains(name, ignoreCase = true)
    }

    private fun save() {
        try {
            file.writeText(gson.toJson(_entries))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save mentioned places: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "MentionedPlacesStore"
    }
}
