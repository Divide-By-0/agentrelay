package com.agentrelay

import android.graphics.Rect
import android.util.Log
import com.agentrelay.models.*

class ElementMapGenerator(
    private val screenWidth: Int,
    private val screenHeight: Int
) {

    fun generate(
        accessibilityElements: List<UIElement>,
        ocrElements: List<UIElement> = emptyList(),
        hasWebView: Boolean = false
    ): ElementMap {
        val merged = mergeElements(accessibilityElements, ocrElements)
        val withPositions = computeRelativePositions(merged)
        val reindexed = reindexElements(withPositions)

        Log.d(TAG, "Generated element map: ${reindexed.size} elements (${accessibilityElements.size} a11y + ${ocrElements.size} OCR)")
        return ElementMap(
            elements = reindexed,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            hasWebView = hasWebView
        )
    }

    private fun mergeElements(
        a11yElements: List<UIElement>,
        ocrElements: List<UIElement>
    ): List<UIElement> {
        if (ocrElements.isEmpty()) return a11yElements

        val merged = a11yElements.toMutableList()
        val matchedOcr = mutableSetOf<Int>()

        // For each OCR element, check if it overlaps with an a11y element
        for ((ocrIdx, ocrEl) in ocrElements.withIndex()) {
            var bestMatch: UIElement? = null
            var bestIoU = 0f

            for (a11yEl in a11yElements) {
                val iou = computeIoU(ocrEl.bounds, a11yEl.bounds)
                if (iou > bestIoU) {
                    bestIoU = iou
                    bestMatch = a11yEl
                }
            }

            if (bestIoU > 0.5f && bestMatch != null) {
                // Enrich the a11y element with OCR text if it has none
                if (bestMatch.text.isBlank() && ocrEl.text.isNotBlank()) {
                    val idx = merged.indexOf(bestMatch)
                    if (idx >= 0) {
                        merged[idx] = bestMatch.copy(
                            text = ocrEl.text,
                            source = ElementSource.MERGED
                        )
                    }
                }
                matchedOcr.add(ocrIdx)
            }
        }

        // Group non-overlapping OCR elements by line, then add as standalone elements
        val standaloneOcr = ocrElements.filterIndexed { idx, el ->
            idx !in matchedOcr && el.text.isNotBlank()
        }
        val grouped = groupOcrTextByLine(standaloneOcr)
        for (el in grouped) {
            merged.add(el.copy(source = ElementSource.OCR))
        }

        return merged
    }

    /**
     * Groups OCR text fragments that are on the same horizontal line into single elements.
     * e.g., "Wi" + "-" + "Fi" → "Wi-Fi", "T" + "-" + "Mobile" → "T-Mobile"
     */
    @androidx.annotation.VisibleForTesting
    internal fun groupOcrTextByLine(elements: List<UIElement>): List<UIElement> {
        if (elements.size <= 1) return elements

        // Sort by quantized y-line (bucket nearby y-centers together), then x-left.
        // This prevents 1-2px y-center differences from breaking left-to-right order.
        val sorted = elements.sortedWith(compareBy(
            { (it.bounds.top + it.bounds.bottom) / 2 / 20 }, // quantize to 20px bands
            { it.bounds.left }
        ))

        val result = mutableListOf<UIElement>()
        var currentGroup = mutableListOf(sorted[0])

        for (i in 1 until sorted.size) {
            val curr = sorted[i]

            // Compare against the entire current group's bounding box, not just last element
            val groupLeft = currentGroup.minOf { it.bounds.left }
            val groupRight = currentGroup.maxOf { it.bounds.right }
            val groupTop = currentGroup.minOf { it.bounds.top }
            val groupBottom = currentGroup.maxOf { it.bounds.bottom }
            val groupCenterY = (groupTop + groupBottom) / 2

            // Check vertical overlap: current element's center within group's y range
            val currCenterY = (curr.bounds.top + curr.bounds.bottom) / 2
            val minHeight = minOf(groupBottom - groupTop, curr.bounds.height()).coerceAtLeast(1)
            val verticalClose = kotlin.math.abs(groupCenterY - currCenterY) < minHeight * 0.7

            // Check horizontal proximity: gap between group's right edge and current's left
            val gap = curr.bounds.left - groupRight
            val avgCharWidth = (curr.bounds.width().toFloat() / curr.text.length.coerceAtLeast(1))
                .coerceAtLeast(10f) // minimum 10px
            val horizontalClose = gap < avgCharWidth * 3 && gap > -avgCharWidth * 0.5

            if (verticalClose && horizontalClose) {
                currentGroup.add(curr)
            } else {
                result.add(mergeGroup(currentGroup))
                currentGroup = mutableListOf(curr)
            }
        }
        result.add(mergeGroup(currentGroup))

        return result
    }

    private fun mergeGroup(group: List<UIElement>): UIElement {
        if (group.size == 1) return group[0]

        val combinedText = group.joinToString("") { el ->
            // Add space between words if there's a gap, otherwise concatenate directly
            el.text
        }
        // Check if words need spacing (look at gaps between consecutive elements)
        val spacedText = buildString {
            for ((i, el) in group.withIndex()) {
                if (i > 0) {
                    val prevRight = group[i - 1].bounds.right
                    val currLeft = el.bounds.left
                    val gap = currLeft - prevRight
                    val avgCharWidth = (el.bounds.width().toFloat() / el.text.length.coerceAtLeast(1))
                    // Add space if gap is larger than a character width
                    if (gap > avgCharWidth * 1.5) append(" ")
                }
                append(el.text)
            }
        }

        val mergedBounds = Rect(
            group.minOf { it.bounds.left },
            group.minOf { it.bounds.top },
            group.maxOf { it.bounds.right },
            group.maxOf { it.bounds.bottom }
        )

        return group[0].copy(
            text = spacedText,
            bounds = mergedBounds,
            isClickable = group.any { it.isClickable }
        )
    }

    @androidx.annotation.VisibleForTesting
    internal fun computeIoU(a: Rect, b: Rect): Float {
        val intersectLeft = maxOf(a.left, b.left)
        val intersectTop = maxOf(a.top, b.top)
        val intersectRight = minOf(a.right, b.right)
        val intersectBottom = minOf(a.bottom, b.bottom)

        if (intersectRight <= intersectLeft || intersectBottom <= intersectTop) return 0f

        val intersectionArea = (intersectRight - intersectLeft).toLong() * (intersectBottom - intersectTop)
        val areaA = (a.right - a.left).toLong() * (a.bottom - a.top)
        val areaB = (b.right - b.left).toLong() * (b.bottom - b.top)
        val unionArea = areaA + areaB - intersectionArea

        return if (unionArea > 0) intersectionArea.toFloat() / unionArea else 0f
    }

    private fun computeRelativePositions(elements: List<UIElement>): List<UIElement> {
        return elements.map { el ->
            val positions = mutableListOf<String>()

            // Vertical region
            val centerY = (el.bounds.top + el.bounds.bottom) / 2f
            val yRatio = centerY / screenHeight
            when {
                yRatio < 0.15f -> positions.add("top")
                yRatio > 0.85f -> positions.add("bottom")
                yRatio < 0.4f -> positions.add("upper")
                yRatio > 0.6f -> positions.add("lower")
                else -> positions.add("middle")
            }

            // Horizontal region
            val centerX = (el.bounds.left + el.bounds.right) / 2f
            val xRatio = centerX / screenWidth
            when {
                xRatio < 0.3f -> positions.add("left")
                xRatio > 0.7f -> positions.add("right")
                else -> positions.add("center")
            }

            el.copy(relativePosition = positions.joinToString("-"))
        }
    }

    private fun reindexElements(elements: List<UIElement>): List<UIElement> {
        val usedIds = mutableMapOf<String, Int>()
        return elements.map { el ->
            val baseId = buildSemanticId(el)
            val count = usedIds.getOrDefault(baseId, 0) + 1
            usedIds[baseId] = count
            // First occurrence: btn_search, duplicates: btn_search_2, btn_search_3
            val finalId = if (count == 1) baseId else "${baseId}_$count"
            el.copy(id = finalId)
        }
    }

    private fun buildSemanticId(element: UIElement): String {
        val prefix = typePrefix(element.type)
        val slug = textToSlug(element.text)
        return if (slug.isNotEmpty()) "${prefix}_$slug" else prefix
    }

    /**
     * Converts element text into a short snake_case slug for use in IDs.
     * "Search results"   → "search_results"
     * "OK"               → "ok"
     * "Enter your name..." → "enter_your_name"
     * "12:45 PM"         → "12_45_pm"
     */
    @androidx.annotation.VisibleForTesting
    internal fun textToSlug(text: String): String {
        if (text.isBlank()) return ""
        return text
            .lowercase()
            .replace(Regex("[^a-z0-9]+"), "_") // non-alphanumeric → underscore
            .replace(Regex("_+"), "_")          // collapse runs
            .trim('_')
            .take(MAX_SLUG_LENGTH)              // cap length
            .trimEnd('_')                       // clean trailing _ after truncation
    }

    private fun typePrefix(type: ElementType): String = when (type) {
        ElementType.BUTTON -> "btn"
        ElementType.INPUT -> "input"
        ElementType.TEXT -> "text"
        ElementType.IMAGE -> "img"
        ElementType.SWITCH -> "switch"
        ElementType.CHECKBOX -> "chk"
        ElementType.LIST_ITEM -> "list"
        ElementType.TAB -> "tab"
        ElementType.ICON -> "icon"
        ElementType.LINK -> "link"
        ElementType.UNKNOWN -> "el"
    }

    companion object {
        private const val TAG = "ElementMapGenerator"
        private const val MAX_SLUG_LENGTH = 24
    }
}
