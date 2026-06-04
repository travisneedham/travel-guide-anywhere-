package com.travelguide.anywhere.data.remote.dto

import com.google.gson.annotations.SerializedName

data class ClaudeResponse(
    val id: String = "",
    val content: List<ClaudeContent> = emptyList(),
    val model: String = "",
    val error: ClaudeError? = null,
    val usage: ClaudeUsage? = null,
) {
    // When web search is used, the response contains pre-search commentary text blocks followed by
    // server_tool_use and web_search_tool_result blocks, then the final answer text blocks.
    // Skip everything up to and including the last search result block.
    val text: String get() {
        val lastSearchIdx = content.indexOfLast { it.type == "web_search_tool_result" }
        return if (lastSearchIdx >= 0) {
            content.drop(lastSearchIdx + 1).filter { it.type == "text" }.joinToString("") { it.text }
        } else {
            content.filter { it.type == "text" }.joinToString("") { it.text }
        }
    }
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
