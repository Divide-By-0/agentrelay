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

// ─── Screenshot Mode ─────────────────────────────────────────────────────────

enum class ScreenshotMode { ON, OFF, AUTO }

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
    val hasWebView: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun findById(id: String): UIElement? = elements.find { it.id == id }

    /**
     * Returns a click point for [target] that avoids overlapping child elements.
     * If another element is fully contained within the target's bounds, the click
     * point is shifted to a region of the target that doesn't overlap any children.
     * Falls back to center if no safe region is found.
     */
    fun safeClickPoint(target: UIElement): android.graphics.Point {
        val b = target.bounds
        // Find elements whose bounds are fully inside the target (and aren't the target itself)
        val children = elements.filter { other ->
            other.id != target.id &&
            b.contains(other.bounds) &&
            // Ignore if it's essentially the same rect
            !(other.bounds.left == b.left && other.bounds.top == b.top &&
              other.bounds.right == b.right && other.bounds.bottom == b.bottom)
        }
        if (children.isEmpty()) {
            return android.graphics.Point(b.centerX(), b.centerY())
        }

        // Try candidate regions of the target that don't overlap any child:
        // edges (left strip, right strip, top strip, bottom strip)
        data class Region(val left: Int, val top: Int, val right: Int, val bottom: Int) {
            fun width() = right - left
            fun height() = bottom - top
            fun area() = width() * height()
            fun centerX() = (left + right) / 2
            fun centerY() = (top + bottom) / 2
            fun overlaps(r: android.graphics.Rect): Boolean =
                left < r.right && right > r.left && top < r.bottom && bottom > r.top
        }

        // Build a union of all child bounds
        val candidates = mutableListOf<Region>()
        // Left strip
        val minChildLeft = children.minOf { it.bounds.left }
        if (minChildLeft > b.left + 10) {
            candidates.add(Region(b.left, b.top, minChildLeft, b.bottom))
        }
        // Right strip
        val maxChildRight = children.maxOf { it.bounds.right }
        if (maxChildRight < b.right - 10) {
            candidates.add(Region(maxChildRight, b.top, b.right, b.bottom))
        }
        // Top strip
        val minChildTop = children.minOf { it.bounds.top }
        if (minChildTop > b.top + 10) {
            candidates.add(Region(b.left, b.top, b.right, minChildTop))
        }
        // Bottom strip
        val maxChildBottom = children.maxOf { it.bounds.bottom }
        if (maxChildBottom < b.bottom - 10) {
            candidates.add(Region(b.left, maxChildBottom, b.right, b.bottom))
        }

        // Pick the largest candidate that doesn't overlap ANY child
        val safeRegion = candidates
            .filter { region -> children.none { region.overlaps(it.bounds) } }
            .maxByOrNull { it.area() }

        if (safeRegion != null) {
            return android.graphics.Point(safeRegion.centerX(), safeRegion.centerY())
        }

        // Fallback: pick the largest candidate even if it overlaps some children
        val fallback = candidates.maxByOrNull { it.area() }
        if (fallback != null) {
            return android.graphics.Point(fallback.centerX(), fallback.centerY())
        }

        // Ultimate fallback: target center
        return android.graphics.Point(b.centerX(), b.centerY())
    }

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
    CLICK, LONG_PRESS, TYPE, SWIPE, BACK, HOME, WAIT, COMPLETE, OPEN_APP, DISMISS_KEYBOARD, PRESS_ENTER, SHARE_FINDING, EXTRACT
}

data class SemanticStep(
    val action: SemanticAction,
    val element: String? = null,
    val text: String? = null,
    val direction: String? = null,
    val packageName: String? = null,
    val durationMs: Long? = null, // for long_press
    val description: String = "",
    val findingKey: String? = null,    // for SHARE_FINDING
    val findingValue: String? = null,  // for SHARE_FINDING
    val extractQuery: String? = null   // for EXTRACT
)

data class SemanticActionPlan(
    val steps: List<SemanticStep>,
    val reasoning: String = "",
    val confidence: String = "",      // "high", "medium", "low"
    val progressAssessment: String = "", // how the task is going overall
    val relevantApps: List<String> = emptyList() // package names the LLM thinks may be needed
)
