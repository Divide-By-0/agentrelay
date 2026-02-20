package com.agentrelay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ComponentName
import android.content.Context
import android.graphics.Path
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import com.agentrelay.intervention.InterventionTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class AutomationService : AccessibilityService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    /** Set to true while dispatching agent gestures so we can filter them from intervention tracking */
    val isAgentGesture = AtomicBoolean(false)

    /** When true, auto-approves MediaProjection "Start now" dialog via accessibility clicks */
    var autoApproveScreenCapture = AtomicBoolean(true)

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // Forward to InterventionTracker if tracking is enabled and this isn't our own gesture
        if (!isAgentGesture.get()) {
            try {
                InterventionTracker.getInstance(this).onAccessibilityEvent(event)
            } catch (_: Exception) {}
        }
        // Auto-approve MediaProjection permission dialog
        if (autoApproveScreenCapture.get() &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.packageName?.toString() == "com.android.systemui") {
            tryAutoApproveMediaProjection()
        }
    }

    /**
     * Attempts to find and click the "Start now" button in the MediaProjection
     * permission dialog. This is more reliable than external adb shell taps.
     */
    private fun tryAutoApproveMediaProjection() {
        try {
            val root = rootInActiveWindow ?: return
            // Look for "Start now" button text
            val startNowNodes = root.findAccessibilityNodeInfosByText("Start now")
            if (!startNowNodes.isNullOrEmpty()) {
                for (node in startNowNodes) {
                    if (node.isClickable) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Log.d(TAG, "Auto-approved MediaProjection dialog (clicked 'Start now')")
                        node.recycle()
                        return
                    }
                    // Try parent if the text node itself isn't clickable
                    var parent = node.parent
                    var depth = 0
                    while (parent != null && depth < 3) {
                        if (parent.isClickable) {
                            parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            Log.d(TAG, "Auto-approved MediaProjection dialog (clicked parent of 'Start now')")
                            parent.recycle()
                            node.recycle()
                            return
                        }
                        val nextParent = parent.parent
                        parent.recycle()
                        parent = nextParent
                        depth++
                    }
                    parent?.recycle()
                    node.recycle()
                }
            }
            // Also try "Allow" for older Android versions
            val allowNodes = root.findAccessibilityNodeInfosByText("Allow")
            if (!allowNodes.isNullOrEmpty()) {
                for (node in allowNodes) {
                    if (node.isClickable) {
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        Log.d(TAG, "Auto-approved MediaProjection dialog (clicked 'Allow')")
                        node.recycle()
                        return
                    }
                    node.recycle()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to auto-approve MediaProjection dialog", e)
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Service interrupted")
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "AutomationService connected")
        // Pre-cache installed apps list in background
        DeviceContextCache.refreshAsync(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    /**
     * Dispatches a gesture with the agent gesture flag set, so that
     * InterventionTracker can distinguish agent gestures from user input.
     */
    private fun dispatchAgentGesture(
        gesture: GestureDescription,
        callback: GestureResultCallback
    ): Boolean {
        isAgentGesture.set(true)
        val dispatched = dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                isAgentGesture.set(false)
                callback.onCompleted(gestureDescription)
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                isAgentGesture.set(false)
                callback.onCancelled(gestureDescription)
            }
        }, null)
        if (!dispatched) {
            isAgentGesture.set(false)
        }
        return dispatched
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

            val dispatched = dispatchAgentGesture(gesture, callback)
            if (!dispatched) {
                Log.e(TAG, "Failed to dispatch tap gesture")
                if (continuation.isActive) {
                    continuation.resume(false)
                }
            }
        }
    }

    /**
     * Performs a long press at the given coordinates for the specified duration.
     */
    suspend fun performLongPress(x: Int, y: Int, durationMs: Long = 1000): Boolean {
        return suspendCancellableCoroutine { continuation ->
            Log.d(TAG, "Performing long press at ($x, $y) for ${durationMs}ms")
            val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
            val gestureBuilder = GestureDescription.Builder()
            val gesture = gestureBuilder
                .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
                .build()

            val callback = object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    Log.d(TAG, "Long press completed at ($x, $y)")
                    if (continuation.isActive) continuation.resume(true)
                }
                override fun onCancelled(gestureDescription: GestureDescription?) {
                    Log.e(TAG, "Long press cancelled at ($x, $y)")
                    if (continuation.isActive) continuation.resume(false)
                }
            }

            val dispatched = dispatchAgentGesture(gesture, callback)
            if (!dispatched) {
                Log.e(TAG, "Failed to dispatch long press gesture")
                if (continuation.isActive) continuation.resume(false)
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

            val dispatched = dispatchAgentGesture(gesture, callback)
            if (!dispatched) {
                Log.e(TAG, "Failed to dispatch swipe gesture")
                if (continuation.isActive) {
                    continuation.resume(false)
                }
            }
        }
    }

    suspend fun performType(text: String): Boolean {
        return try {
            val success = when {
                // Strategy 1: ACTION_SET_TEXT (most reliable, works on most standard fields)
                trySetText(text) -> true

                // Strategy 2: Clipboard paste
                tryClipboardPaste(text) && run {
                    delay(200)
                    verifyTextEntered(text).also { if (!it) Log.w(TAG, "Paste reported success but text not verified, trying fallback") }
                } -> true

                // Strategy 3: Character-by-character key dispatch via InputConnection
                tryKeyboardInput(text).also { if (!it) Log.d(TAG, "Falling back to character-by-character input") } -> true

                // Strategy 4: Last resort - set text on any editable node on screen
                trySetTextOnAnyEditable(text) -> true

                else -> {
                    Log.e(TAG, "All text input strategies failed for: ${text.take(50)}")
                    false
                }
            }

            // Auto-dismiss keyboard after typing so it doesn't obstruct the next action
            if (success) {
                dismissKeyboard()
            }

            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to type text", e)
            false
        }
    }

    private fun trySetText(text: String): Boolean {
        val node = findFocusedInputNode() ?: return false
        return try {
            val args = Bundle()
            args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
            val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            if (success) Log.d(TAG, "Text set via ACTION_SET_TEXT: ${text.take(50)}")
            else Log.w(TAG, "ACTION_SET_TEXT returned false")
            success
        } finally {
            node.recycle()
        }
    }

    private suspend fun tryClipboardPaste(text: String): Boolean {
        return try {
            val clipboardManager = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("agent_text", text)
            clipboardManager.setPrimaryClip(clip)
            delay(100)
            val pasteNode = findFocusedInputNode() ?: return false
            try {
                val pasted = pasteNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                if (pasted) Log.d(TAG, "Text pasted via ACTION_PASTE: ${text.take(50)}")
                pasted
            } finally {
                pasteNode.recycle()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Clipboard paste failed", e)
            false
        }
    }

    private suspend fun tryKeyboardInput(text: String): Boolean {
        // Clear any existing text first, then type character by character via SET_TEXT
        // This works by building up the text incrementally, which triggers
        // the input connection on apps that reject bulk SET_TEXT
        val node = findFocusedInputNode() ?: return false
        return try {
            // First clear the field
            val clearArgs = Bundle()
            clearArgs.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "")
            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
            delay(50)

            // Type character by character
            val sb = StringBuilder()
            for (char in text) {
                sb.append(char)
                val args = Bundle()
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, sb.toString())
                val ok = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                if (!ok) {
                    Log.w(TAG, "Character-by-character input failed at position ${sb.length}")
                    return false
                }
                delay(15) // Small delay between characters
            }
            Log.d(TAG, "Text entered char-by-char: ${text.take(50)}")
            true
        } finally {
            node.recycle()
        }
    }

    private fun trySetTextOnAnyEditable(text: String): Boolean {
        // Walk the tree to find any editable/focusable text field
        val root = rootInActiveWindow ?: return false
        val editableNode = findEditableNode(root)
        if (editableNode != null) {
            try {
                // Focus it first
                editableNode.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val args = Bundle()
                args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                val success = editableNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                if (success) {
                    Log.d(TAG, "Text set on editable node found by tree walk: ${text.take(50)}")
                    return true
                }
            } finally {
                editableNode.recycle()
            }
        }
        return false
    }

    private fun findEditableNode(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable) return AccessibilityNodeInfo.obtain(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditableNode(child)
            child.recycle()
            if (result != null) return result
        }
        return null
    }

    /**
     * Verify that text was actually entered into the focused field.
     */
    fun verifyTextEntered(expectedText: String): Boolean {
        val node = findFocusedInputNode() ?: return false
        return try {
            val actualText = node.text?.toString() ?: ""
            val contains = actualText.contains(expectedText, ignoreCase = true)
            if (!contains) {
                Log.w(TAG, "Text verification failed: expected '${expectedText.take(30)}' but field contains '${actualText.take(30)}'")
            }
            contains
        } finally {
            node.recycle()
        }
    }

    /**
     * Read the text content of the currently focused input field.
     */
    fun readFocusedFieldText(): String? {
        val node = findFocusedInputNode() ?: return null
        return try {
            node.text?.toString()
        } finally {
            node.recycle()
        }
    }

    private fun findFocusedInputNode(): AccessibilityNodeInfo? {
        return try {
            rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to find focused node", e)
            null
        }
    }

    fun performOpenApp(packageName: String): Boolean {
        return try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(launchIntent)
                Log.d(TAG, "Launched app: $packageName")
                true
            } else {
                Log.e(TAG, "No launch intent for package: $packageName")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch app: $packageName", e)
            false
        }
    }

    fun getCurrentAppPackage(): String? {
        return try {
            rootInActiveWindow?.packageName?.toString()
        } catch (e: Exception) {
            null
        }
    }

    suspend fun performBack(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    suspend fun performHome(): Boolean {
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    fun isKeyboardShowing(): Boolean {
        return try {
            windows?.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD } ?: false
        } catch (_: Exception) { false }
    }

    suspend fun dismissKeyboard(): Boolean {
        if (!isKeyboardShowing()) return true // Already dismissed
        Log.d(TAG, "Dismissing keyboard via back action")
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }

    /**
     * Presses keyboard enter/go/next key.
     * Tries focused node IME action first, then taps bottom-right keyboard area.
     */
    suspend fun pressKeyboardEnter(): Boolean {
        // First try focused-node IME enter action (best signal-preserving path).
        val focused = findFocusedInputNode()
        if (focused != null) {
            try {
                val imeActionOk = try {
                    focused.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                } catch (_: Exception) {
                    false
                }
                if (imeActionOk) {
                    Log.d(TAG, "Pressed keyboard enter via ACTION_IME_ENTER")
                    return true
                }
            } finally {
                focused.recycle()
            }
        }

        // Fallback: tap the keyboard's bottom-right action-key area.
        if (!isKeyboardShowing()) {
            Log.w(TAG, "pressKeyboardEnter requested but keyboard is not visible")
            return false
        }
        return try {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val (w, h) = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                val b = windowManager.currentWindowMetrics.bounds
                b.width() to b.height()
            } else {
                val metrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(metrics)
                metrics.widthPixels to metrics.heightPixels
            }
            val x = (w * 0.92f).toInt()
            val y = (h * 0.94f).toInt()
            Log.d(TAG, "Pressing keyboard enter fallback tap at ($x, $y)")
            performTap(x, y)
        } catch (e: Exception) {
            Log.w(TAG, "pressKeyboardEnter fallback tap failed", e)
            false
        }
    }

    fun getRootNode(): AccessibilityNodeInfo? {
        return try {
            rootInActiveWindow
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get root node via rootInActiveWindow", e)
            null
        } ?: try {
            // Fallback: rootInActiveWindow can be null when our overlay or system UI
            // has focus. Enumerate all windows and find the top app window's root instead.
            windows?.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                ?.firstNotNullOfOrNull { w ->
                    try { w.root } catch (_: Exception) { null }
                }?.also {
                    Log.d(TAG, "getRootNode: fell back to window enumeration (pkg=${it.packageName})")
                }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get root node via window enumeration", e)
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

        // Active windows list — include package for app windows so LLM knows which app it's in
        val windowList = try {
            windows?.mapNotNull { w ->
                val title = w.title?.toString()
                val typeName = when (w.type) {
                    AccessibilityWindowInfo.TYPE_APPLICATION -> {
                        // Include package name for app windows to disambiguate
                        // (e.g., Settings "Internet" page vs the "Internet" browser)
                        val root = w.root
                        val pkg = root?.packageName?.toString()
                        root?.recycle()
                        if (pkg != null && title != null && !title.equals(pkg, ignoreCase = true))
                            "app($pkg)"
                        else
                            "app"
                    }
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

        // Device locale and country
        val locale = java.util.Locale.getDefault()
        val country = locale.displayCountry
        val language = locale.displayLanguage
        val timeZone = java.util.TimeZone.getDefault().id

        // Installed apps — use pre-cached list (refreshed on service connect / app open)
        val installedApps = DeviceContextCache.installedApps.ifEmpty {
            // Fallback: cache not ready yet, trigger async refresh and use empty for now
            DeviceContextCache.refreshAsync(this)
            emptyList()
        }

        root?.recycle()

        return DeviceContext(
            currentAppPackage = currentPackage,
            currentAppName = currentApp,
            keyboardVisible = keyboardVisible,
            windowList = windowList,
            currentTime = currentTime,
            country = country,
            language = language,
            timeZone = timeZone,
            installedApps = installedApps
        )
    }

    /**
     * Returns application windows with their screen bounds, sorted by vertical position.
     */
    fun getAppWindows(): List<Pair<AccessibilityWindowInfo, android.graphics.Rect>> {
        return try {
            windows?.filter { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                ?.map { window ->
                    val bounds = android.graphics.Rect()
                    window.getBoundsInScreen(bounds)
                    window to bounds
                }
                ?.sortedBy { it.second.top }
                ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get app windows", e)
            emptyList()
        }
    }

    /**
     * Triggers split-screen mode via global action.
     */
    fun enterSplitScreen(): Boolean {
        return try {
            performGlobalAction(GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to enter split screen", e)
            false
        }
    }

    /**
     * Launches an app in the adjacent split-screen slot.
     */
    fun launchAppAdjacent(packageName: String): Boolean {
        return try {
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(
                    android.content.Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT or
                    android.content.Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                )
                startActivity(launchIntent)
                Log.d(TAG, "Launched app adjacent: $packageName")
                true
            } else {
                Log.e(TAG, "No launch intent for adjacent package: $packageName")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch app adjacent: $packageName", e)
            false
        }
    }

    companion object {
        private const val TAG = "AutomationService"

        @Volatile
        var instance: AutomationService? = null
            private set

        fun isServiceEnabled(): Boolean = instance != null

        fun isServiceEnabledInSettings(context: Context): Boolean {
            return try {
                val enabled = Settings.Secure.getInt(
                    context.contentResolver,
                    Settings.Secure.ACCESSIBILITY_ENABLED,
                    0
                ) == 1
                if (!enabled) return false

                val expected = ComponentName(context, AutomationService::class.java).flattenToString()
                val enabledServices = Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                ) ?: return false
                enabledServices.split(':').any { it.equals(expected, ignoreCase = true) }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to check accessibility settings state", e)
                false
            }
        }
    }
}

data class DeviceContext(
    val currentAppPackage: String,
    val currentAppName: String,
    val keyboardVisible: Boolean,
    val windowList: List<String>,
    val currentTime: String,
    val country: String = "",
    val language: String = "",
    val timeZone: String = "",
    val installedApps: List<AppInfo>
) {
    fun toPromptText(): String = buildString {
        appendLine("DEVICE CONTEXT:")
        appendLine("  Current app: $currentAppName ($currentAppPackage)")
        appendLine("  Current time: $currentTime")
        if (country.isNotEmpty()) {
            appendLine("  Location: $country (language: $language, timezone: $timeZone)")
            appendLine("  IMPORTANT: The user is in $country. Always prefer apps and services appropriate for this country (e.g. Google Maps, not Baidu Maps, in the US).")
        }
        appendLine("  Keyboard visible: $keyboardVisible")
        if (windowList.isNotEmpty()) {
            appendLine("  Active windows: ${windowList.joinToString(", ")}")
        }
        appendLine("  Installed apps (${installedApps.size}):")
        installedApps.forEach { app ->
            val desc = if (app.description != null) " — ${app.description}" else ""
            appendLine("    - ${app.name} [${app.packageName}]$desc")
        }
    }
}

data class AppInfo(
    val name: String,
    val packageName: String,
    val description: String? = null
)
