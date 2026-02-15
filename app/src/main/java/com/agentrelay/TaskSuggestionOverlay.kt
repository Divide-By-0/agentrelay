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
import android.view.View
import android.view.WindowManager
import android.widget.*
import androidx.cardview.widget.CardView
import com.agentrelay.models.Message
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class TaskSuggestionOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private var isShowing = false

    // iOS colors
    private val cardBg = Color.WHITE
    private val labelColor = Color.parseColor("#000000")
    private val secondaryLabel = Color.parseColor("#6D6D72")
    private val tertiaryLabel = Color.parseColor("#9898A0")
    private val accentBlue = Color.parseColor("#007AFF")
    private val warningOrange = Color.parseColor("#FF9500")
    private val inputBg = Color.parseColor("#F2F2F7")
    private val cancelBg = Color.parseColor("#E5E5EA")
    private val suggestionBg = Color.parseColor("#F2F2F7")

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    fun show(
        originalTask: String,
        failureReason: String,
        conversationHistory: List<Message>
    ) {
        if (isShowing) return

        val container = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        val card = CardView(context).apply {
            radius = dp(14).toFloat()
            cardElevation = dp(20).toFloat()
            setCardBackgroundColor(cardBg)
            layoutParams = FrameLayout.LayoutParams(dp(340), FrameLayout.LayoutParams.WRAP_CONTENT)
        }

        val innerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }

        // Drag handle
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

        // Warning icon + title
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(4) }
        }

        val warningDot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply {
                marginEnd = dp(8)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(warningOrange)
            }
        }

        val title = TextView(context).apply {
            text = "Task Failed"
            setTextColor(labelColor)
            textSize = 20f
            typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        }

        headerRow.addView(warningDot)
        headerRow.addView(title)

        // Failure reason
        val reasonText = TextView(context).apply {
            text = failureReason
            setTextColor(secondaryLabel)
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }

        // Suggestions
        val suggestions = generateSuggestions(originalTask, failureReason)
        val suggestionContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }

        val suggestionLabel = TextView(context).apply {
            text = "SUGGESTIONS"
            setTextColor(tertiaryLabel)
            textSize = 12f
            letterSpacing = 0.05f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }
        suggestionContainer.addView(suggestionLabel)

        suggestions.forEach { suggestion ->
            val btn = TextView(context).apply {
                text = suggestion
                setTextColor(accentBlue)
                textSize = 15f
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(8) }
                background = GradientDrawable().apply {
                    setColor(suggestionBg)
                    cornerRadius = dp(10).toFloat()
                }
                setPadding(dp(16), dp(12), dp(16), dp(12))
                setOnClickListener {
                    hide()
                    TaskHistory.addTask(context, suggestion)
                    CoroutineScope(Dispatchers.Main).launch {
                        AgentOrchestrator.getInstance(context).startTask(suggestion)
                    }
                }
            }
            suggestionContainer.addView(btn)
        }

        // Custom input
        val customLabel = TextView(context).apply {
            text = "OR MODIFY THE TASK"
            setTextColor(tertiaryLabel)
            textSize = 12f
            letterSpacing = 0.05f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(8) }
        }

        val editText = EditText(context).apply {
            setText(originalTask)
            setTextColor(labelColor)
            textSize = 16f
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
            minLines = 2
            maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = GradientDrawable().apply {
                setColor(inputBg)
                cornerRadius = dp(10).toFloat()
            }
        }

        // Buttons
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

        val retryButton = TextView(context).apply {
            text = "Retry"
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
        buttonLayout.addView(retryButton)

        // Assemble
        innerLayout.addView(handleContainer)
        innerLayout.addView(headerRow)
        innerLayout.addView(reasonText)
        innerLayout.addView(suggestionContainer)
        innerLayout.addView(customLabel)
        innerLayout.addView(editText)
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

        try {
            windowManager.addView(container, params)
            isShowing = true
            Log.d(TAG, "Task suggestion overlay shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show task suggestion overlay", e)
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun generateSuggestions(originalTask: String, failureReason: String): List<String> {
        val suggestions = mutableListOf<String>()

        when {
            failureReason.contains("not found", ignoreCase = true) ||
            failureReason.contains("cannot find", ignoreCase = true) -> {
                suggestions.add("First open the app, then $originalTask")
                suggestions.add("Navigate to home screen, then $originalTask")
            }
            failureReason.contains("permission", ignoreCase = true) -> {
                suggestions.add("Grant necessary permissions, then $originalTask")
            }
            failureReason.contains("login", ignoreCase = true) ||
            failureReason.contains("sign in", ignoreCase = true) -> {
                suggestions.add("Sign in to the app first, then $originalTask")
            }
            else -> {
                suggestions.add("Try a simpler version: ${simplifyTask(originalTask)}")
                suggestions.add("Break into steps: ${breakIntoSteps(originalTask)}")
            }
        }

        return suggestions.take(3)
    }

    @androidx.annotation.VisibleForTesting
    internal fun simplifyTask(task: String): String {
        val words = task.split(" ")
        return if (words.size > 5) {
            words.take(5).joinToString(" ") + "..."
        } else {
            task
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun breakIntoSteps(task: String): String {
        return "First step: ${task.split(",").first().trim()}"
    }

    fun hide() {
        if (!isShowing) return

        overlayView?.let { view ->
            try {
                windowManager.removeView(view)
                isShowing = false
                overlayView = null
                Log.d(TAG, "Task suggestion overlay hidden")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to hide task suggestion overlay", e)
            }
        }
    }

    companion object {
        private const val TAG = "TaskSuggestionOverlay"

        @Volatile
        private var instance: TaskSuggestionOverlay? = null

        fun getInstance(context: Context): TaskSuggestionOverlay {
            return instance ?: synchronized(this) {
                instance ?: TaskSuggestionOverlay(context.applicationContext).also { instance = it }
            }
        }
    }
}
