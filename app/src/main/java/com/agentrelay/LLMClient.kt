package com.agentrelay

import android.util.Log
import com.agentrelay.models.*
import com.google.gson.JsonArray
import com.google.gson.JsonParser

/**
 * Abstract base class for LLM API clients.
 * Provider-specific subclasses implement [sendRaw] for their HTTP API.
 * Shared prompt construction, JSON parsing, and element-diff logic lives here.
 */
abstract class LLMClient(
    protected val apiKey: String,
    protected val model: String,
    protected val onUploadComplete: ((bytes: Int, milliseconds: Long) -> Unit)? = null
) {

    /**
     * Provider-specific HTTP call. Sends messages + system prompt and returns the raw text response.
     */
    abstract suspend fun sendRaw(
        messages: List<Message>,
        systemPrompt: String
    ): Result<String>

    /**
     * Sends messages and wraps the raw text into a [ClaudeResponse] for backward compatibility.
     */
    suspend fun sendMessage(
        messages: List<Message>,
        systemPrompt: String
    ): Result<ClaudeResponse> {
        return sendRaw(messages, systemPrompt).map { text ->
            ClaudeResponse(
                id = "llm-${System.currentTimeMillis()}",
                type = "message",
                role = "assistant",
                content = listOf(ResponseContent(type = "text", text = text)),
                model = model,
                stopReason = "end_turn",
                usage = Usage(inputTokens = 0, outputTokens = 0)
            )
        }
    }

    /**
     * High-level call: builds the automation prompt, sends screenshot + element map,
     * and parses the response into a [SemanticActionPlan].
     */
    suspend fun sendWithElementMap(
        screenshotInfo: ScreenshotInfo?,
        elementMap: ElementMap,
        userTask: String,
        conversationHistory: List<Message>,
        deviceContext: DeviceContext? = null,
        peerFindings: Map<String, String>? = null,
        previousElementMapText: String? = null,
        isFirstCall: Boolean = false
    ): Result<SemanticActionPlan> {
        val elementMapText = elementMap.toTextRepresentation()
        val deviceContextText = deviceContext?.toPromptText() ?: ""
        val diffText = if (previousElementMapText != null) {
            computeElementDiff(previousElementMapText, elementMapText)
        } else ""

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
            ${diffText}

            CRITICAL: Be CONCISE. Descriptions should be 3-7 words max. Get to the goal efficiently.

            Respond with a JSON object containing:
            - "steps": array of action objects, each with:
              - "action": one of "click", "long_press", "type", "swipe", "back", "home", "wait", "complete", "open_app", "dismiss_keyboard", "press_enter", "extract"
              - "element": element ID from the map (e.g. "btn_search", "input_query") — required for click/type/long_press. IDs are semantic: type prefix + text slug.
              - "text": text to type (for type action only)
              - "direction": "up", "down", "left", "right" (for swipe only)
              - "duration_ms": hold duration in milliseconds for long_press (default 1000)
              - "package": package name of the app to open (for open_app only) — MUST be copied EXACTLY from the "Installed apps" list in device context. NEVER guess package names.
              - "extract_query": a question about the screen content (for extract only). Result available next iteration.
              - "description": brief description (3-7 words)
            - "reasoning": brief explanation of your plan
            - "confidence": "high", "medium", or "low" — how confident you are this plan will work
            - "progress": 1-2 sentence assessment of overall task progress. Say what's been done so far, what's left, and flag anything that went wrong or might be tricky. Be honest — if something failed or you're unsure, say so.${if (isFirstCall) """
            - "relevant_apps": array of package name strings from the installed apps list that MIGHT be needed for ANY step of this task. Include the primary app plus any helpers (e.g. browser, settings, file manager). This field is REQUIRED on the first response only. Be generous — include anything potentially useful.""" else ""}

            Example response:
            {"steps": [{"action": "open_app", "package": "com.google.android.gm", "description": "Open Gmail app"}, {"action": "click", "element": "btn_compose", "description": "Tap compose button"}], "reasoning": "Opening Gmail to compose email", "confidence": "high", "progress": "Starting task — need to open Gmail and compose a new email."}

            Rules:
            1. Respond with ONLY valid JSON (no markdown, no explanation outside JSON)
            2. Reference elements by their IDs from the element map
            3. MAXIMIZE STEPS PER PLAN: Each API round-trip costs ~4 seconds. Return as many steps as you can confidently predict will succeed. Chain obvious follow-ups: after typing a URL, include a click on the Go/autocomplete suggestion; after typing in a search field, include clicking the search button or first result; after opening an app, include the first navigation action. Don't stop at a single step when the next action is predictable from the current screen state.
            4. For click/type, the "element" field is REQUIRED — ONLY use IDs that exist in the element map above. Do NOT guess or fabricate IDs.
            5. ONLY use "complete" when the ENTIRE user task is fully finished — not after a single sub-step. For multi-part tasks (e.g. "open X and do Y"), completing the first part does NOT mean the task is done. Keep going until every part of the request is satisfied. CRITICAL: "complete" must be the ONLY step in a plan. NEVER combine "complete" with other actions (click, type, swipe, etc.) in the same response. After performing an action that should finish the task (e.g. clicking "Send"), return ONLY that action. On the NEXT iteration, once you can SEE the result on screen confirming success, THEN return "complete" alone.
            6. WHEN STUCK or an action doesn't seem to work, STOP and DIAGNOSE before retrying. Consider these specific causes:
               a. WRONG BUTTON — There may be MULTIPLE elements with similar text (e.g., two "Save" buttons, a section header vs. the real button, a disabled vs. enabled copy). Carefully scan ALL elements and pick the correct one.
               b. KEYBOARD BLOCKING — The soft keyboard may be covering the button/field you need. Use "dismiss_keyboard" first, then re-examine what's visible.
               c. CONTENT OFF-SCREEN — The target may be below the fold. Use "swipe" up to scroll down. Many forms have submit buttons at the bottom that require scrolling to reach.
               d. POPUP/DIALOG BLOCKING — A permission dialog, cookie banner, tooltip, or system popup may be covering your target. Look for "Allow", "Accept", "OK", "Dismiss", "Got it", or "X" buttons to clear it first.
               e. ELEMENT MISCLASSIFIED — A TEXT element might be tappable (links, labels acting as buttons). An IMAGE might be a button. Scan ALL elements for matching text regardless of type.
               f. NEEDS DIFFERENT GESTURE — Try "long_press" instead of "click", or check if the element needs a swipe gesture.
               g. WRONG SCREEN — A previous action may not have taken effect. Check if you're on the screen you expect. If not, use "back" and re-navigate.
               h. ALTERNATIVE PATH — Abandon the current approach entirely. Use a menu icon, search bar, different tab, settings page, or even a completely different app.
               i. Only report failure after exhausting ALL of the above strategies.
            7. Keep descriptions brief and action-focused
            8. When you use "complete", the description MUST explain what was accomplished so it can be verified
            9. Use the device context to know which app is open, what time it is, whether the keyboard is showing, and what apps are installed. If keyboard is showing and you need to interact with elements behind it, use "dismiss_keyboard" first.
            10. CORRECTNESS IS CRITICAL: When selecting a contact, recipient, or item from a list, VERIFY the name/text matches EXACTLY. If multiple similar options exist, prefer using search/autofill to narrow results rather than blindly tapping. For contacts, type the person's name in the search/To field and wait for autocomplete suggestions before selecting.
            11. When the task involves sending a message, email, or performing an action targeting a specific person/item, use the search field or "To" field to type their name. Then select from the autocomplete/suggestion results to ensure accuracy. Do NOT scroll through a long list guessing — always search first.
            12. If you cannot find the exact target (contact, app, setting, etc.), try these strategies in order: (a) use the search bar if one exists, (b) swipe to find it, (c) go back and try an alternative path. Only report failure after exhausting these options.
            13. After TYPE, when the flow likely needs submit/next (search bars, login forms, message send fields), use "press_enter" to trigger the keyboard enter/go/next key instead of typing a newline character.
            14. ELEMENT MISCLASSIFICATION: The element map may not perfectly classify every element. A "TEXT" element might be tappable (links, labels acting as buttons). An "IMAGE" might be an icon button. A "SWITCH" might be labeled as a "CHECKBOX". If you can't find an element by its expected type, look for ANY element with matching or similar text and try clicking it — the type classification is a hint, not a guarantee.
            15. APP TARGETING: Before performing any task, CHECK the "Current app" in device context. If you're not in the correct app for the task, your FIRST step MUST be "open_app" with the correct package name from the installed apps list. CRITICAL: You MUST use ONLY package names that appear in the "Installed apps" list in device context — NEVER guess or fabricate a package name. App package names are often non-obvious (e.g., Temu is "com.einnovation.temu", not "com.temu"). If you cannot find the app in the installed list, fall back to tapping its icon on the home screen instead of guessing a package name.
            16. APP VERIFICATION: After every action, the system verifies you're still in the target app. If you get redirected to a different app unexpectedly, use open_app to return. Never assume you're in the right app — always check the device context.
            17. NEVER HALLUCINATE DATA: Do NOT fabricate, guess, or make up email addresses, phone numbers, usernames, or any contact information. ONLY use information that is: (a) explicitly provided in the user's task, (b) visible on the current screen, or (c) found through search/autocomplete on the device. If the task says "email John" but no email address is provided, you MUST search for John in the contacts or app first — NEVER invent an email like "john@gmail.com".
            18. SELF-APP AVOIDANCE: You are controlling the device from "com.agentrelay". NEVER interact with the agentrelay app UI. If device context shows current app is "com.agentrelay", your first action MUST be "open_app" or "home" to navigate away.
            19. DIRECT NAVIGATION OVER SEARCH: When the task involves visiting a website or looking up information online, navigate DIRECTLY to the target website by typing the URL in the browser's address bar (e.g., "amazon.com", "weather.com") instead of going to Google and searching. Prefer using installed native apps (e.g., Amazon app, news apps) over the browser when available. Only fall back to Google search when you genuinely don't know which website has the information needed.
            20. CLEAR BEFORE TYPING: When you need to type into a field that ALREADY contains text (visible in the element map as an INPUT with existing text), you MUST clear the field first. Use "click" on the field, then "type" with your new text — the system will select-all and replace. Do NOT assume your text will replace existing content automatically. If the field shows old/wrong text, always click it first to focus, then type the new text.
            21. AVOID ADS AND SPONSORED CONTENT: Do NOT click elements labeled "Sponsored", "Ad", "Promoted", or promotional content unless the user specifically asked to interact with ads. Prefer organic/non-sponsored results. In search results, scroll past sponsored sections to find real results. Sponsored results often lead to irrelevant websites.
            22. CLICK VISIBLE TARGETS BEFORE SCROLLING: Before issuing a swipe/scroll, SCAN the entire element map for clickable elements that match your current goal. If a button, link, or actionable element relevant to the task is ALREADY VISIBLE in the element map (e.g., "Pay now", "Submit", "Next", "Continue"), CLICK IT IMMEDIATELY — do NOT scroll past it. Only scroll when the target element is genuinely not present in the current element map.
            23. EXTRACT FOR INFORMATION: Use "extract" when you need to understand screen content (prices, names, counts, status text) before deciding your next action. Provide "extract_query" with a specific question. The answer will be available in the next iteration. Use extract as the LAST step in your plan — don't combine it with other actions after it. Example: {"action": "extract", "extract_query": "What is the total price shown?", "description": "Check total price"}

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
                val actionStr = step.get("action")?.asString ?: "wait"
                val parsedAction = when (actionStr.lowercase()) {
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
                    "press_enter" -> SemanticAction.PRESS_ENTER
                    "share_finding" -> SemanticAction.SHARE_FINDING
                    "extract" -> SemanticAction.EXTRACT
                    else -> {
                        Log.w(TAG, "Unknown action '$actionStr' from model; coercing to WAIT")
                        ConversationHistoryManager.add(
                            ConversationItem(
                                timestamp = System.currentTimeMillis(),
                                type = ConversationItem.ItemType.ERROR,
                                status = "Unknown action '$actionStr' from LLM — coerced to WAIT"
                            )
                        )
                        SemanticAction.WAIT
                    }
                }
                SemanticStep(
                    action = parsedAction,
                    element = step.get("element")?.asString,
                    text = step.get("text")?.asString,
                    direction = step.get("direction")?.asString,
                    packageName = step.get("package")?.asString,
                    durationMs = step.get("duration_ms")?.asLong,
                    description = step.get("description")?.asString ?: "",
                    findingKey = step.get("finding_key")?.asString,
                    findingValue = step.get("finding_value")?.asString,
                    extractQuery = step.get("extract_query")?.asString
                )
            }

            val confidence = json.get("confidence")?.asString ?: ""
            val progress = json.get("progress")?.asString ?: ""
            val relevantApps = json.getAsJsonArray("relevant_apps")
                ?.map { it.asString }
                ?: emptyList()

            SemanticActionPlan(
                steps = steps,
                reasoning = reasoning,
                confidence = confidence,
                progressAssessment = progress,
                relevantApps = relevantApps
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse semantic plan from: $jsonText", e)
            ConversationHistoryManager.add(
                ConversationItem(
                    timestamp = System.currentTimeMillis(),
                    type = ConversationItem.ItemType.ERROR,
                    status = "Failed to parse LLM response: ${e.message?.take(120)}",
                    response = jsonText.take(500)
                )
            )
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

    internal fun computeElementDiff(previous: String, current: String): String {
        val idRegex = Regex("""\[(\w+)]""")

        fun parseElements(text: String): Map<String, String> {
            val map = mutableMapOf<String, String>()
            for (line in text.lines()) {
                val match = idRegex.find(line)
                if (match != null) {
                    map[match.groupValues[1]] = line.trim()
                }
            }
            return map
        }

        val prevElements = parseElements(previous)
        val currElements = parseElements(current)

        if (prevElements.isEmpty()) return ""

        val appeared = currElements.keys - prevElements.keys
        val removed = prevElements.keys - currElements.keys
        val changed = currElements.keys.intersect(prevElements.keys).filter { id ->
            currElements[id] != prevElements[id]
        }

        if (appeared.isEmpty() && removed.isEmpty() && changed.isEmpty()) return ""

        return buildString {
            appendLine("STATE CHANGES (since last action):")
            for (id in appeared.take(15)) {
                val line = currElements[id] ?: continue
                val desc = line.substringAfter("]").trim().take(60)
                appendLine("+ [$id] $desc appeared")
            }
            for (id in removed.take(15)) {
                appendLine("- [$id] removed")
            }
            for (id in changed.take(15)) {
                val prevLine = prevElements[id]?.substringAfter("]")?.trim()?.take(40) ?: ""
                val currLine = currElements[id]?.substringAfter("]")?.trim()?.take(40) ?: ""
                appendLine("~ [$id] changed: \"$prevLine\" → \"$currLine\"")
            }
            val total = appeared.size + removed.size + changed.size
            val shown = minOf(appeared.size, 15) + minOf(removed.size, 15) + minOf(changed.size, 15)
            if (shown < total) {
                appendLine("  ... and ${total - shown} more changes")
            }
        }
    }

    companion object {
        private const val TAG = "LLMClient"
    }
}
