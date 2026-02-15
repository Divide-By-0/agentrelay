package com.agentrelay

import android.util.Log
import com.agentrelay.models.*
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonDeserializer
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

interface ClaudeAPI {
    @POST("v1/messages")
    suspend fun createMessage(
        @Header("x-api-key") apiKey: String,
        @Header("anthropic-version") version: String = "2023-06-01",
        @Body request: ClaudeRequest
    ): Response<ClaudeResponse>
}

class ClaudeAPIClient(
    private val apiKey: String,
    private val model: String = "claude-sonnet-4-5", // Default model
    private val onUploadComplete: ((bytes: Int, milliseconds: Long) -> Unit)? = null
) {

    private val gson: Gson = GsonBuilder()
        .registerTypeAdapter(ContentBlock::class.java, JsonDeserializer { json, _, _ ->
            val jsonObject = json.asJsonObject
            when (jsonObject.get("type").asString) {
                "text" -> ContentBlock.TextContent(
                    text = jsonObject.get("text").asString
                )
                "image" -> ContentBlock.ImageContent(
                    source = Gson().fromJson(jsonObject.get("source"), ImageSource::class.java)
                )
                else -> null
            }
        })
        .create()

    private val retrofit = Retrofit.Builder()
        .baseUrl("https://api.anthropic.com/")
        .client(
            OkHttpClient.Builder()
                .addInterceptor(HttpLoggingInterceptor().apply {
                    level = HttpLoggingInterceptor.Level.BODY
                })
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        )
        .addConverterFactory(GsonConverterFactory.create(gson))
        .build()

    private val api = retrofit.create(ClaudeAPI::class.java)

    suspend fun sendMessage(
        messages: List<Message>,
        systemPrompt: String
    ): Result<ClaudeResponse> {
        return try {
            val request = ClaudeRequest(
                model = model,
                maxTokens = 4096,
                messages = messages,
                system = systemPrompt
            )

            val startTime = System.currentTimeMillis()
            val response = api.createMessage(apiKey, request = request)
            val endTime = System.currentTimeMillis()

            // Estimate upload size (rough approximation based on image data)
            val estimatedBytes = messages.sumOf { message ->
                message.content.sumOf { content ->
                    when (content) {
                        is ContentBlock.ImageContent -> content.source.data.length / 4 * 3 // Base64 to bytes
                        is ContentBlock.TextContent -> content.text.length
                        else -> 0
                    }
                }
            }
            onUploadComplete?.invoke(estimatedBytes, endTime - startTime)

            if (response.isSuccessful && response.body() != null) {
                Result.success(response.body()!!)
            } else {
                Result.failure(Exception("API Error: ${response.code()} - ${response.errorBody()?.string()}"))
            }
        } catch (e: Exception) {
            Log.e(TAG, "API request failed", e)
            Result.failure(e)
        }
    }

    suspend fun sendWithElementMap(
        screenshotInfo: ScreenshotInfo?,
        elementMap: ElementMap,
        userTask: String,
        conversationHistory: List<Message>,
        deviceContext: DeviceContext? = null,
        peerFindings: Map<String, String>? = null
    ): Result<SemanticActionPlan> {
        val elementMapText = elementMap.toTextRepresentation()
        val deviceContextText = deviceContext?.toPromptText() ?: ""

        val inputContext = if (screenshotInfo != null) {
            "You receive a screenshot, a structured ELEMENT MAP, and device context."
        } else {
            "You receive a structured ELEMENT MAP and device context. No screenshot is provided."
        }

        val systemPrompt = """
            You are an Android automation agent. $inputContext
            Use the element map to identify UI elements by their IDs and issue actions referencing those IDs.

            ${deviceContextText}
            ${elementMapText}

            CRITICAL: Be CONCISE. Descriptions should be 3-7 words max. Get to the goal efficiently.

            Respond with a JSON object containing:
            - "steps": array of action objects, each with:
              - "action": one of "click", "long_press", "type", "swipe", "back", "home", "wait", "complete", "open_app", "dismiss_keyboard"
              - "element": element ID from the map (e.g. "btn_search", "input_query") — required for click/type/long_press. IDs are semantic: type prefix + text slug.
              - "text": text to type (for type action only)
              - "direction": "up", "down", "left", "right" (for swipe only)
              - "duration_ms": hold duration in milliseconds for long_press (default 1000)
              - "package": package name of the app to open (for open_app only) — use the EXACT package name from the installed apps list in device context
              - "description": brief description (3-7 words)
            - "reasoning": brief explanation of your plan
            - "confidence": "high", "medium", or "low" — how confident you are this plan will work
            - "progress": 1-2 sentence assessment of overall task progress. Say what's been done so far, what's left, and flag anything that went wrong or might be tricky. Be honest — if something failed or you're unsure, say so.

            Example response:
            {"steps": [{"action": "open_app", "package": "com.google.android.gm", "description": "Open Gmail app"}, {"action": "click", "element": "btn_compose", "description": "Tap compose button"}], "reasoning": "Opening Gmail to compose email", "confidence": "high", "progress": "Starting task — need to open Gmail and compose a new email."}

            Rules:
            1. Respond with ONLY valid JSON (no markdown, no explanation outside JSON)
            2. Reference elements by their IDs from the element map
            3. MAXIMIZE STEPS PER PLAN: Each API round-trip costs ~4 seconds. Return as many steps as you can confidently predict will succeed. Chain obvious follow-ups: after typing a URL, include a click on the Go/autocomplete suggestion; after typing in a search field, include clicking the search button or first result; after opening an app, include the first navigation action. Don't stop at a single step when the next action is predictable from the current screen state.
            4. For click/type, the "element" field is REQUIRED — ONLY use IDs that exist in the element map above. Do NOT guess or fabricate IDs.
            5. ONLY use "complete" when the ENTIRE user task is fully finished — not after a single sub-step. For multi-part tasks (e.g. "open X and do Y"), completing the first part does NOT mean the task is done. Keep going until every part of the request is satisfied.
            6. WHEN STUCK, try these recovery strategies IN ORDER before giving up:
               a. If the keyboard is showing (check device context), use "dismiss_keyboard" — it may be hiding buttons or elements you need
               b. Try "swipe" up/down to reveal off-screen elements
               c. Try "back" to return to a previous screen and try a different path
               d. Look for elements that might be MISCLASSIFIED — a TEXT element might actually be tappable, an IMAGE might be a button, a LIST_ITEM might contain the button you need. If the element you want isn't in the map by its expected type, scan ALL elements for matching text regardless of type and try clicking it
               e. Look for alternative UI paths — e.g. a menu icon, overflow button (⋮), or long-press that might reveal the option you need
               f. Only report failure after exhausting ALL of the above
            7. Keep descriptions brief and action-focused
            8. When you use "complete", the description MUST explain what was accomplished so it can be verified
            9. Use the device context to know which app is open, what time it is, whether the keyboard is showing, and what apps are installed. If keyboard is showing and you need to interact with elements behind it, use "dismiss_keyboard" first.
            10. CORRECTNESS IS CRITICAL: When selecting a contact, recipient, or item from a list, VERIFY the name/text matches EXACTLY. If multiple similar options exist, prefer using search/autofill to narrow results rather than blindly tapping. For contacts, type the person's name in the search/To field and wait for autocomplete suggestions before selecting.
            11. When the task involves sending a message, email, or performing an action targeting a specific person/item, use the search field or "To" field to type their name. Then select from the autocomplete/suggestion results to ensure accuracy. Do NOT scroll through a long list guessing — always search first.
            12. If you cannot find the exact target (contact, app, setting, etc.), try these strategies in order: (a) use the search bar if one exists, (b) swipe to find it, (c) go back and try an alternative path. Only report failure after exhausting these options.
            15. ELEMENT MISCLASSIFICATION: The element map may not perfectly classify every element. A "TEXT" element might be tappable (links, labels acting as buttons). An "IMAGE" might be an icon button. A "SWITCH" might be labeled as a "CHECKBOX". If you can't find an element by its expected type, look for ANY element with matching or similar text and try clicking it — the type classification is a hint, not a guarantee.
            13. APP TARGETING: Before performing any task, CHECK the "Current app" in device context. If you're not in the correct app for the task, your FIRST step MUST be "open_app" with the correct package name from the installed apps list. For example, to send a text message, open_app "com.google.android.apps.messaging" (or the relevant messaging app). Do NOT try to navigate from an unrelated app — use open_app directly.
            14. APP VERIFICATION: After every action, the system verifies you're still in the target app. If you get redirected to a different app unexpectedly, use open_app to return. Never assume you're in the right app — always check the device context.
            16. NEVER HALLUCINATE DATA: Do NOT fabricate, guess, or make up email addresses, phone numbers, usernames, or any contact information. ONLY use information that is: (a) explicitly provided in the user's task, (b) visible on the current screen, or (c) found through search/autocomplete on the device. If the task says "email John" but no email address is provided, you MUST search for John in the contacts or app first — NEVER invent an email like "john@gmail.com".
            17. SELF-APP AVOIDANCE: You are controlling the device from "com.agentrelay". NEVER interact with the agentrelay app UI. If device context shows current app is "com.agentrelay", your first action MUST be "open_app" or "home" to navigate away.
            18. DIRECT NAVIGATION OVER SEARCH: When the task involves visiting a website or looking up information online, navigate DIRECTLY to the target website by typing the URL in the browser's address bar (e.g., "amazon.com", "weather.com") instead of going to Google and searching. Prefer using installed native apps (e.g., Amazon app, news apps) over the browser when available. Only fall back to Google search when you genuinely don't know which website has the information needed.

            ${if (!peerFindings.isNullOrEmpty()) {
                buildString {
                    appendLine("PEER FINDINGS (from parallel agent in other split-screen half):")
                    peerFindings.forEach { (key, value) -> appendLine("- $key: $value") }
                    appendLine("Use this information to complete your task.")
                    appendLine()
                    appendLine("You can share findings with the other agent using:")
                    appendLine("""{"action": "share_finding", "finding_key": "key_name", "finding_value": "the value", "description": "What was found"}""")
                }
            } else ""}

            User task: $userTask

            Respond with ONLY a JSON object.
        """.trimIndent()

        val messages = conversationHistory.toMutableList()
        val contentBlocks = mutableListOf<ContentBlock>()
        if (screenshotInfo != null) {
            contentBlocks.add(
                ContentBlock.ImageContent(
                    source = ImageSource(
                        data = screenshotInfo.base64Data,
                        mediaType = screenshotInfo.mediaType
                    )
                )
            )
            contentBlocks.add(ContentBlock.TextContent(text = "Element map and screenshot above. What should I do next?"))
        } else {
            contentBlocks.add(ContentBlock.TextContent(
                text = "No screenshot available — use the element map and device context above to decide what to do next."
            ))
        }
        messages.add(Message(role = "user", content = contentBlocks))

        return sendMessage(messages, systemPrompt).mapCatching { response ->
            val text = response.content.firstOrNull()?.text
                ?: throw Exception("No text in response")
            parseSemanticActionPlan(text)
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun parseSemanticActionPlan(jsonText: String): SemanticActionPlan {
        return try {
            var cleanJson = jsonText.trim()

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
            val reasoning = json.get("reasoning")?.asString ?: ""

            val stepsArray = json.getAsJsonArray("steps") ?: JsonArray()
            val steps = stepsArray.map { stepJson ->
                val step = stepJson.asJsonObject
                val actionStr = step.get("action")?.asString ?: "complete"
                SemanticStep(
                    action = when (actionStr) {
                        "click" -> SemanticAction.CLICK
                        "long_press" -> SemanticAction.LONG_PRESS
                        "type" -> SemanticAction.TYPE
                        "swipe" -> SemanticAction.SWIPE
                        "back" -> SemanticAction.BACK
                        "home" -> SemanticAction.HOME
                        "wait" -> SemanticAction.WAIT
                        "complete" -> SemanticAction.COMPLETE
                        "open_app" -> SemanticAction.OPEN_APP
                        "dismiss_keyboard" -> SemanticAction.DISMISS_KEYBOARD
                        "share_finding" -> SemanticAction.SHARE_FINDING
                        else -> SemanticAction.COMPLETE
                    },
                    element = step.get("element")?.asString,
                    text = step.get("text")?.asString,
                    direction = step.get("direction")?.asString,
                    packageName = step.get("package")?.asString,
                    durationMs = step.get("duration_ms")?.asLong,
                    description = step.get("description")?.asString ?: "",
                    findingKey = step.get("finding_key")?.asString,
                    findingValue = step.get("finding_value")?.asString
                )
            }

            val confidence = json.get("confidence")?.asString ?: ""
            val progress = json.get("progress")?.asString ?: ""

            SemanticActionPlan(
                steps = steps,
                reasoning = reasoning,
                confidence = confidence,
                progressAssessment = progress
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse semantic plan from: $jsonText", e)
            // Return a WAIT step instead of COMPLETE so the agent retries
            // rather than immediately declaring failure
            SemanticActionPlan(
                steps = listOf(
                    SemanticStep(
                        action = SemanticAction.WAIT,
                        description = "LLM returned unparseable response, retrying"
                    )
                ),
                reasoning = "Parse error — will retry: ${e.message?.take(80)}"
            )
        }
    }

    @Deprecated("Use sendWithElementMap for semantic element-based actions")
    suspend fun sendWithScreenshot(
        screenshotInfo: ScreenshotInfo,
        userTask: String,
        conversationHistory: List<Message>
    ): Result<ActionWithDescription> {
        val systemPrompt = """
            You are an Android automation agent. Analyze the screenshot and respond with a JSON action.

            CRITICAL: Be CONCISE. Descriptions should be 3-7 words max. Get to the goal efficiently.

            IMPORTANT SCREEN INFORMATION:
            - The screenshot you see is ${screenshotInfo.scaledWidth}x${screenshotInfo.scaledHeight} pixels
            - The screenshot has a NUMBERED GRID OVERLAY with lines every 100 pixels
            - Yellow numbers show X coordinates (at top) and Y coordinates (at left)
            - Use these grid numbers to determine precise tap coordinates
            - Return coordinates based on what you see in the screenshot
            - The system will automatically scale your coordinates to the actual device screen

            Available actions:
            - {"action": "tap", "x": 100, "y": 200, "description": "Tapping Settings button"}
            - {"action": "swipe", "startX": 100, "startY": 200, "endX": 300, "endY": 400, "duration": 500, "description": "Scrolling down"}
            - {"action": "type", "text": "hello", "description": "Typing 'hello'"}
            - {"action": "back", "description": "Going back"}
            - {"action": "home", "description": "Going to home screen"}
            - {"action": "complete", "message": "Task finished", "description": "Task completed"}
            - {"action": "wait", "ms": 1000, "description": "Waiting for UI to settle"}

            Rules:
            1. Respond with ONLY ONE action as valid JSON (no markdown, no explanation)
            2. ALWAYS include a brief "description" field (max 5-10 words)
            3. Use coordinates based on the ${screenshotInfo.scaledWidth}x${screenshotInfo.scaledHeight} screenshot
            4. Be precise with tap coordinates - aim for the center of visible elements
            5. For multi-step tasks, choose the MOST EFFICIENT path with fewest actions
            6. Minimize unnecessary steps - go directly to the goal when possible
            7. When you need to type and then submit, tap the input field in one action, then type in the next iteration
            8. Use "complete" when the task is done or impossible
            9. If stuck after 3 attempts, use "back" or report completion failure
           10. Keep descriptions brief and action-focused (5-10 words max)

            User task: $userTask

            Respond with ONLY a single JSON action object.
        """.trimIndent()

        val messages = conversationHistory.toMutableList()
        messages.add(
            Message(
                role = "user",
                content = listOf(
                    ContentBlock.ImageContent(
                        source = ImageSource(
                            data = screenshotInfo.base64Data,
                            mediaType = screenshotInfo.mediaType
                        )
                    ),
                    ContentBlock.TextContent(text = "What should I do next?")
                )
            )
        )

        return sendMessage(messages, systemPrompt).mapCatching { response ->
            val text = response.content.firstOrNull()?.text
                ?: throw Exception("No text in response")

            parseActionWithDescription(text)
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun parseActionWithDescription(jsonText: String): ActionWithDescription {
        return try {
            // Extract JSON from markdown code blocks or find first JSON object
            var cleanJson = jsonText.trim()

            // Remove markdown code blocks
            if (cleanJson.contains("```json")) {
                cleanJson = cleanJson.substringAfter("```json").substringBefore("```").trim()
            } else if (cleanJson.contains("```")) {
                cleanJson = cleanJson.substringAfter("```").substringBefore("```").trim()
            }

            // Find first { and last } to extract just the JSON object
            val startIndex = cleanJson.indexOf('{')
            val endIndex = cleanJson.lastIndexOf('}')

            if (startIndex >= 0 && endIndex > startIndex) {
                cleanJson = cleanJson.substring(startIndex, endIndex + 1)
            }

            val actionReader = com.google.gson.stream.JsonReader(java.io.StringReader(cleanJson))
            actionReader.isLenient = true
            val json = JsonParser.parseReader(actionReader).asJsonObject
            val description = json.get("description")?.asString ?: "No description provided"

            val action = when (json.get("action").asString) {
                "tap" -> AgentAction.Tap(
                    x = json.get("x").asInt,
                    y = json.get("y").asInt
                )
                "swipe" -> AgentAction.Swipe(
                    startX = json.get("startX").asInt,
                    startY = json.get("startY").asInt,
                    endX = json.get("endX").asInt,
                    endY = json.get("endY").asInt,
                    duration = json.get("duration")?.asLong ?: 500
                )
                "type" -> AgentAction.Type(text = json.get("text").asString)
                "back" -> AgentAction.Back
                "home" -> AgentAction.Home
                "wait" -> AgentAction.Wait(ms = json.get("ms").asLong)
                "complete" -> AgentAction.Complete(
                    message = json.get("message")?.asString ?: "Task completed"
                )
                else -> AgentAction.Error("Unknown action: ${json.get("action").asString}")
            }

            ActionWithDescription(action, description)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse action from: $jsonText", e)
            ActionWithDescription(
                AgentAction.Error("Failed to parse action: ${e.message}"),
                "Failed to parse action"
            )
        }
    }

    companion object {
        private const val TAG = "ClaudeAPIClient"
    }
}
