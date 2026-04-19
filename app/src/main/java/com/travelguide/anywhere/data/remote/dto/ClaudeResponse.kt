package com.travelguide.anywhere.data.remote.dto

data class ClaudeResponse(
    val id: String = "",
    val content: List<ClaudeContent> = emptyList(),
    val model: String = "",
    val error: ClaudeError? = null
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
