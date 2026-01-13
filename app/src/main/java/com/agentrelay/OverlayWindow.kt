package com.agentrelay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.cardview.widget.CardView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class OverlayWindow(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private var isShowing = false

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
            radius = 24f
            cardElevation = 16f
            setCardBackgroundColor(Color.parseColor("#2B2826"))
            layoutParams = LinearLayout.LayoutParams(
                800,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val innerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }

        // Header
        val headerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 16
            }
        }

        val icon = TextView(context).apply {
            text = "🤖"
            textSize = 24f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 12
            }
        }

        val title = TextView(context).apply {
            text = "What should I do?"
            setTextColor(Color.parseColor("#E8E3E0"))
            textSize = 20f
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD
            )
        }

        headerLayout.addView(icon)
        headerLayout.addView(title)

        // Continue button (if last task exists) - positioned prominently at top
        val lastTask = TaskHistory.getLastTask(context)
        val continueButton = Button(context).apply {
            text = "↻ Continue: ${lastTask?.take(40) ?: ""}${if ((lastTask?.length ?: 0) > 40) "..." else ""}"
            setTextColor(Color.parseColor("#E8E3E0"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 16
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#4A4643"))
                cornerRadius = 12f
            }
            setPadding(24, 16, 24, 16)
            isAllCaps = false
            visibility = if (lastTask != null) android.view.View.VISIBLE else android.view.View.GONE
            setOnClickListener {
                lastTask?.let { task ->
                    hide()
                    TaskHistory.addTask(context, task)
                    CoroutineScope(Dispatchers.Main).launch {
                        AgentOrchestrator.getInstance(context).startTask(task)
                    }
                }
            }
        }

        // Input field
        val editText = EditText(context).apply {
            hint = "Describe the task..."
            setHintTextColor(Color.parseColor("#808080"))
            setTextColor(Color.parseColor("#E8E3E0"))
            setBackgroundColor(Color.parseColor("#1A1818"))
            setPadding(24, 24, 24, 24)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 16
            }
            minLines = 3
            maxLines = 6
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#1A1818"))
                cornerRadius = 12f
            }
        }

        // Example text
        val exampleText = TextView(context).apply {
            text = "Examples: \"Open Chrome and search for cats\" • \"Send a text message to John\""
            setTextColor(Color.parseColor("#999999"))
            textSize = 11f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 12
            }
        }

        // Recent tasks carousel
        val recentTasks = TaskHistory.getTasks(context)

        val recentTasksLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 16
            }
            visibility = if (recentTasks.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        }

        if (recentTasks.isNotEmpty()) {
            val recentLabel = TextView(context).apply {
                text = "Recent"
                setTextColor(Color.parseColor("#999999"))
                textSize = 10f
                typeface = android.graphics.Typeface.create(
                    android.graphics.Typeface.DEFAULT,
                    android.graphics.Typeface.BOLD
                )
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 8
                }
            }
            recentTasksLayout.addView(recentLabel)

            val scrollView = android.widget.HorizontalScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
                isHorizontalScrollBarEnabled = false
            }

            val chipContainer = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )
            }

            recentTasks.take(5).forEach { task ->
                val chip = Button(context).apply {
                    text = task.take(30) + if (task.length > 30) "..." else ""
                    setTextColor(Color.parseColor("#E8E3E0"))
                    textSize = 12f
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        marginEnd = 8
                    }
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(Color.parseColor("#2B2826"))
                        cornerRadius = 20f
                    }
                    setPadding(24, 12, 24, 12)
                    isAllCaps = false
                    setOnClickListener {
                        editText.setText(task)
                        editText.setSelection(task.length)
                    }
                }
                chipContainer.addView(chip)
            }

            scrollView.addView(chipContainer)
            recentTasksLayout.addView(scrollView)
        }

        // Buttons
        val buttonLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val cancelButton = Button(context).apply {
            text = "Cancel"
            setTextColor(Color.parseColor("#E8E3E0"))
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginEnd = 12
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#3A3734"))
                cornerRadius = 12f
            }
            setPadding(24, 20, 24, 20)
            isAllCaps = false
            setOnClickListener {
                hide()
            }
        }

        val startButton = Button(context).apply {
            text = "Start →"
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                marginStart = 12
            }
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#CC9B6D"))
                cornerRadius = 12f
            }
            setPadding(24, 20, 24, 20)
            isAllCaps = false
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD
            )
            setOnClickListener {
                val task = editText.text.toString().trim()
                if (task.isNotEmpty()) {
                    hide()
                    TaskHistory.addTask(context, task)
                    CoroutineScope(Dispatchers.Main).launch {
                        AgentOrchestrator.getInstance(context).startTask(task)
                    }
                } else {
                    Toast.makeText(context, "Please enter a task", Toast.LENGTH_SHORT).show()
                }
            }
        }

        buttonLayout.addView(cancelButton)
        buttonLayout.addView(startButton)

        innerLayout.addView(headerLayout)
        if (lastTask != null) {
            innerLayout.addView(continueButton)
        }
        innerLayout.addView(editText)
        innerLayout.addView(exampleText)
        innerLayout.addView(recentTasksLayout)
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
