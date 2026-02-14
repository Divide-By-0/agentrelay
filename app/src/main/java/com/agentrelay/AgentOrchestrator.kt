package com.agentrelay

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.agentrelay.models.*
import com.agentrelay.OCRClient
import kotlinx.coroutines.*

class AgentOrchestrator(private val context: Context) {

    private val orchestratorScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentJob: Job? = null
    private var isRunning = false

    private val conversationHistory = mutableListOf<Message>()
    private var currentTask: String = ""

    suspend fun startTask(task: String) {
        if (isRunning) {
            Log.w(TAG, "Agent already running")
            return
        }

        // Validate prerequisites
        val apiKey = SecureStorage.getInstance(context).getApiKey()
        if (apiKey.isNullOrEmpty()) {
            showToast("Please set API key in settings")
            return
        }

        if (!AutomationService.isServiceEnabled()) {
            showToast("Please enable Accessibility Service")
            return
        }

        val captureService = ScreenCaptureService.instance
        if (captureService == null) {
            showToast("Screen capture not initialized")
            return
        }

        currentTask = task
        conversationHistory.clear()
        ConversationHistoryManager.clear()
        isRunning = true

        // Show cursor and status overlay
        CursorOverlay.getInstance(context).show()
        StatusOverlay.getInstance(context).show()
        addStatus("Agent started for task: $task")
        ConversationHistoryManager.add(
            ConversationItem(
                timestamp = System.currentTimeMillis(),
                type = ConversationItem.ItemType.API_REQUEST,
                prompt = "Starting task: $task",
                status = "Agent started for task: $task"
            )
        )

        currentJob = orchestratorScope.launch {
            try {
                runAgentLoop(apiKey, task, captureService)
            } catch (e: Exception) {
                Log.e(TAG, "Agent loop failed", e)
                addStatus("Error: ${e.message}")
                showToast("Agent error: ${e.message}")
            } finally {
                stop()
            }
        }
    }

    fun stop() {
        isRunning = false
        currentJob?.cancel()
        currentJob = null
        CursorOverlay.getInstance(context).hide()
        StatusOverlay.getInstance(context).hide()
        addStatus("Agent stopped")
        Log.d(TAG, "Agent stopped")
    }

    private fun addStatus(message: String) {
        StatusOverlay.getInstance(context).addStatus(message)
    }

