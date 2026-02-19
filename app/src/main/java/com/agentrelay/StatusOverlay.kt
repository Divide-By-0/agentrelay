package com.agentrelay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.cardview.widget.CardView
import java.text.SimpleDateFormat
import java.util.*

class StatusOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private var statusText: TextView? = null
    private var scrollView: ScrollView? = null
    private var resizeHandle: View? = null
    private var stopButton: View? = null
    private var collapseButton: TextView? = null
    private var contentBody: View? = null
    private var isShowing = false
    private val statusMessages = mutableListOf<String>()
    private var scrollViewHeight = 500

    // Persistent state
    private var savedIsCollapsed = false
    private var savedX = 20
    private var savedY = 200
    private var hasSavedPosition = false
    private var invisibleSince = 0L

    // iOS colors
    private val cardBg = Color.WHITE
    private val headerBg = Color.parseColor("#F2F2F7")
    private val labelColor = Color.parseColor("#000000")
    private val secondaryLabel = Color.parseColor("#6D6D72")
    private val tertiaryLabel = Color.parseColor("#9898A0")
    private val logBg = Color.parseColor("#F2F2F7")
    private val logText = Color.parseColor("#1C1C1E")
    private val accentBlue = Color.parseColor("#007AFF")
    private val stopRed = Color.parseColor("#FF3B30")
    private val separatorColor = Color.parseColor("#E5E5EA")
    private val handleColor = Color.parseColor("#D1D1D6")

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    fun setInvisible(invisible: Boolean) {
        val action = Runnable {
            if (invisible) {
                invisibleSince = System.currentTimeMillis()
                Log.d(TAG, "setInvisible(true)")
            } else {
                val dur = if (invisibleSince > 0) System.currentTimeMillis() - invisibleSince else 0
                Log.d(TAG, "setInvisible(false) — was invisible for ${dur}ms")
                invisibleSince = 0
            }
            overlayView?.visibility = if (invisible) View.INVISIBLE else View.VISIBLE
        }
        // Run directly if on main thread to avoid post{} deferral
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else {
            overlayView?.post(action)
        }
    }

    /**
     * Makes the overlay pass-through so gestures go to the real UI behind it.
     * Call with true before executing agent taps, false after.
     */
    fun setPassThrough(passThrough: Boolean) {
        val action = Runnable {
            try {
                val lp = overlayView?.layoutParams as? WindowManager.LayoutParams ?: return@Runnable
                if (passThrough) {
                    lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                } else {
                    lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                }
                windowManager.updateViewLayout(overlayView, lp)
            } catch (e: Exception) {
                Log.w(TAG, "setPassThrough($passThrough) failed", e)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            action.run()
        } else {
            overlayView?.post(action)
        }
    }

    fun show() {
        if (isShowing) return

        val container = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val card = CardView(context).apply {
            radius = dp(14).toFloat()
            cardElevation = dp(16).toFloat()
            setCardBackgroundColor(cardBg)
            layoutParams = FrameLayout.LayoutParams(dp(300), FrameLayout.LayoutParams.WRAP_CONTENT)
        }

        val innerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        // Header bar
        val headerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            background = GradientDrawable().apply {
                setColor(headerBg)
                cornerRadii = floatArrayOf(
                    dp(14).toFloat(), dp(14).toFloat(),
                    dp(14).toFloat(), dp(14).toFloat(),
                    0f, 0f, 0f, 0f
                )
            }
            setPadding(dp(16), dp(12), dp(12), dp(12))
        }

        // Status dot
        val statusDot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply {
                marginEnd = dp(8)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#34C759"))
            }
        }

        val titleText = TextView(context).apply {
            text = "Agent Log"
            setTextColor(labelColor)
            textSize = 16f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val collapseBtn = TextView(context).apply {
            text = if (savedIsCollapsed) "+" else "−"
            setTextColor(accentBlue)
            textSize = 22f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(dp(32), dp(32))
            setOnClickListener { toggleCollapse() }
        }
        this.collapseButton = collapseBtn

        headerLayout.addView(statusDot)
        headerLayout.addView(titleText)
        headerLayout.addView(collapseBtn)

        // Content body (log + resize + stop) — hidden when collapsed
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(12))
        }
        this.contentBody = body

        // Log scroll area
        val sv = ScrollView(context).apply {
            id = View.generateViewId()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                scrollViewHeight
            ).apply { bottomMargin = dp(8) }
            background = GradientDrawable().apply {
                setColor(logBg)
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }

        statusText = TextView(context).apply {
            setTextColor(logText)
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
            setLineSpacing(dp(2).toFloat(), 1f)
        }

        sv.addView(statusText)
        this.scrollView = sv

        // Resize handle
        val resizeBar = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(20)
            )
        }
        val resizePill = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(40), dp(4))
            background = GradientDrawable().apply {
                setColor(handleColor)
                cornerRadius = dp(2).toFloat()
            }
        }
        resizeBar.addView(resizePill)
        this.resizeHandle = resizeBar

        // Resize touch handling
        var initialResizeHeight = 0
        var initialResizeTouchY = 0f
        resizeBar.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialResizeHeight = sv.layoutParams.height
                    initialResizeTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaY = (event.rawY - initialResizeTouchY).toInt()
                    val newHeight = (initialResizeHeight + deltaY).coerceIn(dp(100), dp(500))
                    sv.layoutParams.height = newHeight
                    sv.requestLayout()
                    scrollViewHeight = newHeight
                    true
                }
                else -> false
            }
        }

        // Stop button
        val stopBtn = TextView(context).apply {
            text = "Stop Agent"
            setTextColor(stopRed)
            textSize = 15f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44)
            ).apply { topMargin = dp(4) }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#FFF2F1"))
                cornerRadius = dp(10).toFloat()
            }
            setOnClickListener {
                AgentOrchestrator.getInstance(context).stop()
            }
        }
        this.stopButton = stopBtn

        body.addView(sv)
        body.addView(resizeBar)
        body.addView(stopBtn)

        // Separator
        val sep = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 1
            )
            setBackgroundColor(separatorColor)
        }

        innerLayout.addView(headerLayout)
        innerLayout.addView(sep)
        innerLayout.addView(body)

        card.addView(innerLayout)
        container.addView(card)

        overlayView = container

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.TOP
            x = savedX
            y = savedY
        }

        // Drag by header
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        headerLayout.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX - (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    savedX = params.x
                    savedY = params.y
                    hasSavedPosition = true
                    windowManager.updateViewLayout(container, params)
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(container, params)
            isShowing = true

            if (savedIsCollapsed) {
                body.visibility = View.GONE
                sep.visibility = View.GONE
                collapseBtn.text = "+"
            }

            Log.d(TAG, "Status overlay shown (collapsed=$savedIsCollapsed)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show status overlay", e)
        }
    }

    fun hide() {
        if (!isShowing) return

        overlayView?.let { view ->
            try {
                windowManager.removeView(view)
                isShowing = false
                overlayView = null
                statusText = null
                scrollView = null
                resizeHandle = null
                stopButton = null
                collapseButton = null
                contentBody = null
                Log.d(TAG, "Status overlay hidden")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to hide status overlay", e)
            }
        }
    }

    fun addStatus(message: String) {
        val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val formattedMessage = "[$timestamp] $message"

        statusMessages.add(formattedMessage)

        if (statusMessages.size > 50) {
            statusMessages.removeAt(0)
        }

        updateDisplay()
        StatusBroadcaster.broadcast(context, formattedMessage)
    }

    private fun updateDisplay() {
        statusText?.post {
            statusText?.text = statusMessages.joinToString("\n")
            scrollView?.post {
                scrollView?.fullScroll(View.FOCUS_DOWN)
            }
        }
    }

    fun clear() {
        statusMessages.clear()
        updateDisplay()
    }

    private fun toggleCollapse() {
        savedIsCollapsed = !savedIsCollapsed

        val visibility = if (savedIsCollapsed) View.GONE else View.VISIBLE
        contentBody?.visibility = visibility
        // Also hide separator (it's the sibling right before contentBody)
        (contentBody?.parent as? LinearLayout)?.let { parent ->
            val bodyIndex = parent.indexOfChild(contentBody as View)
            if (bodyIndex > 0) {
                parent.getChildAt(bodyIndex - 1)?.visibility = visibility
            }
        }

        collapseButton?.text = if (savedIsCollapsed) "+" else "−"
        Log.d(TAG, "Toggled collapse: isCollapsed=$savedIsCollapsed")
    }

    companion object {
        private const val TAG = "StatusOverlay"

        @Volatile
        private var instance: StatusOverlay? = null

        fun getInstance(context: Context): StatusOverlay {
            return instance ?: synchronized(this) {
                instance ?: StatusOverlay(context.applicationContext).also { instance = it }
            }
        }
    }
}

object StatusBroadcaster {
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(String) -> Unit>()

    fun addListener(listener: (String) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (String) -> Unit) {
        listeners.remove(listener)
    }

    fun broadcast(context: Context, message: String) {
        listeners.forEach { it(message) }
    }
}
