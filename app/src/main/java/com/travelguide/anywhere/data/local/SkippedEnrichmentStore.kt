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
class SkippedEnrichmentStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gson: Gson
) {
    data class Entry(
        val skippedAt: Long = System.currentTimeMillis(),
        val source: String,
        val poiName: String,
        val osmId: String,
        val qid: String?,
        val resolvedTitle: String,
        val reason: String,
    )

    private val file: File get() = File(context.filesDir, "skipped_enrichments.json")
    private val _entries = mutableListOf<Entry>()

    init {
        if (file.exists()) {
            try {
                val type = object : TypeToken<List<Entry>>() {}.type
                val loaded: List<Entry> = gson.fromJson(file.readText(), type) ?: emptyList()
                _entries.addAll(loaded)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load skipped enrichments: ${e.message}")
            }
        }
    }

    fun record(source: String, poiName: String, osmId: String, qid: String?, resolvedTitle: String, reason: String) {
        val isDupe = _entries.any { it.osmId == osmId && it.source == source && it.resolvedTitle == resolvedTitle }
        if (isDupe) return
        _entries.add(Entry(System.currentTimeMillis(), source, poiName, osmId, qid, resolvedTitle, reason))
        if (_entries.size > 500) _entries.subList(0, _entries.size - 500).clear()
        save()
    }

    fun allSorted(): List<Entry> = _entries.sortedByDescending { it.skippedAt }

    fun clear() {
        _entries.clear()
        try { file.delete() } catch (e: Exception) {
            Log.w(TAG, "Failed to delete skipped enrichments file: ${e.message}")
        }
    }

    private fun save() {
        try {
            file.writeText(gson.toJson(_entries))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save skipped enrichments: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "SkippedEnrichmentStore"
    }
}
