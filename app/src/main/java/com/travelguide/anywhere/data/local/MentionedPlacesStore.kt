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
        val mentionedAt: Long = System.currentTimeMillis()
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

    // Persists a POI to disk. No-op if already in the file (by osmId).
    fun commit(osmId: String, name: String, lat: Double, lon: Double) {
        if (_entries.none { it.osmId == osmId }) {
            _entries.add(Entry(osmId, name, lat, lon, System.currentTimeMillis()))
            save()
        }
    }

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
