package com.agentrelay

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.agentrelay.models.ElementSource
import com.agentrelay.models.ElementType
import com.agentrelay.models.UIElement
import kotlinx.coroutines.delay

class AccessibilityTreeExtractor(private val service: AutomationService) {

    suspend fun extract(rootOverride: AccessibilityNodeInfo? = null): List<UIElement> {
        var root: AccessibilityNodeInfo? = rootOverride
        if (root == null) {
            for (attempt in 1..3) {
                root = service.getRootNode()
                if (root != null) break
                Log.w(TAG, "Root node null, retry $attempt/3")
                delay(200)
            }
        }

        if (root == null) {
            Log.e(TAG, "Failed to get root node after 3 retries")
            return emptyList()
        }

        val elements = mutableListOf<UIElement>()
        traverseNode(root, elements)
        root.recycle()

        Log.d(TAG, "Extracted ${elements.size} UI elements")
        return elements
    }

    private fun traverseNode(node: AccessibilityNodeInfo, elements: MutableList<UIElement>) {
        if (elements.size >= MAX_ELEMENTS) return

        val bounds = Rect()
        node.getBoundsInScreen(bounds)

        // Skip off-screen or zero-size nodes
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            recycleChildren(node)
            return
        }

        val type = classNameToElementType(node.className?.toString())
        val text = extractText(node)
        val isInteractive = node.isClickable || node.isFocusable || node.isCheckable

        // Extract resource ID (strip package prefix for brevity)
        val rawResId = node.viewIdResourceName
        val resourceId = rawResId?.substringAfter(":id/", rawResId)

        // Extract scroll position/range for scrollable elements
        var scrollRangeY = -1
        var scrollPosY = -1
        if (node.isScrollable) {
            try {
                val rangeInfo = node.collectionInfo
                // Use rangeInfo if available, otherwise try refresh
                if (rangeInfo != null) {
                    scrollRangeY = rangeInfo.rowCount
                }
            } catch (_: Exception) {}
            // Try RangeInfo for seekbars/sliders
            try {
                val ri = node.rangeInfo
                if (ri != null) {
                    scrollRangeY = ri.max.toInt()
                    scrollPosY = ri.current.toInt()
                }
            } catch (_: Exception) {}
        }

        // Only include nodes that have text or are interactive
        if (text.isNotBlank() || isInteractive) {
            elements.add(
                UIElement(
                    id = "", // assigned later by ElementMapGenerator
                    type = type,
                    text = text.take(100), // Truncate very long text
                    bounds = bounds,
                    isClickable = node.isClickable,
                    isFocusable = node.isFocusable,
                    isScrollable = node.isScrollable,
                    isChecked = node.isChecked,
                    isEnabled = node.isEnabled,
                    resourceId = resourceId,
                    scrollRangeY = scrollRangeY,
                    scrollPositionY = scrollPosY,
                    source = ElementSource.ACCESSIBILITY_TREE
                )
            )
        }

        // Traverse children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            traverseNode(child, elements)
            child.recycle()
        }
    }

    private fun recycleChildren(node: AccessibilityNodeInfo) {
        for (i in 0 until node.childCount) {
            node.getChild(i)?.recycle()
        }
    }

    private fun extractText(node: AccessibilityNodeInfo): String {
        return node.text?.toString()
            ?: node.contentDescription?.toString()
            ?: node.hintText?.toString()
            ?: ""
    }

    @androidx.annotation.VisibleForTesting
    internal fun classNameToElementType(className: String?): ElementType {
        if (className == null) return ElementType.UNKNOWN
        return when {
            className.contains("Button") -> ElementType.BUTTON
            className.contains("EditText") || className.contains("AutoComplete") -> ElementType.INPUT
            className.contains("TextView") -> ElementType.TEXT
            className.contains("ImageView") || className.contains("ImageButton") -> ElementType.IMAGE
            className.contains("Switch") || className.contains("ToggleButton") -> ElementType.SWITCH
            className.contains("CheckBox") || className.contains("CheckedTextView") -> ElementType.CHECKBOX
            className.contains("RecyclerView") || className.contains("ListView") -> ElementType.LIST_ITEM
            className.contains("TabWidget") || className.contains("Tab") -> ElementType.TAB
            className.contains("WebView") -> ElementType.TEXT // WebView content handled by OCR
            else -> ElementType.UNKNOWN
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
        private const val TAG = "A11yTreeExtractor"
        private const val MAX_ELEMENTS = 200
    }
}
