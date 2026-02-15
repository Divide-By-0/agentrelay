package com.agentrelay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class AutomationService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to handle events for this use case
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "AutomationService connected")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    suspend fun performTap(x: Int, y: Int): Boolean {
        return suspendCancellableCoroutine { continuation ->
            Log.d(TAG, "Performing tap at ($x, $y)")

            // Get display info for debugging
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val bounds = windowManager.currentWindowMetrics.bounds
                Log.d(TAG, "Display: ${bounds.width()}x${bounds.height()}")
            } else {
                val metrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(metrics)
                Log.d(TAG, "Display: ${metrics.widthPixels}x${metrics.heightPixels}, density: ${metrics.densityDpi}")
            }

            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gestureBuilder = GestureDescription.Builder()
            val gesture = gestureBuilder
                .addStroke(GestureDescription.StrokeDescription(path, 0, 50))
                .build()

            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    Log.d(TAG, "Tap completed at ($x, $y)")
                    if (continuation.isActive) {
                        continuation.resume(true)
                    }
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    Log.e(TAG, "Tap cancelled at ($x, $y)")
                    if (continuation.isActive) {
                        continuation.resume(false)
                    }
                }
            }

            val dispatched = dispatchGesture(gesture, callback, null)
            if (!dispatched) {
                Log.e(TAG, "Failed to dispatch tap gesture")
                if (continuation.isActive) {
                    continuation.resume(false)
                }
            }
        }
    }

    suspend fun performSwipe(startX: Int, startY: Int, endX: Int, endY: Int, duration: Long = 500): Boolean {
        return suspendCancellableCoroutine { continuation ->
            val path = Path().apply {
                moveTo(startX.toFloat(), startY.toFloat())
                lineTo(endX.toFloat(), endY.toFloat())
            }

            val gestureBuilder = GestureDescription.Builder()
            val gesture = gestureBuilder
                .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
                .build()

            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    super.onCompleted(gestureDescription)
                    Log.d(TAG, "Swipe completed from ($startX, $startY) to ($endX, $endY)")
                    if (continuation.isActive) {
                        continuation.resume(true)
                    }
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    super.onCancelled(gestureDescription)
                    Log.e(TAG, "Swipe cancelled")
                    if (continuation.isActive) {
                        continuation.resume(false)
                    }
                }
            }

            val dispatched = dispatchGesture(gesture, callback, null)
            if (!dispatched) {
                Log.e(TAG, "Failed to dispatch swipe gesture")
                if (continuation.isActive) {
                    continuation.resume(false)
                }
            }
        }
    }

    suspend fun performType(text: String): Boolean {
        // Typing is done by copying to clipboard
        // The user can then manually paste or the agent can tap the paste button
        return try {
            val clipboardManager = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("agent_text", text)
            clipboardManager.setPrimaryClip(clip)
            delay(100)
            Log.d(TAG, "Text copied to clipboard: $text")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy text to clipboard", e)
            false
        }
    }

    suspend fun performBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    suspend fun performHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun getRootNode(): AccessibilityNodeInfo? {
        return try {
            rootInActiveWindow
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get root node", e)
            null
        }
    }

    /**
     * Gathers device context: current app, keyboard visibility, active windows, time, installed apps.
     */
    fun getDeviceContext(): DeviceContext {
        val root = rootInActiveWindow
        val currentPackage = root?.packageName?.toString() ?: "unknown"
        val currentApp = try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(currentPackage, 0)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: Exception) { currentPackage }

        // Keyboard visibility: check if an IME window is present
        val keyboardVisible = try {
            windows?.any { w ->
                w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD
            } ?: false
        } catch (_: Exception) { false }

        // Active windows list
        val windowList = try {
            windows?.mapNotNull { w ->
                val title = w.title?.toString()
                val typeName = when (w.type) {
                    AccessibilityWindowInfo.TYPE_APPLICATION -> "app"
                    AccessibilityWindowInfo.TYPE_INPUT_METHOD -> "keyboard"
                    AccessibilityWindowInfo.TYPE_SYSTEM -> "system"
                    AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY -> "overlay"
                    else -> "other"
                }
                if (title != null) "$typeName:$title" else null
            } ?: emptyList()
        } catch (_: Exception) { emptyList() }

        // Current time
        val timeFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", java.util.Locale.getDefault())
        val currentTime = timeFormat.format(java.util.Date())

        // Installed apps (launchable only)
        val installedApps = try {
            val pm = packageManager
            val intent = android.content.Intent(android.content.Intent.ACTION_MAIN, null)
            intent.addCategory(android.content.Intent.CATEGORY_LAUNCHER)
            val resolveInfos = pm.queryIntentActivities(intent, 0)
            resolveInfos.map { ri ->
                AppInfo(
                    name = ri.loadLabel(pm).toString(),
                    packageName = ri.activityInfo.packageName
                )
            }.distinctBy { it.packageName }.sortedBy { it.name.lowercase() }
        } catch (_: Exception) { emptyList() }

        root?.recycle()

        return DeviceContext(
            currentAppPackage = currentPackage,
            currentAppName = currentApp,
            keyboardVisible = keyboardVisible,
            windowList = windowList,
            currentTime = currentTime,
            installedApps = installedApps
        )
    }

    companion object {
        private const val TAG = "AutomationService"

        @Volatile
        var instance: AutomationService? = null
            private set

        fun isServiceEnabled(): Boolean = instance != null
    }
}

data class DeviceContext(
    val currentAppPackage: String,
    val currentAppName: String,
    val keyboardVisible: Boolean,
    val windowList: List<String>,
    val currentTime: String,
    val installedApps: List<AppInfo>
) {
    fun toPromptText(): String = buildString {
        appendLine("DEVICE CONTEXT:")
        appendLine("  Current app: $currentAppName ($currentAppPackage)")
        appendLine("  Current time: $currentTime")
        appendLine("  Keyboard visible: $keyboardVisible")
        if (windowList.isNotEmpty()) {
            appendLine("  Active windows: ${windowList.joinToString(", ")}")
        }
        appendLine("  Installed apps (${installedApps.size}): ${installedApps.joinToString(", ") { it.name }}")
    }
}

data class AppInfo(
    val name: String,
    val packageName: String
)
