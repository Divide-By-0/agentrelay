package com.agentrelay

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class ScreenCaptureService : Service() {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())

    private var screenWidth = 1080
    private var screenHeight = 1920
    private var screenDensity = 0
    private var statusBarHeight = 0

    override fun onCreate() {
        super.onCreate()

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics) // Use getRealMetrics for true hardware pixels
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        screenDensity = metrics.densityDpi

        // Get status bar height
        statusBarHeight = getStatusBarHeight()

        Log.d(TAG, "Screen dimensions: ${screenWidth}x${screenHeight}, density: $screenDensity dpi")
        Log.d(TAG, "Density scale: ${metrics.density}, scaledDensity: ${metrics.scaledDensity}")
        Log.d(TAG, "Status bar height: $statusBarHeight px")

        createNotificationChannel()
        instance = this
    }

    private fun getStatusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) {
            resources.getDimensionPixelSize(resourceId)
        } else {
            0
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Always start foreground with notification first
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        when (intent?.action) {
            ACTION_START_AGENT -> {
                Log.d(TAG, "Start agent action received")
                OverlayWindow.getInstance(this).show()
            }
            ACTION_STOP_AGENT -> {
                Log.d(TAG, "Stop agent action received")
                AgentOrchestrator.getInstance(this).stop()
            }
            ACTION_INIT_PROJECTION -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA)
                if (resultCode != 0 && data != null) {
                    Log.d(TAG, "Initializing MediaProjection with permission data")
                    initMediaProjection(resultCode, data)
                } else {
                    Log.e(TAG, "Invalid MediaProjection data received")
                }
            }
            null -> {
                // Service started without action - just show notification and wait for projection
                Log.d(TAG, "Service started, waiting for screen capture permission")
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopCapture()
        instance = null
        serviceScope.cancel()
    }

    private fun initMediaProjection(resultCode: Int, data: Intent) {
        try {
            Log.d(TAG, "Starting MediaProjection initialization...")
            val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            mediaProjection = projectionManager.getMediaProjection(resultCode, data)

            if (mediaProjection == null) {
                Log.e(TAG, "MediaProjection is null after getMediaProjection()")
                return
            }

            // Register callback (required for Android 14+)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped")
                    stopCapture()
                }
            }, null)

            Log.d(TAG, "Creating ImageReader: ${screenWidth}x${screenHeight}")
            imageReader = ImageReader.newInstance(
                screenWidth,
                screenHeight,
                PixelFormat.RGBA_8888,
                2
            )

            Log.d(TAG, "Creating VirtualDisplay...")
            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "AgentRelayCapture",
                screenWidth,
                screenHeight,
                screenDensity,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                null
            )

            if (virtualDisplay == null) {
                Log.e(TAG, "VirtualDisplay is null after creation")
            } else {
                Log.d(TAG, "MediaProjection initialized successfully!")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize media projection", e)
        }
    }

    suspend fun captureScreenshot(): ScreenshotInfo? = suspendCoroutine { continuation ->
        val reader = imageReader
        if (reader == null) {
            Log.e(TAG, "ImageReader not initialized")
            continuation.resume(null)
            return@suspendCoroutine
        }

        try {
            val image = reader.acquireLatestImage()
            if (image == null) {
                Log.e(TAG, "Failed to acquire image")
                continuation.resume(null)
                return@suspendCoroutine
            }

            val bitmap = imageToBitmap(image)
            image.close()

            if (bitmap == null) {
                Log.e(TAG, "Failed to convert image to bitmap")
                continuation.resume(null)
                return@suspendCoroutine
            }

            val actualWidth = screenWidth
            val actualHeight = screenHeight
            val screenshotInfo = bitmapToBase64WithDimensions(bitmap, actualWidth, actualHeight)
            bitmap.recycle()

            continuation.resume(screenshotInfo)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture screenshot", e)
            continuation.resume(null)
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        return try {
            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * screenWidth

            val bitmap = Bitmap.createBitmap(
                screenWidth + rowPadding / pixelStride,
                screenHeight,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)

            if (rowPadding == 0) {
                bitmap
            } else {
                Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert image to bitmap", e)
            null
        }
    }

    private fun addGridOverlay(bitmap: Bitmap): Bitmap {
        val mutableBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(mutableBitmap)

        // Configure paint for grid lines - bright white for visibility on dark backgrounds
        val gridPaint = Paint().apply {
            color = Color.argb(120, 255, 255, 255) // Bright white with moderate opacity
            strokeWidth = 2f
            style = Paint.Style.STROKE
        }

        // Configure paint for text - bright cyan with shadow for visibility on any background
        val textPaint = Paint().apply {
            color = Color.argb(255, 0, 255, 255) // Bright cyan
            textSize = 16f
            isAntiAlias = true
            style = Paint.Style.FILL
            setShadowLayer(3f, 0f, 0f, Color.BLACK) // Dark shadow for contrast
        }

        // Configure background for text numbers - inverted (white) for dark backgrounds
        val textBgPaint = Paint().apply {
            color = Color.argb(180, 255, 255, 255) // Semi-transparent white background
            style = Paint.Style.FILL
        }

        val width = bitmap.width
        val height = bitmap.height

        // Draw grid every 100 pixels
        val gridSpacing = 100

        // Vertical lines with X coordinates
        var x = gridSpacing
        while (x < width) {
            canvas.drawLine(x.toFloat(), 0f, x.toFloat(), height.toFloat(), gridPaint)
            // Draw X coordinate label at top
            val label = x.toString()
            val textWidth = textPaint.measureText(label)
            canvas.drawRect(
                x - textWidth / 2 - 2,
                2f,
                x + textWidth / 2 + 2,
                18f,
                textBgPaint
            )
            canvas.drawText(label, x - textWidth / 2, 14f, textPaint)
            x += gridSpacing
        }

        // Horizontal lines with Y coordinates
        var y = gridSpacing
        while (y < height) {
            canvas.drawLine(0f, y.toFloat(), width.toFloat(), y.toFloat(), gridPaint)
            // Draw Y coordinate label at left
            val label = y.toString()
            val textWidth = textPaint.measureText(label)
            canvas.drawRect(
                2f,
                y - 8f,
                textWidth + 6,
                y + 8f,
                textBgPaint
            )
            canvas.drawText(label, 4f, y + 5f, textPaint)
            y += gridSpacing
        }

        Log.d(TAG, "Added grid overlay with ${width / gridSpacing} x ${height / gridSpacing} cells")

        return mutableBitmap
    }

    private fun bitmapToBase64WithDimensions(bitmap: Bitmap, actualWidth: Int, actualHeight: Int): ScreenshotInfo {
        val secureStorage = SecureStorage.getInstance(this)
        var quality = secureStorage.getScreenshotQuality()

        // Auto quality based on last upload speed - fine-grained calculation
        if (quality == -1) {
            val lastSpeed = secureStorage.getLastUploadSpeed() // KB/s
            quality = when {
                lastSpeed == 0f -> 60 // First time, use moderate quality
                lastSpeed > 1000f -> 95 // Very fast (>1 MB/s)
                lastSpeed > 800f -> 90 // Fast (>800 KB/s)
                lastSpeed > 600f -> 85 // Good (>600 KB/s)
                lastSpeed > 400f -> 80 // Above average (>400 KB/s)
                lastSpeed > 300f -> 75 // Average (>300 KB/s)
                lastSpeed > 200f -> 65 // Below average (>200 KB/s)
                lastSpeed > 150f -> 55 // Slow (>150 KB/s)
                lastSpeed > 100f -> 45 // Very slow (>100 KB/s)
                lastSpeed > 50f -> 35 // Extremely slow (>50 KB/s)
                else -> 25 // Minimal quality for very poor connections (<50 KB/s)
            }
            Log.d(TAG, "Auto quality: lastSpeed=${lastSpeed}KB/s → quality=$quality")
        }

        // Scale down for API efficiency
        val maxDimension = 1920
        val scale = if (bitmap.width > bitmap.height) {
            maxDimension.toFloat() / bitmap.width
        } else {
            maxDimension.toFloat() / bitmap.height
        }.coerceAtMost(1f)

        val scaledWidth = (bitmap.width * scale).toInt()
        val scaledHeight = (bitmap.height * scale).toInt()
        var scaledBitmap = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true)

        // Add numbered gridlines overlay for better coordinate accuracy
        scaledBitmap = addGridOverlay(scaledBitmap)

        val outputStream = ByteArrayOutputStream()
        // Use JPEG for better compression if quality < 100, PNG otherwise
        val mediaType = if (quality < 100) {
            scaledBitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            "image/jpeg"
        } else {
            scaledBitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)
            "image/png"
        }
        scaledBitmap.recycle()

        val bytes = outputStream.toByteArray()
        val base64 = Base64.encodeToString(bytes, Base64.NO_WRAP)

        Log.d(TAG, "Screenshot compressed: quality=$quality, format=$mediaType, size=${bytes.size / 1024}KB")

        return ScreenshotInfo(
            base64Data = base64,
            actualWidth = actualWidth,
            actualHeight = actualHeight,
            scaledWidth = scaledWidth,
            scaledHeight = scaledHeight,
            statusBarHeight = statusBarHeight,
            mediaType = mediaType
        )
    }

    private fun stopCapture() {
        virtualDisplay?.release()
        imageReader?.close()
        mediaProjection?.stop()

        virtualDisplay = null
        imageReader = null
        mediaProjection = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.channel_description)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val startIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_START_AGENT
        }
        val startPendingIntent = PendingIntent.getService(
            this,
            0,
            startIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ACTION_STOP_AGENT
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_play,
                getString(R.string.action_start_agent),
                startPendingIntent
            )
            .addAction(
                android.R.drawable.ic_delete,
                getString(R.string.action_stop_agent),
                stopPendingIntent
            )
            .build()
    }

    companion object {
        private const val TAG = "ScreenCaptureService"
        private const val CHANNEL_ID = "agent_service_channel"
        private const val NOTIFICATION_ID = 1001

        const val ACTION_START_AGENT = "com.agentrelay.START_AGENT"
        const val ACTION_STOP_AGENT = "com.agentrelay.STOP_AGENT"
        const val ACTION_INIT_PROJECTION = "com.agentrelay.INIT_PROJECTION"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"

        @Volatile
        var instance: ScreenCaptureService? = null
            private set
    }
}
