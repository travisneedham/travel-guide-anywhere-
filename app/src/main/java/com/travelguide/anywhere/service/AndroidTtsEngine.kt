package com.travelguide.anywhere.service

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

class AndroidTtsEngine(context: Context) : TtsEngine {

    private var tts: TextToSpeech? = null
    override var isReady = false
        private set

    private var savedChunks: List<String> = emptyList()
    private var currentChunkIndex = 0
    private var savedSpeechRate = 0.95f
    private var onDoneCallback: (() -> Unit)? = null
    private var onErrorCallback: (() -> Unit)? = null

    override val canResume: Boolean get() = savedChunks.isNotEmpty()

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                isReady = true
            }
        }
    }

    override fun speak(text: String, speechRate: Float, onDone: () -> Unit, onError: () -> Unit) {
        val chunks = splitIntoChunks(text)
        savedChunks = chunks
        currentChunkIndex = 0
        savedSpeechRate = speechRate
        onDoneCallback = onDone
        onErrorCallback = onError

        tts?.setSpeechRate(speechRate)
        tts?.setOnUtteranceProgressListener(buildListener())
        enqueueChunks(chunks, startIndex = 0)
    }

    override fun pause() {
        tts?.setOnUtteranceProgressListener(null)
        tts?.stop()
    }

    override fun resume() {
        if (savedChunks.isEmpty()) return
        val remaining = savedChunks.drop(currentChunkIndex)
        if (remaining.isEmpty()) {
            savedChunks = emptyList()
            onDoneCallback?.invoke()
            return
        }
        tts?.setSpeechRate(savedSpeechRate)
        tts?.setOnUtteranceProgressListener(buildListener())
        enqueueChunks(remaining, startIndex = currentChunkIndex)
    }

    override fun stop() {
        tts?.setOnUtteranceProgressListener(null)
        tts?.stop()
        savedChunks = emptyList()
        currentChunkIndex = 0
        onDoneCallback = null
        onErrorCallback = null
    }

    override fun shutdown() {
        tts?.shutdown()
        tts = null
    }

    private fun buildListener() = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            utteranceId?.removePrefix("chunk_")?.toIntOrNull()?.let { currentChunkIndex = it }
        }
        override fun onDone(utteranceId: String?) {
            if (utteranceId == LAST_UTTERANCE_ID) {
                savedChunks = emptyList()
                onDoneCallback?.invoke()
            }
        }
        override fun onError(utteranceId: String?) {
            savedChunks = emptyList()
            onErrorCallback?.invoke()
        }
    }

    private fun enqueueChunks(chunks: List<String>, startIndex: Int) {
        try {
            chunks.forEachIndexed { i, chunk ->
                val globalIndex = startIndex + i
                val utteranceId = if (globalIndex == savedChunks.lastIndex) LAST_UTTERANCE_ID else "chunk_$globalIndex"
                tts?.speak(chunk, TextToSpeech.QUEUE_ADD, null, utteranceId)
            }
        } catch (e: Exception) {
            Log.w(TAG, "TTS enqueue failed: ${e.message}")
            onErrorCallback?.invoke()
        }
    }

    private fun splitIntoChunks(text: String, maxLength: Int = 3800): List<String> {
        if (text.length <= maxLength) return listOf(text)
        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val end = minOf(start + maxLength, text.length)
            val splitAt = if (end < text.length) {
                text.lastIndexOf(". ", end).takeIf { it > start } ?: end
            } else end
            chunks.add(text.substring(start, splitAt).trim())
            start = splitAt
        }
        return chunks.filter { it.isNotBlank() }
    }

    companion object {
        private const val TAG = "AndroidTtsEngine"
        private const val LAST_UTTERANCE_ID = "last_utterance"
    }
}
