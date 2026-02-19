package com.agentrelay

import android.util.Log
import com.agentrelay.models.ElementMap
import com.agentrelay.models.ElementType

/**
 * Scores an ElementMap to decide whether a screenshot should be sent to the LLM.
 * Runs purely locally — no API calls.
 *
 * Inspired by browser-use's DOM-first approach: skip vision when structured data
 * provides sufficient context, include it when the element map is sparse or ambiguous.
 */
object ElementMapAnalyzer {

    data class ScreenshotDecision(
        val shouldSend: Boolean,
        val score: Float,   // 0.0 = definitely need screenshot, 1.0 = element map is rich
        val reason: String
    )

    /**
     * Evaluates whether the element map alone provides sufficient context for the LLM.
     *
     * @param elementMap The current element map
     * @param ocrOnlyCount Number of OCR elements that didn't merge with a11y elements
     * @param previousIterationFailed Whether the last iteration had a failure
     * @param sameMapCount Number of consecutive iterations with unchanged element map
     * @param structureRepeatCount Number of recent iterations with the same structure hash
     * @param keyboardVisible Whether keyboard is currently visible
     * @return Decision with score and human-readable reason
     */
    fun shouldSendScreenshot(
        elementMap: ElementMap,
        ocrOnlyCount: Int,
        previousIterationFailed: Boolean,
        sameMapCount: Int = 0,
        structureRepeatCount: Int = 0,
        keyboardVisible: Boolean = false
    ): ScreenshotDecision {
        val elements = elementMap.elements
        if (elements.isEmpty()) {
            return ScreenshotDecision(true, 0f, "empty element map")
        }

        var score = 0f
        val reasons = mutableListOf<String>()

        // Signal 1: Clickable element count (weight 0.15)
        val clickableCount = elements.count { it.isClickable }
        val clickableScore = when {
            clickableCount >= 10 -> 1f
            clickableCount >= 5 -> 0.7f
            clickableCount >= 2 -> 0.3f
            else -> 0f
        }
        score += clickableScore * 0.15f
        if (clickableCount < 5) reasons.add("few clickable elements ($clickableCount)")

        // Signal 2: Empty-text ratio (weight 0.15)
        val emptyTextRatio = elements.count { it.text.isBlank() }.toFloat() / elements.size
        val textScore = when {
            emptyTextRatio < 0.2f -> 1f
            emptyTextRatio < 0.4f -> 0.6f
            emptyTextRatio < 0.6f -> 0.3f
            else -> 0f
        }
        score += textScore * 0.15f
        if (emptyTextRatio > 0.4f) reasons.add("${(emptyTextRatio * 100).toInt()}% elements have no text")

        // Signal 3: UNKNOWN type ratio (weight 0.12)
        val unknownRatio = elements.count { it.type == ElementType.UNKNOWN }.toFloat() / elements.size
        val typeScore = when {
            unknownRatio < 0.1f -> 1f
            unknownRatio < 0.3f -> 0.5f
            else -> 0f
        }
        score += typeScore * 0.12f
        if (unknownRatio > 0.3f) reasons.add("${(unknownRatio * 100).toInt()}% unknown types")

        // Signal 4: WebView present (weight 0.12)
        val webViewScore = if (elementMap.hasWebView) 0f else 1f
        score += webViewScore * 0.12f
        if (elementMap.hasWebView) reasons.add("WebView detected")

        // Signal 5: Many OCR-only elements (weight 0.14)
        val ocrScore = when {
            ocrOnlyCount <= 2 -> 1f
            ocrOnlyCount <= 5 -> 0.5f
            else -> 0f
        }
        score += ocrScore * 0.14f
        if (ocrOnlyCount > 5) reasons.add("$ocrOnlyCount OCR-only elements")

        // Signal 6: Previous iteration failed (weight 0.12)
        val failScore = if (previousIterationFailed) 0f else 1f
        score += failScore * 0.12f
        if (previousIterationFailed) reasons.add("previous iteration failed")

        // Signal 7: Duplicate clickable labels imply ambiguity (weight 0.10)
        val clickableText = elements
            .filter { it.isClickable && it.text.isNotBlank() }
            .map { it.text.trim().lowercase() }
        val duplicateRatio = if (clickableText.isNotEmpty()) {
            val distinct = clickableText.distinct().size
            ((clickableText.size - distinct).coerceAtLeast(0)).toFloat() / clickableText.size
        } else 0f
        val ambiguityScore = when {
            duplicateRatio <= 0.1f -> 1f
            duplicateRatio <= 0.3f -> 0.5f
            else -> 0f
        }
        score += ambiguityScore * 0.10f
        if (duplicateRatio > 0.2f) reasons.add("ambiguous duplicate labels")

        // Signal 8: Stagnation hints (weight 0.10)
        val stagnationScore = when {
            structureRepeatCount >= 3 || sameMapCount >= 2 -> 0f
            structureRepeatCount == 2 || sameMapCount == 1 -> 0.4f
            keyboardVisible -> 0.7f
            else -> 1f
        }
        score += stagnationScore * 0.10f
        if (structureRepeatCount >= 3 || sameMapCount >= 2) reasons.add("stagnating UI state")
        if (keyboardVisible) reasons.add("keyboard visible")

        val shouldSend = score < AUTO_SCREENSHOT_THRESHOLD
        val reason = if (shouldSend) {
            "including screenshot (${reasons.joinToString(", ")}, richness score=${String.format("%.2f", score)} < ${String.format("%.2f", AUTO_SCREENSHOT_THRESHOLD)})"
        } else {
            "skipping screenshot (richness score=${String.format("%.2f", score)} >= ${String.format("%.2f", AUTO_SCREENSHOT_THRESHOLD)})"
        }

        Log.d(TAG, "Auto screenshot: $reason")
        return ScreenshotDecision(shouldSend, score, reason)
    }

    private const val TAG = "ElementMapAnalyzer"
    private const val AUTO_SCREENSHOT_THRESHOLD = 0.62f
}
