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
    )

    private val file: File get() = File(context.filesDir, "mentioned_places.json")
    private val _entries = mutableListOf<Entry>()

    // Names selected this session (in-memory). Prevents same-session repeats
    // even for POIs not yet committed to disk (played < 10s and not skipped).
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

    // Persists a narrated POI with its AI-generated summary.
    fun commitWithSummary(osmId: String, name: String, lat: Double, lon: Double, summary: String) {
        if (_entries.none { it.osmId == osmId }) {
            _entries.add(Entry(osmId, name, lat, lon, System.currentTimeMillis(), summary))
            save()
        }
    }

    // Persists a skipped POI (no summary available).
    fun commit(osmId: String, name: String, lat: Double, lon: Double) {
        if (_entries.none { it.osmId == osmId }) {
            _entries.add(Entry(osmId, name, lat, lon))
            save()
        }
    }

    // Persists a POI that was auto-skipped due to similarity with disliked places.
    fun commitAutoSkipped(osmId: String, name: String, lat: Double, lon: Double, summary: String) {
        if (_entries.none { it.osmId == osmId }) {
            _entries.add(Entry(osmId, name, lat, lon, System.currentTimeMillis(), summary, autoSkipped = true))
            save()
        }
    }

    // Toggles the thumbsDown flag for an entry and persists.
    fun markThumbsDown(osmId: String) {
        val idx = _entries.indexOfFirst { it.osmId == osmId }
        if (idx >= 0) {
            _entries[idx] = _entries[idx].copy(thumbsDown = !_entries[idx].thumbsDown)
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
