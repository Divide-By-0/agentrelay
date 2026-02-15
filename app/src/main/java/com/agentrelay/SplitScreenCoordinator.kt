package com.agentrelay

import android.content.Context
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.*
import java.util.concurrent.ConcurrentHashMap

data class SplitScreenConfig(
    val topApp: String,
    val bottomApp: String,
    val topTask: String,
    val bottomTask: String,
    val originalTask: String
)

enum class SplitScreenSlot { TOP, BOTTOM }

data class WindowInfo(
    val windowId: Int,
    val packageName: String,
    val bounds: Rect,
    val windowInfo: AccessibilityWindowInfo
)

class SplitScreenCoordinator(private val context: Context) {

    private val sharedFindings = ConcurrentHashMap<String, String>()
    private val completionState = ConcurrentHashMap<SplitScreenSlot, Boolean>()
    private val loopErrors = ConcurrentHashMap<SplitScreenSlot, String>()

    suspend fun enterSplitScreen(config: SplitScreenConfig): Boolean {
        val automationService = AutomationService.instance ?: return false

        // 1. Open the top app
        Log.d(TAG, "Opening top app: ${config.topApp}")
        if (!automationService.performOpenApp(config.topApp)) {
            Log.e(TAG, "Failed to open top app: ${config.topApp}")
            return false
        }
        delay(1000)

        // 2. Enter split-screen mode
        Log.d(TAG, "Entering split-screen mode")
        if (!automationService.enterSplitScreen()) {
            Log.e(TAG, "Failed to enter split-screen mode")
            return false
        }
        delay(1000)

        // 3. Launch bottom app adjacent
        Log.d(TAG, "Launching bottom app adjacent: ${config.bottomApp}")
        if (!automationService.launchAppAdjacent(config.bottomApp)) {
            Log.e(TAG, "Failed to launch adjacent app: ${config.bottomApp}")
            exitSplitScreen()
            return false
        }
        delay(1500)

        // 4. Verify we have 2 app windows
        val windows = identifyWindows()
        if (windows == null) {
            // Retry once
            Log.w(TAG, "Split-screen verification failed, retrying...")
            delay(1000)
            val retryWindows = identifyWindows()
            if (retryWindows == null) {
                Log.e(TAG, "Split-screen verification failed after retry")
                exitSplitScreen()
                return false
            }
        }

        Log.d(TAG, "Split-screen entered successfully")
        return true
    }

    suspend fun runParallelLoops(
        config: SplitScreenConfig,
        apiKey: String,
        model: String,
        captureService: ScreenCaptureService?
    ) {
        val windows = identifyWindows()
        if (windows == null) {
            Log.e(TAG, "Cannot run parallel loops: windows not identified")
            return
        }

        val (topWindow, bottomWindow) = windows
        Log.d(TAG, "Running parallel loops: top=${topWindow.packageName} bottom=${bottomWindow.packageName}")

        try {
            withTimeout(180_000) {
                supervisorScope {
                    val topJob = async(Dispatchers.Default) {
                        try {
                            val loop = WindowAgentLoop(
                                context = context,
                                slot = SplitScreenSlot.TOP,
                                windowInfo = topWindow,
                                coordinator = this@SplitScreenCoordinator,
                                apiKey = apiKey,
                                model = model,
                                captureService = captureService
                            )
                            loop.run(config.topTask)
                        } catch (e: Exception) {
                            Log.e(TAG, "Top loop failed", e)
                            loopErrors[SplitScreenSlot.TOP] = e.message ?: "Unknown error"
                        }
                    }

                    val bottomJob = async(Dispatchers.Default) {
                        try {
                            val loop = WindowAgentLoop(
                                context = context,
                                slot = SplitScreenSlot.BOTTOM,
                                windowInfo = bottomWindow,
                                coordinator = this@SplitScreenCoordinator,
                                apiKey = apiKey,
                                model = model,
                                captureService = captureService
                            )
                            loop.run(config.bottomTask)
                        } catch (e: Exception) {
                            Log.e(TAG, "Bottom loop failed", e)
                            loopErrors[SplitScreenSlot.BOTTOM] = e.message ?: "Unknown error"
                        }
                    }

                    topJob.await()
                    bottomJob.await()
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.w(TAG, "Parallel loops timed out after 3 minutes")
        }
    }

    suspend fun exitSplitScreen() {
        val automationService = AutomationService.instance ?: return
        Log.d(TAG, "Exiting split-screen")
        automationService.performHome()
        delay(500)
    }

    fun identifyWindows(): Pair<WindowInfo, WindowInfo>? {
        val automationService = AutomationService.instance ?: return null
        val appWindows = automationService.getAppWindows()

        if (appWindows.size < 2) {
            Log.w(TAG, "Expected 2+ app windows, found ${appWindows.size}")
            return null
        }

        // Sort by vertical position (top first)
        val sorted = appWindows.sortedBy { it.second.top }
        val topPair = sorted[0]
        val bottomPair = sorted[1]

        val topInfo = WindowInfo(
            windowId = topPair.first.id,
            packageName = topPair.first.root?.packageName?.toString() ?: "unknown",
            bounds = topPair.second,
            windowInfo = topPair.first
        )
        val bottomInfo = WindowInfo(
            windowId = bottomPair.first.id,
            packageName = bottomPair.first.root?.packageName?.toString() ?: "unknown",
            bounds = bottomPair.second,
            windowInfo = bottomPair.first
        )

        Log.d(TAG, "Identified windows: top=${topInfo.packageName} (${topInfo.bounds}), bottom=${bottomInfo.packageName} (${bottomInfo.bounds})")
        return topInfo to bottomInfo
    }

    fun postFinding(slot: SplitScreenSlot, key: String, value: String) {
        sharedFindings[key] = value
        Log.d(TAG, "Finding posted by $slot: $key = $value")
    }

    fun getFindings(): Map<String, String> = sharedFindings.toMap()

    fun markComplete(slot: SplitScreenSlot) {
        completionState[slot] = true
        Log.d(TAG, "$slot marked complete")
    }

    fun isComplete(): Boolean =
        completionState[SplitScreenSlot.TOP] == true && completionState[SplitScreenSlot.BOTTOM] == true

    fun getErrors(): Map<SplitScreenSlot, String> = loopErrors.toMap()

    companion object {
        private const val TAG = "SplitScreenCoordinator"
    }
}
