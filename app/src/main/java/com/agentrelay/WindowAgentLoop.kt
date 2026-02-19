package com.agentrelay

import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.agentrelay.models.*
import kotlinx.coroutines.*

class WindowAgentLoop(
    private val context: Context,
    private val slot: SplitScreenSlot,
    private val windowInfo: WindowInfo,
    private val coordinator: SplitScreenCoordinator,
    private val apiKey: String,
    private val model: String,
    private val captureService: ScreenCaptureService?
) {
    private val conversationHistory = mutableListOf<Message>()
    private val recentActions = mutableListOf<ActionRecord>()
    private var currentWindowInfo: WindowInfo = windowInfo

    private data class ActionRecord(val action: SemanticAction, val elementId: String?, val text: String?)

    suspend fun run(task: String, maxIterations: Int = 30) {
        val automationService = AutomationService.instance ?: run {
            Log.e(TAG, "[$slot] AutomationService not available")
            return
        }
        val secureStorage = SecureStorage.getInstance(context)
        val claudeClient = LLMClientFactory.create(apiKey, model) { bytes, ms ->
            secureStorage.saveLastUploadTime(bytes, ms)
        }
        val treeExtractor = AccessibilityTreeExtractor(automationService)
        val screenshotMode = secureStorage.getScreenshotMode()
        val cursorOverlay = CursorOverlay.getInstance(context)

        for (iteration in 1..maxIterations) {
            refreshWindowInfo()
            if (!isActive()) break

            Log.d(TAG, "[$slot] Iteration $iteration/$maxIterations")

            // 1. Get window root
            var windowRoot: AccessibilityNodeInfo? = try {
                currentWindowInfo.windowInfo.root
            } catch (e: Exception) {
                Log.e(TAG, "[$slot] Failed to get window root", e)
                null
            }

            if (windowRoot == null) {
                Log.w(TAG, "[$slot] Window root is null, window may have closed")
                delay(500)
                // Try once more
                refreshWindowInfo()
                windowRoot = try { currentWindowInfo.windowInfo.root } catch (_: Exception) { null }
                if (windowRoot == null) {
                    Log.e(TAG, "[$slot] Window root still null, aborting loop")
                    break
                }
            }

            // 2. Extract tree from this window's root
            val elements = treeExtractor.extract(rootOverride = windowRoot)

            // 3. Generate element map using window bounds for dimensions
            val bounds = currentWindowInfo.bounds
            val mapGenerator = ElementMapGenerator(bounds.width(), bounds.height())
            val elementMap = mapGenerator.generate(elements, hasWebView = treeExtractor.hasWebView)
            val elementMapText = elementMap.toTextRepresentation()

            // 4. Decide screenshot and crop to this window's bounds
            val shouldSendScreenshot = when (screenshotMode) {
                ScreenshotMode.ON -> true
                ScreenshotMode.OFF -> false
                ScreenshotMode.AUTO -> {
                    val ocrOnlyCount = 0 // OCR not used in split-screen loop
                    ElementMapAnalyzer.shouldSendScreenshot(
                        elementMap = elementMap,
                        ocrOnlyCount = ocrOnlyCount,
                        previousIterationFailed = false
                    ).shouldSend
                }
            }
            val screenshotInfo = if (shouldSendScreenshot && captureService != null) {
                try {
                    // Hide overlays briefly for clean capture
                    withContext(Dispatchers.Main) {
                        CursorOverlay.getInstance(context).setInvisible(true)
                        StatusOverlay.getInstance(context).setInvisible(true)
                    }
                    delay(80)
                    captureService.captureScreenshotCropped(bounds)
                } catch (e: Exception) {
                    Log.w(TAG, "[$slot] Screenshot capture failed", e)
                    null
                } finally {
                    withContext(Dispatchers.Main) {
                        CursorOverlay.getInstance(context).setInvisible(false)
                        StatusOverlay.getInstance(context).setInvisible(false)
                    }
                }
            } else null

            // 5. Inject peer findings into the task
            val peerFindings = coordinator.getFindings()
            val enhancedTask = buildString {
                append(task)
                if (peerFindings.isNotEmpty()) {
                    append("\n\nPEER FINDINGS (from the other split-screen agent):\n")
                    peerFindings.forEach { (key, value) ->
                        append("- $key: $value\n")
                    }
                    append("Use this information to complete your task.")
                }
            }

            // 6. Call Claude API
            val deviceContext = try {
                automationService.getDeviceContext()
            } catch (_: Exception) { null }

            val planResult = withContext(Dispatchers.IO) {
                claudeClient.sendWithElementMap(
                    if (shouldSendScreenshot) screenshotInfo else null,
                    elementMap,
                    enhancedTask,
                    conversationHistory,
                    deviceContext,
                    peerFindings = peerFindings.takeIf { it.isNotEmpty() }
                )
            }

            if (planResult.isFailure) {
                Log.e(TAG, "[$slot] API call failed: ${planResult.exceptionOrNull()?.message}")
                delay(2000)
                continue
            }

            val plan = planResult.getOrNull() ?: continue
            Log.d(TAG, "[$slot] Plan: ${plan.reasoning}, ${plan.steps.size} steps")

            // Add to conversation history
            conversationHistory.add(
                Message(
                    role = "assistant",
                    content = listOf(ContentBlock.TextContent(
                        text = "Plan: ${plan.reasoning}\nSteps: ${plan.steps.joinToString("; ") { "${it.action} ${it.element ?: ""} ${it.description}" }}"
                    ))
                )
            )

            // 7. Execute steps
            var taskCompleted = false
            for (step in plan.steps) {
                if (!isActive()) break

                when (step.action) {
                    SemanticAction.SHARE_FINDING -> {
                        val key = step.findingKey
                        val value = step.findingValue
                        if (key != null && value != null) {
                            coordinator.postFinding(slot, key, value)
                            Log.d(TAG, "[$slot] Shared finding: $key = $value")
                        }
                        continue
                    }

                    SemanticAction.EXTRACT -> {
                        val query = step.extractQuery
                        if (!query.isNullOrBlank()) {
                            val extractor = SemanticExtractor(claudeClient)
                            val extractResult = extractor.extract(query, elementMap, screenshotInfo)
                            conversationHistory.add(Message(
                                role = "user",
                                content = listOf(ContentBlock.TextContent(
                                    text = "EXTRACTION RESULT for '$query': ${extractResult.answer}"
                                ))
                            ))
                            Log.d(TAG, "[$slot] Extract: ${extractResult.answer.take(60)}")
                        }
                        continue
                    }

                    SemanticAction.COMPLETE -> {
                        Log.d(TAG, "[$slot] Task complete: ${step.description}")
                        coordinator.markComplete(slot)
                        taskCompleted = true
                        break
                    }

                    else -> {
                        val result = executeStep(step, elementMap, automationService, cursorOverlay)
                        recentActions.add(ActionRecord(step.action, step.element, step.text))
                        if (recentActions.size > 15) recentActions.removeAt(0)

                        if (!result.success) {
                            Log.w(TAG, "[$slot] Step failed: ${step.description} — ${result.failureReason}")
                            conversationHistory.add(
                                Message(
                                    role = "user",
                                    content = listOf(ContentBlock.TextContent(
                                        text = "Step FAILED: ${step.description}. Reason: ${result.failureReason}. Try a different approach."
                                    ))
                                )
                            )
                            break
                        }

                        // Wait for UI to settle
                        delay(300)
                    }
                }
            }

            if (taskCompleted) break

            // Add user message for next iteration
            conversationHistory.add(
                Message(
                    role = "user",
                    content = listOf(ContentBlock.TextContent(
                        text = "Continue working on the task. What should I do next?"
                    ))
                )
            )
        }
    }

    private suspend fun executeStep(
        step: SemanticStep,
        elementMap: ElementMap,
        automationService: AutomationService,
        cursorOverlay: CursorOverlay
    ): StepResult {
        return try {
            when (step.action) {
                SemanticAction.CLICK, SemanticAction.LONG_PRESS -> {
                    val element = step.element?.let { elementMap.findById(it) }
                        ?: return StepResult(false, failureReason = "Element '${step.element}' not found")
                    val clickPt = elementMap.safeClickPoint(element)
                    val x = clickPt.x
                    val y = clickPt.y
                    withContext(Dispatchers.Main) { cursorOverlay.moveTo(x, y, showClick = true) }
                    delay(200)
                    val ok = if (step.action == SemanticAction.LONG_PRESS) {
                        automationService.performLongPress(x, y, step.durationMs ?: 1000)
                    } else {
                        automationService.performTap(x, y)
                    }
                    StepResult(ok, failureReason = if (!ok) "Gesture failed at ($x, $y)" else null)
                }

                SemanticAction.TYPE -> {
                    val text = step.text ?: return StepResult(false, failureReason = "TYPE missing text")
                    if (step.element != null) {
                        val element = elementMap.findById(step.element)
                        if (element != null) {
                            val clickPt = elementMap.safeClickPoint(element)
                            val x = clickPt.x
                            val y = clickPt.y
                            withContext(Dispatchers.Main) { cursorOverlay.moveTo(x, y, showClick = true) }
                            delay(200)
                            automationService.performTap(x, y)
                            delay(300)
                        }
                    }
                    val ok = automationService.performType(text)
                    StepResult(ok, failureReason = if (!ok) "Type failed" else null)
                }

                SemanticAction.SWIPE -> {
                    val direction = step.direction ?: "down"
                    val w = elementMap.screenWidth
                    val h = elementMap.screenHeight
                    // Offset swipe coordinates to window position
                    val offsetX = currentWindowInfo.bounds.left
                    val offsetY = currentWindowInfo.bounds.top
                    val cx = offsetX + w / 2
                    val cy = offsetY + h / 2
                    val dist = h / 3
                    val (sx, sy, ex, ey) = when (direction) {
                        "up" -> listOf(cx, cy + dist / 2, cx, cy - dist / 2)
                        "down" -> listOf(cx, cy - dist / 2, cx, cy + dist / 2)
                        "left" -> listOf(offsetX + w * 3 / 4, cy, offsetX + w / 4, cy)
                        "right" -> listOf(offsetX + w / 4, cy, offsetX + w * 3 / 4, cy)
                        else -> listOf(cx, cy + dist / 2, cx, cy - dist / 2)
                    }
                    val ok = automationService.performSwipe(sx, sy, ex, ey, 500)
                    StepResult(ok, failureReason = if (!ok) "Swipe $direction failed" else null)
                }

                SemanticAction.BACK -> StepResult(automationService.performBack())
                SemanticAction.HOME -> StepResult(automationService.performHome())
                SemanticAction.WAIT -> { delay(1000); StepResult(true) }
                SemanticAction.DISMISS_KEYBOARD -> StepResult(automationService.dismissKeyboard())
                SemanticAction.PRESS_ENTER -> {
                    val ok = automationService.pressKeyboardEnter()
                    StepResult(ok, failureReason = if (!ok) "Failed to press keyboard enter key" else null)
                }

                SemanticAction.OPEN_APP -> {
                    val pkg = step.packageName ?: return StepResult(false, failureReason = "Missing package")
                    val ok = automationService.performOpenApp(pkg)
                    if (ok) delay(1500)
                    StepResult(ok, failureReason = if (!ok) "Failed to open $pkg" else null)
                }

                else -> StepResult(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "[$slot] Step execution error", e)
            StepResult(false, failureReason = "${e.javaClass.simpleName}: ${e.message}")
        }
    }

    private fun isActive(): Boolean {
        return try {
            val refreshed = coordinator.getWindowForSlot(slot)
            if (refreshed != null) {
                currentWindowInfo = refreshed
                true
            } else {
                currentWindowInfo.windowInfo.root != null
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun refreshWindowInfo() {
        try {
            coordinator.getWindowForSlot(slot)?.let { currentWindowInfo = it }
        } catch (_: Exception) {
            // Fall back to the last known window info for this iteration.
        }
    }

    private data class StepResult(
        val success: Boolean,
        val failureReason: String? = null
    )

    companion object {
        private const val TAG = "WindowAgentLoop"
    }
}
