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
        ocrElements: List<UIElement> = emptyList()
    ): ElementMap {
        val merged = mergeElements(accessibilityElements, ocrElements)
        val withPositions = computeRelativePositions(merged)
        val reindexed = reindexElements(withPositions)

        Log.d(TAG, "Generated element map: ${reindexed.size} elements (${accessibilityElements.size} a11y + ${ocrElements.size} OCR)")
        return ElementMap(
            elements = reindexed,
            screenWidth = screenWidth,
            screenHeight = screenHeight
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

        // Add non-overlapping OCR elements as standalone TEXT elements
        for ((ocrIdx, ocrEl) in ocrElements.withIndex()) {
            if (ocrIdx !in matchedOcr && ocrEl.text.isNotBlank()) {
                merged.add(ocrEl.copy(source = ElementSource.OCR))
            }
        }

        return merged
    }

    private fun computeIoU(a: Rect, b: Rect): Float {
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
        val counters = mutableMapOf<ElementType, Int>()
        return elements.map { el ->
            val count = counters.getOrDefault(el.type, 0) + 1
            counters[el.type] = count
            el.copy(id = "${typePrefix(el.type)}_$count")
        }
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
    }
}
