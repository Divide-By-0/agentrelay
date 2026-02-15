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

data class SplitScreenRecommendation(
    val recommended: Boolean,
    val topApp: String,
    val bottomApp: String,
    val topTask: String,
    val bottomTask: String
)

data class PlanningResult(
    val approaches: List<ApproachStrategy>,
    val recommendedIndex: Int,
    val diagnosis: String? = null,
    val splitScreen: SplitScreenRecommendation? = null
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
        elementMapText: String?,
        installedApps: List<AppInfo> = emptyList()
    ): PlanningResult? {
        return try {
            val systemPrompt = """
                You are a strategic planning agent for an Android automation system.
                A fast execution agent will carry out actions on the device. Your job is to analyze the current screen
                and devise 2-3 high-level approach strategies for completing the user's task.

                IMPORTANT RULES:
                1. For each approach, identify which app(s) to use. Use EXACT package names from the installed apps list.
                2. Different approaches should consider DIFFERENT apps when possible (e.g., for messaging: SMS app vs WhatsApp vs Messenger).
                3. NEVER fabricate or guess contact info (emails, phone numbers, usernames). The agent must ONLY use information visible on screen or provided by the user. If the task requires a contact detail not provided, the approach must include a step to search/look it up on device.
                4. The agent MUST NOT act inside "com.agentrelay" — always navigate to the correct app first.
                5. DIRECT NAVIGATION OVER SEARCH: If the task involves looking something up on a website (e.g., checking prices, reading news, looking up information), navigate DIRECTLY to the relevant website in the browser (e.g., open browser → type "amazon.com" in the URL bar) rather than opening Google and searching. Use native apps when installed (e.g., Amazon app instead of amazon.com), but if a browser is needed, go directly to the target URL. Only use Google search as a last resort when you don't know which website has the information.

                For each approach, provide:
                - A short name
                - A description of the strategy
                - Which app to use (package name)
                - A summary of key steps (high-level, not individual taps)
                - A confidence score (0.0-1.0)

                Respond with JSON:
                {
                  "approaches": [
                    {
                      "name": "Direct Navigation",
                      "description": "Navigate directly via the settings menu",
                      "app": "com.android.settings",
                      "steps": ["Open settings", "Find the target option", "Toggle it"],
                      "confidence": 0.8
                    }
                  ],
                  "recommendedIndex": 0
                }

                SPLIT-SCREEN PARALLEL EXECUTION:
                If the task benefits from using two apps simultaneously, include a "splitScreen" field.
                Two key patterns:
                1. LOOKUP + ACTION: One app finds info, the other uses it (e.g., Contacts → Gmail)
                2. PARALLEL SEARCH: Compare results across two apps simultaneously (e.g., search Amazon
                   and eBay for the cheapest price of a product). Look at the installed apps list and pick
                   the two most relevant apps for comparison.

                Format: "splitScreen": {"recommended": true, "topApp": "pkg.name", "bottomApp": "pkg.name",
                "topTask": "description of what the top agent should do", "bottomTask": "description of what the bottom agent should do"}

                For parallel search, each sub-task should include "share_finding" to report what it finds
                (e.g., price, availability) so results can be compared.
                Only recommend split-screen for genuine multi-app coordination, not simple single-app tasks.

                ${if (installedApps.isNotEmpty()) "Installed apps:\n${installedApps.joinToString("\n") { "- ${it.name} (${it.packageName})" }}" else ""}

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

            Log.d(TAG, "Calling planning API...")
            val result = client.sendMessage(messages, systemPrompt)
            if (result.isFailure) {
                Log.e(TAG, "Planning API call failed: ${result.exceptionOrNull()?.message}")
                return null
            }
            result.getOrNull()?.let { response ->
                val text = response.content.firstOrNull()?.text ?: run {
                    Log.w(TAG, "Planning response had no text content")
                    return null
                }
                Log.d(TAG, "Planning response received, parsing (${text.length} chars)")
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
        previousPlan: PlanningResult?,
        installedApps: List<AppInfo> = emptyList()
    ): PlanningResult? {
        return try {
            val systemPrompt = """
                You are a strategic recovery agent for an Android automation system.
                The execution agent has been failing repeatedly. Analyze what went wrong and suggest
                fundamentally DIFFERENT approaches — not just retrying the same thing.

                CRITICAL RECOVERY RULES:
                1. Consider switching to a COMPLETELY DIFFERENT APP. If the current app isn't working, suggest an alternative (e.g., if Messenger fails, try SMS; if Gmail fails, try another email app).
                2. Each approach MUST specify the target app package name.
                3. NEVER fabricate contact details (emails, phone numbers). Only use what the user provided or what's visible on screen. If missing, suggest searching for the contact on the device.
                4. If the agent keeps clicking the same element without progress, the element may not be interactive in the expected way — suggest a completely different UI flow.
                5. The agent MUST NOT act inside "com.agentrelay".
                6. Consider SPLIT-SCREEN mode if the task involves two apps. Include "splitScreen" field if recommending it:
                   "splitScreen": {"recommended": true, "topApp": "pkg", "bottomApp": "pkg", "topTask": "...", "bottomTask": "..."}

                Previous failures:
                ${failureContext.joinToString("\n") { "- $it" }}

                ${if (previousPlan != null) "Previous strategy attempted: ${previousPlan.approaches.getOrNull(previousPlan.recommendedIndex)?.name ?: "unknown"}" else ""}

                ${if (installedApps.isNotEmpty()) "Installed apps:\n${installedApps.joinToString("\n") { "- ${it.name} (${it.packageName})" }}" else ""}

                ${if (elementMapText != null) "Current screen element map:\n$elementMapText" else ""}

                Respond with JSON:
                {
                  "diagnosis": "The agent was trying to tap elements that don't exist on this screen",
                  "approaches": [
                    {
                      "name": "Switch to Different App",
                      "description": "Try using a different app entirely",
                      "app": "com.google.android.apps.messaging",
                      "steps": ["Go home", "Open alternative app", "Navigate to target"],
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

            val reader = com.google.gson.stream.JsonReader(java.io.StringReader(cleanJson))
            reader.isLenient = true
            val json = JsonParser.parseReader(reader).asJsonObject
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

            // Parse split-screen recommendation if present
            val splitScreen = try {
                val ssJson = json.getAsJsonObject("splitScreen")
                if (ssJson != null && ssJson.get("recommended")?.asBoolean == true) {
                    SplitScreenRecommendation(
                        recommended = true,
                        topApp = ssJson.get("topApp")?.asString ?: "",
                        bottomApp = ssJson.get("bottomApp")?.asString ?: "",
                        topTask = ssJson.get("topTask")?.asString ?: "",
                        bottomTask = ssJson.get("bottomTask")?.asString ?: ""
                    )
                } else null
            } catch (e: Exception) {
                Log.w(TAG, "Failed to parse splitScreen recommendation", e)
                null
            }

            PlanningResult(
                approaches = approaches,
                recommendedIndex = recommendedIndex,
                diagnosis = diagnosis,
                splitScreen = splitScreen
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
