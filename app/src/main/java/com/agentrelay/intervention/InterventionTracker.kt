package com.agentrelay.intervention

import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import com.agentrelay.SecureStorage
import com.agentrelay.models.SemanticAction
import com.agentrelay.models.SemanticStep
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.min

class InterventionTracker private constructor(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val db by lazy { InterventionDatabase.getInstance(context) }
    private val gson = Gson()

    // Current planned action state
    private var currentPlannedStep: SemanticStep? = null
    private var currentElementMapSnapshot: String? = null
    private var plannedActionTimestamp: Long = 0
    private var taskDescription: String = ""
    private var currentApp: String = ""
    private var currentAppPackage: String = ""

    /** Set by AutomationService before/after dispatchGesture to filter agent's own gestures */
    val isAgentGesture = AtomicBoolean(false)

    fun setTaskContext(task: String) {
        taskDescription = task
    }

    fun setCurrentApp(appName: String, appPackage: String) {
        currentApp = appName
        currentAppPackage = appPackage
    }

    fun setPlannedAction(step: SemanticStep, elementMapSnapshot: String?) {
        currentPlannedStep = step
        currentElementMapSnapshot = elementMapSnapshot
        plannedActionTimestamp = System.currentTimeMillis()
    }

    fun clearPlannedAction() {
        currentPlannedStep = null
        currentElementMapSnapshot = null
    }

    /**
     * Called from AutomationService.onAccessibilityEvent() when tracking is enabled
     * and the event is NOT from the agent's own gestures.
     */
    fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (!SecureStorage.getInstance(context).getInterventionTrackingEnabled()) return
        if (isAgentGesture.get()) return

        val eventType = event.eventType
        // Only track meaningful user interactions
        if (eventType != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            eventType != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED &&
            eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            return
        }

        val planned = currentPlannedStep
        val timeSincePlanned = System.currentTimeMillis() - plannedActionTimestamp

        // Only compare if a planned action exists and is recent (within 5 seconds)
        if (planned == null || timeSincePlanned > 5000) return

        // Extract approximate coordinates from the event source node
        var actualX: Int? = null
        var actualY: Int? = null
        try {
            val source = event.source
            if (source != null) {
                val bounds = android.graphics.Rect()
                source.getBoundsInScreen(bounds)
                actualX = bounds.centerX()
                actualY = bounds.centerY()
                source.recycle()
            }
        } catch (_: Exception) {}

        val actualText = event.text?.joinToString(" ") ?: event.contentDescription?.toString()

        // Compute match
        val (matchType, confidence) = computeMatch(
            planned, eventType, actualX, actualY, actualText
        )

        val intervention = UserIntervention(
            timestamp = System.currentTimeMillis(),
            taskDescription = taskDescription,
            currentApp = currentApp,
            currentAppPackage = currentAppPackage,
            plannedAction = planned.action.name,
            plannedElementId = planned.element,
            plannedElementText = planned.text,
            plannedX = null, // We don't know the resolved coords at this point
            plannedY = null,
            plannedText = planned.text,
            plannedDescription = planned.description,
            actualEventType = eventType,
            actualClassName = event.className?.toString(),
            actualPackage = event.packageName?.toString(),
            actualText = actualText,
            actualX = actualX,
            actualY = actualY,
            matchType = matchType,
            matchConfidence = confidence,
            elementMapSnapshot = currentElementMapSnapshot
        )

        scope.launch {
            try {
                db.interventionDao().insert(intervention)
                Log.d(TAG, "Recorded $matchType (confidence=$confidence): " +
                    "planned=${planned.action.name}, actual=eventType=$eventType")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save intervention", e)
            }
        }
    }

    private fun computeMatch(
        planned: SemanticStep,
        eventType: Int,
        actualX: Int?,
        actualY: Int?,
        actualText: String?
    ): Pair<String, Float> {
        return when {
            // CLICK planned vs click event
            planned.action == SemanticAction.CLICK &&
                eventType == AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                if (actualX != null && actualY != null) {
                    // We can't easily compare coords without resolving the element,
                    // so we check if the same element ID matches
                    // For now, use a moderate confidence for click-during-click
                    "CONFIRMED" to 0.6f
                } else {
                    "CONFIRMED" to 0.4f
                }
            }

            // TYPE planned vs text changed event
            planned.action == SemanticAction.TYPE &&
                eventType == AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                val plannedText = planned.text ?: ""
                val actual = actualText ?: ""
                if (plannedText.isNotEmpty() && actual.isNotEmpty()) {
                    val similarity = computeTextSimilarity(plannedText, actual)
                    if (similarity > 0.7f) "CONFIRMED" to similarity
                    else "INTERVENTION" to (1f - similarity)
                } else {
                    "INTERVENTION" to 0.5f
                }
            }

            // SWIPE planned vs scroll event
            planned.action == SemanticAction.SWIPE &&
                eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                "CONFIRMED" to 0.5f
            }

            // Mismatch: different action types
            else -> "INTERVENTION" to 0.8f
        }
    }

    /**
     * Simple text similarity based on longest common subsequence ratio.
     */
    private fun computeTextSimilarity(a: String, b: String): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val maxLen = maxOf(a.length, b.length)
        val distance = levenshteinDistance(a.lowercase(), b.lowercase())
        return 1f - (distance.toFloat() / maxLen)
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (s1[i - 1] == s2[j - 1]) {
                    dp[i - 1][j - 1]
                } else {
                    min(min(dp[i - 1][j], dp[i][j - 1]), dp[i - 1][j - 1]) + 1
                }
            }
        }
        return dp[m][n]
    }

    // ── Clarification Logging ──

    fun logClarification(
        task: String,
        iteration: Int,
        defaultPath: String,
        alternativePath: String,
        userChose: String, // "default", "alternative", "timeout"
        confidence: String,
        elementMapSnapshot: String? = null
    ) {
        scope.launch {
            try {
                db.userClarificationDao().insert(
                    UserClarification(
                        taskDescription = task,
                        iteration = iteration,
                        defaultPath = defaultPath,
                        alternativePath = alternativePath,
                        userChose = userChose,
                        confidence = confidence,
                        elementMapSnapshot = elementMapSnapshot
                    )
                )
                Log.d(TAG, "Recorded clarification: userChose=$userChose, confidence=$confidence")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save clarification", e)
            }
        }
    }

    // ── Agent Trace Logging ──

    fun logTrace(
        eventType: String,
        description: String,
        action: String? = null,
        elementId: String? = null,
        reasoning: String? = null,
        confidence: String? = null,
        iteration: Int = 0,
        stepIndex: Int = 0,
        success: Boolean = true,
        failureReason: String? = null,
        planSteps: String? = null
    ) {
        scope.launch {
            try {
                db.agentTraceDao().insert(
                    AgentTraceEvent(
                        taskDescription = taskDescription,
                        eventType = eventType,
                        action = action,
                        elementId = elementId,
                        description = description,
                        reasoning = reasoning,
                        confidence = confidence,
                        currentApp = currentApp,
                        currentAppPackage = currentAppPackage,
                        iteration = iteration,
                        stepIndex = stepIndex,
                        success = success,
                        failureReason = failureReason,
                        planSteps = planSteps
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save trace event", e)
            }
        }
    }

    companion object {
        private const val TAG = "InterventionTracker"

        @Volatile
        private var INSTANCE: InterventionTracker? = null

        fun getInstance(context: Context): InterventionTracker {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: InterventionTracker(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
