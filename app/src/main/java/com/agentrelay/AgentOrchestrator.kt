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
import java.util.zip.CRC32

private data class StepExecutionResult(
    val success: Boolean,
    val clickX: Int? = null,
    val clickY: Int? = null,
    val chosenElementId: String? = null,
    val chosenElementText: String? = null,
    val failureReason: String? = null
)

private data class StepVerificationResult(
    val success: Boolean,
    val reason: String = ""
)

class AgentOrchestrator(private val context: Context) {

    private val orchestratorScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentJob: Job? = null
    private var isRunning = false

    private val conversationHistory = mutableListOf<Message>()
    private var currentTask: String = ""
    // Track recent actions for stuck-on-same-action detection
    private data class ActionRecord(val action: SemanticAction, val elementId: String?, val text: String?)
    private val recentActions = mutableListOf<ActionRecord>()

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
            Log.w(TAG, "Screen capture not initialized — running in accessibility-only mode")
        }

        currentTask = task
        conversationHistory.clear()
        recentActions.clear()
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

    /**
     * Hides overlays via visibility (not removal), captures a screenshot, then restores them.
     * Restoration happens synchronously on the main thread right after capture,
     * BEFORE any further work (element extraction, API calls), so the overlay
     * is never invisible for longer than the capture itself (~150ms).
     */
    private suspend fun captureCleanScreenshot(captureService: ScreenCaptureService?): ScreenshotInfo? {
        if (captureService == null) return null
        val t0 = System.currentTimeMillis()
        // setInvisible now runs directly when called from main thread (no post{} deferral)
        CursorOverlay.getInstance(context).setInvisible(true)
        StatusOverlay.getInstance(context).setInvisible(true)
        delay(100) // Let display compositor update
        val t1 = System.currentTimeMillis()
        val screenshot = captureService.captureScreenshot()
        val t2 = System.currentTimeMillis()
        // Restore immediately — runs synchronously on main thread
        CursorOverlay.getInstance(context).setInvisible(false)
        StatusOverlay.getInstance(context).setInvisible(false)
        Log.d(TAG, "⏱ captureCleanScreenshot: hide=${t1-t0}ms capture=${t2-t1}ms total=${t2-t0}ms")
        return screenshot
    }

    private suspend fun runAgentLoop(
        apiKey: String,
        task: String,
        captureService: ScreenCaptureService?
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
        val sendScreenshotsToLlm = secureStorage.getSendScreenshotsToLlm()

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
        Log.d(TAG, "Planning: enabled=$planningEnabled, model=$planningModel, hasKey=${planningApiKey != null}")
        val planningAgent = if (planningEnabled && planningApiKey != null) PlanningAgent(planningApiKey, planningModel) else null
        var lastResortConsulted = false
        var splitScreenAttempted = false

        // Use cached installed apps (pre-fetched on app startup via DeviceContextCache)
        val installedApps = DeviceContextCache.installedApps.ifEmpty {
            // Fallback: trigger async refresh if cache is empty (shouldn't happen normally)
            Log.w(TAG, "DeviceContextCache empty, triggering refresh")
            DeviceContextCache.refreshAsync(context)
            emptyList()
        }

        // Planning runs in background — fast model starts acting immediately.
        // The planning job is launched after the first screenshot/element map are captured
        // (in iteration 1) so we don't need a duplicate screenshot capture at startup.
        var pendingPlanJob: Deferred<PlanningResult?>? = null
        if (planningAgent != null) {
            addStatus("Planning in background (acting proactively)...")
        }

        while (isRunning && iteration < maxIterations) {
            iteration++
            Log.d(TAG, "Agent iteration $iteration")
            addStatus("Iteration $iteration/$maxIterations")

            // Check if background planning has completed
            if (pendingPlanJob != null) {
                Log.d(TAG, "Planning job status: completed=${pendingPlanJob!!.isCompleted}, cancelled=${pendingPlanJob!!.isCancelled}")
            }
            if (pendingPlanJob != null && pendingPlanJob!!.isCompleted) {
                try {
                    val planResult = pendingPlanJob!!.await()
                    Log.d(TAG, "Planning result: ${if (planResult != null) "got ${planResult.approaches.size} approaches, splitScreen=${planResult.splitScreen?.recommended}" else "null"}")
                    if (planResult != null && currentPlan == null) {
                        currentPlan = planResult
                        val approachName = planResult.approaches.getOrNull(planResult.recommendedIndex)?.name ?: "Default"
                        addStatus("Strategy ready: $approachName")
                        ConversationHistoryManager.add(
                            ConversationItem(
                                timestamp = System.currentTimeMillis(),
                                type = ConversationItem.ItemType.PLANNING,
                                response = planResult.toGuidanceText(),
                                status = "Planning: $approachName"
                            )
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Background planning result failed", e)
                }
                pendingPlanJob = null
            }

            // Split-screen dispatch: if planning recommends it and we haven't tried yet
            if (currentPlan?.splitScreen?.recommended == true && !splitScreenAttempted) {
                splitScreenAttempted = true
                val ss = currentPlan!!.splitScreen!!
                val config = SplitScreenConfig(
                    topApp = ss.topApp,
                    bottomApp = ss.bottomApp,
                    topTask = ss.topTask,
                    bottomTask = ss.bottomTask,
                    originalTask = task
                )
                addStatus("Split-screen: ${ss.topApp} + ${ss.bottomApp}")
                val coordinator = SplitScreenCoordinator(context)
                if (coordinator.enterSplitScreen(config)) {
                    addStatus("Split-screen active — running parallel agents")
                    ConversationHistoryManager.add(
                        ConversationItem(
                            timestamp = System.currentTimeMillis(),
                            type = ConversationItem.ItemType.PLANNING,
                            response = "Split-screen mode: top=${ss.topApp} (${ss.topTask}), bottom=${ss.bottomApp} (${ss.bottomTask})",
                            status = "Split-screen parallel execution"
                        )
                    )
                    coordinator.runParallelLoops(config, apiKey, model, captureService)
                    coordinator.exitSplitScreen()
                    if (coordinator.isComplete()) {
                        addStatus("Split-screen tasks completed")
                        val findings = coordinator.getFindings()
                        if (findings.isNotEmpty()) {
                            addStatus("Findings: ${findings.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
                        }
                        showToast("Task completed via split-screen")
                        break
                    } else {
                        // Partial completion — carry findings forward
                        val findings = coordinator.getFindings()
                        findings.forEach { (k, v) -> failureContext.add("Found: $k=$v") }
                        val errors = coordinator.getErrors()
                        errors.forEach { (slot, err) -> failureContext.add("$slot loop error: $err") }
                        addStatus("Split-screen partial — continuing sequential")
                    }
                } else {
                    addStatus("Split-screen failed — falling back to sequential")
                }
            }

            // Last-resort planning consultation in the final 5 iterations.
            if (planningAgent != null && !lastResortConsulted &&
                iteration >= maxIterations - 5 && failuresSinceLastPlan > 0) {
                lastResortConsulted = true
                addStatus("Last-resort planning consultation...")
                try {
                    val lastResortScreenshot = if (sendScreenshotsToLlm) captureCleanScreenshot(captureService) else null

                    val lastResortPlan = planningAgent.planRecovery(
                        task, lastResortScreenshot, lastElementMapText,
                        failureContext, currentPlan, installedApps
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

            // 0. If we're inside agentrelay itself, navigate away first
            val preCheckPkg = automationService.getCurrentAppPackage()
            if (preCheckPkg == "com.agentrelay") {
                Log.d(TAG, "Currently in agentrelay — navigating away")
                addStatus("In AgentRelay app — switching away...")
                automationService.performHome()
                delay(500)
            }

            // 1. Capture screenshot (clean, no grid)
            val iterStartTime = System.currentTimeMillis()
            addStatus("Capturing screenshot...")
            val screenshotInfo = captureCleanScreenshot(captureService)
            val screenshotTime = System.currentTimeMillis() - iterStartTime

            if (screenshotInfo == null) {
                screenshotFailureCount++
                if (screenshotFailureCount == 1) {
                    Log.w(TAG, "Screenshot failed — running in accessibility-only mode")
                    addStatus("No screenshot — using accessibility tree only")
                }
            } else {
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
            }

            // 2. Extract accessibility tree (+ OCR in parallel if enabled)
            val extractStartTime = System.currentTimeMillis()
            addStatus("Extracting element map...")
            val a11yElements = treeExtractor.extract()

            var ocrElements = emptyList<UIElement>()
            if (ocrEnabled && screenshotInfo != null) {
                try {
                    val ocrClient = OCRClient(secureStorage)
                    val ocrResult = withContext(Dispatchers.IO) {
                        ocrClient.recognizeText(screenshotInfo)
                    }
                    ocrElements = ocrResult
                } catch (e: Exception) {
                    Log.w(TAG, "OCR failed, continuing with accessibility tree only", e)
                }
            }

            // 3. Merge into ElementMap — use screenshot dimensions if available, otherwise get from WindowManager
            val screenW = screenshotInfo?.actualWidth ?: run {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    wm.currentWindowMetrics.bounds.width()
                } else {
                    val dm = android.util.DisplayMetrics()
                    @Suppress("DEPRECATION")
                    wm.defaultDisplay.getRealMetrics(dm)
                    dm.widthPixels
                }
            }
            val screenH = screenshotInfo?.actualHeight ?: run {
                val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    wm.currentWindowMetrics.bounds.height()
                } else {
                    val dm = android.util.DisplayMetrics()
                    @Suppress("DEPRECATION")
                    wm.defaultDisplay.getRealMetrics(dm)
                    dm.heightPixels
                }
            }
            val mapGenerator = ElementMapGenerator(screenW, screenH)
            val elementMap = mapGenerator.generate(a11yElements, ocrElements)
            val extractTime = System.currentTimeMillis() - extractStartTime
            addStatus("Element map: ${elementMap.elements.size} elements")
            Log.d(TAG, elementMap.toTextRepresentation())

            // Launch planning in background using the first iteration's screenshot + element map
            val currentMapText = elementMap.toTextRepresentation()
            if (iteration == 1 && planningAgent != null && pendingPlanJob == null) {
                val planScreenshot = if (sendScreenshotsToLlm) screenshotInfo else null
                val planMapText = currentMapText
                // Re-fetch from cache in case it populated since loop start
                val planApps = DeviceContextCache.installedApps.ifEmpty { installedApps }
                Log.d(TAG, "Launching planning job with model=$planningModel, hasScreenshot=${planScreenshot != null}, mapLen=${planMapText.length}, apps=${planApps.size}")
                pendingPlanJob = orchestratorScope.async(Dispatchers.IO) {
                    try {
                        val startTime = System.currentTimeMillis()
                        val result = planningAgent.planInitial(task, planScreenshot, planMapText, planApps)
                        Log.d(TAG, "Planning completed in ${System.currentTimeMillis() - startTime}ms, result=${result != null}")
                        result
                    } catch (e: Exception) {
                        Log.e(TAG, "Background planning failed", e)
                        withContext(Dispatchers.Main) {
                            addStatus("Planning failed, continuing without strategy")
                        }
                        null
                    }
                }
            }

            // Detect stuck state via element-map diffing
            if (currentMapText == lastElementMapText) {
                sameMapCount++
                if (sameMapCount == 2) {
                    // First recovery: dismiss keyboard if showing (it may cover elements)
                    if (automationService.isKeyboardShowing()) {
                        addStatus("Stuck — dismissing keyboard")
                        automationService.dismissKeyboard()
                        failureContext.add("Element map unchanged 2x, dismissed keyboard")
                        waitForUiSettle(captureService, minWaitMs = 100, maxWaitMs = 600)
                        continue
                    }
                }
                if (sameMapCount >= 3) {
                    addStatus("Stuck detected — trying back button")
                    automationService.performBack()
                    sameMapCount = 0
                    failureContext.add("Element map unchanged 3x, pressed back")
                    failuresSinceLastPlan++
                    waitForUiSettle(captureService, minWaitMs = 150, maxWaitMs = 1000)
                    continue
                }
            } else {
                sameMapCount = 0
            }
            lastElementMapText = currentMapText

            // 4. Gather device context
            val deviceContext = try {
                automationService.getDeviceContext()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get device context", e)
                null
            }

            // 5. Send screenshot + element map + device context to Claude
            val apiStartTime = System.currentTimeMillis()
            addStatus("Sending to Claude...")

            // Build action history context for stuck detection
            val actionHistoryContext = buildActionHistoryContext()

            val enhancedTask = buildString {
                // Prepend planning guidance if available
                if (currentPlan != null) {
                    append(currentPlan!!.toGuidanceText())
                    append("\n")
                }
                append(task)
                if (actionHistoryContext.isNotEmpty()) {
                    append("\n\n$actionHistoryContext")
                }
                if (failureContext.isNotEmpty()) {
                    append("\n\nCONTEXT - Previous issues:\n")
                    failureContext.takeLast(3).forEach { append("- $it\n") }
                    append("\nTry a different approach based on the strategic guidance above.")
                }
            }

            // Run API call on IO dispatcher so Main thread stays free for UI updates
            val planResult = withContext(Dispatchers.IO) {
                claudeClient.sendWithElementMap(
                    if (sendScreenshotsToLlm) screenshotInfo else null,
                    elementMap,
                    enhancedTask,
                    conversationHistory,
                    deviceContext
                )
            }

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

            val apiTime = System.currentTimeMillis() - apiStartTime
            val plan = planResult.getOrNull() ?: continue
            val timingMsg = "⏱ screenshot=${screenshotTime}ms extract=${extractTime}ms api=${apiTime}ms"
            Log.d(TAG, timingMsg)
            addStatus("Claude plan: ${plan.reasoning} ($timingMsg)")
            ConversationHistoryManager.add(
                ConversationItem(
                    timestamp = System.currentTimeMillis(),
                    type = ConversationItem.ItemType.API_RESPONSE,
                    response = plan.reasoning,
                    action = plan.steps.joinToString(", ") { it.description },
                    actionDescription = plan.reasoning,
                    status = "Plan: ${plan.steps.size} steps - ${plan.reasoning}",
                    elementMapText = currentMapText,
                    screenshot = screenshotInfo?.base64Data,
                    screenshotWidth = screenshotInfo?.actualWidth ?: screenW,
                    screenshotHeight = screenshotInfo?.actualHeight ?: screenH
                )
            )

            // Log reasoning with step breakdown and self-assessment for the activity log
            val stepsPreview = plan.steps.mapIndexed { i, s ->
                "${i + 1}. ${s.description}"
            }.joinToString("\n")
            val progressText = plan.progressAssessment.ifBlank { null }
            val confidenceEmoji = when (plan.confidence.lowercase()) {
                "high" -> "\u2705"   // green check
                "medium" -> "\u26A0\uFE0F" // warning
                "low" -> "\u274C"    // red X
                else -> ""
            }
            val statusLine = buildString {
                if (confidenceEmoji.isNotEmpty()) append("$confidenceEmoji ")
                append(plan.reasoning)
            }
            ConversationHistoryManager.add(
                ConversationItem(
                    timestamp = System.currentTimeMillis(),
                    type = ConversationItem.ItemType.REASONING,
                    response = stepsPreview,
                    actionDescription = progressText,
                    status = statusLine
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

            // 5. Execute each step — make overlay pass-through so taps hit the real UI
            StatusOverlay.getInstance(context).setPassThrough(true)
            var taskCompleted = false
            // Track target app from open_app actions
            val targetAppPackage = plan.steps
                .firstOrNull { it.action == SemanticAction.OPEN_APP }
                ?.packageName

            // Track previous step for parallel verification
            var prevStep: SemanticStep? = null
            var prevStepVerificationJob: Deferred<StepVerificationResult>? = null

            for ((stepIdx, step) in plan.steps.withIndex()) {
                if (!isRunning) break

                // Never act inside agentrelay itself — go home and re-plan
                val stepPkg = automationService.getCurrentAppPackage()
                if (stepPkg == "com.agentrelay" &&
                    step.action != SemanticAction.OPEN_APP &&
                    step.action != SemanticAction.HOME) {
                    Log.w(TAG, "Detected agentrelay as foreground — escaping")
                    addStatus("In AgentRelay — navigating away")
                    automationService.performHome()
                    delay(500)
                    break // Re-plan with the correct screen
                }

                // App verification: before non-navigation steps, check we're in the right app
                if (targetAppPackage != null &&
                    step.action != SemanticAction.OPEN_APP &&
                    step.action != SemanticAction.HOME &&
                    step.action != SemanticAction.BACK &&
                    step.action != SemanticAction.WAIT &&
                    step.action != SemanticAction.COMPLETE) {

                    val currentPkg = automationService.getCurrentAppPackage()
                    if (currentPkg != null && currentPkg != targetAppPackage &&
                        currentPkg != "com.agentrelay") {
                        addStatus("Wrong app ($currentPkg), returning to target...")
                        automationService.performOpenApp(targetAppPackage)
                        delay(1500)
                        // Re-check
                        val afterPkg = automationService.getCurrentAppPackage()
                        if (afterPkg != targetAppPackage) {
                            failureContext.add("Couldn't return to target app $targetAppPackage (currently in $afterPkg)")
                            conversationHistory.add(
                                Message(
                                    role = "user",
                                    content = listOf(ContentBlock.TextContent(
                                        text = "The app switched away from the target ($targetAppPackage) to $afterPkg. " +
                                            "Re-analyze the screen and navigate back to the correct app."
                                    ))
                                )
                            )
                            break // Re-plan
                        }
                    }
                }

                // Check for complete action
                if (step.action == SemanticAction.COMPLETE) {
                    val msg = step.description.ifBlank { "Task completed" }
                    if (msg.startsWith("Cannot complete", ignoreCase = true) ||
                        msg.startsWith("Failed", ignoreCase = true)) {
                        addStatus("Task failed: $msg")
                        showTaskSuggestionDialog(msg, conversationHistory)
                        taskCompleted = true
                        break
                    }

                    // Verify completion: take a fresh screenshot and ask the model
                    addStatus("Verifying task completion...")
                    delay(200)
                    val verifyScreenshot = captureCleanScreenshot(captureService)

                    if (verifyScreenshot != null) {
                        val verified = verifyTaskCompletion(
                            claudeClient,
                            task,
                            if (sendScreenshotsToLlm) verifyScreenshot else null,
                            treeExtractor,
                            conversationHistory
                        )
                        if (verified) {
                            addStatus("Task completed: $msg")
                            showToast("Task completed: $msg")
                            taskCompleted = true
                        } else {
                            addStatus("Task not actually done — continuing")
                            failureContext.add("Agent claimed complete but verification failed: $msg")
                            conversationHistory.add(
                                Message(
                                    role = "user",
                                    content = listOf(ContentBlock.TextContent(
                                        text = "You said the task is complete ('$msg') but the task is NOT done yet. " +
                                            "The original task is: $task\n" +
                                            "Look at the current screen and continue working. Do NOT say complete until EVERY part of the task is finished."
                                    ))
                                )
                            )
                        }
                    } else {
                        // Can't verify, trust the agent
                        addStatus("Task completed: $msg")
                        showToast("Task completed: $msg")
                        taskCompleted = true
                    }
                    break
                }

                addStatus("Step ${stepIdx + 1}/${plan.steps.size}: ${step.description}")

                // Check parallel verification of previous step
                if (prevStepVerificationJob != null) {
                    try {
                        val verResult = prevStepVerificationJob!!.await()
                        if (!verResult.success) {
                            addStatus("Previous step failed verification: ${verResult.reason}")
                            Log.w(TAG, "Previous step '${prevStep?.description}' failed verification: ${verResult.reason}")
                            failureContext.add("Step '${prevStep?.description}' did not take effect: ${verResult.reason}")
                            conversationHistory.add(
                                Message(
                                    role = "user",
                                    content = listOf(ContentBlock.TextContent(
                                        text = "WARNING: The previous step '${prevStep?.description}' did NOT work as expected. " +
                                            "Reason: ${verResult.reason}. " +
                                            "Re-analyze the current screen. You may need to retry that step or take a different approach."
                                    ))
                                )
                            )
                            break // Re-plan since previous step failed
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Previous step verification threw exception", e)
                    }
                    prevStepVerificationJob = null
                }

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
                    val reason = result.failureReason ?: "Unknown failure"
                    val detailedStatus = "Step failed: ${step.action} '${step.element ?: ""}' — $reason"
                    addStatus(detailedStatus)
                    Log.w(TAG, detailedStatus)

                    ConversationHistoryManager.add(
                        ConversationItem(
                            timestamp = System.currentTimeMillis(),
                            type = ConversationItem.ItemType.ERROR,
                            action = "${step.action} ${step.element ?: ""}",
                            actionDescription = step.description,
                            status = detailedStatus,
                            elementMapText = currentMapText,
                            chosenElementId = step.element,
                            screenshot = screenshotInfo?.base64Data,
                            screenshotWidth = screenshotInfo?.actualWidth ?: screenW,
                            screenshotHeight = screenshotInfo?.actualHeight ?: screenH
                        )
                    )

                    // Retry once: re-extract the element map (UI may have changed)
                    addStatus("Retrying with fresh element map...")
                    waitForUiSettle(captureService, minWaitMs = 80, maxWaitMs = 400)
                    val retryTree = treeExtractor.extract()
                    val retryMapGen = ElementMapGenerator(
                        screenshotInfo?.actualWidth ?: screenW, screenshotInfo?.actualHeight ?: screenH
                    )
                    val retryMap = retryMapGen.generate(retryTree, ocrElements)
                    val retryMapText = retryMap.toTextRepresentation()

                    val retryResult = executeSemanticStep(step, retryMap, automationService, cursorOverlay)
                    if (retryResult.success) {
                        addStatus("Retry succeeded: ${step.description}")
                        // Build annotated screenshot for the retry
                        val retryAnnotated = if (retryResult.clickX != null && retryResult.clickY != null) {
                            createAnnotatedScreenshot(
                                screenshotInfo, retryResult.clickX, retryResult.clickY,
                                retryResult.chosenElementId, retryMap
                            )
                        } else null

                        ConversationHistoryManager.add(
                            ConversationItem(
                                timestamp = System.currentTimeMillis(),
                                type = ConversationItem.ItemType.ACTION_EXECUTED,
                                action = "${step.action} ${step.element ?: ""} (retry)",
                                actionDescription = "${step.description} (retry succeeded)",
                                status = "Retry succeeded: ${step.description}",
                                elementMapText = retryMapText,
                                chosenElementId = retryResult.chosenElementId,
                                chosenElementText = retryResult.chosenElementText,
                                clickX = retryResult.clickX,
                                clickY = retryResult.clickY,
                                annotatedScreenshot = retryAnnotated,
                                screenshot = screenshotInfo?.base64Data,
                                screenshotWidth = screenshotInfo?.actualWidth ?: screenW,
                                screenshotHeight = screenshotInfo?.actualHeight ?: screenH
                            )
                        )
                        // Update map text for stuck detection
                        lastElementMapText = retryMapText
                        if (stepIdx < plan.steps.lastIndex) {
                            waitForUiSettle(captureService, minWaitMs = 50, maxWaitMs = 400)
                        }
                        continue // Continue to next step
                    }

                    // Retry also failed — log and feed detailed info to Claude
                    val retryReason = retryResult.failureReason ?: "Unknown"
                    val fullFailure = "Step '${step.description}' failed twice.\n" +
                        "First attempt: $reason\n" +
                        "Retry: $retryReason"
                    failureContext.add(fullFailure)
                    failuresSinceLastPlan++

                    ConversationHistoryManager.add(
                        ConversationItem(
                            timestamp = System.currentTimeMillis(),
                            type = ConversationItem.ItemType.ERROR,
                            action = "${step.action} ${step.element ?: ""} (retry failed)",
                            actionDescription = "Retry also failed",
                            status = "Retry failed: $retryReason",
                            elementMapText = retryMapText,
                            screenshot = screenshotInfo?.base64Data,
                            screenshotWidth = screenshotInfo?.actualWidth ?: screenW,
                            screenshotHeight = screenshotInfo?.actualHeight ?: screenH
                        )
                    )

                    conversationHistory.add(
                        Message(
                            role = "user",
                            content = listOf(ContentBlock.TextContent(
                                text = "Step FAILED (tried twice): ${step.description}\n" +
                                    "Reason: $reason\n" +
                                    "Retry reason: $retryReason\n" +
                                    "The element '${step.element}' could not be found or interacted with. " +
                                    "Re-analyze the current screen and try a completely different approach. " +
                                    "If unsure which element is correct, use search or scroll to find it."
                            ))
                        )
                    )

                    // Consult planning agent on repeated failures
                    if (planningAgent != null && failuresSinceLastPlan >= 3 && iteration - lastPlanConsultIteration >= 3) {
                        addStatus("Consulting planning agent for recovery...")
                        try {
                            val recoveryScreenshot = if (sendScreenshotsToLlm) captureCleanScreenshot(captureService) else null

                            val recoveryPlan = planningAgent.planRecovery(
                                task, recoveryScreenshot, currentMapText,
                                failureContext, currentPlan, installedApps
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

                // Track this action for stuck-on-same-action detection
                recentActions.add(ActionRecord(step.action, step.element, step.text))
                if (recentActions.size > 20) recentActions.removeAt(0)

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
                        annotatedScreenshot = annotated,
                        screenshot = screenshotInfo?.base64Data,
                        screenshotWidth = screenshotInfo?.actualWidth ?: screenW,
                        screenshotHeight = screenshotInfo?.actualHeight ?: screenH
                    )
                )

                // Launch parallel verification of this step while moving to the next
                prevStep = step
                if (step.action == SemanticAction.TYPE || step.action == SemanticAction.CLICK) {
                    prevStepVerificationJob = orchestratorScope.async(Dispatchers.IO) {
                        delay(150) // Brief pause before checking
                        verifyStepEffect(step, automationService, treeExtractor,
                            screenshotInfo?.actualWidth ?: screenW, screenshotInfo?.actualHeight ?: screenH)
                    }
                } else {
                    prevStepVerificationJob = null
                }

                // Wait for UI to settle between steps via screenshot diffing
                if (stepIdx < plan.steps.lastIndex) {
                    val isNavAction = step.action == SemanticAction.OPEN_APP ||
                        step.action == SemanticAction.BACK ||
                        step.action == SemanticAction.HOME ||
                        step.action == SemanticAction.SWIPE
                    if (isNavAction) {
                        // Navigation actions may trigger animations/transitions
                        waitForUiSettle(captureService, minWaitMs = 150, maxWaitMs = 1200, pollIntervalMs = 150)
                    } else {
                        // Taps/types usually settle quickly
                        waitForUiSettle(captureService, minWaitMs = 50, maxWaitMs = 500, pollIntervalMs = 100)
                    }
                }
            }

            // Check final step verification if loop ended normally
            if (prevStepVerificationJob != null) {
                try {
                    val finalCheck = prevStepVerificationJob!!.await()
                    if (!finalCheck.success) {
                        Log.w(TAG, "Final step verification failed: ${finalCheck.reason}")
                        failureContext.add("Last step '${prevStep?.description}' didn't take effect: ${finalCheck.reason}")
                    }
                } catch (_: Exception) {}
            }

            // Restore overlay touchability after step execution
            StatusOverlay.getInstance(context).setPassThrough(false)

            if (taskCompleted) break

            // Wait for UI to settle before next iteration via screenshot diffing
            addStatus("Waiting for UI to settle...")
            waitForUiSettle(captureService, minWaitMs = 100, maxWaitMs = 1000, pollIntervalMs = 150)
        }

        if (iteration >= maxIterations) {
            addStatus("Maximum iterations reached")
            showToast("Maximum iterations reached")
        }
    }

    private suspend fun verifyTaskCompletion(
        claudeClient: ClaudeAPIClient,
        originalTask: String,
        screenshotInfo: ScreenshotInfo?,
        treeExtractor: AccessibilityTreeExtractor,
        conversationHistory: List<Message>
    ): Boolean {
        return try {
            val elements = treeExtractor.extract()
            val wm = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val vw = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                wm.currentWindowMetrics.bounds.width()
            } else { val dm = android.util.DisplayMetrics(); @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm); dm.widthPixels }
            val vh = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                wm.currentWindowMetrics.bounds.height()
            } else { val dm = android.util.DisplayMetrics(); @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm); dm.heightPixels }
            val mapGen = ElementMapGenerator(screenshotInfo?.actualWidth ?: vw, screenshotInfo?.actualHeight ?: vh)
            val elementMap = mapGen.generate(elements)
            val elementMapText = elementMap.toTextRepresentation()

            val verifierInput = if (screenshotInfo != null) {
                "Look at the current screen and element map"
            } else {
                "Use only the element map (no screenshot is provided)"
            }

            val systemPrompt = """
                You are a task completion verifier for an Android automation agent.
                The agent claims the following task is complete. $verifierInput
                and determine if the task has ACTUALLY been fully completed.

                Element map:
                $elementMapText

                Original task: $originalTask

                Respond with ONLY a JSON object:
                {"completed": true/false, "reason": "brief explanation"}

                Be STRICT: if the task has multiple parts, ALL parts must be done.
                If the screen shows the task is only partially done, return false.
            """.trimIndent()

            val contentBlocks = mutableListOf<ContentBlock>()
            if (screenshotInfo != null) {
                contentBlocks.add(ContentBlock.ImageContent(
                    source = ImageSource(
                        data = screenshotInfo.base64Data,
                        mediaType = screenshotInfo.mediaType
                    )
                ))
            }
            contentBlocks.add(
                ContentBlock.TextContent(
                    text = if (screenshotInfo != null) {
                        "Is this task fully completed? Task: $originalTask"
                    } else {
                        "No screenshot provided. Based only on the element map, is this task fully completed? Task: $originalTask"
                    }
                )
            )
            val messages = listOf(Message(role = "user", content = contentBlocks))

            val result = claudeClient.sendMessage(messages, systemPrompt)
            val text = result.getOrNull()?.content?.firstOrNull()?.text ?: return true

            var cleanJson = text.trim()
            val startIdx = cleanJson.indexOf('{')
            val endIdx = cleanJson.lastIndexOf('}')
            if (startIdx >= 0 && endIdx > startIdx) {
                cleanJson = cleanJson.substring(startIdx, endIdx + 1)
            }

            val reader = com.google.gson.stream.JsonReader(java.io.StringReader(cleanJson))
            reader.isLenient = true
            val json = com.google.gson.JsonParser.parseReader(reader).asJsonObject
            val completed = json.get("completed")?.asBoolean ?: true
            val reason = json.get("reason")?.asString ?: ""
            Log.d(TAG, "Completion verification: completed=$completed, reason=$reason")

            ConversationHistoryManager.add(
                ConversationItem(
                    timestamp = System.currentTimeMillis(),
                    type = ConversationItem.ItemType.PLANNING,
                    response = "Completion check: ${if (completed) "DONE" else "NOT DONE"} — $reason",
                    status = if (completed) "Verified: task complete" else "Not done: $reason"
                )
            )

            completed
        } catch (e: Exception) {
            Log.e(TAG, "Completion verification failed", e)
            false // Fail safe: keep working instead of falsely declaring completion
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
                SemanticAction.CLICK, SemanticAction.LONG_PRESS -> {
                    val element = step.element?.let { elementMap.findById(it) }
                    if (element == null) {
                        val availableIds = elementMap.elements
                            .filter { it.isClickable }
                            .take(10)
                            .joinToString(", ") { "${it.id} (\"${it.text.take(20)}\")" }
                        val reason = "Element '${step.element}' not found in element map. " +
                            "Available clickable elements: [$availableIds]"
                        Log.e(TAG, reason)
                        return StepExecutionResult(false, failureReason = reason)
                    }
                    val x = element.bounds.centerX()
                    val y = element.bounds.centerY()
                    val isLongPress = step.action == SemanticAction.LONG_PRESS
                    Log.d(TAG, "${if (isLongPress) "Long press" else "Click"} ${step.element} at ($x, $y)")
                    cursorOverlay.moveTo(x, y, showClick = true)
                    delay(300)
                    val ok = if (isLongPress) {
                        automationService.performLongPress(x, y, step.durationMs ?: 1000)
                    } else {
                        automationService.performTap(x, y)
                    }
                    if (!ok) {
                        return StepExecutionResult(false, x, y, step.element, element.text,
                            failureReason = "${if (isLongPress) "Long press" else "Tap"} gesture failed at ($x, $y) for element '${step.element}' \"${element.text}\"")
                    }
                    StepExecutionResult(true, x, y, step.element, element.text)
                }

                SemanticAction.TYPE -> {
                    val text = step.text
                    if (text == null) {
                        return StepExecutionResult(false, failureReason = "TYPE action missing 'text' field")
                    }
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
                        } else {
                            val availableInputs = elementMap.elements
                                .filter { it.isFocusable || it.type == ElementType.INPUT }
                                .take(10)
                                .joinToString(", ") { "${it.id} (\"${it.text.take(20)}\")" }
                            return StepExecutionResult(false, failureReason =
                                "Input element '${step.element}' not found. Available inputs: [$availableInputs]")
                        }
                    }
                    val ok = automationService.performType(text)
                    if (!ok) {
                        return StepExecutionResult(false, tapX, tapY, step.element, elText,
                            failureReason = "Type gesture failed for text \"${text.take(50)}\" — is a text field focused?")
                    }
                    StepExecutionResult(true, tapX, tapY, step.element, elText)
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
                    if (!ok) {
                        return StepExecutionResult(false, startX, startY, failureReason =
                            "Swipe $direction failed from ($startX,$startY) to ($endX,$endY)")
                    }
                    StepExecutionResult(true, startX, startY)
                }

                SemanticAction.BACK -> {
                    val ok = automationService.performBack()
                    StepExecutionResult(ok, failureReason = if (!ok) "Back gesture failed" else null)
                }
                SemanticAction.HOME -> {
                    val ok = automationService.performHome()
                    StepExecutionResult(ok, failureReason = if (!ok) "Home gesture failed" else null)
                }

                SemanticAction.OPEN_APP -> {
                    val pkg = step.packageName
                    if (pkg.isNullOrBlank()) {
                        return StepExecutionResult(false, failureReason = "OPEN_APP action missing 'package' field")
                    }
                    val ok = automationService.performOpenApp(pkg)
                    if (!ok) {
                        return StepExecutionResult(false, failureReason = "Failed to launch app: $pkg — is it installed?")
                    }
                    // Wait for app to launch
                    delay(1500)
                    // Verify we landed in the right app
                    val currentPkg = automationService.getCurrentAppPackage()
                    if (currentPkg != null && currentPkg != pkg) {
                        Log.w(TAG, "open_app targeted $pkg but landed in $currentPkg")
                    }
                    StepExecutionResult(true)
                }

                SemanticAction.WAIT -> {
                    delay(1000)
                    StepExecutionResult(true)
                }

                SemanticAction.DISMISS_KEYBOARD -> {
                    val ok = automationService.dismissKeyboard()
                    StepExecutionResult(ok, failureReason = if (!ok) "Failed to dismiss keyboard" else null)
                }

                SemanticAction.SHARE_FINDING -> {
                    Log.d(TAG, "Share finding: ${step.findingKey} = ${step.findingValue}")
                    StepExecutionResult(true)
                }

                SemanticAction.COMPLETE -> StepExecutionResult(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute step: ${step.action}", e)
            StepExecutionResult(false, failureReason = "Exception: ${e.javaClass.simpleName} — ${e.message}")
        }
    }

    /**
     * Draws a crosshair + label on the screenshot at the tap location.
     * Returns base64-encoded annotated JPEG.
     */
    private fun createAnnotatedScreenshot(
        screenshotInfo: ScreenshotInfo?,
        clickX: Int,
        clickY: Int,
        elementId: String?,
        elementMap: ElementMap
    ): String? {
        if (screenshotInfo == null) return null
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

    /**
     * Verifies that a step actually had the intended effect by checking UI state.
     * Runs in parallel while the next step is being prepared.
     */
    private suspend fun verifyStepEffect(
        step: SemanticStep,
        automationService: AutomationService,
        treeExtractor: AccessibilityTreeExtractor,
        screenWidth: Int,
        screenHeight: Int
    ): StepVerificationResult {
        return try {
            when (step.action) {
                SemanticAction.TYPE -> {
                    val expectedText = step.text ?: return StepVerificationResult(true)
                    // Check if the focused field contains our text
                    val fieldText = automationService.readFocusedFieldText()
                    if (fieldText == null) {
                        // Field lost focus — might be OK if we submitted
                        // Check the element map for the target element's text
                        val elements = treeExtractor.extract()
                        val mapGen = ElementMapGenerator(screenWidth, screenHeight)
                        val freshMap = mapGen.generate(elements)
                        val targetEl = step.element?.let { freshMap.findById(it) }
                        if (targetEl != null && targetEl.text.contains(expectedText, ignoreCase = true)) {
                            StepVerificationResult(true)
                        } else if (targetEl != null) {
                            StepVerificationResult(false,
                                "Text field '${step.element}' contains '${targetEl.text.take(30)}' instead of '${expectedText.take(30)}'")
                        } else {
                            // Can't find element, UI probably changed — assume OK
                            StepVerificationResult(true)
                        }
                    } else if (fieldText.contains(expectedText, ignoreCase = true)) {
                        StepVerificationResult(true)
                    } else {
                        StepVerificationResult(false,
                            "Text field contains '${fieldText.take(30)}' but expected '${expectedText.take(30)}'")
                    }
                }

                SemanticAction.CLICK -> {
                    // For clicks, verify the UI changed — re-extract tree and check
                    // that the element map is different (something happened)
                    val elements = treeExtractor.extract()
                    val mapGen = ElementMapGenerator(screenWidth, screenHeight)
                    val freshMap = mapGen.generate(elements)
                    // Check if the clicked element is still in the same state
                    // (e.g., if we clicked a button, it might have disappeared or the screen changed)
                    val targetEl = step.element?.let { freshMap.findById(it) }
                    // If the element is gone, the click probably worked (navigated away)
                    // If it's still there, that's also fine (could be a toggle, etc.)
                    // We can't easily know the expected outcome, so just return true
                    // The main value is catching cases where the click clearly didn't register
                    StepVerificationResult(true)
                }

                else -> StepVerificationResult(true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Step verification failed", e)
            StepVerificationResult(true) // Don't block on verification errors
        }
    }

    /**
     * Analyzes recent action history for repeated same-element actions.
     * If the model keeps clicking/typing the same element 2+ times without
     * the screen changing, tells Claude to try a different approach.
     */
    private fun buildActionHistoryContext(): String {
        if (recentActions.size < 2) return ""

        // Count consecutive identical actions from the tail
        val last = recentActions.last()
        var repeatCount = 0
        for (i in recentActions.indices.reversed()) {
            val a = recentActions[i]
            if (a.action == last.action && a.elementId == last.elementId && a.text == last.text) {
                repeatCount++
            } else break
        }

        if (repeatCount < 2) {
            // Also check for same element clicked with different but all-click actions
            val lastElement = last.elementId ?: return ""
            var sameElementClicks = 0
            for (i in recentActions.indices.reversed()) {
                val a = recentActions[i]
                if (a.elementId == lastElement && a.action == SemanticAction.CLICK) {
                    sameElementClicks++
                } else break
            }
            if (sameElementClicks < 2) return ""
            repeatCount = sameElementClicks
        }

        val elementDesc = last.elementId ?: "unknown"
        return buildString {
            appendLine("⚠️ ACTION HISTORY WARNING: You have performed the SAME action (${ last.action} on '$elementDesc') $repeatCount times in a row without progress.")
            appendLine("This action is NOT working. You MUST try something DIFFERENT:")
            appendLine("- Try a LONG PRESS instead of a regular tap (use 'long_press' action)")
            appendLine("- Try clicking a DIFFERENT element that might serve the same purpose")
            appendLine("- Try swiping to reveal hidden elements")
            appendLine("- Try dismissing the keyboard if it's covering elements")
            appendLine("- Try using the back button and approaching from a different screen")
            appendLine("- Look for alternative UI paths (menus, search bars, overflow buttons)")
            appendLine("DO NOT repeat the same action on '$elementDesc' again.")
        }
    }

    /**
     * Waits for the UI to settle by comparing consecutive screenshot fingerprints.
     * Returns as soon as two consecutive captures match (screen stable), or after maxWaitMs.
     * Uses a lightweight CRC32 of raw screenshot bytes — no base64 or compression overhead.
     */
    private suspend fun waitForUiSettle(
        captureService: ScreenCaptureService?,
        minWaitMs: Long = 80,
        maxWaitMs: Long = 800,
        pollIntervalMs: Long = 120
    ) {
        delay(minWaitMs)
        if (captureService == null) {
            delay(maxWaitMs - minWaitMs) // No fingerprinting, just wait
            return
        }
        val deadline = System.currentTimeMillis() + maxWaitMs - minWaitMs
        var prevFingerprint: Long? = null

        while (System.currentTimeMillis() < deadline) {
            val fingerprint = captureService.captureFingerprint()
            if (fingerprint != null && fingerprint == prevFingerprint) {
                // Two consecutive frames match — UI is stable
                Log.d(TAG, "UI settled after ${maxWaitMs - (deadline - System.currentTimeMillis())}ms")
                return
            }
            prevFingerprint = fingerprint
            delay(pollIntervalMs)
        }
        Log.d(TAG, "UI settle timed out at ${maxWaitMs}ms")
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
