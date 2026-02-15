package com.agentrelay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.sqrt

class CursorOverlay(private val context: Context) {

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var cursorView: CursorView? = null
    private var isShowing = false

    private var currentX = 0f
    private var currentY = 0f

    /**
     * Make the overlay invisible without removing it from the WindowManager.
     * Used during screenshot capture so the overlay doesn't appear in screenshots.
     */
    fun setInvisible(invisible: Boolean) {
        val action = Runnable {
            cursorView?.visibility = if (invisible) View.INVISIBLE else View.VISIBLE
        }
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            action.run()
        } else {
            cursorView?.post(action)
        }
    }

    fun show() {
        if (isShowing) return

        cursorView = CursorView(context).also { view ->
            val params = WindowManager.LayoutParams(
                CURSOR_SIZE,
                CURSOR_SIZE,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.START
                x = 100
                y = 100
            }

            currentX = 100f
            currentY = 100f

            try {
                windowManager.addView(view, params)
                isShowing = true
                Log.d(TAG, "Cursor overlay shown")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to show cursor overlay", e)
            }
        }
    }

    fun hide() {
        if (!isShowing) return

        cursorView?.let { view ->
            try {
                windowManager.removeView(view)
                isShowing = false
                cursorView = null
                Log.d(TAG, "Cursor overlay hidden")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to hide cursor overlay", e)
            }
        }
    }

    suspend fun moveTo(targetX: Int, targetY: Int, showClick: Boolean = false): Boolean {
        val view = cursorView ?: return false

        return suspendCancellableCoroutine { continuation ->
            val startX = currentX
            val startY = currentY

            // Calculate distance for duration
            val distance = sqrt(
                (targetX - startX) * (targetX - startX) +
                        (targetY - startY) * (targetY - startY)
            )
            val duration = (distance / CURSOR_SPEED * 1000).toLong().coerceIn(100, 1000)

            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                this.duration = duration
                interpolator = DecelerateInterpolator()

                addUpdateListener { animation ->
                    val progress = animation.animatedValue as Float
                    val newX = startX + (targetX - startX) * progress
                    val newY = startY + (targetY - startY) * progress

                    currentX = newX
                    currentY = newY

                    val params = view.layoutParams as WindowManager.LayoutParams
                    params.x = newX.toInt() - CURSOR_SIZE / 2
                    params.y = newY.toInt() - CURSOR_SIZE / 2

                    try {
                        windowManager.updateViewLayout(view, params)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to update cursor position", e)
                    }
                }

                addListener(object : android.animation.Animator.AnimatorListener {
                    override fun onAnimationStart(animation: android.animation.Animator) {}

                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        if (showClick) {
                            view.showClickAnimation()
                        }
                        if (continuation.isActive) {
                            continuation.resume(true)
                        }
                    }

                    override fun onAnimationCancel(animation: android.animation.Animator) {
                        if (continuation.isActive) {
                            continuation.resume(false)
                        }
                    }

                    override fun onAnimationRepeat(animation: android.animation.Animator) {}
                })
            }

            continuation.invokeOnCancellation {
                animator.cancel()
            }

            animator.start()
        }
    }

    private class CursorView(context: Context) : View(context) {

        private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            style = Paint.Style.FILL
            setShadowLayer(8f, 0f, 0f, Color.BLACK)
        }

        private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }

        private var clickAnimProgress = 0f

        init {
            setLayerType(LAYER_TYPE_SOFTWARE, null)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val centerX = width / 2f
            val centerY = height / 2f

            // Draw cursor pointer (arrow shape)
            val path = Path().apply {
                moveTo(centerX, centerY - 10)
                lineTo(centerX - 7, centerY + 5)
                lineTo(centerX, centerY + 2)
                lineTo(centerX + 7, centerY + 5)
                close()
            }

            canvas.drawPath(path, cursorPaint)
            canvas.drawPath(path, outlinePaint)

            // Draw click animation
            if (clickAnimProgress > 0f) {
                val clickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.argb((255 * (1 - clickAnimProgress)).toInt(), 255, 255, 0)
                    style = Paint.Style.STROKE
                    strokeWidth = 3f
                }
                val radius = 15f + clickAnimProgress * 20f
                canvas.drawCircle(centerX, centerY, radius, clickPaint)
            }
        }

        fun showClickAnimation() {
            val animator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 300
                addUpdateListener { animation ->
                    clickAnimProgress = animation.animatedValue as Float
                    invalidate()
                }
                addListener(object : android.animation.Animator.AnimatorListener {
                    override fun onAnimationStart(animation: android.animation.Animator) {}
                    override fun onAnimationEnd(animation: android.animation.Animator) {
                        clickAnimProgress = 0f
                        invalidate()
                    }

                    override fun onAnimationCancel(animation: android.animation.Animator) {}
                    override fun onAnimationRepeat(animation: android.animation.Animator) {}
                })
            }
            animator.start()
        }
    }

    companion object {
        private const val TAG = "CursorOverlay"
        private const val CURSOR_SIZE = 48 // dp
        private const val CURSOR_SPEED = 1000f // pixels per second

        @Volatile
        private var instance: CursorOverlay? = null

        fun getInstance(context: Context): CursorOverlay {
            return instance ?: synchronized(this) {
                instance ?: CursorOverlay(context.applicationContext).also { instance = it }
            }
        }
    }
}
