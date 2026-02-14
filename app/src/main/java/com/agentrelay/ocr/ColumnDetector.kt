package com.agentrelay.ocr

import android.graphics.Rect
import android.util.Log
import com.agentrelay.models.UIElement

class ColumnDetector(private val screenWidth: Int) {

    data class Column(
        val left: Int,
        val right: Int
    )

    fun detectColumns(elements: List<UIElement>, minGap: Int = 50): List<Column> {
        if (elements.isEmpty()) return listOf(Column(0, screenWidth))

        // Build horizontal occupancy histogram
        val occupancy = BooleanArray(screenWidth)
        for (el in elements) {
            val left = el.bounds.left.coerceIn(0, screenWidth - 1)
            val right = el.bounds.right.coerceIn(0, screenWidth - 1)
            for (x in left..right) {
                occupancy[x] = true
            }
        }

        // Find gaps (runs of unoccupied pixels wider than minGap)
        val gaps = mutableListOf<IntRange>()
        var gapStart = -1
        for (x in occupancy.indices) {
            if (!occupancy[x]) {
                if (gapStart == -1) gapStart = x
            } else {
                if (gapStart != -1) {
                    val gapWidth = x - gapStart
                    if (gapWidth >= minGap) {
                        gaps.add(gapStart until x)
                    }
                    gapStart = -1
                }
            }
        }

        if (gaps.isEmpty()) return listOf(Column(0, screenWidth))

        // Build columns from gaps
        val columns = mutableListOf<Column>()
        var prevRight = 0
        for (gap in gaps) {
            columns.add(Column(prevRight, gap.first))
            prevRight = gap.last + 1
        }
        columns.add(Column(prevRight, screenWidth))

        Log.d(TAG, "Detected ${columns.size} columns: $columns")
        return columns.filter { it.right - it.left > minGap }
    }

    fun offsetElements(elements: List<UIElement>, offsetX: Int): List<UIElement> {
        return elements.map { el ->
            el.copy(
                bounds = Rect(
                    el.bounds.left + offsetX,
                    el.bounds.top,
                    el.bounds.right + offsetX,
                    el.bounds.bottom
                )
            )
        }
    }

    companion object {
        private const val TAG = "ColumnDetector"
    }
}
