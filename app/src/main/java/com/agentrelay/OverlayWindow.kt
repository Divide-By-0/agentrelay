package com.agentrelay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.cardview.widget.CardView
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class OverlayWindow(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private var isShowing = false

    // iOS colors
    private val bgColor = Color.parseColor("#F2F2F7")
    private val cardColor = Color.WHITE
    private val labelColor = Color.parseColor("#000000")
    private val secondaryLabel = Color.parseColor("#3C3C43")
    private val tertiaryLabel = Color.parseColor("#9898A0")
    private val separatorColor = Color.parseColor("#E5E5EA")
    private val accentBlue = Color.parseColor("#007AFF")
    private val chipBg = Color.parseColor("#E5E5EA")
    private val inputBg = Color.parseColor("#F2F2F7")
    private val cancelBg = Color.parseColor("#E5E5EA")

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    fun show() {
        if (isShowing) return

        val container = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Main card
        val card = CardView(context).apply {
            radius = dp(14).toFloat()
            cardElevation = dp(20).toFloat()
            setCardBackgroundColor(cardColor)
            layoutParams = FrameLayout.LayoutParams(dp(340), FrameLayout.LayoutParams.WRAP_CONTENT)
        }

        val innerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        // Drag handle pill
        val handleContainer = LinearLayout(context).apply {
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
        }
        val dragHandle = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(36), dp(5))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#D1D1D6"))
                cornerRadius = dp(3).toFloat()
            }
        }
        handleContainer.addView(dragHandle)

        // Title
        val title = TextView(context).apply {
            text = "What should I do?"
            setTextColor(labelColor)
            textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        }

        // Subtitle
        val subtitle = TextView(context).apply {
            text = "Describe a task for the agent"
            setTextColor(tertiaryLabel)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }

        // Continue button (if last task exists)
        val lastTask = TaskHistory.getLastTask(context)
        val continueButton = TextView(context).apply {
            val truncated = lastTask?.take(45) ?: ""
            val ellipsis = if ((lastTask?.length ?: 0) > 45) "..." else ""
            text = "Continue: $truncated$ellipsis"
            setTextColor(accentBlue)
            textSize = 15f
            typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(44)
            ).apply { bottomMargin = dp(12) }
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EBF2FF"))
                cornerRadius = dp(10).toFloat()
            }
            setPadding(dp(16), 0, dp(16), 0)
            visibility = if (lastTask != null) View.VISIBLE else View.GONE
            setOnClickListener {
                lastTask?.let { task ->
                    hide()
                    startTaskWithCaptureCheck(task)
                }
            }
        }

        // Input field
        val editText = EditText(context).apply {
            hint = "e.g. Open Chrome and search for cats"
            setHintTextColor(tertiaryLabel)
            setTextColor(labelColor)
            textSize = 16f
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(12) }
            minLines = 2
            maxLines = 5
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = GradientDrawable().apply {
                setColor(inputBg)
                cornerRadius = dp(10).toFloat()
            }
        }

        // Recent tasks
        val recentTasks = TaskHistory.getTasks(context)
        val recentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
            visibility = if (recentTasks.isNotEmpty()) View.VISIBLE else View.GONE
        }

        if (recentTasks.isNotEmpty()) {
            val recentLabel = TextView(context).apply {
                text = "RECENTS"
                setTextColor(tertiaryLabel)
                textSize = 12f
                typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                letterSpacing = 0.05f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
            }
            recentLayout.addView(recentLabel)

            val scrollView = HorizontalScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                isHorizontalScrollBarEnabled = false
            }

            val chipRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }

            recentTasks.take(5).forEach { task ->
                val chip = TextView(context).apply {
                    text = task.take(25) + if (task.length > 25) "..." else ""
                    setTextColor(labelColor)
                    textSize = 13f
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = dp(8) }
                    background = GradientDrawable().apply {
                        setColor(chipBg)
                        cornerRadius = dp(16).toFloat()
                    }
                    setPadding(dp(14), dp(8), dp(14), dp(8))
                    setOnClickListener {
                        editText.setText(task)
                        editText.setSelection(task.length)
                    }
                }
                chipRow.addView(chip)
            }

            scrollView.addView(chipRow)
            recentLayout.addView(scrollView)
        }

        // Buttons row
        val buttonLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val cancelButton = TextView(context).apply {
            text = "Cancel"
            setTextColor(labelColor)
            textSize = 17f
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f).apply {
                marginEnd = dp(8)
            }
            background = GradientDrawable().apply {
                setColor(cancelBg)
                cornerRadius = dp(12).toFloat()
            }
            setOnClickListener { hide() }
        }

        val startButton = TextView(context).apply {
            text = "Start"
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(0, dp(50), 1f).apply {
                marginStart = dp(8)
            }
            background = GradientDrawable().apply {
                setColor(accentBlue)
                cornerRadius = dp(12).toFloat()
            }
            setOnClickListener {
                val task = editText.text.toString().trim()
                if (task.isNotEmpty()) {
                    hide()
                    startTaskWithCaptureCheck(task)
                } else {
                    Toast.makeText(context, "Please enter a task", Toast.LENGTH_SHORT).show()
                }
            }
        }

        buttonLayout.addView(cancelButton)
        buttonLayout.addView(startButton)

        // Assemble
        innerLayout.addView(handleContainer)
        innerLayout.addView(title)
        innerLayout.addView(subtitle)
        if (lastTask != null) innerLayout.addView(continueButton)
        innerLayout.addView(editText)
        innerLayout.addView(recentLayout)
        innerLayout.addView(buttonLayout)

        card.addView(innerLayout)
        container.addView(card)

        overlayView = container

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        // Make draggable by handle
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f

        handleContainer.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager.updateViewLayout(container, params)
                    true
                }
                else -> false
            }
        }

        try {
            windowManager.addView(container, params)
            isShowing = true
            Log.d(TAG, "Overlay window shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show overlay window", e)
        }
    }

    fun hide() {
        if (!isShowing) return

        overlayView?.let { view ->
            try {
                windowManager.removeView(view)
                isShowing = false
                overlayView = null
                Log.d(TAG, "Overlay window hidden")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to hide overlay window", e)
            }
        }
    }

    /**
     * Called from VoiceCommandService when a voice task is captured.
     * Briefly shows the overlay with the task text, then auto-starts after a delay.
     */
    fun startTaskFromVoice(task: String) {
        Handler(Looper.getMainLooper()).post {
            // Show a brief toast-like notification of what was heard
            Toast.makeText(context, "\uD83C\uDF99 \"$task\"", Toast.LENGTH_SHORT).show()

            // Auto-start the task after a short delay so the user sees the feedback
            Handler(Looper.getMainLooper()).postDelayed({
                startTaskWithCaptureCheck(task)
            }, 1500)
        }
    }

    /**
     * Checks if screen capture is available. If not, launches the transparent
     * ScreenCaptureRequestActivity to request permission first, then starts the task.
     * If already available, starts the task directly.
     */
    private fun startTaskWithCaptureCheck(task: String) {
        if (ScreenCaptureService.instance != null) {
            // Screen capture already active — start directly
            TaskHistory.addTask(context, task)
            CoroutineScope(Dispatchers.Main).launch {
                AgentOrchestrator.getInstance(context).startTask(task)
            }
        } else {
            // Need to request screen capture permission via Activity
            ScreenCaptureRequestActivity.launch(context, task)
        }
    }

    companion object {
        private const val TAG = "OverlayWindow"

        @Volatile
        private var instance: OverlayWindow? = null

        fun getInstance(context: Context): OverlayWindow {
            return instance ?: synchronized(this) {
                instance ?: OverlayWindow(context.applicationContext).also { instance = it }
            }
        }
    }
}
