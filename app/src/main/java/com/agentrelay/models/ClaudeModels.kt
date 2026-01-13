package com.agentrelay.models

import com.google.gson.annotations.SerializedName

// Request models
data class ClaudeRequest(
    val model: String,
    @SerializedName("max_tokens")
    val maxTokens: Int,
    val messages: List<Message>,
    val system: String? = null
)

data class Message(
    val role: String,
    val content: List<ContentBlock>
)

sealed class ContentBlock {
    data class TextContent(
        val type: String = "text",
        val text: String
    ) : ContentBlock()

    data class ImageContent(
        val type: String = "image",
        val source: ImageSource
    ) : ContentBlock()
}

data class ImageSource(
    val type: String = "base64",
    @SerializedName("media_type")
    val mediaType: String = "image/png",
    val data: String
)

// Response models
data class ClaudeResponse(
    val id: String,
    val type: String,
    val role: String,
    val content: List<ResponseContent>,
    val model: String,
    @SerializedName("stop_reason")
    val stopReason: String?,
    val usage: Usage
)

data class ResponseContent(
    val type: String,
    val text: String?
)

data class Usage(
    @SerializedName("input_tokens")
    val inputTokens: Int,
    @SerializedName("output_tokens")
    val outputTokens: Int
)

// Action models for parsing Claude's responses
sealed class AgentAction {
    data class Tap(val x: Int, val y: Int) : AgentAction()
    data class Swipe(val startX: Int, val startY: Int, val endX: Int, val endY: Int, val duration: Long = 500) : AgentAction()
    data class Type(val text: String) : AgentAction()
    object Back : AgentAction()
    object Home : AgentAction()
    data class Wait(val ms: Long) : AgentAction()
    data class Complete(val message: String) : AgentAction()
    data class Error(val message: String) : AgentAction()
}

data class ActionWithDescription(
    val action: AgentAction,
    val description: String
)
