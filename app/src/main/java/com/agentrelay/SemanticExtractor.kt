package com.agentrelay

import android.util.Log
import com.agentrelay.models.*

/**
 * Semantic extraction tool: answers targeted queries about the current screen
 * by searching the element map locally first, falling back to an LLM call.
 *
 * Inspired by browser-use's `extract` tool — instead of the LLM parsing 200 elements,
 * it asks a specific question and gets a concise answer.
 */
class SemanticExtractor(private val claudeClient: LLMClient? = null) {

    data class ExtractionResult(
        val answer: String,
        val source: String  // "local" or "llm"
    )

    suspend fun extract(
        query: String,
        elementMap: ElementMap,
        screenshotInfo: ScreenshotInfo?
    ): ExtractionResult {
        // Try local extraction first (fast, free)
        val localResult = tryLocalExtraction(query, elementMap)
        if (localResult != null) {
            Log.d(TAG, "Local extraction succeeded for: $query")
            return localResult
        }

        // Fall back to LLM
        if (claudeClient != null) {
            Log.d(TAG, "Falling back to LLM extraction for: $query")
            return llmExtraction(query, elementMap, screenshotInfo)
        }

        return ExtractionResult("Could not extract: no local match and no LLM available", "local")
    }

    private fun tryLocalExtraction(query: String, elementMap: ElementMap): ExtractionResult? {
        val q = query.lowercase()
        val elements = elementMap.elements

        // Strategy 1: Count queries
        if (q.contains("how many") || q.contains("count")) {
            return tryCountQuery(q, elements)
        }

        // Strategy 2: Price/number pattern extraction
        if (q.contains("price") || q.contains("cost") || q.contains("total") || q.contains("amount")) {
            val pricePattern = Regex("""\$[\d,]+\.?\d*|\d+\.\d{2}""")
            val prices = elements.flatMap { el ->
                pricePattern.findAll(el.text).map { it.value }
            }.distinct()
            if (prices.isNotEmpty()) {
                return ExtractionResult(prices.joinToString(", "), "local")
            }
        }

        // Strategy 3: Type-based listing
        val typeMatch = matchTypeQuery(q)
        if (typeMatch != null) {
            val matching = elements.filter { it.type == typeMatch }
            if (matching.isNotEmpty()) {
                val summary = matching.take(20).joinToString("; ") { "[${it.id}] \"${it.text.take(40)}\"" }
                return ExtractionResult("${matching.size} found: $summary", "local")
            }
            return ExtractionResult("None found", "local")
        }

        // Strategy 4: Region-based queries
        val regionMatch = matchRegionQuery(q)
        if (regionMatch != null) {
            val matching = elements.filter { it.relativePosition.contains(regionMatch) }
            if (matching.isNotEmpty()) {
                val summary = matching.take(10).joinToString("; ") {
                    "[${it.id}] ${it.type} \"${it.text.take(30)}\""
                }
                return ExtractionResult(summary, "local")
            }
        }

        // Strategy 5: Keyword search in element text
        val keywords = q.replace(Regex("\\b(what|is|the|are|any|show|find|list|all|on|screen|visible|current)\\b"), "")
            .trim().split(Regex("\\s+")).filter { it.length > 2 }
        if (keywords.isNotEmpty()) {
            val matching = elements.filter { el ->
                keywords.any { kw -> el.text.lowercase().contains(kw) }
            }
            if (matching.isNotEmpty()) {
                val summary = matching.take(10).joinToString("; ") {
                    "[${it.id}] ${it.type} \"${it.text.take(50)}\""
                }
                return ExtractionResult(summary, "local")
            }
        }

        // Strategy 6: Yes/no existence queries
        if (q.startsWith("is there") || q.startsWith("does") || q.contains("exist")) {
            // Already did keyword search above and found nothing
            return null // Fall through to LLM
        }

        return null // Can't answer locally
    }

    private fun tryCountQuery(query: String, elements: List<UIElement>): ExtractionResult? {
        val typeMatch = matchTypeQuery(query)
        if (typeMatch != null) {
            val count = elements.count { it.type == typeMatch }
            return ExtractionResult("$count", "local")
        }
        if (query.contains("element") || query.contains("item")) {
            return ExtractionResult("${elements.size} total elements", "local")
        }
        if (query.contains("clickable") || query.contains("interactive")) {
            val count = elements.count { it.isClickable }
            return ExtractionResult("$count clickable elements", "local")
        }
        return null
    }

    private fun matchTypeQuery(query: String): ElementType? {
        return when {
            query.contains("button") -> ElementType.BUTTON
            query.contains("input") || query.contains("text field") || query.contains("text box") -> ElementType.INPUT
            query.contains("image") || query.contains("photo") || query.contains("picture") -> ElementType.IMAGE
            query.contains("switch") || query.contains("toggle") -> ElementType.SWITCH
            query.contains("checkbox") || query.contains("check box") -> ElementType.CHECKBOX
            query.contains("tab") -> ElementType.TAB
            query.contains("link") -> ElementType.LINK
            else -> null
        }
    }

    private fun matchRegionQuery(query: String): String? {
        return when {
            query.contains("top") && query.contains("left") -> "top-left"
            query.contains("top") && query.contains("right") -> "top-right"
            query.contains("bottom") && query.contains("left") -> "bottom-left"
            query.contains("bottom") && query.contains("right") -> "bottom-right"
            query.contains("top") -> "top"
            query.contains("bottom") -> "bottom"
            query.contains("left") -> "left"
            query.contains("right") -> "right"
            query.contains("center") || query.contains("middle") -> "middle"
            else -> null
        }
    }

    private suspend fun llmExtraction(
        query: String,
        elementMap: ElementMap,
        screenshotInfo: ScreenshotInfo?
    ): ExtractionResult {
        return try {
            val mapText = elementMap.toTextRepresentation()
            val systemPrompt = """
                Answer the following question about an Android screen based on the element map below.
                Be concise — 1-2 sentences max. If you can't determine the answer, say so.

                $mapText
            """.trimIndent()

            val contentBlocks = mutableListOf<ContentBlock>()
            if (screenshotInfo != null) {
                contentBlocks.add(ContentBlock.ImageContent(
                    source = ImageSource(data = screenshotInfo.base64Data, mediaType = screenshotInfo.mediaType)
                ))
            }
            contentBlocks.add(ContentBlock.TextContent(text = query))

            val messages = listOf(Message(role = "user", content = contentBlocks))
            val result = claudeClient!!.sendMessage(messages, systemPrompt)
            val text = result.getOrNull()?.content?.firstOrNull()?.text ?: "No answer"

            ExtractionResult(text.trim(), "llm")
        } catch (e: Exception) {
            Log.e(TAG, "LLM extraction failed", e)
            ExtractionResult("Extraction failed: ${e.message}", "llm")
        }
    }

    companion object {
        private const val TAG = "SemanticExtractor"
    }
}
