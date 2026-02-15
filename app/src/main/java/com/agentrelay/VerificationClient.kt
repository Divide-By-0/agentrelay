package com.agentrelay

import android.util.Log
import com.agentrelay.models.*
import com.google.gson.JsonParser

data class VerificationResult(
    val safe: Boolean,
    val reason: String
)

class VerificationClient(
    private val automationService: AutomationService,
    private val claudeClient: ClaudeAPIClient,
    private val secureStorage: SecureStorage
) {

    private val treeExtractor = AccessibilityTreeExtractor(automationService)

    suspend fun verify(
        originalMap: ElementMap,
        plan: SemanticActionPlan,
        step: SemanticStep
    ): VerificationResult {
        // Re-extract accessibility tree (skip OCR for speed)
        val currentElements = treeExtractor.extract()
        val currentMap = ElementMapGenerator(
            originalMap.screenWidth,
            originalMap.screenHeight
        ).generate(currentElements)

        // Quick checks before calling Haiku
        val targetId = step.element ?: return VerificationResult(true, "No target element")

        val originalElement = originalMap.findById(targetId)
        val currentElement = currentMap.findById(targetId)

        // Check if target element still exists
        if (originalElement == null) {
            return VerificationResult(false, "Original element $targetId not found")
        }

        if (currentElement == null) {
            // Element disappeared - check if it's a minor reindex
            // Look for an element with matching text and type
            val match = currentMap.elements.find {
                it.type == originalElement.type && it.text == originalElement.text
            }
            if (match != null) {
                return VerificationResult(true, "Element re-indexed as ${match.id}")
            }
            return VerificationResult(false, "Element $targetId no longer exists")
        }

        // Check if bounds shifted significantly (> 50px)
        val dx = Math.abs(currentElement.bounds.centerX() - originalElement.bounds.centerX())
        val dy = Math.abs(currentElement.bounds.centerY() - originalElement.bounds.centerY())
        if (dx > 50 || dy > 50) {
            // Bounds shifted - call Haiku for deeper analysis
            return verifyWithHaiku(originalMap, currentMap, plan, step)
        }

        // Check for new modal overlays (elements covering most of the screen)
        val screenArea = originalMap.screenWidth.toLong() * originalMap.screenHeight
        val newLargeElements = currentMap.elements.filter { el ->
            val area = el.bounds.width().toLong() * el.bounds.height()
            area > screenArea * 0.5 && originalMap.findById(el.id) == null
        }
        if (newLargeElements.isNotEmpty()) {
            return verifyWithHaiku(originalMap, currentMap, plan, step)
        }

        return VerificationResult(true, "Element verified")
    }

    private suspend fun verifyWithHaiku(
        originalMap: ElementMap,
        currentMap: ElementMap,
        plan: SemanticActionPlan,
        step: SemanticStep
    ): VerificationResult {
        val haikuModel = "claude-haiku-4-5-20251001"
        val apiKey = secureStorage.getApiKeyForModel(haikuModel) ?: return VerificationResult(false, "No Claude API key for verification")

        val haikuClient = ClaudeAPIClient(apiKey, haikuModel)

        val prompt = """
            ORIGINAL ELEMENT MAP:
            ${originalMap.toTextRepresentation()}

            CURRENT ELEMENT MAP:
            ${currentMap.toTextRepresentation()}

            PLANNED ACTION: ${step.action} on element "${step.element}" - ${step.description}

            The UI may have changed between planning and execution.
            Is it still safe to execute this action?

            Respond with ONLY JSON: {"safe": true/false, "reason": "brief explanation"}
        """.trimIndent()

        val messages = listOf(
            Message(
                role = "user",
                content = listOf(ContentBlock.TextContent(text = prompt))
            )
        )

        val result = haikuClient.sendMessage(messages, "You are a UI verification assistant. Respond only with JSON.")

        return try {
            val text = result.getOrNull()?.content?.firstOrNull()?.text
                ?: return VerificationResult(false, "Haiku call failed")

            var cleanJson = text.trim()
            val startIdx = cleanJson.indexOf('{')
            val endIdx = cleanJson.lastIndexOf('}')
            if (startIdx >= 0 && endIdx > startIdx) {
                cleanJson = cleanJson.substring(startIdx, endIdx + 1)
            }

            val reader = com.google.gson.stream.JsonReader(java.io.StringReader(cleanJson))
            reader.isLenient = true
            val json = JsonParser.parseReader(reader).asJsonObject
            VerificationResult(
                safe = json.get("safe")?.asBoolean ?: false,
                reason = json.get("reason")?.asString ?: "Unknown"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Haiku verification", e)
            // If we can't verify, err on the side of caution
            VerificationResult(false, "Verification parse error: ${e.message}")
        }
    }

    companion object {
        private const val TAG = "VerificationClient"
    }
}
