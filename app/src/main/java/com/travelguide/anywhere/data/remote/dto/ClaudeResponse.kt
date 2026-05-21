package com.travelguide.anywhere.data.remote.dto

import com.google.gson.annotations.SerializedName

data class ClaudeResponse(
    val id: String = "",
    val content: List<ClaudeContent> = emptyList(),
    val model: String = "",
    val error: ClaudeError? = null,
    val usage: ClaudeUsage? = null,
) {
    val text: String get() = content.firstOrNull { it.type == "text" }?.text ?: ""
}

data class ClaudeContent(
    val type: String,
    val text: String = ""
)

data class ClaudeError(
    val type: String,
    val message: String
)

data class ClaudeUsage(
    @SerializedName("input_tokens") val inputTokens: Int = 0,
    @SerializedName("output_tokens") val outputTokens: Int = 0,
)
