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

// ─── Semantic Element-Based Action Models ────────────────────────────────────

enum class ElementType {
    BUTTON, INPUT, TEXT, IMAGE, SWITCH, CHECKBOX, LIST_ITEM, TAB, ICON, LINK, UNKNOWN
}

enum class ElementSource {
    ACCESSIBILITY_TREE, OCR, MERGED
}

data class UIElement(
    val id: String,
    val type: ElementType,
    val text: String,
    val bounds: android.graphics.Rect,
    val isClickable: Boolean = false,
    val isFocusable: Boolean = false,
    val isScrollable: Boolean = false,
    val isChecked: Boolean = false,
    val isEnabled: Boolean = true,
    val resourceId: String? = null,
    val scrollRangeY: Int = -1, // -1 = not scrollable or unknown
    val scrollPositionY: Int = -1,
    val relativePosition: String = "",
    val source: ElementSource = ElementSource.ACCESSIBILITY_TREE
)

data class ElementMap(
    val elements: List<UIElement>,
    val screenWidth: Int,
    val screenHeight: Int,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun findById(id: String): UIElement? = elements.find { it.id == id }

    fun toTextRepresentation(): String = buildString {
        appendLine("ELEMENT MAP (${screenWidth}x${screenHeight}):")
        for (el in elements) {
            val flags = mutableListOf<String>()
            if (el.isClickable) flags.add("clickable")
            if (el.isFocusable) flags.add("focusable")
            if (el.isScrollable) flags.add("scrollable")
            if (el.isChecked) flags.add("checked")
            if (!el.isEnabled) flags.add("disabled")
            if (el.scrollRangeY >= 0) flags.add("scroll:${el.scrollPositionY}/${el.scrollRangeY}")
            val flagStr = if (flags.isNotEmpty()) " ${flags.joinToString(",")}" else ""
            val textStr = if (el.text.isNotBlank()) " \"${el.text}\"" else ""
            val posStr = if (el.relativePosition.isNotBlank()) " ${el.relativePosition}" else ""
            val resIdStr = if (!el.resourceId.isNullOrBlank()) " res=${el.resourceId}" else ""
            appendLine("[${el.id}] ${el.type}$textStr$flagStr$resIdStr (${el.bounds.left},${el.bounds.top},${el.bounds.right},${el.bounds.bottom})$posStr")
        }
    }
}

enum class SemanticAction {
    CLICK, TYPE, SWIPE, BACK, HOME, WAIT, COMPLETE
}

data class SemanticStep(
    val action: SemanticAction,
    val element: String? = null,
    val text: String? = null,
    val direction: String? = null,
    val description: String = ""
)

data class SemanticActionPlan(
    val steps: List<SemanticStep>,
    val reasoning: String = ""
)
