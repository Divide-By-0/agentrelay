package com.agentrelay

import android.content.Context
import android.graphics.*
import android.util.Base64
import android.util.Log
import android.widget.Toast
import com.agentrelay.intervention.InterventionTracker
import com.agentrelay.models.*
import com.agentrelay.ocr.OCRClient
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
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
    val reason: String = "",
    val diffSummary: String? = null
)

private data class CaptureResult(
    val screenshotInfo: ScreenshotInfo?,
    val elementMap: ElementMap,
    val ocrElements: List<UIElement>,
    val screenW: Int,
    val screenH: Int,
    val screenshotTime: Long,
    val extractTime: Long
)

/** Signals from step execution back to the main loop */
private enum class StepLoopSignal { CONTINUE, BREAK_REPLAN, TASK_COMPLETED }

class AgentOrchestrator(private val context: Context) {

    private val orchestratorScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var currentJob: Job? = null
    private var isRunning = false
    private var pendingRecoveryPlanJob: Deferred<PlanningResult?>? = null

    /** Background expert/search query job — launched by ASK_EXPERT/WEB_SEARCH, consumed at top of main loop */
    private data class PendingExpertQuery(
        val job: Deferred<String>,
        val query: String,
        val action: SemanticAction // ASK_EXPERT or WEB_SEARCH
    )
    private var pendingExpertJob: PendingExpertQuery? = null

    private val conversationHistory = mutableListOf<Message>()
    private var currentTask: String = ""
    private var screenRecorder: ScreenRecorder? = null
    private var taskSucceeded: Boolean = false
    /** After the first LLM call, only send these app packages in device context */
    private var relevantAppPackages: Set<String>? = null
    /** Facts discovered during the task (from extract/note actions) — pinned and never trimmed */
    private val discoveredFacts = mutableListOf<Pair<String, String>>()
    /** Sub-tasks completed so far — pinned in message[0] so the LLM never re-does them after trimming */
    private val completedSubTasks = mutableListOf<String>()

    /** Optional callback fired when a task finishes (used by benchmark harness). Auto-clears after firing. */
    var onTaskFinished: ((status: String, iterations: Int, message: String) -> Unit)? = null
    // Track recent actions for stuck-on-same-action detection
    private data class ActionRecord(val action: SemanticAction, val elementId: String?, val text: String?, val description: String = "")
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

        val accessibilityInstanceEnabled = AutomationService.isServiceEnabled()
        val accessibilitySettingsEnabled = AutomationService.isServiceEnabledInSettings(context)
        if (accessibilityInstanceEnabled != accessibilitySettingsEnabled) {
            Log.w(
                TAG,
                "Accessibility state mismatch (instance=$accessibilityInstanceEnabled, settings=$accessibilitySettingsEnabled). " +
                    "NOTE: automation has been working so far; keep monitoring consistency."
            )
        }
        if (!accessibilityInstanceEnabled) {
            showToast("Please enable Accessibility Service")
            return
        }

        val screenshotMode = secStore.getScreenshotMode()
        val screenRecordingEnabled = secStore.getScreenRecordingEnabled()
        // Always capture screenshots — even when mode is OFF, we need them for
        // OCR/segmentation to enrich the element map (WebView content is invisible
        // to the accessibility tree without visual analysis).
        val useScreenshotsInLoop = true

        val captureService = ScreenCaptureService.instance
        val hasProjection = captureService?.hasActiveProjection() == true
        if (useScreenshotsInLoop && !hasProjection) {
            Log.e(TAG, "Screen capture not active — refusing to start agent")
            showToast("Enable screen capture before starting the agent")
            ConversationHistoryManager.add(
                ConversationItem(
                    timestamp = System.currentTimeMillis(),
                    type = ConversationItem.ItemType.ERROR,
                    status = "Agent start blocked: screen capture not active (service=${captureService != null}, projection=${captureService?.hasActiveProjection()}, useScreenshotsInLoop=$useScreenshotsInLoop)"
                )
            )
            return
        }
        if (hasProjection) {
            Log.d(TAG, "Screen capture active: ${captureService?.getVirtualDisplayInfo()}")
            ConversationHistoryManager.add(
                ConversationItem(
                    timestamp = System.currentTimeMillis(),
                    type = ConversationItem.ItemType.API_REQUEST,
                    status = "Screen capture: ${captureService?.getVirtualDisplayInfo()}"
                )
            )
        } else {
            Log.i(
                TAG,
                "Starting accessibility-only loop (screenshots disabled by settings). " +
                    "screenshotMode=$screenshotMode, ocr=${secStore.getOcrEnabled()}, recording=$screenRecordingEnabled"
            )
        }

        currentTask = task
        conversationHistory.clear()
        recentActions.clear()
        relevantAppPackages = null
        discoveredFacts.clear()
        completedSubTasks.clear()
        taskSucceeded = false
        isRunning = true

        // Initialize intervention tracker and log task start
        val tracker = InterventionTracker.getInstance(context)
        tracker.setTaskContext(task)
        tracker.logTrace("TASK_START", "Agent started: $task")

        // Show touch block overlay if enabled
        if (secStore.getBlockTouchDuringAgent()) {
            val touchBlocker = TouchBlockOverlay.getInstance(context)
            touchBlocker.setOnStopRequested { stop() }
            touchBlocker.show()
        }

        // Start screen recording if enabled
        val secStore2 = SecureStorage.getInstance(context)
        if (secStore2.getScreenRecordingEnabled() && captureService != null) {
            val vd = captureService.getVirtualDisplay()
            val irSurface = captureService.getImageReaderSurface()
            if (vd != null && irSurface != null) {
                val recorder = ScreenRecorder(
                    context,
                    vd,
                    irSurface,
                    captureService.getScreenWidth(),
                    captureService.getScreenHeight(),
                    captureService.getScreenDensity()
                )
                if (recorder.startRecording(task)) {
                    screenRecorder = recorder
                    Log.d(TAG, "Screen recording started for trajectory")
                } else {
                    Log.w(TAG, "Failed to start screen recording")
                }
            } else {
                Log.w(TAG, "Cannot start recording: VirtualDisplay=${vd != null}, ImageReaderSurface=${irSurface != null}")
            }
        }

