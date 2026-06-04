package com.travelguide.anywhere.data.remote.dto

import com.google.gson.annotations.SerializedName

data class ClaudeRequest(
    val model: String = "claude-sonnet-4-6",
    @SerializedName("max_tokens") val maxTokens: Int = 700,
    val system: String,
    val messages: List<ClaudeMessage>,
    val tools: List<ClaudeTool>? = null,
)

data class ClaudeMessage(
    val role: String,
    val content: String
)

data class ClaudeTool(
    val type: String,
    val name: String,
    @SerializedName("max_uses") val maxUses: Int? = null,
)
