package com.travelguide.anywhere.service

interface TtsEngine {
    val isReady: Boolean
    /** True when there is paused content that can be resumed. */
    val canResume: Boolean

    fun speak(text: String, speechRate: Float, onStart: () -> Unit, onDone: () -> Unit, onError: () -> Unit)
    fun pause()
    fun resume()
    fun stop()
    fun shutdown()
}
