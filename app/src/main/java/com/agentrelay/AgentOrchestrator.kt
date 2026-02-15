package com.agentrelay

import android.content.Context
import android.graphics.*
import android.util.Base64
import android.util.Log
import android.widget.Toast
import com.agentrelay.models.*
import com.agentrelay.ocr.OCRClient
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream

private data class StepExecutionResult(
    val success: Boolean,
    val clickX: Int? = null,
    val clickY: Int? = null,
    val chosenElementId: String? = null,
    val chosenElementText: String? = null
)

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
        val secStore = SecureStorage.getInstance(context)
        val selectedModel = secStore.getModel()
        val apiKey = secStore.getApiKeyForModel(selectedModel)
        if (apiKey.isNullOrEmpty()) {
            val provider = SecureStorage.providerForModel(selectedModel)
            showToast("Please set ${provider.name} API key in settings")
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
        isRunning = true

        // Hide floating bubble while agent is running, show status overlay
        FloatingBubble.getInstance(context).hide()
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
        // Restore floating bubble when agent stops
        if (SecureStorage.getInstance(context).getFloatingBubbleEnabled()) {
            FloatingBubble.getInstance(context).show()
        }
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

        // Planning agent state
        var currentPlan: PlanningResult? = null
        var lastPlanConsultIteration = -10
        var failuresSinceLastPlan = 0
        val planningEnabled = secureStorage.getPlanningEnabled()
        val planningModel = secureStorage.getPlanningModel()
        val planningApiKey = secureStorage.getApiKeyForModel(planningModel)
        val planningAgent = if (planningEnabled && planningApiKey != null) PlanningAgent(planningApiKey, planningModel) else null

        // Initial planning
        if (planningAgent != null) {
            addStatus("Planning strategy...")
            try {
                CursorOverlay.getInstance(context).hide()
                StatusOverlay.getInstance(context).hide()
                delay(100)
                val initScreenshot = captureService.captureScreenshot()
                CursorOverlay.getInstance(context).show()
                StatusOverlay.getInstance(context).show()

                var initElementMapText: String? = null
                if (initScreenshot != null) {
                    val initTree = treeExtractor.extract()
                    val initMapGen = ElementMapGenerator(initScreenshot.actualWidth, initScreenshot.actualHeight)
                    val initMap = initMapGen.generate(initTree, emptyList())
                    initElementMapText = initMap.toTextRepresentation()
                }

                currentPlan = planningAgent.planInitial(task, initScreenshot, initElementMapText)
                if (currentPlan != null) {
                    val approachName = currentPlan!!.approaches.getOrNull(currentPlan!!.recommendedIndex)?.name ?: "Default"
                    addStatus("Strategy: $approachName")
                    ConversationHistoryManager.add(
                        ConversationItem(
                            timestamp = System.currentTimeMillis(),
                            type = ConversationItem.ItemType.PLANNING,
                            response = currentPlan!!.toGuidanceText(),
                            status = "Planning: $approachName"
                        )
                    )
                } else {
                    addStatus("Planning skipped (no result)")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Initial planning failed, continuing without", e)
                addStatus("Planning failed, continuing without strategy")
            }
        }

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
                    failuresSinceLastPlan++
                    delay(1000)
                    continue
                }
            } else {
                sameMapCount = 0
            }
            lastElementMapText = currentMapText

            // 4. Send screenshot + element map to Claude
            addStatus("Sending to Claude...")
            val enhancedTask = buildString {
                // Prepend planning guidance if available
                if (currentPlan != null) {
                    append(currentPlan!!.toGuidanceText())
                    append("\n")
                }
                append(task)
                if (failureContext.isNotEmpty()) {
                    append("\n\nCONTEXT - Previous issues:\n")
                    failureContext.takeLast(3).forEach { append("- $it\n") }
                    append("\nTry a different approach based on the strategic guidance above.")
                }
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
                    status = "Plan: ${plan.steps.size} steps - ${plan.reasoning}",
                    elementMapText = currentMapText
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
                val result = executeSemanticStep(step, elementMap, automationService, cursorOverlay)

                if (!result.success) {
                    addStatus("Step failed: ${step.description}")
                    failureContext.add("Step failed: ${step.description}")
                    failuresSinceLastPlan++
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

                    // Consult planning agent on repeated failures
                    if (planningAgent != null && failuresSinceLastPlan >= 3 && iteration - lastPlanConsultIteration >= 3) {
                        addStatus("Consulting planning agent for recovery...")
                        try {
                            CursorOverlay.getInstance(context).hide()
                            StatusOverlay.getInstance(context).hide()
                            delay(100)
                            val recoveryScreenshot = captureService.captureScreenshot()
                            CursorOverlay.getInstance(context).show()
                            StatusOverlay.getInstance(context).show()

                            val recoveryPlan = planningAgent.planRecovery(
                                task, recoveryScreenshot, currentMapText,
                                failureContext, currentPlan
                            )
                            if (recoveryPlan != null) {
                                currentPlan = recoveryPlan
                                val approachName = recoveryPlan.approaches.getOrNull(recoveryPlan.recommendedIndex)?.name ?: "Recovery"
                                addStatus("New strategy: $approachName")
                                ConversationHistoryManager.add(
                                    ConversationItem(
                                        timestamp = System.currentTimeMillis(),
                                        type = ConversationItem.ItemType.PLANNING,
                                        response = recoveryPlan.toGuidanceText(),
                                        status = "Recovery plan: $approachName"
                                    )
                                )
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Recovery planning failed", e)
                        }
                        failuresSinceLastPlan = 0
                        lastPlanConsultIteration = iteration
                    }

                    break // Re-plan
                }

                // Build annotated screenshot if we have click coordinates
                val annotated = if (result.clickX != null && result.clickY != null) {
                    createAnnotatedScreenshot(
                        screenshotInfo, result.clickX, result.clickY,
                        result.chosenElementId, elementMap
                    )
                } else null

                addStatus("Step completed: ${step.description}")
                ConversationHistoryManager.add(
                    ConversationItem(
                        timestamp = System.currentTimeMillis(),
                        type = ConversationItem.ItemType.ACTION_EXECUTED,
                        action = "${step.action} ${step.element ?: ""}",
                        actionDescription = step.description,
                        status = "Executed: ${step.description}",
                        elementMapText = currentMapText,
                        chosenElementId = result.chosenElementId,
                        chosenElementText = result.chosenElementText,
                        clickX = result.clickX,
                        clickY = result.clickY,
                        annotatedScreenshot = annotated
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

        // Last-resort planning consultation near max iterations
        if (planningAgent != null && iteration == maxIterations - 5 && failuresSinceLastPlan > 0) {
            addStatus("Last-resort planning consultation...")
            try {
                CursorOverlay.getInstance(context).hide()
                StatusOverlay.getInstance(context).hide()
                delay(100)
                val lastResortScreenshot = captureService.captureScreenshot()
                CursorOverlay.getInstance(context).show()
                StatusOverlay.getInstance(context).show()

                val lastResortPlan = planningAgent.planRecovery(
                    task, lastResortScreenshot, lastElementMapText,
                    failureContext, currentPlan
                )
                if (lastResortPlan != null) {
                    currentPlan = lastResortPlan
                    val approachName = lastResortPlan.approaches.getOrNull(lastResortPlan.recommendedIndex)?.name ?: "Last Resort"
                    addStatus("Final strategy: $approachName")
                    ConversationHistoryManager.add(
                        ConversationItem(
                            timestamp = System.currentTimeMillis(),
                            type = ConversationItem.ItemType.PLANNING,
                            response = lastResortPlan.toGuidanceText(),
                            status = "Final strategy: $approachName"
                        )
                    )
                    failuresSinceLastPlan = 0
                }
            } catch (e: Exception) {
                Log.w(TAG, "Last-resort planning failed", e)
            }
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
    ): StepExecutionResult {
        return try {
            when (step.action) {
                SemanticAction.CLICK -> {
                    val element = step.element?.let { elementMap.findById(it) }
                    if (element == null) {
                        Log.e(TAG, "Element not found: ${step.element}")
                        return StepExecutionResult(false)
                    }
                    val x = element.bounds.centerX()
                    val y = element.bounds.centerY()
                    Log.d(TAG, "Click ${step.element} at ($x, $y)")
                    cursorOverlay.moveTo(x, y, showClick = true)
                    delay(300)
                    val ok = automationService.performTap(x, y)
                    StepExecutionResult(ok, x, y, step.element, element.text)
                }

                SemanticAction.TYPE -> {
                    val text = step.text ?: return StepExecutionResult(false)
                    var tapX: Int? = null
                    var tapY: Int? = null
                    var elText: String? = null
                    if (step.element != null) {
                        val element = elementMap.findById(step.element)
                        if (element != null) {
                            tapX = element.bounds.centerX()
                            tapY = element.bounds.centerY()
                            elText = element.text
                            cursorOverlay.moveTo(tapX, tapY, showClick = true)
                            delay(300)
                            automationService.performTap(tapX, tapY)
                            delay(300)
                        }
                    }
                    val ok = automationService.performType(text)
                    StepExecutionResult(ok, tapX, tapY, step.element, elText)
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
                    val ok = automationService.performSwipe(startX, startY, endX, endY, 500)
                    StepExecutionResult(ok, startX, startY)
                }

                SemanticAction.BACK -> StepExecutionResult(automationService.performBack())
                SemanticAction.HOME -> StepExecutionResult(automationService.performHome())

                SemanticAction.WAIT -> {
                    delay(1000)
                    StepExecutionResult(true)
                }

                SemanticAction.COMPLETE -> StepExecutionResult(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute step: ${step.action}", e)
            StepExecutionResult(false)
        }
    }

    /**
     * Draws a crosshair + label on the screenshot at the tap location.
     * Returns base64-encoded annotated JPEG.
     */
    private fun createAnnotatedScreenshot(
        screenshotInfo: ScreenshotInfo,
        clickX: Int,
        clickY: Int,
        elementId: String?,
        elementMap: ElementMap
    ): String? {
        return try {
            val imageBytes = Base64.decode(screenshotInfo.base64Data, Base64.NO_WRAP)
            val original = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                ?: return null
            val bitmap = original.copy(Bitmap.Config.ARGB_8888, true)
            original.recycle()
            val canvas = Canvas(bitmap)

            // Scale click coords from screen space to screenshot bitmap space
            val scaleX = bitmap.width.toFloat() / screenshotInfo.actualWidth
            val scaleY = bitmap.height.toFloat() / screenshotInfo.actualHeight
            val cx = (clickX * scaleX)
            val cy = (clickY * scaleY)

            // Draw outer ring
            val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.RED
                style = Paint.Style.STROKE
                strokeWidth = 4f
            }
            canvas.drawCircle(cx, cy, 28f, ringPaint)

            // Draw crosshair lines
            val crossPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.RED
                strokeWidth = 2f
            }
            canvas.drawLine(cx - 40, cy, cx + 40, cy, crossPaint)
            canvas.drawLine(cx, cy - 40, cx, cy + 40, crossPaint)

            // Draw center dot
            val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.RED
                style = Paint.Style.FILL
            }
            canvas.drawCircle(cx, cy, 5f, dotPaint)

            // Draw element bounding box if available
            if (elementId != null) {
                val element = elementMap.findById(elementId)
                if (element != null) {
                    val rectPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        color = Color.argb(120, 0, 255, 0)
                        style = Paint.Style.STROKE
                        strokeWidth = 3f
                    }
                    val r = element.bounds
                    canvas.drawRect(
                        r.left * scaleX, r.top * scaleY,
                        r.right * scaleX, r.bottom * scaleY,
                        rectPaint
                    )
                }
            }

            // Label
            val label = elementId ?: "($clickX, $clickY)"
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = 24f
                typeface = Typeface.DEFAULT_BOLD
                setShadowLayer(4f, 0f, 0f, Color.BLACK)
            }
            val bgPaint = Paint().apply {
                color = Color.argb(180, 0, 0, 0)
                style = Paint.Style.FILL
            }
            val textWidth = labelPaint.measureText(label)
            val labelX = (cx - textWidth / 2).coerceIn(4f, bitmap.width - textWidth - 4f)
            val labelY = (cy - 44).coerceAtLeast(28f)
            canvas.drawRoundRect(
                labelX - 6, labelY - 22, labelX + textWidth + 6, labelY + 6,
                8f, 8f, bgPaint
            )
            canvas.drawText(label, labelX, labelY, labelPaint)

            // Encode
            val out = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            bitmap.recycle()
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create annotated screenshot", e)
            null
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
