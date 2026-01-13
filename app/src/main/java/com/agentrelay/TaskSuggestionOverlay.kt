package com.agentrelay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.text.InputType
import android.util.Log
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

    fun show(
        originalTask: String,
        failureReason: String,
        conversationHistory: List<Message>
    ) {
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
            text = "⚠️"
            textSize = 24f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginEnd = 12
            }
        }

        val title = TextView(context).apply {
            text = "Task Failed"
            setTextColor(Color.parseColor("#E8E3E0"))
            textSize = 20f
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD
            )
        }

        headerLayout.addView(icon)
        headerLayout.addView(title)

        // Failure reason
        val reasonText = TextView(context).apply {
            text = failureReason
            setTextColor(Color.parseColor("#E8E3E0"))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 16
            }
        }

        // Suggestion section
        val suggestionLabel = TextView(context).apply {
            text = "Suggested modifications:"
            setTextColor(Color.parseColor("#999999"))
            textSize = 12f
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

        // Generate suggestions based on failure
        val suggestions = generateSuggestions(originalTask, failureReason)
        val suggestionContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 16
            }
        }

        suggestions.forEach { suggestion ->
            val suggestionButton = Button(context).apply {
                text = suggestion
                setTextColor(Color.parseColor("#E8E3E0"))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 8
                }
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(Color.parseColor("#3A3734"))
                    cornerRadius = 8f
                }
                setPadding(16, 16, 16, 16)
                isAllCaps = false
                setOnClickListener {
                    hide()
                    TaskHistory.addTask(context, suggestion)
                    CoroutineScope(Dispatchers.Main).launch {
                        AgentOrchestrator.getInstance(context).startTask(suggestion)
                    }
                }
            }
            suggestionContainer.addView(suggestionButton)
        }

        // Custom task input
        val customLabel = TextView(context).apply {
            text = "Or modify the task:"
            setTextColor(Color.parseColor("#999999"))
            textSize = 12f
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

        val editText = EditText(context).apply {
            setText(originalTask)
            setTextColor(Color.parseColor("#E8E3E0"))
            setBackgroundColor(Color.parseColor("#1A1818"))
            setPadding(24, 24, 24, 24)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 16
            }
            minLines = 2
            maxLines = 4
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#1A1818"))
                cornerRadius = 12f
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

        val cancelButton = Button(context).apply {
            text = "Cancel"
            setTextColor(Color.parseColor("#E8E3E0"))
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

        val retryButton = Button(context).apply {
            text = "Retry Task →"
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
        buttonLayout.addView(retryButton)

        innerLayout.addView(headerLayout)
        innerLayout.addView(reasonText)
        innerLayout.addView(suggestionLabel)
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

    private fun generateSuggestions(originalTask: String, failureReason: String): List<String> {
        val suggestions = mutableListOf<String>()

        // Parse failure reason and generate context-aware suggestions
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

    private fun simplifyTask(task: String): String {
        // Remove complex parts and make it simpler
        val words = task.split(" ")
        return if (words.size > 5) {
            words.take(5).joinToString(" ") + "..."
        } else {
            task
        }
    }

    private fun breakIntoSteps(task: String): String {
        // Suggest breaking into first step
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