    private suspend fun runAgentLoop(
        apiKey: String,
        task: String,
        captureService: ScreenCaptureService
    ) {
        val secureStorage = SecureStorage.getInstance(context)
        val model = secureStorage.getModel()
        val claudeClient = ClaudeAPIClient(apiKey, model) { bytes, milliseconds ->
            secureStorage.saveLastUploadTime(bytes, milliseconds)
        }
        val automationService = AutomationService.instance
        val cursorOverlay = CursorOverlay.getInstance(context)

        if (automationService == null) {
            showToast("Automation service not available")
            return
        }

        val treeExtractor = AccessibilityTreeExtractor(automationService)
        val ocrEnabled = secureStorage.getOcrEnabled()
        val verificationEnabled = secureStorage.getVerificationEnabled()

        var iteration = 0
        val maxIterations = 50
        var screenshotFailureCount = 0
        var lastElementMapText = ""
        var sameMapCount = 0
        val failureContext = mutableListOf<String>()

        while (isRunning && iteration < maxIterations) {
            iteration++
            Log.d(TAG, "Agent iteration $iteration")
            addStatus("Iteration $iteration/$maxIterations")

            // 1. Capture screenshot (clean, no grid)
            addStatus("Capturing screenshot...")
            CursorOverlay.getInstance(context).hide()
            StatusOverlay.getInstance(context).hide()
            delay(100)
            val screenshotInfo = captureService.captureScreenshot()
            CursorOverlay.getInstance(context).show()
            StatusOverlay.getInstance(context).show()

            if (screenshotInfo == null) {
                screenshotFailureCount++
                Log.e(TAG, "Failed to capture screenshot (attempt $screenshotFailureCount)")
                addStatus("Screenshot failed (attempt $screenshotFailureCount)")
                ConversationHistoryManager.add(
                    ConversationItem(
                        timestamp = System.currentTimeMillis(),
                        type = ConversationItem.ItemType.ERROR,
                        status = "Screenshot capture failed (attempt $screenshotFailureCount)"
                    )
                )
                if (screenshotFailureCount >= 2) {
                    addStatus("Multiple screenshot failures - check permissions")
                    showToast("Screen capture failed. Please restart the app and grant permissions.")
                    PermissionFixOverlay.getInstance(context).show()
                    break
                }
                delay(1000)
                continue
            }

            screenshotFailureCount = 0
            addStatus("Screenshot captured (${screenshotInfo.actualWidth}x${screenshotInfo.actualHeight})")
            ConversationHistoryManager.add(
                ConversationItem(
                    timestamp = System.currentTimeMillis(),
                    type = ConversationItem.ItemType.SCREENSHOT_CAPTURED,
                    screenshot = screenshotInfo.base64Data,
                    screenshotWidth = screenshotInfo.actualWidth,
                    screenshotHeight = screenshotInfo.actualHeight,
                    status = "Screenshot: ${screenshotInfo.actualWidth}x${screenshotInfo.actualHeight}"
                )
            )

            // 2. Extract accessibility tree (+ OCR in parallel if enabled)
            addStatus("Extracting element map...")
            val a11yElements = treeExtractor.extract()

            var ocrElements = emptyList<UIElement>()
            if (ocrEnabled) {
                try {
                    val ocrClient = OCRClient(secureStorage)
                    val ocrResult = ocrClient.recognizeText(screenshotInfo)
                    ocrElements = ocrResult
                } catch (e: Exception) {
                    Log.w(TAG, "OCR failed, continuing with accessibility tree only", e)
                }
            }

            // 3. Merge into ElementMap
            val mapGenerator = ElementMapGenerator(
                screenshotInfo.actualWidth,
                screenshotInfo.actualHeight
            )
            val elementMap = mapGenerator.generate(a11yElements, ocrElements)
            addStatus("Element map: ${elementMap.elements.size} elements")
            Log.d(TAG, elementMap.toTextRepresentation())

            // Detect stuck state via element-map diffing
            val currentMapText = elementMap.toTextRepresentation()
            if (currentMapText == lastElementMapText) {
                sameMapCount++
                if (sameMapCount >= 3) {
                    addStatus("Stuck detected - trying back button")
                    automationService.performBack()
                    sameMapCount = 0
                    failureContext.add("Element map unchanged 3x, pressed back")
                    delay(1000)
                    continue
                }
            } else {
                sameMapCount = 0
            }
            lastElementMapText = currentMapText

            // 4. Send screenshot + element map to Claude
            addStatus("Sending to Claude...")
            val enhancedTask = if (failureContext.isNotEmpty()) {
                buildString {
                    append("$task\n\n")
                    append("CONTEXT - Previous issues:\n")
                    failureContext.takeLast(3).forEach { append("- $it\n") }
                    append("\nTry a different approach.")
                }
            } else {
                task
            }

            val planResult = claudeClient.sendWithElementMap(
                screenshotInfo,
                elementMap,
                enhancedTask,
                conversationHistory
            )

            if (planResult.isFailure) {
                Log.e(TAG, "Claude API failed: ${planResult.exceptionOrNull()?.message}")
                addStatus("API error: ${planResult.exceptionOrNull()?.message}")
                ConversationHistoryManager.add(
                    ConversationItem(
                        timestamp = System.currentTimeMillis(),
                        type = ConversationItem.ItemType.ERROR,
                        status = "API error: ${planResult.exceptionOrNull()?.message}"
                    )
                )
                delay(2000)
                continue
            }

            val plan = planResult.getOrNull() ?: continue
            addStatus("Claude plan: ${plan.reasoning}")
            ConversationHistoryManager.add(
                ConversationItem(
                    timestamp = System.currentTimeMillis(),
                    type = ConversationItem.ItemType.API_RESPONSE,
                    response = plan.reasoning,
                    action = plan.steps.joinToString(", ") { it.description },
                    actionDescription = plan.reasoning,
                    status = "Plan: ${plan.steps.size} steps - ${plan.reasoning}"
                )
            )

            // Add to conversation history
            conversationHistory.add(
                Message(
                    role = "assistant",
                    content = listOf(ContentBlock.TextContent(
                        text = "Plan: ${plan.reasoning}\nSteps: ${plan.steps.joinToString("; ") { "${it.action} ${it.element ?: ""} ${it.description}" }}"
                    ))
                )
            )

            // 5. Execute each step
            var taskCompleted = false
            for ((stepIdx, step) in plan.steps.withIndex()) {
                if (!isRunning) break

                // Check for complete action
                if (step.action == SemanticAction.COMPLETE) {
                    val msg = step.description.ifBlank { "Task completed" }
                    if (msg.startsWith("Cannot complete", ignoreCase = true) ||
                        msg.startsWith("Failed", ignoreCase = true)) {
                        addStatus("Task failed: $msg")
                        showTaskSuggestionDialog(msg, conversationHistory)
                    } else {
                        addStatus("Task completed: $msg")
                        showToast("Task completed: $msg")
                    }
                    taskCompleted = true
                    break
                }

                addStatus("Step ${stepIdx + 1}/${plan.steps.size}: ${step.description}")

                // 6. Verify before execution (if enabled and it's a click/type)
                if (verificationEnabled && step.element != null &&
                    (step.action == SemanticAction.CLICK || step.action == SemanticAction.TYPE)) {
                    val verifier = VerificationClient(automationService, claudeClient, secureStorage)
                    val verifyResult = verifier.verify(elementMap, plan, step)
                    if (!verifyResult.safe) {
                        addStatus("Verification failed: ${verifyResult.reason}")
                        failureContext.add("Verification: ${verifyResult.reason}")
                        conversationHistory.add(
                            Message(
                                role = "user",
                                content = listOf(ContentBlock.TextContent(
                                    text = "Verification failed: ${verifyResult.reason}. The UI changed. Re-analyze."
                                ))
                            )
                        )
                        break // Re-plan with updated screen
                    }
                }

                // Resolve element ID to screen coordinates
                val success = executeSemanticStep(step, elementMap, automationService, cursorOverlay)

                if (!success) {
                    addStatus("Step failed: ${step.description}")
                    failureContext.add("Step failed: ${step.description}")
                    ConversationHistoryManager.add(
                        ConversationItem(
                            timestamp = System.currentTimeMillis(),
                            type = ConversationItem.ItemType.ERROR,
                            status = "Step failed: ${step.description}"
                        )
                    )
                    conversationHistory.add(
                        Message(
                            role = "user",
                            content = listOf(ContentBlock.TextContent(
                                text = "Step failed: ${step.description}. Try something else."
                            ))
                        )
                    )
                    break // Re-plan
                }

                addStatus("Step completed: ${step.description}")
                ConversationHistoryManager.add(
                    ConversationItem(
                        timestamp = System.currentTimeMillis(),
                        type = ConversationItem.ItemType.ACTION_EXECUTED,
                        action = "${step.action} ${step.element ?: ""}",
                        actionDescription = step.description,
                        status = "Executed: ${step.description}"
                    )
                )

                // Short delay between steps for UI to settle
                if (stepIdx < plan.steps.lastIndex) {
                    delay(500)
                }
            }

            if (taskCompleted) break

            // Wait for UI to settle before next iteration
            addStatus("Waiting for UI to settle...")
            delay(1000)
        }

        if (iteration >= maxIterations) {
            addStatus("Maximum iterations reached")
            showToast("Maximum iterations reached")
        }
    }

