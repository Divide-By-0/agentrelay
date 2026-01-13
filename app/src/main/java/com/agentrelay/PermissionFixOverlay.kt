package com.agentrelay

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.cardview.widget.CardView

class PermissionFixOverlay(private val context: Context) {

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
            text = "Screen Capture Failed"
            setTextColor(Color.parseColor("#E8E3E0"))
            textSize = 20f
            typeface = android.graphics.Typeface.create(
                android.graphics.Typeface.DEFAULT,
                android.graphics.Typeface.BOLD
            )
        }

        headerLayout.addView(icon)
        headerLayout.addView(title)

        // Message
        val messageText = TextView(context).apply {
            text = "The agent can't capture screenshots. This usually happens when:\n\n" +
                    "• Screen capture permission was denied\n" +
                    "• The app was moved to background\n" +
                    "• Android stopped the screen capture service"
            setTextColor(Color.parseColor("#E8E3E0"))
            textSize = 14f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 16
            }
        }

        // Instructions
        val instructionsText = TextView(context).apply {
            text = "To fix this:"
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

        val stepsList = TextView(context).apply {
            text = "1. Tap 'Open App' below\n" +
                    "2. Go to Setup tab\n" +
                    "3. Tap 'Stop Service'\n" +
                    "4. Tap 'Start Service' again\n" +
                    "5. Grant screen capture permission\n" +
                    "6. Choose 'Entire screen'"
            setTextColor(Color.parseColor("#E8E3E0"))
            textSize = 13f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 24
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

        val dismissButton = Button(context).apply {
            text = "Dismiss"
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

        val openAppButton = Button(context).apply {
            text = "Open App →"
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
                hide()
                // Open the main app
                val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                intent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(intent)
            }
        }

        buttonLayout.addView(dismissButton)
        buttonLayout.addView(openAppButton)

        innerLayout.addView(headerLayout)
        innerLayout.addView(messageText)
        innerLayout.addView(instructionsText)
        innerLayout.addView(stepsList)
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
            Log.d(TAG, "Permission fix overlay shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show permission fix overlay", e)
        }
    }

    fun hide() {
        if (!isShowing) return

        overlayView?.let { view ->
            try {
                windowManager.removeView(view)
                isShowing = false
                overlayView = null
                Log.d(TAG, "Permission fix overlay hidden")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to hide permission fix overlay", e)
            }
        }
    }

    companion object {
        private const val TAG = "PermissionFixOverlay"

        @Volatile
        private var instance: PermissionFixOverlay? = null

        fun getInstance(context: Context): PermissionFixOverlay {
            return instance ?: synchronized(this) {
                instance ?: PermissionFixOverlay(context.applicationContext).also { instance = it }
            }
        }
    }
}
