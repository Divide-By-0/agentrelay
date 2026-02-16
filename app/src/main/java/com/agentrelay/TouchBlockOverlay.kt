package com.agentrelay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Fullscreen transparent overlay that intercepts all touches when enabled.
 * Used to prevent accidental user interaction during agent execution.
 * Provides a small "Stop Agent" button at the bottom center.
 */
class TouchBlockOverlay private constructor(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null
    private var isShowing = false
    private var onStopRequested: (() -> Unit)? = null

    fun setOnStopRequested(callback: () -> Unit) {
        onStopRequested = callback
    }

    fun show() {
        if (isShowing) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !android.provider.Settings.canDrawOverlays(context)) {
            Log.w(TAG, "Cannot show touch blocker: no overlay permission")
            return
        }

        try {
            val layout = FrameLayout(context).apply {
                // Consume all touches on the main area
                setOnTouchListener { _, _ -> true }

                // Semi-transparent background to give visual feedback
                setBackgroundColor(Color.argb(1, 0, 0, 0)) // Nearly invisible

                // Stop button at bottom center
                val stopButton = TextView(context).apply {
                    text = "Stop Agent"
                    setTextColor(Color.WHITE)
                    textSize = 14f
                    setPadding(48, 24, 48, 24)
                    setBackgroundColor(Color.argb(200, 255, 59, 48)) // iOS Red
                    gravity = Gravity.CENTER

                    setOnClickListener {
                        Log.d(TAG, "Stop button tapped")
                        onStopRequested?.invoke()
                    }
                }

                val buttonParams = FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    bottomMargin = 120
                }
                addView(stopButton, buttonParams)
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            )

            windowManager.addView(layout, params)
            overlayView = layout
            isShowing = true
            Log.d(TAG, "Touch block overlay shown")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show touch block overlay", e)
        }
    }

    fun hide() {
        if (!isShowing) return
        try {
            overlayView?.let { windowManager.removeView(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove touch block overlay", e)
        }
        overlayView = null
        isShowing = false
        Log.d(TAG, "Touch block overlay hidden")
    }

    fun isShowing(): Boolean = isShowing

    companion object {
        private const val TAG = "TouchBlockOverlay"

        @Volatile
        private var INSTANCE: TouchBlockOverlay? = null

        fun getInstance(context: Context): TouchBlockOverlay {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TouchBlockOverlay(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