        // Hide floating bubble while agent is running, show status overlay
        FloatingBubble.getInstance(context).hide()
        CursorOverlay.getInstance(context).show()
        StatusOverlay.getInstance(context).show()
        StatusOverlay.getInstance(context).setPinnedTask(task)
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
                runAgentLoop(apiKey, task, if (useScreenshotsInLoop) captureService else null)
            } catch (e: Exception) {
                Log.e(TAG, "Agent loop failed", e)
                addStatus("Error: ${e.message}")
                showToast("Agent error: ${e.message}")
                onTaskFinished?.invoke("error", 0, e.message ?: "Unknown error")
                onTaskFinished = null
            } finally {
                stop()
            }
        }
    }

    /** Returns true if the agent loop is currently executing a task */
    fun isCurrentlyRunning(): Boolean = isRunning

    fun stop() {
        if (!isRunning && currentJob == null) return // already stopped
        isRunning = false

        // Stop screen recording BEFORE cancelling the coroutine so the
        // MediaRecorder can finalize the MP4 while everything is still alive.
        val recordingFile = screenRecorder?.stopRecording(taskSucceeded)
        screenRecorder = null
        if (recordingFile != null) {
            Log.d(TAG, "Screen recording saved: ${recordingFile.absolutePath}")
            addStatus("Recording saved: ${recordingFile.name}")
        }

        currentJob?.cancel()
        currentJob = null

        // Hide touch block overlay and clear intervention tracker
        TouchBlockOverlay.getInstance(context).hide()
        val tracker = InterventionTracker.getInstance(context)
        tracker.logTrace("TASK_END", "Agent stopped")
        tracker.clearPlannedAction()

        CursorOverlay.getInstance(context).hide()
        StatusOverlay.getInstance(context).dismissClarification()
        StatusOverlay.getInstance(context).setPinnedTask(null)
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
        val recorder = screenRecorder
        val isRecordingNow = recorder?.isRecording() == true
        var swappedToImageReader = false
        var t1 = t0
        var t2 = t0

        return try {
            // setInvisible now runs directly when called from main thread (no post{} deferral)
            CursorOverlay.getInstance(context).setInvisible(true)
            StatusOverlay.getInstance(context).setInvisible(true)

            // If recording, swap VirtualDisplay surface to ImageReader for screenshot
            if (isRecordingNow) {
                recorder?.swapToImageReader()
                swappedToImageReader = true
            }

            delay(100) // Let display compositor update & deliver a frame
            t1 = System.currentTimeMillis()
            val screenshot = captureService.captureScreenshot()
            t2 = System.currentTimeMillis()
            screenshot
        } finally {
            if (swappedToImageReader) {
                try {
                    recorder?.swapToRecorder()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to swap recorder surface back after capture", e)
                }
            }

            // Restore immediately — runs synchronously on main thread
            CursorOverlay.getInstance(context).setInvisible(false)
            StatusOverlay.getInstance(context).setInvisible(false)
            Log.d(TAG, "⏱ captureCleanScreenshot: hide=${t1-t0}ms capture=${t2-t1}ms total=${t2-t0}ms")
        }
    }

    private suspend fun runAgentLoop(
        apiKey: String,
        task: String,
        captureService: ScreenCaptureService?
    ) {
        val secureStorage = SecureStorage.getInstance(context)
        val model = secureStorage.getModel()
        val claudeClient = LLMClientFactory.create(apiKey, model) { bytes, milliseconds ->
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
        val screenshotMode = secureStorage.getScreenshotMode()

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

        // Progress-based re-intervention state
        var iterationsSinceLastComplete = 0
        var lastProgressCheckIteration = 0

        // Screen structure hash stagnation detection
        val screenStructureHashes = mutableListOf<Pair<Int, Long>>() // (iteration, hash)

        // Use cached installed apps (pre-fetched on app startup via DeviceContextCache)
        val installedApps = DeviceContextCache.installedApps.ifEmpty {
            // Fallback: trigger async refresh if cache is empty (shouldn't happen normally)
            Log.w(TAG, "DeviceContextCache empty, triggering refresh")
            DeviceContextCache.refreshAsync(context)
            emptyList()
        }

        // Generate app knowledge descriptions in background if missing
        if (planningAgent != null && installedApps.isNotEmpty()) {
            AppKnowledgeCache.loadDescriptions(context)
            val missingApps = AppKnowledgeCache.getMissingApps(installedApps)
            if (missingApps.isNotEmpty()) {
                Log.d(TAG, "Generating app knowledge for ${missingApps.size} apps in background")
                orchestratorScope.launch(Dispatchers.IO) {
                    try {
                        val descriptions = planningAgent.generateAppKnowledge(missingApps)
                        if (descriptions.isNotEmpty()) {
                            AppKnowledgeCache.saveDescriptions(descriptions, context)
                            // Refresh the cache so subsequent iterations have descriptions
                            DeviceContextCache.refreshAsync(context)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "App knowledge generation failed", e)
                    }
                }
            }
        }

        // Planning runs in background — fast model starts acting immediately.
        // The planning job is launched after the first screenshot/element map are captured
        // (in iteration 1) so we don't need a duplicate screenshot capture at startup.
        var pendingPlanJob: Deferred<PlanningResult?>? = null
        pendingRecoveryPlanJob = null
        pendingExpertJob = null
        if (planningAgent != null) {
            addStatus("Planning in background (acting proactively)...")
        }

        // Fix 6: Pin original task as first conversation message so it's never trimmed
        conversationHistory.add(Message(role = "user", content = listOf(
            ContentBlock.TextContent(text = "TASK: $task")
        )))

        while (isRunning && iteration < maxIterations) {
            iteration++
            iterationsSinceLastComplete++
            Log.d(TAG, "Agent iteration $iteration")
            addStatus("Iteration $iteration/$maxIterations")

            // Sliding window: keep conversation history bounded
            trimConversationHistory(maxWindowSize = 12)

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
                        InterventionTracker.getInstance(context).logTrace(
                            "PLANNING", "Strategy: $approachName",
                            reasoning = planResult.toGuidanceText(),
                            iteration = iteration
                        )
                        ConversationHistoryManager.add(
                            ConversationItem(
                                timestamp = System.currentTimeMillis(),
                                type = ConversationItem.ItemType.PLANNING,
                                response = planResult.toGuidanceText(),
                                status = "Planning: $approachName"
                            )
                        )
                        // Update pinned task message with planning guidance
                        updatePinnedTaskMessage(task, currentPlan)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Background planning result failed", e)
                }
                pendingPlanJob = null
            }

            // Check if background recovery planning has completed
            if (pendingRecoveryPlanJob != null && pendingRecoveryPlanJob!!.isCompleted) {
                try {
                    val recovResult = pendingRecoveryPlanJob!!.await()
                    if (recovResult != null) {
                        currentPlan = recovResult
                        val approachName = recovResult.approaches.getOrNull(recovResult.recommendedIndex)?.name ?: "Recovery"
                        addStatus("Recovery strategy ready: $approachName")
                        ConversationHistoryManager.add(
                            ConversationItem(
                                timestamp = System.currentTimeMillis(),
                                type = ConversationItem.ItemType.PLANNING,
                                response = recovResult.toGuidanceText(),
                                status = "Recovery plan: $approachName"
                            )
                        )
                        failuresSinceLastPlan = 0
                        lastPlanConsultIteration = iteration
                        // Update pinned task message with new planning guidance
                        updatePinnedTaskMessage(task, currentPlan)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Background recovery planning result failed", e)
                }
                pendingRecoveryPlanJob = null
            }

            // Check if background expert/search query has completed
            if (pendingExpertJob != null && pendingExpertJob!!.job.isCompleted) {
                try {
                    val answer = pendingExpertJob!!.job.await()
                    val query = pendingExpertJob!!.query
                    val action = pendingExpertJob!!.action
                    val actionLabel = if (action == SemanticAction.ASK_EXPERT) "EXPERT ANSWER" else "WEB SEARCH RESULT"

                    if (action == SemanticAction.WEB_SEARCH && answer.contains("NAVIGATE_NEEDED")) {
                        conversationHistory.add(Message(
                            role = "user",
                            content = listOf(ContentBlock.TextContent(
                                text = "WEB SEARCH for '$query': This requires live data. Navigate to a browser and search manually."
                            ))
                        ))
                        addStatus("Search: needs live data — navigate manually")
                    } else {
                        conversationHistory.add(Message(
                            role = "user",
                            content = listOf(ContentBlock.TextContent(
                                text = "$actionLabel for '$query': $answer"
                            ))
                        ))
                        discoveredFacts.add(query to answer)
                        updatePinnedTaskMessage(task, currentPlan)
                        addStatus("${if (action == SemanticAction.ASK_EXPERT) "Expert" else "Search"}: ${answer.take(60)}")
                    }
                    ConversationHistoryManager.add(
                        ConversationItem(
                            timestamp = System.currentTimeMillis(),
                            type = ConversationItem.ItemType.ACTION_EXECUTED,
                            action = action.name,
                            actionDescription = "${action.name}: $query → ${answer.take(80)}",
                            status = "${if (action == SemanticAction.ASK_EXPERT) "Expert" else "Search"}: ${answer.take(60)}"
                        )
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Background expert query failed", e)
                    addStatus("Expert query failed: ${e.message?.take(40)}")
                }
                pendingExpertJob = null
            }

            // Split-screen dispatch: if planning recommends it, we haven't tried yet, and it's enabled in settings
            if (currentPlan?.splitScreen?.recommended == true && !splitScreenAttempted && secureStorage.getSplitScreenEnabled()) {
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
                    val lastResortScreenshot = captureCleanScreenshot(captureService)

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

            // 1-3. Capture screenshot, extract accessibility tree, merge into element map
            val capture = captureAndExtract(captureService, treeExtractor, ocrEnabled, secureStorage, screenshotFailureCount)
            if (capture.screenshotInfo == null) screenshotFailureCount++ else screenshotFailureCount = 0
            val screenshotInfo = capture.screenshotInfo
            val elementMap = capture.elementMap
            val ocrElements = capture.ocrElements
            val screenW = capture.screenW
            val screenH = capture.screenH
            val screenshotTime = capture.screenshotTime
            val extractTime = capture.extractTime

            // Launch planning in background using the first iteration's screenshot + element map
            val currentMapText = elementMap.toTextRepresentation()
            val previousMapForDiff = if (iteration > 1) lastElementMapText else null
            if (iteration == 1 && planningAgent != null && pendingPlanJob == null) {
                val planScreenshot = screenshotInfo
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
            if (previousMapForDiff != null && currentMapText == previousMapForDiff) {
                sameMapCount++
                if (sameMapCount == 2) {
                    // First recovery: dismiss keyboard if showing (it may cover elements)
                    if (automationService.isKeyboardShowing()) {
                        addStatus("Stuck — dismissing keyboard")
                        automationService.dismissKeyboard()
                        failureContext.add("Element map unchanged 2x, dismissed keyboard (may have been hiding buttons)")
                        waitForUiSettle(captureService, minWaitMs = 100, maxWaitMs = 600)
                        continue
                    }
                }
                if (sameMapCount == 3) {
                    // Second recovery: try scrolling down — target may be off-screen
                    addStatus("Stuck — scrolling to reveal more content")
                    automationService.performSwipe(540, 1600, 540, 600, 300)
                    failureContext.add("Element map unchanged 3x, scrolled down to reveal off-screen content")
                    waitForUiSettle(captureService, minWaitMs = 150, maxWaitMs = 800)
                    continue
                }
                if (sameMapCount >= 4) {
                    addStatus("Stuck detected — trying back button")
                    automationService.performBack()
                    sameMapCount = 0
                    failureContext.add("Element map unchanged 4x, pressed back to try different path")
                    failuresSinceLastPlan++
                    waitForUiSettle(captureService, minWaitMs = 150, maxWaitMs = 1000)
                    continue
                }
            } else {
                sameMapCount = 0
            }
            lastElementMapText = currentMapText

            // Screen structure hash stagnation detection
            val structureHash = computeScreenStructureHash(elementMap)
            screenStructureHashes.add(Pair(iteration, structureHash))
            if (screenStructureHashes.size > 15) screenStructureHashes.removeAt(0)
            val recentWindow = screenStructureHashes.takeLast(10)
            val hashOccurrences = recentWindow.count { it.second == structureHash }
            if (hashOccurrences >= 3) {
                val msg = "Screen structure stagnant: same layout seen $hashOccurrences times in last ${recentWindow.size} iterations"
                Log.w(TAG, msg)
                addStatus("Stagnation detected — same screen structure repeating")
                failureContext.add(msg)
                failuresSinceLastPlan++
                ConversationHistoryManager.add(
                    ConversationItem(
                        timestamp = System.currentTimeMillis(),
                        type = ConversationItem.ItemType.ERROR,
                        status = msg
                    )
                )
            }

            // Progress-based planning re-intervention (every 3 iterations without COMPLETE)
            if (iterationsSinceLastComplete >= 3 &&
                iteration - lastProgressCheckIteration >= 3 &&
                planningAgent != null) {
                lastProgressCheckIteration = iteration
                Log.d(TAG, "Running progress check at iteration $iteration (${iterationsSinceLastComplete} since last complete)")
                val recentActionsSummary = recentActions.takeLast(5).joinToString("\n") {
                    "  - ${it.action} on '${it.elementId ?: "N/A"}': ${it.description}"
                }
                try {
                    val progressResult = checkProgressWithHaiku(
                        claudeClient, task, currentMapText, recentActionsSummary
                    )
                    // Auto-complete: if progress check says task is done, finish immediately
                    if (progressResult.third) {
                        val reason = progressResult.second
                        Log.i(TAG, "Progress check detected task completed: $reason")
                        addStatus("Auto-completing: progress check confirmed task done")
                        onTaskFinished?.invoke("completed", iteration, "Auto-completed: $reason")
                        return
                    }

                    if (!progressResult.first) {
                        val reason = progressResult.second
                        Log.w(TAG, "Progress check: NOT progressing — $reason")
                        addStatus("Not making progress: $reason")
                        failureContext.add("Progress check (iter $iteration): $reason")
                        failuresSinceLastPlan += 2
                        ConversationHistoryManager.add(
                            ConversationItem(
                                timestamp = System.currentTimeMillis(),
                                type = ConversationItem.ItemType.ERROR,
                                status = "Progress stalled: $reason"
                            )
                        )
                        // Launch recovery planning in background (non-blocking)
                        if (planningAgent != null && iteration - lastPlanConsultIteration >= 3
                            && pendingRecoveryPlanJob == null) {
                            addStatus("Launching recovery planning in background...")
                            val recovMapText = currentMapText
                            val recovFailureCtx = failureContext.toList()
                            val recovCurrentPlan = currentPlan
                            val recovApps = installedApps
                            pendingRecoveryPlanJob = orchestratorScope.async(Dispatchers.IO) {
                                try {
                                    val recovScreenshot = withContext(Dispatchers.Main) { captureCleanScreenshot(captureService) }
                                    planningAgent.planRecovery(
                                        task, recovScreenshot, recovMapText,
                                        recovFailureCtx, recovCurrentPlan, recovApps
                                    )
                                } catch (e: Exception) {
                                    Log.w(TAG, "Background recovery planning failed", e)
                                    null
                                }
                            }
                        }
                    } else {
                        Log.d(TAG, "Progress check: progressing — ${progressResult.second}")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Progress check failed", e)
                }
            }

            // 4. Gather device context
            val deviceContext = try {
                automationService.getDeviceContext()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to get device context", e)
                null
            }

            // 5. Send screenshot + element map + device context to selected provider
            val apiStartTime = System.currentTimeMillis()
            addStatus("Sending...")

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

            // Filter installed apps to only relevant ones after first call
            val filteredDeviceContext = if (relevantAppPackages != null && deviceContext != null) {
                val currentPkg = deviceContext.currentAppPackage
                val filtered = deviceContext.installedApps.filter { app ->
                    app.packageName in relevantAppPackages!! || app.packageName == currentPkg
                }
                Log.d(TAG, "Filtered apps: ${deviceContext.installedApps.size} → ${filtered.size}")
                deviceContext.copy(installedApps = filtered)
            } else {
                deviceContext
            }
            val isFirstCall = relevantAppPackages == null

            // Decide whether to include screenshot for this iteration
            val shouldSendScreenshot = when (screenshotMode) {
                com.agentrelay.models.ScreenshotMode.ON -> true
                com.agentrelay.models.ScreenshotMode.OFF -> false
                com.agentrelay.models.ScreenshotMode.AUTO -> {
                    val ocrOnlyCount = ocrElements.count { it.source == com.agentrelay.models.ElementSource.OCR }
                    val decision = ElementMapAnalyzer.shouldSendScreenshot(
                        elementMap = elementMap,
                        ocrOnlyCount = ocrOnlyCount,
                        previousIterationFailed = failureContext.isNotEmpty(),
                        sameMapCount = sameMapCount,
                        structureRepeatCount = hashOccurrences,
                        keyboardVisible = deviceContext?.keyboardVisible == true
                    )
                    addStatus("Auto: ${decision.reason}")
                    decision.shouldSend
                }
            }

            // Run API call on IO dispatcher so Main thread stays free for UI updates
            val planResult = withContext(Dispatchers.IO) {
                claudeClient.sendWithElementMap(
                    if (shouldSendScreenshot) screenshotInfo else null,
                    elementMap,
                    enhancedTask,
                    conversationHistory,
                    filteredDeviceContext,
                    previousElementMapText = previousMapForDiff,
                    isFirstCall = isFirstCall
                )
            }

            if (planResult.isFailure) {
                Log.e(TAG, "API failed: ${planResult.exceptionOrNull()?.message}")
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

            // Capture relevant apps from first LLM response to filter future calls
            if (isFirstCall && plan.relevantApps.isNotEmpty()) {
                relevantAppPackages = plan.relevantApps.toSet()
                Log.d(TAG, "LLM selected ${relevantAppPackages!!.size} relevant apps: ${relevantAppPackages}")
            } else if (isFirstCall) {
                // LLM didn't return relevant_apps — don't filter
                relevantAppPackages = deviceContext?.installedApps?.map { it.packageName }?.toSet() ?: emptySet()
                Log.d(TAG, "LLM didn't specify relevant apps — keeping all")
            }

            val timingMsg = "⏱ screenshot=${screenshotTime}ms extract=${extractTime}ms api=${apiTime}ms"
            Log.d(TAG, timingMsg)
            addStatus("Plan: ${plan.reasoning} ($timingMsg)")
            InterventionTracker.getInstance(context).logTrace(
                "LLM_PLAN", plan.reasoning,
                reasoning = plan.steps.joinToString("; ") { "${it.action} ${it.element ?: ""}: ${it.description}" },
                confidence = plan.confidence,
                iteration = iteration,
                planSteps = plan.steps.mapIndexed { i, s -> "${i+1}. ${s.action} ${s.element ?: ""}" }.joinToString(", ")
            )
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
                    screenshotHeight = screenshotInfo?.actualHeight ?: screenH,
                    latencyMs = apiTime
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

            // 5a. Non-blocking clarification prompt (if alternative_path present and confidence != high)
            val clarificationPromptsEnabled = secureStorage.getClarificationPromptsEnabled()
            var redirectToAlternative = false
            var alternativeText: String? = null
            if (clarificationPromptsEnabled &&
                plan.alternativePath != null &&
                plan.confidence.lowercase() != "high"
            ) {
                val alternativeChosen = AtomicBoolean(false)
                val userDismissed = AtomicBoolean(false)
                val altPath = plan.alternativePath
                val defaultDesc = plan.reasoning.take(60)
                val statusOverlay = StatusOverlay.getInstance(context)

                withContext(Dispatchers.Main) {
                    statusOverlay.showClarification(defaultDesc, altPath,
                        onAlternativeChosen = {
                            alternativeChosen.set(true)
                            alternativeText = altPath
                        },
                        onDismissed = {
                            userDismissed.set(true)
                        }
                    )
                }

                // Give the user a brief window to tap before proceeding
                delay(4000)

                withContext(Dispatchers.Main) {
                    statusOverlay.dismissClarification()
                }

                if (alternativeChosen.get()) {
                    redirectToAlternative = true
                    addStatus("User chose alternative: $altPath")
                }

                // Log the clarification event
                val userChoice = when {
                    alternativeChosen.get() -> "alternative"
                    userDismissed.get() -> "dismissed"
                    else -> "timeout"
                }
                InterventionTracker.getInstance(context).logClarification(
                    task = task,
                    iteration = iteration,
                    defaultPath = plan.reasoning,
                    alternativePath = altPath,
                    userChose = userChoice,
                    confidence = plan.confidence,
                    elementMapSnapshot = currentMapText.take(2000)
                )
            }

            // 5b. If user chose alternative, redirect the agent
            if (redirectToAlternative && alternativeText != null) {
                conversationHistory.add(
                    Message(
                        role = "user",
                        content = listOf(ContentBlock.TextContent(
                            text = "USER REDIRECT: The user wants you to try a different approach: ${alternativeText}. Abandon the current plan and follow this alternative path instead."
                        ))
                    )
                )
                ConversationHistoryManager.add(
                    ConversationItem(
                        timestamp = System.currentTimeMillis(),
                        type = ConversationItem.ItemType.PLANNING,
                        status = "User redirect: $alternativeText"
                    )
                )
                continue // skip step execution, re-plan with alternative
            }

            // 5. Execute each step in the plan
            val stepSignal = executeStepPlan(
                plan, task, elementMap, currentMapText, screenshotInfo, screenW, screenH,
                ocrElements, automationService, cursorOverlay, claudeClient, treeExtractor,
                captureService, screenshotMode != com.agentrelay.models.ScreenshotMode.OFF, verificationEnabled,
                failureContext, planningAgent, currentPlan, installedApps,
                iteration, lastPlanConsultIteration, failuresSinceLastPlan
            )
            // Update mutable state from step execution
            if (stepSignal.updatedPlan != null) currentPlan = stepSignal.updatedPlan
            if (stepSignal.updatedLastPlanIteration >= 0) lastPlanConsultIteration = stepSignal.updatedLastPlanIteration
            failuresSinceLastPlan = stepSignal.updatedFailuresSinceLastPlan
            if (stepSignal.updatedMapText != null) lastElementMapText = stepSignal.updatedMapText!!
            var taskCompleted = stepSignal.signal == StepLoopSignal.TASK_COMPLETED

            // Track completed sub-tasks so the LLM never redoes them after history trimming.
            // Record when a plan fully executed (CONTINUE = all steps ran, no replan needed).
            if (stepSignal.signal == StepLoopSignal.CONTINUE) {
                val milestone = plan.steps
                    .filter { it.action != SemanticAction.WAIT && it.action != SemanticAction.COMPLETE }
                    .joinToString(", ") { it.description }
                if (milestone.isNotBlank()) {
                    completedSubTasks.add(milestone)
                    // Keep list bounded to avoid bloating the pinned message
                    if (completedSubTasks.size > 15) completedSubTasks.removeAt(0)
                    updatePinnedTaskMessage(task, currentPlan)
                }
            }

            if (taskCompleted) {
                iterationsSinceLastComplete = 0
                break
            }

            // Wait for UI to settle before next iteration via screenshot diffing
            addStatus("Awaiting ui settle...")
            waitForUiSettle(captureService, minWaitMs = 100, maxWaitMs = 1000, pollIntervalMs = 150)
        }

        if (iteration >= maxIterations) {
            addStatus("Maximum iterations reached")
            showToast("Maximum iterations reached")
            onTaskFinished?.invoke("max_iterations", iteration, "Maximum iterations reached")
            onTaskFinished = null
        }
    }

    // ── Extracted: capture screenshot + extract accessibility tree + build element map ──

    private suspend fun captureAndExtract(
        captureService: ScreenCaptureService?,
        treeExtractor: AccessibilityTreeExtractor,
        ocrEnabled: Boolean,
        secureStorage: SecureStorage,
        screenshotFailureCount: Int
    ): CaptureResult {
        val iterStartTime = System.currentTimeMillis()
        val screenshotInfo = captureCleanScreenshot(captureService)
        val screenshotTime = System.currentTimeMillis() - iterStartTime

        if (screenshotInfo == null) {
            if (screenshotFailureCount == 0) {
                Log.w(TAG, "Screenshot failed — running in accessibility-only mode")
                addStatus("No screenshot — using accessibility tree only")
            }
        } else {
            ConversationHistoryManager.add(
                ConversationItem(
                    timestamp = System.currentTimeMillis(),
                    type = ConversationItem.ItemType.SCREENSHOT_CAPTURED,
                    screenshot = screenshotInfo.base64Data,
                    screenshotWidth = screenshotInfo.actualWidth,
                    screenshotHeight = screenshotInfo.actualHeight,
                    status = "Screenshot: ${screenshotInfo.actualWidth}x${screenshotInfo.actualHeight}",
                    latencyMs = screenshotTime
                )
            )
        }

        val extractStartTime = System.currentTimeMillis()
        // Hide overlays during tree extraction so they don't obscure underlying elements
        StatusOverlay.getInstance(context).setInvisible(true)
        CursorOverlay.getInstance(context).setInvisible(true)
        val a11yElements = treeExtractor.extract()
        // Restore overlays immediately after extraction
        StatusOverlay.getInstance(context).setInvisible(false)
        CursorOverlay.getInstance(context).setInvisible(false)

        // Always run OCR when we have a screenshot — even if user toggled OCR off,
        // OCR is critical for reading WebView/rendered content that the accessibility
        // tree can't see (emails, web pages, etc.)
        var ocrElements = emptyList<UIElement>()
        if (screenshotInfo != null) {
            try {
                val ocrClient = OCRClient(secureStorage)
                val ocrResult = withContext(Dispatchers.IO) {
                    ocrClient.recognizeText(screenshotInfo)
                }
                ocrElements = ocrResult
                if (!ocrEnabled) {
                    Log.d(TAG, "OCR ran implicitly (setting off but needed for element map enrichment): ${ocrElements.size} elements")
                }
            } catch (e: Exception) {
                Log.w(TAG, "OCR failed, continuing with accessibility tree only", e)
            }
        }

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
        val elementMap = mapGenerator.generate(a11yElements, ocrElements, treeExtractor.hasWebView)
        val extractTime = System.currentTimeMillis() - extractStartTime
        addStatus("Element map: ${elementMap.elements.size} elements")
        Log.d(TAG, elementMap.toTextRepresentation())

        return CaptureResult(screenshotInfo, elementMap, ocrElements, screenW, screenH, screenshotTime, extractTime)
    }

    // ── Extracted: execute all steps in a plan ──

    private data class StepPlanResult(
        val signal: StepLoopSignal,
        val updatedPlan: PlanningResult? = null,
        val updatedLastPlanIteration: Int = -1,
        val updatedFailuresSinceLastPlan: Int = 0,
        val updatedMapText: String? = null
    )

    private suspend fun executeStepPlan(
        plan: SemanticActionPlan,
        task: String,
        elementMap: ElementMap,
        currentMapText: String,
        screenshotInfo: ScreenshotInfo?,
        screenW: Int, screenH: Int,
        ocrElements: List<UIElement>,
        automationService: AutomationService,
        cursorOverlay: CursorOverlay,
        claudeClient: LLMClient,
        treeExtractor: AccessibilityTreeExtractor,
        captureService: ScreenCaptureService?,
        sendScreenshotsToLlm: Boolean,
        verificationEnabled: Boolean,
        failureContext: MutableList<String>,
        planningAgent: PlanningAgent?,
        currentPlan: PlanningResult?,
        installedApps: List<AppInfo>,
        iteration: Int,
        lastPlanConsultIteration: Int,
        incomingFailuresSinceLastPlan: Int
    ): StepPlanResult {
        StatusOverlay.getInstance(context).setPassThrough(true)
        var updatedPlan = currentPlan
        var updatedLastPlanIteration = lastPlanConsultIteration
        var failuresSinceLastPlan = incomingFailuresSinceLastPlan
        var updatedMapText: String? = null

        // Mutable copies so we can refresh between steps
        var liveElementMap = elementMap
        var liveMapText = currentMapText

        val targetAppPackage = plan.steps
            .firstOrNull { it.action == SemanticAction.OPEN_APP }
            ?.packageName

        var prevStep: SemanticStep? = null
        var prevStepVerificationJob: Deferred<StepVerificationResult>? = null
        var prevStepMapText: String = liveMapText  // snapshot before each step for verification
        var signal = StepLoopSignal.CONTINUE

        for ((stepIdx, step) in plan.steps.withIndex()) {
            if (!isRunning) break

            // Never act inside agentrelay itself
            val stepPkg = automationService.getCurrentAppPackage()
            if (stepPkg == "com.agentrelay" &&
                step.action != SemanticAction.OPEN_APP &&
                step.action != SemanticAction.HOME) {
                Log.w(TAG, "Detected agentrelay as foreground — escaping")
                addStatus("In AgentRelay — navigating away")
                automationService.performHome()
                delay(500)
                break
            }

            // App verification
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
                    val afterPkg = automationService.getCurrentAppPackage()
                    if (afterPkg != targetAppPackage) {
                        failureContext.add("Couldn't return to target app $targetAppPackage (currently in $afterPkg)")
                        conversationHistory.add(Message(role = "user", content = listOf(
                            ContentBlock.TextContent(
                                text = "The app switched away from the target ($targetAppPackage) to $afterPkg. " +
                                    "Re-analyze the screen and navigate back to the correct app."
                            )
                        )))
                        break
                    }
                }
            }

            // Handle complete action
            if (step.action == SemanticAction.COMPLETE) {
                // If COMPLETE is bundled with other actions, skip it — force re-evaluation
                // next iteration so the model can verify the result of its actions first.
                if (stepIdx > 0) {
                    Log.w(TAG, "Stripping premature COMPLETE bundled with actions — will re-evaluate next iteration")
                    addStatus("Verifying action results before completing...")
                    // Await any pending verification from the previous step
                    if (prevStepVerificationJob != null) {
                        try {
                            val verResult = prevStepVerificationJob!!.await()
                            if (!verResult.success) {
                                Log.w(TAG, "Pre-complete verification failed: ${verResult.reason}")
                                failureContext.add("Action before COMPLETE didn't take effect: ${verResult.reason}")
                                conversationHistory.add(Message(role = "user", content = listOf(
                                    ContentBlock.TextContent(
                                        text = "You tried to complete the task, but the previous action " +
                                            "'${prevStep?.description}' did NOT work as expected: ${verResult.reason}. " +
                                            "Check the current screen and try again."
                                    )
                                )))
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Pre-complete verification threw exception", e)
                        }
                        prevStepVerificationJob = null
                    }
                    break  // Break to re-evaluate with fresh screen state
                }
                val completeResult = handleCompleteAction(step, task, claudeClient, treeExtractor,
                    captureService, sendScreenshotsToLlm, failureContext)
                signal = completeResult.signal
                failuresSinceLastPlan += completeResult.failuresSinceLastPlanDelta
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
                        conversationHistory.add(Message(role = "user", content = listOf(
                            ContentBlock.TextContent(
                                text = "WARNING: The previous step '${prevStep?.description}' did NOT work as expected. " +
                                    "Reason: ${verResult.reason}. " +
                                    "Re-analyze the current screen. You may need to retry that step or take a different approach."
                            )
                        )))
                        break
                    } else if (verResult.diffSummary != null) {
                        // Feed UI change context into conversation so Claude knows what happened
                        Log.d(TAG, "Step '${prevStep?.description}' UI changes: ${verResult.diffSummary}")
                        conversationHistory.add(Message(role = "user", content = listOf(
                            ContentBlock.TextContent(
                                text = "Step '${prevStep?.description}' executed. UI changes: ${verResult.diffSummary}"
                            )
                        )))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Previous step verification threw exception", e)
                }
                prevStepVerificationJob = null
            }

            // Pre-execution verification
            if (verificationEnabled && step.element != null &&
                (step.action == SemanticAction.CLICK || step.action == SemanticAction.TYPE)) {
                val verifier = VerificationClient(automationService, claudeClient, SecureStorage.getInstance(context))
                val verifyResult = verifier.verify(liveElementMap, plan, step)
                if (!verifyResult.safe) {
                    addStatus("Verification failed: ${verifyResult.reason}")
                    failureContext.add("Verification: ${verifyResult.reason}")
                    val directive = buildSafetyDirective(verifyResult.reason, step)
                    conversationHistory.add(Message(role = "user", content = listOf(
                        ContentBlock.TextContent(text = directive)
                    )))
                    ConversationHistoryManager.add(
                        ConversationItem(
                            timestamp = System.currentTimeMillis(),
                            type = ConversationItem.ItemType.ERROR,
                            status = "Safety: ${verifyResult.reason}",
                            actionDescription = directive
                        )
                    )
                    break
                }
            }

            // Snapshot element map text before execution for verification comparison
            prevStepMapText = liveMapText

            // Set planned action for intervention tracking
            val interventionTracker = InterventionTracker.getInstance(context)
            // Build a compact element map snapshot (IDs + texts, up to 30 elements)
            val compactSnapshot = liveElementMap.elements.take(30).joinToString(";") {
                "${it.id}:${it.text.take(30)}"
            }
            interventionTracker.setPlannedAction(step, compactSnapshot)

            // Update current app context for tracker
            try {
                val deviceCtx = automationService.getDeviceContext()
                interventionTracker.setCurrentApp(deviceCtx.currentAppName, deviceCtx.currentAppPackage)
            } catch (_: Exception) {}

            // Handle EXTRACT action inline (needs claudeClient + conversationHistory)
            if (step.action == SemanticAction.EXTRACT) {
                val query = step.extractQuery
                if (query.isNullOrBlank()) {
                    addStatus("Extract: missing query")
                } else {
                    val extractor = SemanticExtractor(claudeClient)
                    val extractResult = extractor.extract(query, liveElementMap, screenshotInfo)
                    conversationHistory.add(Message(
                        role = "user",
                        content = listOf(ContentBlock.TextContent(
                            text = "EXTRACTION RESULT for '$query': ${extractResult.answer} [source: ${extractResult.source}]"
                        ))
                    ))
                    discoveredFacts.add(query to extractResult.answer)
                    updatePinnedTaskMessage(task, updatedPlan)
                    addStatus("Extract (${extractResult.source}): ${extractResult.answer.take(60)}")
                    ConversationHistoryManager.add(
                        ConversationItem(
                            timestamp = System.currentTimeMillis(),
                            type = ConversationItem.ItemType.ACTION_EXECUTED,
                            action = "EXTRACT",
                            actionDescription = "Extract: $query → ${extractResult.answer.take(80)}",
                            status = "Extract: ${extractResult.answer.take(60)}"
                        )
                    )
                }
                continue // Move to next step
            }

            // Handle NOTE action inline — persist a fact to the pinned scratchpad
            if (step.action == SemanticAction.NOTE) {
                val noteText = step.noteText
                if (!noteText.isNullOrBlank()) {
                    val label = step.description.ifBlank { "Note" }
                    discoveredFacts.add(label to noteText)
                    updatePinnedTaskMessage(task, updatedPlan)
                    Log.d(TAG, "Note saved: $label = $noteText")
                    addStatus("Noted: ${noteText.take(60)}")
                    ConversationHistoryManager.add(
                        ConversationItem(
                            timestamp = System.currentTimeMillis(),
                            type = ConversationItem.ItemType.ACTION_EXECUTED,
                            action = "NOTE",
                            actionDescription = "Note: $label → ${noteText.take(80)}",
                            status = "Noted: ${noteText.take(60)}"
                        )
                    )
                }
                continue
            }

            // Handle ASK_EXPERT / WEB_SEARCH — launch in background, result consumed at top of main loop
            if (step.action == SemanticAction.ASK_EXPERT || step.action == SemanticAction.WEB_SEARCH) {
                val query = step.searchQuery ?: step.extractQuery
                if (query.isNullOrBlank()) {
                    addStatus("${step.action}: missing query")
                } else if (planningAgent != null) {
                    val label = if (step.action == SemanticAction.ASK_EXPERT) "expert" else "search"
                    addStatus("Asking $label in background: ${query.take(50)}...")
                    val fullQuery = if (step.action == SemanticAction.WEB_SEARCH) {
                        "Web search query: $query\nProvide the most accurate, current answer you can. " +
                        "If this requires truly real-time data you don't have, say NAVIGATE_NEEDED."
                    } else query
                    pendingExpertJob = PendingExpertQuery(
                        job = orchestratorScope.async(Dispatchers.IO) {
                            planningAgent.answerQuestion(fullQuery)
                        },
                        query = query,
                        action = step.action
                    )
                } else {
                    val fallback = if (step.action == SemanticAction.ASK_EXPERT) {
                        "EXPERT UNAVAILABLE: No thinking model configured. Try using web_search or navigating to the information manually."
                    } else {
                        "WEB SEARCH UNAVAILABLE: Navigate to a browser and search for: $query"
                    }
                    conversationHistory.add(Message(
                        role = "user",
                        content = listOf(ContentBlock.TextContent(text = fallback))
                    ))
                    addStatus("${step.action} unavailable — no thinking model")
                }
                continue
            }

            // Execute the step
            val stepStartTime = System.currentTimeMillis()
            val result = executeSemanticStep(step, liveElementMap, automationService, cursorOverlay)
            val stepLatencyMs = System.currentTimeMillis() - stepStartTime

            // Clear planned action after a short buffer to catch delayed events
            orchestratorScope.launch {
                delay(500)
                interventionTracker.clearPlannedAction()
            }

            if (!result.success) {
                InterventionTracker.getInstance(context).logTrace(
                    "STEP_FAILED", step.description,
                    action = step.action.name,
                    elementId = step.element,
                    iteration = iteration,
                    stepIndex = stepIdx,
                    success = false,
                    failureReason = result.failureReason
                )
                val retryOutcome = handleStepFailure(
                    step, result, liveElementMap, liveMapText, screenshotInfo, screenW, screenH,
                    ocrElements, automationService, cursorOverlay, treeExtractor, captureService,
                    sendScreenshotsToLlm, failureContext, planningAgent, updatedPlan, installedApps,
                    task, iteration, updatedLastPlanIteration, failuresSinceLastPlan, stepIdx, plan
                )
                failuresSinceLastPlan = retryOutcome.updatedFailuresSinceLastPlan
                if (retryOutcome.updatedPlan != null) updatedPlan = retryOutcome.updatedPlan
                if (retryOutcome.updatedLastPlanIteration >= 0) updatedLastPlanIteration = retryOutcome.updatedLastPlanIteration
                if (retryOutcome.updatedMapText != null) updatedMapText = retryOutcome.updatedMapText
                if (retryOutcome.shouldContinue) continue // Retry succeeded, next step
                break // Re-plan
            }

            // Success — log and track
            val annotated = if (result.clickX != null && result.clickY != null) {
                createAnnotatedScreenshot(screenshotInfo, result.clickX, result.clickY, result.chosenElementId, liveElementMap)
            } else null

            recentActions.add(ActionRecord(step.action, step.element, step.text, step.description))
            if (recentActions.size > 20) recentActions.removeAt(0)

            addStatus("done: ${step.description}")
            InterventionTracker.getInstance(context).logTrace(
                "STEP_EXECUTED", step.description,
                action = step.action.name,
                elementId = step.element,
                iteration = iteration,
                stepIndex = stepIdx,
                success = true
            )
            ConversationHistoryManager.add(
                ConversationItem(
                    timestamp = System.currentTimeMillis(),
                    type = ConversationItem.ItemType.ACTION_EXECUTED,
                    action = "${step.action} ${step.element ?: ""}",
                    actionDescription = step.description,
                    status = "Executed: ${step.description}",
                    elementMapText = liveMapText,
                    chosenElementId = result.chosenElementId,
                    chosenElementText = result.chosenElementText,
                    clickX = result.clickX, clickY = result.clickY,
                    annotatedScreenshot = annotated,
                    screenshot = screenshotInfo?.base64Data,
                    screenshotWidth = screenshotInfo?.actualWidth ?: screenW,
                    screenshotHeight = screenshotInfo?.actualHeight ?: screenH,
                    latencyMs = stepLatencyMs
                )
            )

            // Launch parallel verification with pre-step snapshot
            prevStep = step
            val capturedPreStepMapText = prevStepMapText  // capture for async lambda
            prevStepVerificationJob = if (step.action == SemanticAction.TYPE || step.action == SemanticAction.CLICK) {
                orchestratorScope.async(Dispatchers.IO) {
                    delay(150)
                    verifyStepEffect(step, automationService, treeExtractor,
                        screenshotInfo?.actualWidth ?: screenW, screenshotInfo?.actualHeight ?: screenH,
                        capturedPreStepMapText)
                }
            } else null

            // Wait for UI to settle between steps
            if (stepIdx < plan.steps.lastIndex) {
                val isNavAction = step.action in listOf(
                    SemanticAction.OPEN_APP, SemanticAction.BACK, SemanticAction.HOME, SemanticAction.SWIPE, SemanticAction.PRESS_ENTER
                )
                if (isNavAction) waitForUiSettle(captureService, minWaitMs = 150, maxWaitMs = 1200, pollIntervalMs = 150)
                else waitForUiSettle(captureService, minWaitMs = 50, maxWaitMs = 500, pollIntervalMs = 100)

                // Refresh element map after UI-changing actions so subsequent steps use fresh data
                if (step.action in listOf(SemanticAction.CLICK, SemanticAction.TYPE, SemanticAction.SWIPE,
                        SemanticAction.OPEN_APP, SemanticAction.BACK, SemanticAction.HOME, SemanticAction.PRESS_ENTER)) {
                    try {
                        // Hide overlays during extraction so they don't obscure elements
                        StatusOverlay.getInstance(context).setInvisible(true)
                        CursorOverlay.getInstance(context).setInvisible(true)
                        val freshElements = treeExtractor.extract()
                        StatusOverlay.getInstance(context).setInvisible(false)
                        CursorOverlay.getInstance(context).setInvisible(false)
                        val freshMap = ElementMapGenerator(
                            screenshotInfo?.actualWidth ?: screenW,
                            screenshotInfo?.actualHeight ?: screenH
                        ).generate(freshElements, ocrElements)
                        liveElementMap = freshMap
                        liveMapText = freshMap.toTextRepresentation()
                        updatedMapText = liveMapText
                        Log.d(TAG, "Refreshed element map between steps: ${freshMap.elements.size} elements")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to refresh element map between steps", e)
                        // Ensure overlays are restored even on failure
                        StatusOverlay.getInstance(context).setInvisible(false)
                        CursorOverlay.getInstance(context).setInvisible(false)
                    }
                }
            }
        }

        // Check final step verification
        if (prevStepVerificationJob != null) {
            try {
                val finalCheck = prevStepVerificationJob!!.await()
                if (!finalCheck.success) {
                    Log.w(TAG, "Final step verification failed: ${finalCheck.reason}")
                    failureContext.add("Last step '${prevStep?.description}' didn't take effect: ${finalCheck.reason}")
                } else if (finalCheck.diffSummary != null) {
                    Log.d(TAG, "Final step '${prevStep?.description}' UI changes: ${finalCheck.diffSummary}")
                    conversationHistory.add(Message(role = "user", content = listOf(
                        ContentBlock.TextContent(
                            text = "Step '${prevStep?.description}' executed. UI changes: ${finalCheck.diffSummary}"
                        )
                    )))
                }
            } catch (_: Exception) {}
        }

        StatusOverlay.getInstance(context).setPassThrough(false)
        return StepPlanResult(signal, updatedPlan, updatedLastPlanIteration, failuresSinceLastPlan, updatedMapText)
    }

    // ── Extracted: handle COMPLETE action with verification ──

    private data class CompleteActionResult(
        val signal: StepLoopSignal,
        val failuresSinceLastPlanDelta: Int = 0  // amount to add to failuresSinceLastPlan
    )

    private suspend fun handleCompleteAction(
        step: SemanticStep,
        task: String,
        claudeClient: LLMClient,
        treeExtractor: AccessibilityTreeExtractor,
        captureService: ScreenCaptureService?,
        sendScreenshotsToLlm: Boolean,
        failureContext: MutableList<String>
    ): CompleteActionResult {
        val msg = step.description.ifBlank { "Task completed" }
        if (msg.startsWith("Cannot complete", ignoreCase = true) ||
            msg.startsWith("Failed", ignoreCase = true)) {
            addStatus("Task failed: $msg")
            showTaskSuggestionDialog(msg, conversationHistory)
            onTaskFinished?.invoke("failed", -1, msg)
            onTaskFinished = null
            return CompleteActionResult(StepLoopSignal.TASK_COMPLETED)
        }

        addStatus("Verifying task completion...")
        delay(200)
        val verifyScreenshot = captureCleanScreenshot(captureService)

        if (verifyScreenshot != null) {
            val (verified, reason) = verifyTaskCompletion(
                claudeClient, task,
                if (sendScreenshotsToLlm) verifyScreenshot else null,
                treeExtractor, conversationHistory
            )
            if (verified) {
                taskSucceeded = true
                addStatus("Task completed: $msg")
                showToast("Task completed: $msg")
                onTaskFinished?.invoke("completed", -1, msg)
                onTaskFinished = null
                return CompleteActionResult(StepLoopSignal.TASK_COMPLETED)
            } else {
                // Detect "wrong task" — agent is working on something completely different
                val reasonLower = reason.lowercase()
                val isWrongTask = listOf("wrong", "different", "instead of", "not the", "completely", "unrelated")
                    .any { it in reasonLower }

                val failureDelta = if (isWrongTask) 3 else 1  // fast-track re-plan if on wrong task

                addStatus(if (isWrongTask) "Wrong task detected — forcing re-plan" else "Task not actually done — continuing")
                failureContext.add("Agent claimed complete but verification failed: $msg. Reason: $reason")
                completedSubTasks.add("[INCOMPLETE — verification failed] $msg (reason: ${reason.take(80)})")
                updatePinnedTaskMessage(task, null)
                conversationHistory.add(Message(role = "user", content = listOf(
                    ContentBlock.TextContent(
                        text = "You said the task is complete ('$msg') but the task is NOT done yet. " +
                            "Verification says: $reason\n" +
                            "The original task is: $task\n" +
                            "Look at the current screen and continue working. Do NOT say complete until EVERY part of the task is finished."
                    )
                )))
                return CompleteActionResult(StepLoopSignal.CONTINUE, failuresSinceLastPlanDelta = failureDelta)
            }
        } else {
            taskSucceeded = true
            addStatus("Task completed: $msg")
            showToast("Task completed: $msg")
            onTaskFinished?.invoke("completed", -1, msg)
            onTaskFinished = null
            return CompleteActionResult(StepLoopSignal.TASK_COMPLETED)
        }
    }

    // ── Extracted: handle step failure with retry + recovery planning ──

    private data class StepFailureResult(
        val shouldContinue: Boolean,
        val updatedPlan: PlanningResult? = null,
        val updatedLastPlanIteration: Int = -1,
        val updatedFailuresSinceLastPlan: Int = 0,
        val updatedMapText: String? = null
    )

    private suspend fun handleStepFailure(
        step: SemanticStep,
        result: StepExecutionResult,
        elementMap: ElementMap,
        currentMapText: String,
        screenshotInfo: ScreenshotInfo?,
        screenW: Int, screenH: Int,
        ocrElements: List<UIElement>,
        automationService: AutomationService,
        cursorOverlay: CursorOverlay,
        treeExtractor: AccessibilityTreeExtractor,
        captureService: ScreenCaptureService?,
        sendScreenshotsToLlm: Boolean,
        failureContext: MutableList<String>,
        planningAgent: PlanningAgent?,
        currentPlan: PlanningResult?,
        installedApps: List<AppInfo>,
        task: String,
        iteration: Int,
        lastPlanConsultIteration: Int,
        incomingFailuresSinceLastPlan: Int,
        stepIdx: Int,
        plan: SemanticActionPlan
    ): StepFailureResult {
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

        // Retry once with fresh element map
        addStatus("Retrying with fresh element map...")
        waitForUiSettle(captureService, minWaitMs = 80, maxWaitMs = 400)
        val retryTree = treeExtractor.extract()
        val retryMap = ElementMapGenerator(
            screenshotInfo?.actualWidth ?: screenW, screenshotInfo?.actualHeight ?: screenH
        ).generate(retryTree, ocrElements)
        val retryMapText = retryMap.toTextRepresentation()

        val retryResult = executeSemanticStep(step, retryMap, automationService, cursorOverlay)
        if (retryResult.success) {
            addStatus("Retry succeeded: ${step.description}")
            val retryAnnotated = if (retryResult.clickX != null && retryResult.clickY != null) {
                createAnnotatedScreenshot(screenshotInfo, retryResult.clickX, retryResult.clickY, retryResult.chosenElementId, retryMap)
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
                    clickX = retryResult.clickX, clickY = retryResult.clickY,
                    annotatedScreenshot = retryAnnotated,
                    screenshot = screenshotInfo?.base64Data,
                    screenshotWidth = screenshotInfo?.actualWidth ?: screenW,
                    screenshotHeight = screenshotInfo?.actualHeight ?: screenH
                )
            )
            if (stepIdx < plan.steps.lastIndex) {
                waitForUiSettle(captureService, minWaitMs = 50, maxWaitMs = 400)
            }
            return StepFailureResult(shouldContinue = true, updatedMapText = retryMapText)
        }

        // Retry also failed
        val retryReason = retryResult.failureReason ?: "Unknown"
        val fullFailure = "Step '${step.description}' failed twice.\nFirst attempt: $reason\nRetry: $retryReason"
        failureContext.add(fullFailure)
        var failuresSinceLastPlan = incomingFailuresSinceLastPlan + 1
        var updatedPlan: PlanningResult? = null
        var updatedLastPlanIteration = -1

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

        conversationHistory.add(Message(role = "user", content = listOf(
            ContentBlock.TextContent(
                text = "Step FAILED (tried twice): ${step.description}\n" +
                    "Reason: $reason\nRetry reason: $retryReason\n" +
                    "The element '${step.element}' could not be found or interacted with. " +
                    "Re-analyze the current screen and try a completely different approach. " +
                    "If unsure which element is correct, use search or scroll to find it."
            )
        )))

        // Launch recovery planning in background (non-blocking) on repeated failures
        if (planningAgent != null && failuresSinceLastPlan >= 3 && iteration - lastPlanConsultIteration >= 3
            && pendingRecoveryPlanJob == null) {
            addStatus("Launching recovery planning in background...")
            val recovMapText = currentMapText
            val recovFailureCtx = failureContext.toList()
            val recovCurrentPlan = currentPlan
            val recovApps = installedApps
            pendingRecoveryPlanJob = orchestratorScope.async(Dispatchers.IO) {
                try {
                    val recoveryScreenshot = withContext(Dispatchers.Main) { captureCleanScreenshot(captureService) }
                    planningAgent.planRecovery(
                        task, recoveryScreenshot, recovMapText,
                        recovFailureCtx, recovCurrentPlan, recovApps
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Background recovery planning failed", e)
                    null
                }
            }
            failuresSinceLastPlan = 0
            updatedLastPlanIteration = iteration
        }

        return StepFailureResult(
            shouldContinue = false,
            updatedPlan = updatedPlan,
            updatedLastPlanIteration = updatedLastPlanIteration,
            updatedFailuresSinceLastPlan = failuresSinceLastPlan
        )
    }

    /**
     * Returns Pair(completed, reason). Reason is always populated when completed=false.
     */
    private suspend fun verifyTaskCompletion(
        claudeClient: LLMClient,
        originalTask: String,
        screenshotInfo: ScreenshotInfo?,
        treeExtractor: AccessibilityTreeExtractor,
        conversationHistory: List<Message>
    ): Pair<Boolean, String> {
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
                Look for CONCRETE EVIDENCE that the action succeeded — e.g. a sent message appearing in a chat thread,
                a confirmation screen, a success toast/banner, or the expected result visible on screen.
                Do NOT assume success just because a button was tapped. If the screen looks the same as before
                the action, or shows an input field still waiting for content, the task is NOT complete.
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
            val text = result.getOrNull()?.content?.firstOrNull()?.text ?: return Pair(true, "")

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

            Pair(completed, reason)
        } catch (e: Exception) {
            Log.e(TAG, "Completion verification failed", e)
            Pair(false, "Verification error: ${e.message}") // Fail safe: keep working
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
                            .filter { it.isClickable || it.source == com.agentrelay.models.ElementSource.OCR }
                            .take(15)
                            .joinToString(", ") { "${it.id} (\"${it.text.take(20)}\")" }
                        val reason = "Element '${step.element}' not found in element map. " +
                            "Available elements: [$availableIds]"
                        Log.e(TAG, reason)
                        return StepExecutionResult(false, failureReason = reason)
                    }
                    val clickPt = elementMap.safeClickPoint(element)
                    val x = clickPt.x
                    val y = clickPt.y
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
                            val clickPt = elementMap.safeClickPoint(element)
                            tapX = clickPt.x
                            tapY = clickPt.y
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
                    // If an element is specified, swipe within its bounds (useful for
                    // horizontal scroll rows like share sheet app lists)
                    val targetElement = step.element?.let { elementMap.findById(it) }
                    val centerX = targetElement?.let { (it.bounds.left + it.bounds.right) / 2 } ?: (screenW / 2)
                    val centerY = targetElement?.let { (it.bounds.top + it.bounds.bottom) / 2 } ?: (screenH / 2)
                    val swipeDistance = if (targetElement != null && (direction == "left" || direction == "right")) {
                        // For horizontal swipes on an element, use element width
                        (targetElement.bounds.right - targetElement.bounds.left) * 2 / 3
                    } else {
                        screenH / 3
                    }

                    val (startX, startY, endX, endY) = when (direction) {
                        "up" -> listOf(centerX, centerY + swipeDistance / 2, centerX, centerY - swipeDistance / 2)
                        "down" -> listOf(centerX, centerY - swipeDistance / 2, centerX, centerY + swipeDistance / 2)
                        "left" -> {
                            val swW = if (targetElement != null) {
                                (targetElement.bounds.right - targetElement.bounds.left) * 2 / 3
                            } else { screenW / 2 }
                            listOf(centerX + swW / 2, centerY, centerX - swW / 2, centerY)
                        }
                        "right" -> {
                            val swW = if (targetElement != null) {
                                (targetElement.bounds.right - targetElement.bounds.left) * 2 / 3
                            } else { screenW / 2 }
                            listOf(centerX - swW / 2, centerY, centerX + swW / 2, centerY)
                        }
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
                    // Block redundant OPEN_APP if the target app is already in the foreground
                    val currentPkg = automationService.getCurrentAppPackage()
                    if (currentPkg == pkg) {
                        val openAppCount = recentActions.takeLast(5).count { it.action == SemanticAction.OPEN_APP }
                        if (openAppCount >= 2) {
                            return StepExecutionResult(false,
                                failureReason = "BLOCKED: App '$pkg' is ALREADY the current foreground app. " +
                                    "You have opened it ${openAppCount} times recently. " +
                                    "STOP re-opening and interact with the current screen instead — click elements, swipe, or type.")
                        }
                    }
                    val ok = automationService.performOpenApp(pkg)
                    if (!ok) {
                        return StepExecutionResult(false, failureReason = "Failed to launch app: $pkg — is it installed?")
                    }
                    // Wait for app to launch
                    delay(1500)
                    // Verify we landed in the right app
                    val nowPkg = automationService.getCurrentAppPackage()
                    if (nowPkg != null && nowPkg != pkg) {
                        Log.w(TAG, "open_app targeted $pkg but landed in $nowPkg")
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

                SemanticAction.PRESS_ENTER -> {
                    val ok = automationService.pressKeyboardEnter()
                    StepExecutionResult(ok, failureReason = if (!ok) "Failed to press keyboard enter key" else null)
                }

                SemanticAction.SHARE_FINDING -> {
                    Log.d(TAG, "Share finding: ${step.findingKey} = ${step.findingValue}")
                    StepExecutionResult(true)
                }

                SemanticAction.EXTRACT -> StepExecutionResult(true) // handled inline before this call
                SemanticAction.NOTE -> StepExecutionResult(true) // handled inline before this call
                SemanticAction.ASK_EXPERT -> StepExecutionResult(true) // handled inline before this call
                SemanticAction.WEB_SEARCH -> StepExecutionResult(true) // handled inline before this call

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
        screenHeight: Int,
        preStepMapText: String
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
                        val freshText = freshMap.toTextRepresentation()
                        val targetEl = step.element?.let { freshMap.findById(it) }
                        if (targetEl != null && targetEl.text.contains(expectedText, ignoreCase = true)) {
                            StepVerificationResult(true, diffSummary = summarizeMapDiff(preStepMapText, freshText))
                        } else if (targetEl != null) {
                            StepVerificationResult(false,
                                "Text field '${step.element}' contains '${targetEl.text.take(30)}' instead of '${expectedText.take(30)}'")
                        } else {
                            // Can't find element, UI probably changed — assume OK
                            StepVerificationResult(true, diffSummary = summarizeMapDiff(preStepMapText, freshText))
                        }
                    } else if (fieldText.contains(expectedText, ignoreCase = true)) {
                        StepVerificationResult(true)
                    } else {
                        // WebView inputs (Chrome address bar, search fields) often don't expose
                        // text through accessibility focus. Check if the element map now shows
                        // elements containing our text (e.g., autocomplete suggestions with our query).
                        val elements = treeExtractor.extract()
                        val mapGen = ElementMapGenerator(screenWidth, screenHeight)
                        val freshMap = mapGen.generate(elements)
                        val freshText = freshMap.toTextRepresentation()
                        val textLower = expectedText.lowercase()
                        val mapContainsText = freshMap.elements.any { el ->
                            el.text.lowercase().contains(textLower) ||
                            el.id.lowercase().contains(textLower.replace(" ", "_").take(20))
                        }
                        if (mapContainsText) {
                            // Text appears in element map (e.g., in autocomplete suggestions or input field)
                            StepVerificationResult(true,
                                diffSummary = "Text '${expectedText.take(20)}' found in element map (WebView/browser input)")
                        } else {
                            StepVerificationResult(false,
                                "Text field contains '${fieldText.take(30)}' but expected '${expectedText.take(30)}'")
                        }
                    }
                }

                SemanticAction.CLICK -> {
                    val elements = treeExtractor.extract()
                    val freshMap = ElementMapGenerator(screenWidth, screenHeight).generate(elements)
                    val freshText = freshMap.toTextRepresentation()

                    if (freshText == preStepMapText) {
                        // UI is identical — but clicking INPUT/text fields often doesn't
                        // change the a11y tree (focus + keyboard appear, but element props stay same).
                        // Allow these through to avoid false "no visible effect" blocks.
                        val clickedElement = step.element?.let { freshMap.findById(it) }
                        val isInputElement = clickedElement?.type == com.agentrelay.models.ElementType.INPUT
                        val isTextElement = clickedElement?.type == com.agentrelay.models.ElementType.TEXT
                        if (isInputElement || isTextElement) {
                            StepVerificationResult(true,
                                diffSummary = "Clicked ${clickedElement?.type} element (focus may have changed)")
                        } else {
                            StepVerificationResult(false,
                                "Click on '${step.element}' had no visible effect — UI unchanged")
                        }
                    } else {
                        // UI changed — report what changed for context
                        StepVerificationResult(true, diffSummary = summarizeMapDiff(preStepMapText, freshText))
                    }
                }

                else -> StepVerificationResult(true)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Step verification failed", e)
            StepVerificationResult(
                success = false,
                reason = "Verification inconclusive due to ${e.javaClass.simpleName}: ${e.message ?: "unknown error"}"
            )
        }
    }

    /**
     * Compares two element map text representations and returns a short summary of changes.
     */
    private fun summarizeMapDiff(before: String, after: String): String {
        val beforeLines = before.lines().toSet()
        val afterLines = after.lines().toSet()
        val added = (afterLines - beforeLines).take(3).joinToString(", ") { it.take(40) }
        val removed = (beforeLines - afterLines).take(3).joinToString(", ") { it.take(40) }
        return buildString {
            if (added.isNotEmpty()) append("New: $added")
            if (removed.isNotEmpty()) { if (isNotEmpty()) append("; "); append("Gone: $removed") }
            if (isEmpty()) append("Minor UI changes")
        }.take(150)
    }

    /**
     * Analyzes recent action history for repeated same-element actions.
     * If the model keeps clicking/typing the same element 2+ times without
     * the screen changing, provides specific diagnostic scenarios and
     * creative solutions to break out of the loop.
     */
    private fun buildActionHistoryContext(): String {
        if (recentActions.size < 2) return ""

        // Check for WAIT/OPEN_APP spam (these don't have elements, so element-based detection misses them)
        val lastFive = recentActions.takeLast(5)
        val waitOpenCount = lastFive.count { it.action == SemanticAction.WAIT || it.action == SemanticAction.OPEN_APP }
        if (waitOpenCount >= 3) {
            return buildString {
                appendLine("⚠️ STAGNATION DETECTED: You have issued $waitOpenCount WAIT/OPEN_APP actions in the last 5 steps without meaningful interaction.")
                appendLine("Repeatedly opening the app or waiting is NOT making progress. The app IS open — you need to INTERACT with it.")
                appendLine()
                appendLine("IMMEDIATE ACTIONS REQUIRED:")
                appendLine("1. LOOK at the element map — identify buttons, text fields, links, or OCR text elements you can click")
                appendLine("2. If the element map is mostly TEXT elements (from OCR), these ARE clickable — click them by ID")
                appendLine("3. If you see an onboarding screen, look for 'Accept', 'Continue', 'Skip', 'Got it', 'Next' elements and CLICK them")
                appendLine("4. If you see a disabled 'Got it' button on a carousel, SWIPE LEFT to advance through the carousel pages first")
                appendLine("5. If the app shows a permissions dialog, click 'Allow' or 'Accept'")
                appendLine("6. DO NOT issue another WAIT or OPEN_APP — you MUST click or type something on the current screen")
            }
        }

        // Check for EXTRACT/ASK_EXPERT spam — same query repeated
        val extractActions = recentActions.takeLast(6).filter {
            it.action == SemanticAction.EXTRACT || it.action == SemanticAction.ASK_EXPERT
        }
        if (extractActions.size >= 3) {
            // Check if descriptions are similar (same query repeated)
            val descSet = extractActions.map { it.description.lowercase().take(40) }.toSet()
            if (descSet.size <= 2) {
                return buildString {
                    appendLine("⚠️ REPEATED EXTRACT/QUERY DETECTED: You have issued ${extractActions.size} extract/ask_expert queries in the last 6 steps with similar content.")
                    appendLine("The information you're looking for is likely NOT on this screen. STOP querying and:")
                    appendLine("1. If you're looking for a specific field, try scrolling or looking for 'More fields'/'See more' buttons")
                    appendLine("2. If you can't find it after scrolling, SAVE/SUBMIT what you have — partial completion is better than timeout")
                    appendLine("3. Move on to the next part of the task")
                }
            }
        }

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

        // Detect oscillating pattern (A → B → A → B) by element ID
        val oscillatingById = recentActions.size >= 4 && run {
            val n = recentActions.size
            val a1 = recentActions[n - 1]
            val a2 = recentActions[n - 2]
            val a3 = recentActions[n - 3]
            val a4 = recentActions[n - 4]
            a1.elementId == a3.elementId && a2.elementId == a4.elementId && a1.elementId != a2.elementId
        }

        // Detect semantic oscillation: same description pairs cycling even with different element IDs
        // e.g., "Open sort menu" → "Select price sort" → "Open sort menu" → "Select price sort"
        val oscillatingByDesc = !oscillatingById && recentActions.size >= 4 && run {
            val n = recentActions.size
            val d1 = recentActions[n - 1].description.lowercase()
            val d2 = recentActions[n - 2].description.lowercase()
            val d3 = recentActions[n - 3].description.lowercase()
            val d4 = recentActions[n - 4].description.lowercase()
            d1.isNotEmpty() && d2.isNotEmpty() &&
                d1 == d3 && d2 == d4 && d1 != d2
        }

        // Detect cycling action-type pattern: all clicks, no other progress, repeated 6+ times
        val clickCycling = !oscillatingById && !oscillatingByDesc && recentActions.size >= 6 && run {
            val lastSix = recentActions.takeLast(6)
            lastSix.all { it.action == SemanticAction.CLICK } &&
                lastSix.map { it.description.lowercase() }.toSet().size <= 3
        }

        val oscillating = oscillatingById || oscillatingByDesc || clickCycling

        val elementDesc = last.elementId ?: "unknown"
        return buildString {
            if (oscillating) {
                appendLine("⚠️ OSCILLATION/CYCLE DETECTED: You are repeating the same pattern of actions without making real progress.")
                appendLine("You may be opening and closing the same menu/dropdown, or toggling the same control back and forth.")
                appendLine("This action sequence is NOT advancing the task. You MUST try something COMPLETELY DIFFERENT.")
            } else {
                appendLine("⚠️ ACTION LOOP DETECTED: You have performed the SAME action (${last.action} on '$elementDesc') $repeatCount times in a row without progress.")
            }
            appendLine()
            appendLine("STOP and DIAGNOSE — consider these common causes:")
            appendLine()
            appendLine("1. WRONG BUTTON: There may be MULTIPLE elements with similar text (e.g., two 'Submit' buttons, a header vs. an actual button). Look carefully at ALL elements in the map — you may be clicking a non-interactive label instead of the real button, or clicking a button that's part of a different section/dialog.")
            appendLine()
            appendLine("2. KEYBOARD BLOCKING: The soft keyboard may be covering the element you need to tap. Use 'dismiss_keyboard' first, then look for the element again — it may appear in a different position or new elements may become visible.")
            appendLine()
            appendLine("3. CONTENT OFF-SCREEN: The target button or field may be below the visible area. Swipe UP to scroll down and reveal more content. Many forms have submit buttons at the bottom that require scrolling. Also try swiping DOWN if you may have scrolled past it.")
            appendLine()
            appendLine("4. ELEMENT MISIDENTIFICATION: The element map classifies UI elements by type, but this can be wrong. A 'TEXT' element might actually be a tappable link. An 'IMAGE' might be a button. Scan ALL elements for matching text regardless of type.")
            appendLine()
            appendLine("5. DIALOG OR POPUP BLOCKING: A permission dialog, popup, tooltip, or banner may be covering the target. Look for 'Allow', 'Accept', 'Dismiss', 'OK', 'Got it', or 'X' buttons that need to be cleared first.")
            appendLine()
            appendLine("6. WRONG SCREEN STATE: You may not be on the screen you think you are. Check the current app and screen carefully — perhaps a previous action didn't take effect, or navigation went somewhere unexpected. Consider going BACK and retrying the navigation.")
            appendLine()
            appendLine("7. NEEDS DIFFERENT INTERACTION: Try LONG PRESS instead of tap, or try DOUBLE TAP. Some elements only respond to specific gesture types.")
            appendLine()
            appendLine("8. ALTERNATIVE PATH: Completely abandon this approach. Look for a different way to achieve the same goal — a menu icon (⋮), a search bar, a different tab, the app's settings, or even a different app entirely.")
            appendLine()
            appendLine("MANDATORY: You MUST pick one of the above diagnoses and try a DIFFERENT action. DO NOT repeat the same action on '$elementDesc'.")
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
        // When recording, VirtualDisplay is pointed at MediaRecorder — ImageReader
        // won't receive frames, so fingerprinting would always time out. Just wait.
        if (captureService == null || screenRecorder?.isRecording() == true) {
            delay(maxWaitMs - minWaitMs)
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

    /**
     * Updates the pinned first message in conversation history with current task + planning guidance.
     * This ensures the original task is always visible even after history trimming.
     */
    private fun updatePinnedTaskMessage(task: String, plan: PlanningResult?) {
        if (conversationHistory.isEmpty()) return
        val pinnedText = buildString {
            append("TASK: $task")
            if (plan != null) {
                append("\n\nSTRATEGIC GUIDANCE:\n")
                append(plan.toGuidanceText())
            }
            if (discoveredFacts.isNotEmpty()) {
                append("\n\nDISCOVERED FACTS (from earlier screens — use these, do NOT re-look-up):\n")
                discoveredFacts.forEach { (label, value) -> append("- $label: $value\n") }
            }
            if (completedSubTasks.isNotEmpty()) {
                append("\n\nCOMPLETED SUB-TASKS (already done — do NOT redo these):\n")
                completedSubTasks.forEach { append("✓ $it\n") }
                append("Continue from where you left off. Only work on parts of the task NOT listed above.")
            }
        }
        conversationHistory[0] = Message(
            role = "user",
            content = listOf(ContentBlock.TextContent(text = pinnedText))
        )
    }

    /**
     * Sliding window: keeps the first (pinned task) message + last [maxWindowSize] messages,
     * inserting a bridging note where history was trimmed.
     */
    private fun trimConversationHistory(maxWindowSize: Int = 12) {
        // first message + bridging note + last N = maxWindowSize + 2
        if (conversationHistory.size <= maxWindowSize + 2) return
        val first = conversationHistory.first()
        val trimCount = conversationHistory.size - maxWindowSize - 1
        val bridgingNote = Message(
            role = "user",
            content = listOf(ContentBlock.TextContent(
                text = "[Conversation history trimmed — $trimCount earlier messages removed to save context. The pinned TASK message above is always current. Focus on the current screen state and recent actions.]"
            ))
        )
        val tail = conversationHistory.takeLast(maxWindowSize)
        conversationHistory.clear()
        conversationHistory.add(first)
        conversationHistory.add(bridgingNote)
        conversationHistory.addAll(tail)
        Log.d(TAG, "Trimmed conversation history: removed $trimCount messages, now ${conversationHistory.size}")
    }

    /**
     * Computes a structural hash of the element map using CRC32.
     * Quantizes positions to 100px grid and truncates text to 20 chars
     * so minor layout shifts don't produce different hashes.
     */
    private fun computeScreenStructureHash(elementMap: ElementMap): Long {
        val crc = CRC32()
        for (el in elementMap.elements) {
            val typeStr = el.type.name
            val qx = (el.bounds.centerX() / 100) * 100
            val qy = (el.bounds.centerY() / 100) * 100
            val truncText = el.text.take(20)
            val clickable = if (el.isClickable) "C" else ""
            val entry = "$typeStr@${qx},${qy}:${truncText}$clickable\n"
            crc.update(entry.toByteArray())
        }
        return crc.value
    }

    /**
     * Pattern-matches on safety checker reason to generate specific actionable directives
     * instead of the generic "Re-analyze" message.
     */
    private fun buildSafetyDirective(reason: String, step: SemanticStep): String {
        val reasonLower = reason.lowercase()
        val directive = when {
            "disappeared" in reasonLower || "not found" in reasonLower || "missing" in reasonLower ->
                "The element '${step.element}' has DISAPPEARED from the screen since you planned this action. " +
                "A navigation change, popup, or screen transition likely occurred. " +
                "ACTION: Re-read the current element map carefully. Find the NEW element that corresponds " +
                "to what you were trying to interact with, or dismiss any dialog/popup that appeared."

            "re-indexed" in reasonLower || "index" in reasonLower || "id changed" in reasonLower ->
                "The element '${step.element}' has been RE-INDEXED — its ID changed because the element map was " +
                "regenerated after a UI update. The element you want probably still exists but with a DIFFERENT ID. " +
                "ACTION: Search the current element map for an element with similar text/type/position and use its new ID."

            "shifted" in reasonLower || "moved" in reasonLower || "position" in reasonLower ->
                "The element '${step.element}' has SHIFTED position, likely due to keyboard appearance, " +
                "content loading, or a layout change. " +
                "ACTION: If keyboard appeared, dismiss it first. Then re-locate the element in the updated map — " +
                "it should have similar text but different coordinates."

            "modal" in reasonLower || "dialog" in reasonLower || "popup" in reasonLower || "overlay" in reasonLower ->
                "A MODAL/DIALOG/POPUP has appeared on top of your target element. " +
                "ACTION: First dismiss the overlay — look for 'OK', 'Cancel', 'Dismiss', 'X', 'Got it', or " +
                "'Allow'/'Deny' buttons. Only after clearing it should you retry your intended action."

            "different screen" in reasonLower || "wrong screen" in reasonLower || "navigated" in reasonLower ->
                "The app NAVIGATED AWAY from the expected screen. Your target element is on a different screen. " +
                "ACTION: Check what screen you're currently on. Navigate back or to the correct screen first."

            else ->
                "Verification failed: $reason. The UI has changed since this action was planned. " +
                "ACTION: Re-examine the CURRENT element map (not your cached memory of it). " +
                "Identify what changed and adapt your next action to the actual current screen state."
        }
        return "SAFETY CHECK FAILED for step '${step.description}':\n$directive"
    }

    /**
     * Asks a fast model (Haiku) whether the agent is making meaningful progress.
     * Returns (isProgressing, reason).
     */
    /**
     * Returns Triple(progressing, reason, taskCompleted).
     */
    private suspend fun checkProgressWithHaiku(
        claudeClient: LLMClient,
        task: String,
        currentMapText: String,
        recentActionsSummary: String
    ): Triple<Boolean, String, Boolean> {
        val systemPrompt = """
            You are a progress evaluator for an Android automation agent.
            Given the original task, current screen state, and recent actions,
            determine if the agent is making meaningful progress toward completing the task.

            Respond with ONLY a JSON object:
            {"progressing": true/false, "task_completed": true/false, "reason": "brief explanation"}

            Set "task_completed" to true ONLY if ALL parts of the original task have been fully accomplished
            based on the current screen state. For example, if the task was "set a 5 min timer and start it"
            and the screen shows a running timer counting down from 5:00, the task IS completed.

            Signs of NOT progressing:
            - Same actions repeated without visible change
            - Clicking elements that don't advance the task
            - Navigating in circles between screens
            - Getting stuck on irrelevant screens

            Signs of progressing:
            - New screens being reached
            - Form fields being filled
            - Navigation toward task goal
            - Meaningful UI changes after actions
        """.trimIndent()

        val prompt = """
            Task: $task

            Current screen element map (truncated):
            ${currentMapText.take(2000)}

            Last 5 actions:
            $recentActionsSummary

            Is the agent making meaningful progress toward the task?
        """.trimIndent()

        val messages = listOf(Message(role = "user", content = listOf(
            ContentBlock.TextContent(text = prompt)
        )))

        val result = withContext(Dispatchers.IO) {
            claudeClient.sendMessage(messages, systemPrompt)
        }
        val text = result.getOrNull()?.content?.firstOrNull()?.text
            ?: return Triple(true, "Progress check unavailable", false) // Fail open

        return try {
            var cleanJson = text.trim()
            val startIdx = cleanJson.indexOf('{')
            val endIdx = cleanJson.lastIndexOf('}')
            if (startIdx >= 0 && endIdx > startIdx) {
                cleanJson = cleanJson.substring(startIdx, endIdx + 1)
            }
            val reader = com.google.gson.stream.JsonReader(java.io.StringReader(cleanJson))
            reader.isLenient = true
            val json = com.google.gson.JsonParser.parseReader(reader).asJsonObject
            val progressing = json.get("progressing")?.asBoolean ?: true
            val reason = json.get("reason")?.asString ?: "Unknown"
            val taskCompleted = json.get("task_completed")?.asBoolean ?: false
            Triple(progressing, reason, taskCompleted)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse progress check response: $text", e)
            Triple(true, "Parse error", false) // Fail open
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
