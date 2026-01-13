package com.agentrelay

import android.content.Context
import android.util.Log
import android.widget.Toast
import com.agentrelay.models.AgentAction
import com.agentrelay.models.ContentBlock
import com.agentrelay.models.Message
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
        addStatus("🚀 Agent started for task: $task")
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
                addStatus("❌ Error: ${e.message}")
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
        addStatus("⏹️ Agent stopped")
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

        var iteration = 0
        val maxIterations = 50
        var consecutiveFailures = 0
        var lastScreenshotHash = 0
        var screenshotFailureCount = 0
        val failureContext = mutableListOf<String>()

        while (isRunning && iteration < maxIterations) {
            iteration++
            Log.d(TAG, "Agent iteration $iteration")
            addStatus("🔄 Iteration $iteration/$maxIterations")

            // Capture screenshot
            addStatus("📸 Capturing screenshot...")
            // Hide overlays to get clean screenshot
            CursorOverlay.getInstance(context).hide()
            StatusOverlay.getInstance(context).hide()
            delay(100) // Brief delay to ensure overlays are hidden
            val screenshotInfo = captureService.captureScreenshot()
            // Show overlays again
            CursorOverlay.getInstance(context).show()
            StatusOverlay.getInstance(context).show()
            if (screenshotInfo == null) {
                screenshotFailureCount++
                Log.e(TAG, "Failed to capture screenshot (attempt $screenshotFailureCount)")
                addStatus("❌ Screenshot failed (attempt $screenshotFailureCount), retrying...")
                ConversationHistoryManager.add(
                    ConversationItem(
                        timestamp = System.currentTimeMillis(),
                        type = ConversationItem.ItemType.ERROR,
                        status = "Screenshot capture failed (attempt $screenshotFailureCount)"
                    )
                )

                // After 2 consecutive failures, prompt user to fix permissions
                if (screenshotFailureCount >= 2) {
                    addStatus("⚠️ Multiple screenshot failures - please check screen capture permissions")
                    showToast("Screen capture failed. Please restart the app and grant permissions.")
                    PermissionFixOverlay.getInstance(context).show()
                    break
                }

                delay(1000)
                continue
            }

            // Reset failure count on success
            screenshotFailureCount = 0
            addStatus("✅ Screenshot captured (${screenshotInfo.actualWidth}x${screenshotInfo.actualHeight})")
            ConversationHistoryManager.add(
                ConversationItem(
                    timestamp = System.currentTimeMillis(),
                    type = ConversationItem.ItemType.SCREENSHOT_CAPTURED,
                    screenshot = screenshotInfo.base64Data,
                    screenshotWidth = screenshotInfo.actualWidth,
                    screenshotHeight = screenshotInfo.actualHeight,
                    status = "Screenshot captured: ${screenshotInfo.actualWidth}x${screenshotInfo.actualHeight} (scaled to ${screenshotInfo.scaledWidth}x${screenshotInfo.scaledHeight})"
                )
            )

            // Send to Claude with failure context
            addStatus("🤖 Sending to Claude Opus 4.5...")

            // Build enhanced task description with failure context
            val enhancedTask = if (failureContext.isNotEmpty()) {
                buildString {
                    append("$task\n\n")
                    append("IMPORTANT CONTEXT - Previous failures:\n")
                    failureContext.takeLast(3).forEach { failure ->
                        append("- $failure\n")
                    }
                    append("\nPlease try a different approach to avoid repeating failed actions.")
                }
            } else {
                task
            }

            val actionResult = claudeClient.sendWithScreenshot(
                screenshotInfo,
                enhancedTask,
                conversationHistory
            )

            if (actionResult.isFailure) {
                Log.e(TAG, "Claude API failed: ${actionResult.exceptionOrNull()?.message}")
                addStatus("❌ API error: ${actionResult.exceptionOrNull()?.message}")
                showToast("API error, retrying...")
                ConversationHistoryManager.add(
                    ConversationItem(
                        timestamp = System.currentTimeMillis(),
                        type = ConversationItem.ItemType.ERROR,
                        status = "API error: ${actionResult.exceptionOrNull()?.message}"
                    )
                )
                delay(2000)
                continue
            }

            val actionWithDesc = actionResult.getOrNull() ?: continue
            val action = actionWithDesc.action
            val description = actionWithDesc.description

            addStatus("✅ Claude: $description")
            ConversationHistoryManager.add(
                ConversationItem(
                    timestamp = System.currentTimeMillis(),
                    type = ConversationItem.ItemType.API_RESPONSE,
                    response = description,
                    action = actionToString(action),
                    actionDescription = description,
                    status = "Claude responded: $description"
                )
            )

            // Add to conversation history
            conversationHistory.add(
                Message(
                    role = "assistant",
                    content = listOf(ContentBlock.TextContent(text = "Executing: $description - $action"))
                )
            )

            // Scale action coordinates from screenshot to actual screen
            val scaledAction = scaleAction(action, screenshotInfo)

            // Log coordinate info for debugging
            if (action is AgentAction.Tap && scaledAction is AgentAction.Tap) {
                addStatus("📍 Original: (${action.x}, ${action.y}) → Scaled: (${scaledAction.x}, ${scaledAction.y})")
                addStatus("📐 Screen: ${screenshotInfo.actualWidth}x${screenshotInfo.actualHeight}, Screenshot: ${screenshotInfo.scaledWidth}x${screenshotInfo.scaledHeight}")
            }

            // Before tapping, take a fresh screenshot to verify element still exists
            if (action is AgentAction.Tap || action is AgentAction.Swipe) {
                addStatus("🔍 Verifying element still exists...")
                delay(100) // Short delay to let UI settle
                // Hide overlays for clean verification screenshot
                CursorOverlay.getInstance(context).hide()
                StatusOverlay.getInstance(context).hide()
                delay(50)
                val verifyScreenshot = captureService.captureScreenshot()
                // Show overlays again
                CursorOverlay.getInstance(context).show()
                StatusOverlay.getInstance(context).show()
                if (verifyScreenshot == null) {
                    addStatus("⚠️ Could not verify - proceeding anyway")
                } else {
                    // Check if screenshot is identical to previous
                    val currentHash = verifyScreenshot.base64Data.hashCode()
                    if (lastScreenshotHash != 0 && currentHash == lastScreenshotHash) {
                        consecutiveFailures++
                        val failureMessage = "Screen unchanged after attempt $consecutiveFailures: tried $description"
                        addStatus("⚠️ $failureMessage")

                        // Add to failure context for Claude
                        failureContext.add(failureMessage)
                        if (failureContext.size > 5) {
                            failureContext.removeAt(0) // Keep only last 5
                        }

                        // Add feedback to conversation history asking Claude to characterize what it sees
                        conversationHistory.add(
                            Message(
                                role = "user",
                                content = listOf(ContentBlock.TextContent(
                                    text = "⚠️ WARNING: Your last action ($description) did NOT change the screen. " +
                                            "In the next screenshot, carefully describe what you actually see and what element you may have clicked by mistake. " +
                                            "If you keep seeing the same screen, you may be clicking the wrong element (e.g., keyboard letters instead of search button). " +
                                            "Analyze the screenshot with the grid overlay to understand what's at the coordinates you clicked."
                                ))
                            )
                        )

                        if (consecutiveFailures >= 3) {
                            addStatus("🔄 Detected loop - trying back button...")
                            automationService.performBack()
                            consecutiveFailures = 0
                            failureContext.add("Pressed back button to escape loop")
                            delay(1000)
                            continue
                        }
                    } else {
                        // Screen changed successfully
                        if (consecutiveFailures > 0) {
                            addStatus("✅ Screen changed - progress made")
                            // Add positive feedback to conversation
                            conversationHistory.add(
                                Message(
                                    role = "user",
                                    content = listOf(ContentBlock.TextContent(
                                        text = "✅ Good! The screen changed after your last action. Continue with your task."
                                    ))
                                )
                            )
                        }
                        consecutiveFailures = 0
                    }
                    lastScreenshotHash = currentHash
                }
            }

            // Execute action
            addStatus("⚡ Executing: $description")
            val success = executeAction(scaledAction, automationService, cursorOverlay)

            if (!success) {
                Log.w(TAG, "Action execution failed: $action")
                addStatus("❌ Action failed")
                ConversationHistoryManager.add(
                    ConversationItem(
                        timestamp = System.currentTimeMillis(),
                        type = ConversationItem.ItemType.ERROR,
                        status = "Action execution failed: $description"
                    )
                )
                conversationHistory.add(
                    Message(
                        role = "user",
                        content = listOf(ContentBlock.TextContent(text = "Action failed, please try something else"))
                    )
                )
            } else {
                addStatus("✅ Action completed")
                ConversationHistoryManager.add(
                    ConversationItem(
                        timestamp = System.currentTimeMillis(),
                        type = ConversationItem.ItemType.ACTION_EXECUTED,
                        action = actionToString(action),
                        actionDescription = description,
                        status = "Successfully executed: $description"
                    )
                )
            }

            // Check if task is complete
            if (action is AgentAction.Complete) {
                // Check if it's a failure (message starts with "Cannot complete")
                if (action.message.startsWith("Cannot complete", ignoreCase = true)) {
                    addStatus("⚠️ Task failed: ${action.message}")
                    // Show suggestion dialog instead of just stopping
                    showTaskSuggestionDialog(action.message, conversationHistory)
                    break
                } else {
                    addStatus("🎉 Task completed: ${action.message}")
                    showToast("Task completed: ${action.message}")
                    break
                }
            }

            // Wait for UI to settle
            addStatus("⏳ Waiting for UI to settle...")
            delay(1000)
        }

        if (iteration >= maxIterations) {
            addStatus("⚠️ Maximum iterations reached")
            showToast("Maximum iterations reached")
        }
    }

    private fun actionToString(action: AgentAction): String {
        return when (action) {
            is AgentAction.Tap -> "Tap at (${action.x}, ${action.y})"
            is AgentAction.Swipe -> "Swipe from (${action.startX}, ${action.startY}) to (${action.endX}, ${action.endY})"
            is AgentAction.Type -> "Type: ${action.text}"
            is AgentAction.Back -> "Press Back"
            is AgentAction.Home -> "Press Home"
            is AgentAction.Wait -> "Wait ${action.ms}ms"
            is AgentAction.Complete -> "Complete: ${action.message}"
            is AgentAction.Error -> "Error: ${action.message}"
        }
    }

    private fun scaleAction(action: AgentAction, screenshotInfo: ScreenshotInfo): AgentAction {
        val scaleX = screenshotInfo.actualWidth.toFloat() / screenshotInfo.scaledWidth
        val scaleY = screenshotInfo.actualHeight.toFloat() / screenshotInfo.scaledHeight

        Log.d(TAG, "Scaling coordinates: scaleX=$scaleX, scaleY=$scaleY")

        return when (action) {
            is AgentAction.Tap -> {
                val scaledX = (action.x * scaleX).toInt()
                val scaledY = (action.y * scaleY).toInt()
                Log.d(TAG, "Tap: (${action.x}, ${action.y}) -> scaled ($scaledX, $scaledY)")
                AgentAction.Tap(scaledX, scaledY)
            }
            is AgentAction.Swipe -> {
                val scaledStartX = (action.startX * scaleX).toInt()
                val scaledStartY = (action.startY * scaleY).toInt()
                val scaledEndX = (action.endX * scaleX).toInt()
                val scaledEndY = (action.endY * scaleY).toInt()
                AgentAction.Swipe(scaledStartX, scaledStartY, scaledEndX, scaledEndY, action.duration)
            }
            else -> action // Other actions don't need scaling
        }
    }

    private suspend fun executeAction(
        action: AgentAction,
        automationService: AutomationService,
        cursorOverlay: CursorOverlay
    ): Boolean {
        Log.d(TAG, "Executing action: $action")

        return try {
            when (action) {
                is AgentAction.Tap -> {
                    cursorOverlay.moveTo(action.x, action.y, showClick = true)
                    delay(300) // Wait for cursor animation
                    automationService.performTap(action.x, action.y)
                }

                is AgentAction.Swipe -> {
                    cursorOverlay.moveTo(action.startX, action.startY, showClick = false)
                    delay(300)
                    automationService.performSwipe(
                        action.startX,
                        action.startY,
                        action.endX,
                        action.endY,
                        action.duration
                    )
                    cursorOverlay.moveTo(action.endX, action.endY, showClick = false)
                }

                is AgentAction.Type -> {
                    automationService.performType(action.text)
                }

                is AgentAction.Back -> {
                    automationService.performBack()
                }

                is AgentAction.Home -> {
                    automationService.performHome()
                }

                is AgentAction.Wait -> {
                    delay(action.ms)
                    true
                }

                is AgentAction.Complete -> {
                    true
                }

                is AgentAction.Error -> {
                    Log.e(TAG, "Agent returned error: ${action.message}")
                    showToast("Agent error: ${action.message}")
                    false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute action", e)
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
            // Show the overlay window with suggestions
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
