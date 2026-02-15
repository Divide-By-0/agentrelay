package com.agentrelay

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import java.util.zip.CRC32
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val windowMetrics = windowManager.currentWindowMetrics
            val bounds = windowMetrics.bounds
            screenWidth = bounds.width()
            screenHeight = bounds.height()
            screenDensity = resources.configuration.densityDpi
        } else {
            val metrics = DisplayMetrics()
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
            screenWidth = metrics.widthPixels
            screenHeight = metrics.heightPixels
            screenDensity = metrics.densityDpi
        }

        // Get status bar height
        statusBarHeight = getStatusBarHeight()

        Log.d(TAG, "Screen dimensions: ${screenWidth}x${screenHeight}, density: $screenDensity dpi")
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
        val notification = createNotification()

        when (intent?.action) {
            ACTION_INIT_PROJECTION -> {
                // On Android 14+, startForeground with mediaProjection type must only be
                // called when we have the projection token (i.e., user granted permission).
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }
                if (resultCode != 0 && data != null) {
                    // Now we have the projection token - start foreground WITH mediaProjection type
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        startForeground(NOTIFICATION_ID, notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                    } else {
                        startForeground(NOTIFICATION_ID, notification)
                    }
                    Log.d(TAG, "Initializing MediaProjection with permission data")
                    initMediaProjection(resultCode, data)
                    // Show floating bubble if enabled
                    if (SecureStorage.getInstance(this).getFloatingBubbleEnabled()) {
                        FloatingBubble.getInstance(this).show()
                    }
                } else {
                    // No valid projection data - start foreground without mediaProjection type
                    startForegroundSafe(notification)
                    Log.e(TAG, "Invalid MediaProjection data received")
                }
            }
            ACTION_START_AGENT -> {
                startForegroundSafe(notification)
                Log.d(TAG, "Start agent action received")
                OverlayWindow.getInstance(this).show()
            }
            ACTION_STOP_AGENT -> {
                startForegroundSafe(notification)
                Log.d(TAG, "Stop agent action received")
                AgentOrchestrator.getInstance(this).stop()
            }
            null -> {
                // Service started without action - just show notification and wait for projection.
                // Do NOT use mediaProjection type here since we don't have the token yet.
                startForegroundSafe(notification)
                Log.d(TAG, "Service started, waiting for screen capture permission")
            }
        }

        return START_STICKY
    }

    private fun startForegroundSafe(notification: Notification) {
        try {
            // If we already have a projection, we can use the mediaProjection type
            if (mediaProjection != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: SecurityException) {
            // Fallback: start without specifying type
            Log.w(TAG, "startForeground with mediaProjection type failed, trying without type", e)
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (e2: Exception) {
                Log.e(TAG, "startForeground completely failed", e2)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        FloatingBubble.getInstance(this).hide()
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

    /**
     * Lightweight fingerprint of the current screen content.
     * Uses CRC32 of sampled pixel rows — fast enough to poll at ~120ms intervals.
     * Returns null if capture fails.
     */
    fun captureFingerprint(): Long? {
        val reader = imageReader ?: return null
        return try {
            val image = reader.acquireLatestImage() ?: return null
            val planes = image.planes
            val buffer = planes[0].buffer
            val rowStride = planes[0].rowStride

            val crc = CRC32()
            val rowBuf = ByteArray(rowStride)
            // Sample every 40th row for speed (~50 rows on a 2000px screen)
            val step = maxOf(40, screenHeight / 50)
            var row = 0
            while (row < screenHeight) {
                buffer.position(row * rowStride)
                val toRead = minOf(rowStride, buffer.remaining())
                buffer.get(rowBuf, 0, toRead)
                crc.update(rowBuf, 0, toRead)
                row += step
            }
            image.close()
            crc.value
        } catch (e: Exception) {
            Log.w(TAG, "captureFingerprint failed", e)
            null
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
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, screenWidth, screenHeight)
                bitmap.recycle()
                cropped
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert image to bitmap", e)
            null
        }
    }

    internal fun bitmapToBase64WithDimensions(bitmap: Bitmap, actualWidth: Int, actualHeight: Int): ScreenshotInfo {
        val secureStorage = SecureStorage.getInstance(this)
        var quality = secureStorage.getScreenshotQuality()

        // Auto quality based on last upload speed - fine-grained calculation
        if (quality == -1) {
            val lastSpeed = secureStorage.getLastUploadSpeed() // KB/s
            quality = computeAutoQuality(lastSpeed)
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

    /**
     * Captures a screenshot and crops it to the specified screen region.
     * Used for split-screen to get only one app's half.
     */
    suspend fun captureScreenshotCropped(region: android.graphics.Rect): ScreenshotInfo? {
        val reader = imageReader ?: return null
        return try {
            val image = reader.acquireLatestImage() ?: return null
            val fullBitmap = imageToBitmap(image)
            image.close()
            if (fullBitmap == null) return null

            // Scale region from screen coordinates to bitmap coordinates
            val scaleX = fullBitmap.width.toFloat() / screenWidth
            val scaleY = fullBitmap.height.toFloat() / screenHeight
            val cropX = (region.left * scaleX).toInt().coerceIn(0, fullBitmap.width - 1)
            val cropY = (region.top * scaleY).toInt().coerceIn(0, fullBitmap.height - 1)
            val cropW = (region.width() * scaleX).toInt().coerceAtMost(fullBitmap.width - cropX)
            val cropH = (region.height() * scaleY).toInt().coerceAtMost(fullBitmap.height - cropY)

            if (cropW <= 0 || cropH <= 0) {
                fullBitmap.recycle()
                return null
            }

            val cropped = Bitmap.createBitmap(fullBitmap, cropX, cropY, cropW, cropH)
            fullBitmap.recycle()

            val info = bitmapToBase64WithDimensions(cropped, region.width(), region.height())
            cropped.recycle()
            info
        } catch (e: Exception) {
            Log.e(TAG, "Failed to capture cropped screenshot", e)
            null
        }
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

        @androidx.annotation.VisibleForTesting
        internal fun computeAutoQuality(lastSpeedKBps: Float): Int = when {
            lastSpeedKBps == 0f -> 60   // First time, use moderate quality
            lastSpeedKBps > 1000f -> 95 // Very fast (>1 MB/s)
            lastSpeedKBps > 800f -> 90  // Fast (>800 KB/s)
            lastSpeedKBps > 600f -> 85  // Good (>600 KB/s)
            lastSpeedKBps > 400f -> 80  // Above average (>400 KB/s)
            lastSpeedKBps > 300f -> 75  // Average (>300 KB/s)
            lastSpeedKBps > 200f -> 65  // Below average (>200 KB/s)
            lastSpeedKBps > 150f -> 55  // Slow (>150 KB/s)
            lastSpeedKBps > 100f -> 45  // Very slow (>100 KB/s)
            lastSpeedKBps > 50f -> 35   // Extremely slow (>50 KB/s)
            else -> 25                  // Minimal quality for very poor connections (<50 KB/s)
        }
    }
}
