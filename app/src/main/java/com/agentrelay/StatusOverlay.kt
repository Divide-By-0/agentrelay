package com.agentrelay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
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
    private var isShowing = false
    private val statusMessages = mutableListOf<String>()
    private var scrollViewHeight = 600 // Default height

    // Persistent state that survives hide/show cycles
    private var savedIsCollapsed = false
    private var savedX = 20
    private var savedY = 200
    private var hasSavedPosition = false

    fun show() {
        if (isShowing) return

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Card container
        val card = CardView(context).apply {
            radius = 16f
            cardElevation = 12f
            setCardBackgroundColor(Color.parseColor("#2B2826"))
            layoutParams = LinearLayout.LayoutParams(
                600,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val innerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }

        // Header (draggable)
        val headerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 12
            }
            setBackgroundColor(Color.parseColor("#3A3734"))
            setPadding(16, 12, 16, 12)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#3A3734"))
                cornerRadius = 8f
            }
        }

        val icon = TextView(context).apply {
            text = "🤖"
            textSize = 18f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 8
            }
        }

        val titleText = TextView(context).apply {
            text = "Agent Status"
            setTextColor(Color.parseColor("#E8E3E0"))
            textSize = 16f
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD
            )
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }

        val collapseButton = TextView(context).apply {
            text = "▼"
            setTextColor(Color.parseColor("#999999"))
            textSize = 16f
            setPadding(8, 0, 8, 0)
            setOnClickListener {
                toggleCollapse()
            }
        }

        // Store reference for collapse/expand
        this.collapseButton = collapseButton

        val dragHint = TextView(context).apply {
            text = "⋮⋮"
            setTextColor(Color.parseColor("#999999"))
            textSize = 16f
        }

        headerLayout.addView(icon)
        headerLayout.addView(titleText)
        headerLayout.addView(collapseButton)
        headerLayout.addView(dragHint)

        val scrollView = ScrollView(context).apply {
            id = android.view.View.generateViewId()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                scrollViewHeight
            ).apply {
                bottomMargin = 12
            }
            setBackgroundColor(Color.parseColor("#1A1818"))
            setPadding(12, 12, 12, 12)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#1A1818"))
                cornerRadius = 8f
            }
        }

        statusText = TextView(context).apply {
            setTextColor(Color.parseColor("#E8E3E0"))
            textSize = 11f
            setPadding(8, 8, 8, 8)
        }

        scrollView.addView(statusText)

        // Store scrollView reference for autoscroll and collapse
        this.scrollView = scrollView

        // Resize handle
        val resizeHandle = TextView(context).apply {
            text = "⋮⋮⋮"
            gravity = android.view.Gravity.CENTER
            setTextColor(Color.parseColor("#666666"))
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 8
            }
            setPadding(0, 8, 0, 8)
        }

        // Store reference for collapse/expand
        this.resizeHandle = resizeHandle

        // Make resize handle draggable
        var initialResizeHeight = 0
        var initialResizeTouchY = 0f
        resizeHandle.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialResizeHeight = scrollView.layoutParams.height
                    initialResizeTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaY = (event.rawY - initialResizeTouchY).toInt()
                    val newHeight = (initialResizeHeight + deltaY).coerceIn(200, 1200)
                    scrollView.layoutParams.height = newHeight
                    scrollView.requestLayout()
                    scrollViewHeight = newHeight
                    true
                }
                else -> false
            }
        }

        // Stop button
        val stopButton = Button(context).apply {
            text = "⏹ Stop Agent"
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#D32F2F"))
                cornerRadius = 8f
            }
            setPadding(16, 16, 16, 16)
            isAllCaps = false
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD
            )
            setOnClickListener {
                AgentOrchestrator.getInstance(context).stop()
            }
        }

        // Store reference for collapse/expand
        this.stopButton = stopButton

        innerLayout.addView(headerLayout)
        innerLayout.addView(scrollView)
        innerLayout.addView(resizeHandle)
        innerLayout.addView(stopButton)

        card.addView(innerLayout)
        container.addView(card)

        overlayView = container

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.END or Gravity.TOP
            x = savedX
            y = savedY
        }

        // Make draggable by header
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        headerLayout.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    // For Gravity.END, x increases as we move LEFT (away from right edge)
                    // So we need to subtract the delta to make dragging feel natural
                    params.x = initialX - (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    // Persist position so it survives hide/show cycles
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

            // Restore saved collapse state
            if (savedIsCollapsed) {
                // Apply collapse without animation
                val visibility = android.view.View.GONE
                scrollView?.visibility = visibility
                resizeHandle?.visibility = visibility
                stopButton?.visibility = visibility
                collapseButton?.text = "▶"
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

        // Keep only last 50 messages
        if (statusMessages.size > 50) {
            statusMessages.removeAt(0)
        }

        updateDisplay()

        // Notify main app
        StatusBroadcaster.broadcast(context, formattedMessage)
    }

    private fun updateDisplay() {
        statusText?.post {
            statusText?.text = statusMessages.joinToString("\n")
            // Autoscroll to bottom after text update
            scrollView?.post {
                scrollView?.fullScroll(android.view.View.FOCUS_DOWN)
            }
        }
    }

    fun clear() {
        statusMessages.clear()
        updateDisplay()
    }

    private fun toggleCollapse() {
        savedIsCollapsed = !savedIsCollapsed

        // Update visibility
        val visibility = if (savedIsCollapsed) android.view.View.GONE else android.view.View.VISIBLE
        scrollView?.visibility = visibility
        resizeHandle?.visibility = visibility
        stopButton?.visibility = visibility

        // Update collapse button icon
        collapseButton?.text = if (savedIsCollapsed) "▶" else "▼"

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
