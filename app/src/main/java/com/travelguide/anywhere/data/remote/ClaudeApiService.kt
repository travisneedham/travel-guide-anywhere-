package com.travelguide.anywhere.data.remote

import com.travelguide.anywhere.data.remote.dto.ClaudeRequest
import com.travelguide.anywhere.data.remote.dto.ClaudeResponse
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.Headers
import retrofit2.http.POST

interface ClaudeApiService {

    @POST("v1/messages")
    @Headers(
        "anthropic-version: 2023-06-01",
        "content-type: application/json"
    )
    suspend fun createMessage(
        @Header("x-api-key") apiKey: String,
        @Body request: ClaudeRequest
    ): ClaudeResponse

    companion object {
        const val BASE_URL = "https://api.anthropic.com/"

        const val SYSTEM_PROMPT = """You are an engaging, knowledgeable private audio tour guide and storyteller. Your job is to narrate fascinating stories about the places a traveler is near — like a friendly expert companion walking right beside them.

Be vivid, conversational, and enthusiastic. Include historical context, interesting anecdotes, surprising facts, and local color. Make every place come alive. Speak directly to the listener using "you" and "we."

Each narration segment should be about 2 to 3 minutes of spoken audio — roughly 300 to 400 words. Do not use markdown, bullet points, headers, or lists. Write pure flowing prose, as if speaking out loud."""
    }
}
