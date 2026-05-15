package com.travelguide.anywhere.data.local

import android.content.Context
import android.location.Location
import android.util.Log
import com.google.gson.Gson
import com.travelguide.anywhere.data.remote.dto.ClaudeMessage
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists the last [MAX_PAIRS] narration exchanges (user prompt + assistant text) so
 * that each new Claude call includes conversation history. This prevents repetition of
 * city/area context across successive narrations.
 *
 * History is tied to a geographic anchor (the location of the first narration in the
 * current window). It expires after [DEFAULT_EXPIRY_DAYS] days of inactivity (configurable
 * in Settings) and resets automatically when the user moves more than [radiusMiles] from
 * the anchor.
 */
@Singleton
class NarrationHistoryStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val gson: Gson,
) {
    private data class StoredMessage(val role: String, val content: String)

    private data class HistoryState(
        val anchorLat: Double,
        val anchorLon: Double,
        val lastNarrationAt: Long,
        val messages: List<StoredMessage>,
    )

    private val file: File get() = File(context.filesDir, "narration_history.json")
    private var state: HistoryState? = null

    fun load() {
        state = null
        if (!file.exists()) return
        try {
            state = gson.fromJson(file.readText(), HistoryState::class.java)
            Log.i(TAG, "Loaded ${(state?.messages?.size ?: 0) / 2} narration(s) from history")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load narration history: ${e.message}")
        }
    }

    /**
     * Returns the stored conversation as a list of [ClaudeMessage] pairs ready to prepend
     * to a new Claude request, or empty if history has expired or the user has moved away.
     */
    fun getMessages(
        currentLat: Double,
        currentLon: Double,
        radiusMiles: Float,
        expiryDays: Int,
    ): List<ClaudeMessage> {
        val s = state ?: return emptyList()

        val ageDays = (System.currentTimeMillis() - s.lastNarrationAt) / 86_400_000L
        if (ageDays >= expiryDays) {
            Log.i(TAG, "History expired ($ageDays days old, limit=$expiryDays). Resetting.")
            clear()
            return emptyList()
        }

        val results = FloatArray(1)
        Location.distanceBetween(currentLat, currentLon, s.anchorLat, s.anchorLon, results)
        val distMiles = results[0] / 1609.34f
        if (distMiles > radiusMiles) {
            Log.i(TAG, "Moved ${"%.2f".format(distMiles)} mi from anchor (limit=${"%.2f".format(radiusMiles)} mi). Resetting history.")
            clear()
            return emptyList()
        }

        Log.d(TAG, "Injecting ${s.messages.size / 2} history pair(s) into Claude request")
        return s.messages.map { ClaudeMessage(role = it.role, content = it.content) }
    }

    /**
     * Appends a completed narration exchange. Sets the geographic anchor on the first
     * call after a reset. Trims the window to the last [MAX_PAIRS] pairs.
     */
    fun append(userMessage: String, assistantMessage: String, lat: Double, lon: Double) {
        val current = state
        val anchorLat = current?.anchorLat ?: lat
        val anchorLon = current?.anchorLon ?: lon

        val all = (current?.messages ?: emptyList()) +
            listOf(StoredMessage("user", userMessage), StoredMessage("assistant", assistantMessage))
        val trimmed = if (all.size > MAX_PAIRS * 2) all.takeLast(MAX_PAIRS * 2) else all

        state = HistoryState(
            anchorLat = anchorLat,
            anchorLon = anchorLon,
            lastNarrationAt = System.currentTimeMillis(),
            messages = trimmed,
        )
        save()
        Log.d(TAG, "History now ${trimmed.size / 2} pair(s), anchor=(${anchorLat}, ${anchorLon})")
    }

    fun clear() {
        state = null
        try { file.delete() } catch (e: Exception) {
            Log.w(TAG, "Failed to delete history file: ${e.message}")
        }
    }

    val pairCount: Int get() = (state?.messages?.size ?: 0) / 2

    private fun save() {
        try { file.writeText(gson.toJson(state)) } catch (e: Exception) {
            Log.w(TAG, "Failed to save narration history: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "NarrationHistoryStore"
        const val MAX_PAIRS = 10
        const val PREF_EXPIRY_DAYS = "pref_history_expiry_days"
        const val DEFAULT_EXPIRY_DAYS = 30
    }
}
