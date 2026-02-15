package com.agentrelay

import android.util.Log
import com.agentrelay.models.ContentBlock
import com.agentrelay.models.ImageSource
import com.agentrelay.models.Message
import com.google.gson.JsonParser

data class ApproachStrategy(
    val name: String,
    val description: String,
    val stepsSummary: List<String>,
    val confidence: Float
)

data class PlanningResult(
    val approaches: List<ApproachStrategy>,
    val recommendedIndex: Int,
    val diagnosis: String? = null
) {
    fun toGuidanceText(): String = buildString {
        val recommended = approaches.getOrNull(recommendedIndex) ?: approaches.firstOrNull()
            ?: return@buildString
        append("STRATEGIC GUIDANCE (from planning agent):\n")
        append("Recommended approach: ${recommended.name}\n")
        append("${recommended.description}\n")
        append("Steps:\n")
        recommended.stepsSummary.forEachIndexed { i, step ->
            append("  ${i + 1}. $step\n")
        }
        if (approaches.size > 1) {
            append("\nAlternative approaches if this fails:\n")
            approaches.forEachIndexed { i, approach ->
                if (i != recommendedIndex) {
                    append("- ${approach.name}: ${approach.description}\n")
                }
            }
        }
        if (diagnosis != null) {
            append("\nDiagnosis: $diagnosis\n")
        }
    }
}

class PlanningAgent(
    apiKey: String,
    model: String = "claude-opus-4-6"
) {
    private val client = ClaudeAPIClient(apiKey, model)

    suspend fun planInitial(
        task: String,
        screenshotInfo: ScreenshotInfo?,
        elementMapText: String?
    ): PlanningResult? {
        return try {
            val systemPrompt = """
                You are a strategic planning agent for an Android automation system.
                A fast execution agent will carry out actions on the device. Your job is to analyze the current screen
                and devise 2-3 high-level approach strategies for completing the user's task.

                For each approach, provide:
                - A short name
                - A description of the strategy
                - A summary of key steps (high-level, not individual taps)
                - A confidence score (0.0-1.0)

                Respond with JSON:
                {
                  "approaches": [
                    {
                      "name": "Direct Navigation",
                      "description": "Navigate directly via the settings menu",
                      "steps": ["Open settings", "Find the target option", "Toggle it"],
                      "confidence": 0.8
                    }
                  ],
                  "recommendedIndex": 0
                }

                ${if (elementMapText != null) "Current screen element map:\n$elementMapText" else ""}

                User task: $task

                Respond with ONLY valid JSON.
            """.trimIndent()

            val messages = mutableListOf<Message>()
            val content = mutableListOf<ContentBlock>()
            if (screenshotInfo != null) {
                content.add(
                    ContentBlock.ImageContent(
                        source = ImageSource(
                            data = screenshotInfo.base64Data,
                            mediaType = screenshotInfo.mediaType
                        )
                    )
                )
            }
            content.add(ContentBlock.TextContent(text = "Plan strategies for this task: $task"))
            messages.add(Message(role = "user", content = content))

            val result = client.sendMessage(messages, systemPrompt)
            result.getOrNull()?.let { response ->
                val text = response.content.firstOrNull()?.text ?: return null
                parsePlanningResult(text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Initial planning failed", e)
            null
        }
    }

    suspend fun planRecovery(
        task: String,
        screenshotInfo: ScreenshotInfo?,
        elementMapText: String?,
        failureContext: List<String>,
        previousPlan: PlanningResult?
    ): PlanningResult? {
        return try {
            val systemPrompt = """
                You are a strategic recovery agent for an Android automation system.
                The execution agent has been failing repeatedly. Analyze what went wrong and suggest
                fundamentally DIFFERENT approaches — not just retrying the same thing.

                Previous failures:
                ${failureContext.joinToString("\n") { "- $it" }}

                ${if (previousPlan != null) "Previous strategy attempted: ${previousPlan.approaches.getOrNull(previousPlan.recommendedIndex)?.name ?: "unknown"}" else ""}

                ${if (elementMapText != null) "Current screen element map:\n$elementMapText" else ""}

                Respond with JSON:
                {
                  "diagnosis": "The agent was trying to tap elements that don't exist on this screen",
                  "approaches": [
                    {
                      "name": "Alternative Path",
                      "description": "Try a completely different navigation path",
                      "steps": ["Go home first", "Open app drawer", "Find target app"],
                      "confidence": 0.7
                    }
                  ],
                  "recommendedIndex": 0
                }

                User task: $task

                Respond with ONLY valid JSON.
            """.trimIndent()

            val messages = mutableListOf<Message>()
            val content = mutableListOf<ContentBlock>()
            if (screenshotInfo != null) {
                content.add(
                    ContentBlock.ImageContent(
                        source = ImageSource(
                            data = screenshotInfo.base64Data,
                            mediaType = screenshotInfo.mediaType
                        )
                    )
                )
            }
            content.add(ContentBlock.TextContent(
                text = "The agent is stuck. Failures: ${failureContext.takeLast(5).joinToString("; ")}. Suggest recovery strategies for: $task"
            ))
            messages.add(Message(role = "user", content = content))

            val result = client.sendMessage(messages, systemPrompt)
            result.getOrNull()?.let { response ->
                val text = response.content.firstOrNull()?.text ?: return null
                parsePlanningResult(text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recovery planning failed", e)
            null
        }
    }

    private fun parsePlanningResult(text: String): PlanningResult {
        return try {
            var cleanJson = text.trim()

            // Remove markdown code blocks
            if (cleanJson.contains("```json")) {
                cleanJson = cleanJson.substringAfter("```json").substringBefore("```").trim()
            } else if (cleanJson.contains("```")) {
                cleanJson = cleanJson.substringAfter("```").substringBefore("```").trim()
            }

            val startIndex = cleanJson.indexOf('{')
            val endIndex = cleanJson.lastIndexOf('}')
            if (startIndex >= 0 && endIndex > startIndex) {
                cleanJson = cleanJson.substring(startIndex, endIndex + 1)
            }

            val json = JsonParser.parseString(cleanJson).asJsonObject
            val diagnosis = json.get("diagnosis")?.asString
            val recommendedIndex = json.get("recommendedIndex")?.asInt ?: 0

            val approachesArray = json.getAsJsonArray("approaches")
            val approaches = approachesArray?.map { approachJson ->
                val obj = approachJson.asJsonObject
                ApproachStrategy(
                    name = obj.get("name")?.asString ?: "Unnamed",
                    description = obj.get("description")?.asString ?: "",
                    stepsSummary = obj.getAsJsonArray("steps")?.map { it.asString } ?: emptyList(),
                    confidence = obj.get("confidence")?.asFloat ?: 0.5f
                )
            } ?: emptyList()

            PlanningResult(
                approaches = approaches,
                recommendedIndex = recommendedIndex,
                diagnosis = diagnosis
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse planning result from: $text", e)
            // Fallback: wrap raw text as a single approach
            PlanningResult(
                approaches = listOf(
                    ApproachStrategy(
                        name = "Default",
                        description = text.take(200),
                        stepsSummary = listOf("Follow the agent's best judgment"),
                        confidence = 0.5f
                    )
                ),
                recommendedIndex = 0
            )
        }
    }

    companion object {
        private const val TAG = "PlanningAgent"
    }
}
