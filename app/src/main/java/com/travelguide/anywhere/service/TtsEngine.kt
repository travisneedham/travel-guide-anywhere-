package com.travelguide.anywhere.service

interface TtsEngine {
    val isReady: Boolean
    /** True when there is paused content that can be resumed. */
    val canResume: Boolean

    fun speak(
        text: String,
        speechRate: Float,
        onStart: () -> Unit,
        onDone: () -> Unit,
        onError: () -> Unit,
        /** Called when the last chunk has been generated and the engine is idle.
         *  Callers can use this moment to start prewarm() for the next narration. */
        onEnqueued: () -> Unit,
    )

    /** Pre-generate the first audio chunk for [text] while the engine is idle.
     *  If speak() is called shortly after with the same text, chunk 0 is already
     *  prepared and playback begins immediately. No-op by default. */
    fun prewarm(text: String, speechRate: Float) {}

    fun setSpeed(rate: Float)
    fun pause()
    fun resume()
    fun stop()
    fun shutdown()
}
