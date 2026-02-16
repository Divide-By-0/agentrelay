package com.agentrelay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import kotlin.math.abs

class FloatingBubble(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var bubbleView: BubbleView? = null
    private var dismissView: DismissZoneView? = null
    private var isShowing = false
    private var params: WindowManager.LayoutParams? = null
    private var dismissParams: WindowManager.LayoutParams? = null

    fun show() {
        if (isShowing) return

        bubbleView = BubbleView(context).also { view ->
            val lp = WindowManager.LayoutParams(
                BUBBLE_SIZE,
                BUBBLE_SIZE,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = screenHeight() / 3
            }
            params = lp

            setupTouch(view, lp)

            try {
                windowManager.addView(view, lp)
                isShowing = true
                // Entrance animation
                view.alpha = 0f
                view.scaleX = 0.5f
                view.scaleY = 0.5f
                view.animate()
                    .alpha(1f).scaleX(1f).scaleY(1f)
                    .setDuration(250)
                    .setInterpolator(OvershootInterpolator())
                    .start()
                Log.d(TAG, "Floating bubble shown")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show floating bubble", e)
            }
        }
    }

    fun hide() {
        if (!isShowing) return
        hideDismissZone()
        bubbleView?.let { view ->
            try {
                windowManager.removeView(view)
                isShowing = false
                bubbleView = null
                params = null
                Log.d(TAG, "Floating bubble hidden")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to hide floating bubble", e)
            }
        }
    }

    private fun showDismissZone() {
        if (dismissView != null) return
        val dv = DismissZoneView(context)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            DISMISS_ZONE_HEIGHT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
        }
        dismissParams = lp
        try {
            windowManager.addView(dv, lp)
            dismissView = dv
            dv.alpha = 0f
            dv.animate().alpha(1f).setDuration(150).start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show dismiss zone", e)
        }
    }

    private fun hideDismissZone() {
        dismissView?.let { dv ->
            try {
                windowManager.removeView(dv)
            } catch (_: Exception) {}
            dismissView = null
            dismissParams = null
        }
    }

    private fun isInDismissZone(lp: WindowManager.LayoutParams): Boolean {
        val bubbleCenterY = lp.y + BUBBLE_SIZE / 2
        // Use the dismiss zone view's actual on-screen position for accurate hit testing.
        // This accounts for navigation bar insets which offset the Gravity.BOTTOM view.
        val dv = dismissView
        if (dv != null) {
            val loc = IntArray(2)
            dv.getLocationOnScreen(loc)
            return bubbleCenterY > loc[1]
        }
        // Fallback if dismiss zone isn't showing yet
        val threshold = screenHeight() - DISMISS_ZONE_HEIGHT
        return bubbleCenterY > threshold
    }

    private fun setupTouch(view: BubbleView, lp: WindowManager.LayoutParams) {
        var initialX = 0
        var initialY = 0
        var initialTouchX = 0f
        var initialTouchY = 0f
        var isDragging = false

        view.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = lp.x
                    initialY = lp.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    isDragging = false
                    view.setPressed(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (abs(dx) > 10 || abs(dy) > 10) {
                        if (!isDragging) {
                            isDragging = true
                            showDismissZone()
                        }
                    }
                    lp.x = initialX + dx.toInt()
                    lp.y = initialY + dy.toInt()
                    try {
                        windowManager.updateViewLayout(view, lp)
                    } catch (_: Exception) {}
                    // Highlight dismiss zone when bubble is near
                    val inZone = isInDismissZone(lp)
                    dismissView?.setHighlighted(inZone)
                    view.alpha = if (inZone) 0.5f else 1f
                    true
                }
                MotionEvent.ACTION_UP -> {
                    view.setPressed(false)
                    if (!isDragging) {
                        // Tap — open the agent overlay window
                        view.showTapFeedback()
                        OverlayWindow.getInstance(context).show()
                    } else if (isInDismissZone(lp)) {
                        // Dismiss the bubble
                        hideDismissZone()
                        hide()
                        SecureStorage.getInstance(context).setFloatingBubbleEnabled(false)
                        Log.d(TAG, "Bubble dismissed by drag to bottom")
                    } else {
                        hideDismissZone()
                        // Snap to nearest edge
                        snapToEdge(view, lp)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    view.setPressed(false)
                    hideDismissZone()
                    view.alpha = 1f
                    true
                }
                else -> false
            }
        }
    }

    private fun snapToEdge(view: View, lp: WindowManager.LayoutParams) {
        val sw = screenWidth()
        val midX = lp.x + BUBBLE_SIZE / 2
        val targetX = if (midX < sw / 2) 0 else sw - BUBBLE_SIZE

        ValueAnimator.ofInt(lp.x, targetX).apply {
            duration = 200
            addUpdateListener { anim ->
                lp.x = anim.animatedValue as Int
                try {
                    windowManager.updateViewLayout(view, lp)
                } catch (_: Exception) {}
            }
            start()
        }
    }

    private fun screenWidth(): Int {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.width()
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            dm.widthPixels
        }
    }

    private fun screenHeight(): Int {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            windowManager.currentWindowMetrics.bounds.height()
        } else {
            val dm = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(dm)
            dm.heightPixels
        }
    }

    private class BubbleView(context: Context) : View(context) {

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#CC9B6D")
            style = Paint.Style.FILL
            setShadowLayer(12f, 0f, 4f, Color.argb(80, 0, 0, 0))
        }

        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 28f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }

        private var tapScale = 1f

        init {
            setLayerType(LAYER_TYPE_SOFTWARE, null) // needed for shadow
        }

        override fun onDraw(canvas: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val radius = (width / 2f - 8f) * tapScale

            canvas.drawCircle(cx, cy, radius, bgPaint)

            // Draw a simple play/robot icon using text
            val textY = cy - (iconPaint.descent() + iconPaint.ascent()) / 2
            canvas.drawText("\u25B6", cx, textY, iconPaint) // ▶ play symbol
        }

        fun showTapFeedback() {
            val anim = ValueAnimator.ofFloat(1f, 0.85f, 1f).apply {
                duration = 200
                addUpdateListener {
                    tapScale = it.animatedValue as Float
                    invalidate()
                }
            }
            anim.start()
        }
    }

    private class DismissZoneView(context: Context) : View(context) {

        private var highlighted = false

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(60, 255, 59, 48)
            style = Paint.Style.FILL
        }

        private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(120, 255, 59, 48)
            style = Paint.Style.FILL
        }

        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 36f
            textAlign = Paint.Align.CENTER
        }

        fun setHighlighted(value: Boolean) {
            if (highlighted != value) {
                highlighted = value
                invalidate()
            }
        }

        override fun onDraw(canvas: Canvas) {
            val paint = if (highlighted) highlightPaint else bgPaint
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
            // Draw X icon
            val cx = width / 2f
            val cy = height / 2f
            val textY = cy - (iconPaint.descent() + iconPaint.ascent()) / 2
            iconPaint.alpha = if (highlighted) 255 else 180
            canvas.drawText("\u2715", cx, textY, iconPaint) // ✕
        }
    }

    companion object {
        private const val TAG = "FloatingBubble"
        private const val BUBBLE_SIZE = 140 // px
        private const val DISMISS_ZONE_HEIGHT = 200 // px

        @Volatile
        private var instance: FloatingBubble? = null

        fun getInstance(context: Context): FloatingBubble {
            return instance ?: synchronized(this) {
                instance ?: FloatingBubble(context.applicationContext).also { instance = it }
            }
        }
    }
}