    private suspend fun executeSemanticStep(
        step: SemanticStep,
        elementMap: ElementMap,
        automationService: AutomationService,
        cursorOverlay: CursorOverlay
    ): Boolean {
        return try {
            when (step.action) {
                SemanticAction.CLICK -> {
                    val element = step.element?.let { elementMap.findById(it) }
                    if (element == null) {
                        Log.e(TAG, "Element not found: ${step.element}")
                        return false
                    }
                    val x = element.bounds.centerX()
                    val y = element.bounds.centerY()
                    Log.d(TAG, "Click ${step.element} at ($x, $y)")
                    cursorOverlay.moveTo(x, y, showClick = true)
                    delay(300)
                    automationService.performTap(x, y)
                }

                SemanticAction.TYPE -> {
                    val text = step.text ?: return false
                    // If element specified, click it first
                    if (step.element != null) {
                        val element = elementMap.findById(step.element)
                        if (element != null) {
                            val x = element.bounds.centerX()
                            val y = element.bounds.centerY()
                            cursorOverlay.moveTo(x, y, showClick = true)
                            delay(300)
                            automationService.performTap(x, y)
                            delay(300)
                        }
                    }
                    automationService.performType(text)
                }

                SemanticAction.SWIPE -> {
                    val direction = step.direction ?: "down"
                    val screenW = elementMap.screenWidth
                    val screenH = elementMap.screenHeight
                    val centerX = screenW / 2
                    val centerY = screenH / 2
                    val swipeDistance = screenH / 3

                    val (startX, startY, endX, endY) = when (direction) {
                        "up" -> listOf(centerX, centerY + swipeDistance / 2, centerX, centerY - swipeDistance / 2)
                        "down" -> listOf(centerX, centerY - swipeDistance / 2, centerX, centerY + swipeDistance / 2)
                        "left" -> listOf(screenW * 3 / 4, centerY, screenW / 4, centerY)
                        "right" -> listOf(screenW / 4, centerY, screenW * 3 / 4, centerY)
                        else -> listOf(centerX, centerY + swipeDistance / 2, centerX, centerY - swipeDistance / 2)
                    }
                    automationService.performSwipe(startX, startY, endX, endY, 500)
                }

                SemanticAction.BACK -> automationService.performBack()
                SemanticAction.HOME -> automationService.performHome()

                SemanticAction.WAIT -> {
                    delay(1000)
                    true
                }

                SemanticAction.COMPLETE -> true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute step: ${step.action}", e)
            false
        }
    }

    private fun showToast(message: String) {
        orchestratorScope.launch(Dispatchers.Main) {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showTaskSuggestionDialog(failureReason: String, conversationHistory: List<Message>) {
        orchestratorScope.launch(Dispatchers.Main) {
            TaskSuggestionOverlay.getInstance(context).show(
                originalTask = currentTask,
                failureReason = failureReason,
                conversationHistory = conversationHistory
            )
        }
    }

    companion object {
        private const val TAG = "AgentOrchestrator"

        @Volatile
        private var instance: AgentOrchestrator? = null

        fun getInstance(context: Context): AgentOrchestrator {
            return instance ?: synchronized(this) {
                instance ?: AgentOrchestrator(context.applicationContext).also { instance = it }
            }
        }
    }
}
