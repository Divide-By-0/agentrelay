package com.agentrelay

import android.content.Context
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

/**
 * Records the screen during agent trajectories.
 *
 * Android 14+ restricts MediaProjection to a single VirtualDisplay, so we
 * cannot create our own.  Instead we borrow the existing VirtualDisplay from
 * ScreenCaptureService and swap its surface between the MediaRecorder (for
 * recording) and the ImageReader (for screenshots).
 *
 * Call [swapToImageReader] before every screenshot capture, then [swapToRecorder]
 * afterwards to resume recording.
 */
class ScreenRecorder(
    private val context: Context,
    private val virtualDisplay: VirtualDisplay,
    private val imageReaderSurface: android.view.Surface,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val screenDensity: Int
) {
    private var mediaRecorder: MediaRecorder? = null
    @Volatile private var isRecording = false
    private var currentFile: File? = null
    private var recordingStartMs: Long = 0L
    private var recordWidth: Int = 0
    private var recordHeight: Int = 0

    fun startRecording(): Boolean {
        if (isRecording) return true
        return try {
            val outputFile = createOutputFile()
            currentFile = outputFile
            Log.d(TAG, "Preparing MediaRecorder, output: ${outputFile.absolutePath}")

            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }

            // Use lower resolution to save space/CPU
            recordWidth = (screenWidth * 0.5).toInt().let { it - (it % 2) }
            recordHeight = (screenHeight * 0.5).toInt().let { it - (it % 2) }
            Log.d(TAG, "Recording dimensions: ${recordWidth}x${recordHeight}")

            recorder.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(recordWidth, recordHeight)
                setVideoFrameRate(15)
                setVideoEncodingBitRate(2_000_000)
                setOutputFile(outputFile.absolutePath)
                prepare()
            }
            Log.d(TAG, "MediaRecorder prepared")

            // Point the shared VirtualDisplay at the recorder surface
            virtualDisplay.resize(recordWidth, recordHeight, screenDensity)
            virtualDisplay.setSurface(recorder.surface)
            Log.d(TAG, "VirtualDisplay surface swapped to MediaRecorder")

            recorder.start()
            mediaRecorder = recorder
            isRecording = true
            recordingStartMs = System.currentTimeMillis()
            Log.d(TAG, "Screen recording started successfully")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start screen recording", e)
            // Restore ImageReader surface on failure
            try {
                virtualDisplay.resize(screenWidth, screenHeight, screenDensity)
                virtualDisplay.setSurface(imageReaderSurface)
            } catch (_: Exception) {}
            try { mediaRecorder?.release() } catch (_: Exception) {}
            mediaRecorder = null
            deleteIfEmpty(currentFile)
            false
        }
    }

    /**
     * Temporarily swap the VirtualDisplay to the ImageReader surface so
     * ScreenCaptureService can grab a screenshot.  Call [swapToRecorder]
     * when done.
     */
    fun swapToImageReader() {
        if (!isRecording) return
        try {
            virtualDisplay.resize(screenWidth, screenHeight, screenDensity)
            virtualDisplay.setSurface(imageReaderSurface)
        } catch (e: Exception) {
            Log.w(TAG, "swapToImageReader failed", e)
        }
    }

    /**
     * Swap back to the MediaRecorder surface after a screenshot capture.
     */
    fun swapToRecorder() {
        if (!isRecording) return
        val recorder = mediaRecorder ?: return
        try {
            virtualDisplay.resize(recordWidth, recordHeight, screenDensity)
            virtualDisplay.setSurface(recorder.surface)
        } catch (e: Exception) {
            Log.w(TAG, "swapToRecorder failed", e)
        }
    }

    @Synchronized
    fun stopRecording(): File? {
        if (!isRecording) return null
        isRecording = false
        Log.d(TAG, "Stopping screen recording...")

        val recorder = mediaRecorder
        val file = currentFile
        mediaRecorder = null
        currentFile = null
        val elapsedMs = System.currentTimeMillis() - recordingStartMs
        recordingStartMs = 0L
        Log.d(TAG, "Recording duration: ${elapsedMs}ms")

        // Brief minimum run time so encoder emits at least one GOP
        if (elapsedMs in 1 until 800) {
            try { Thread.sleep(800 - elapsedMs) } catch (_: InterruptedException) {}
        }

        // Stop & release MediaRecorder
        try {
            recorder?.stop()
            Log.d(TAG, "MediaRecorder.stop() succeeded")
        } catch (e: Exception) {
            Log.w(TAG, "MediaRecorder.stop() failed: ${e.message}")
        }
        try { recorder?.release() } catch (_: Exception) {}

        // Restore VirtualDisplay to ImageReader surface & full resolution
        try {
            virtualDisplay.resize(screenWidth, screenHeight, screenDensity)
            virtualDisplay.setSurface(imageReaderSurface)
            Log.d(TAG, "VirtualDisplay surface restored to ImageReader")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore ImageReader surface", e)
        }

        // Check if we got a usable file
        if (file != null && file.exists()) {
            val size = file.length()
            if (size > 0L) {
                Log.d(TAG, "Screen recording saved: ${file.absolutePath} ($size bytes)")
                return file
            } else {
                Log.w(TAG, "Deleting 0-byte recording: ${file.absolutePath}")
                file.delete()
            }
        } else {
            Log.w(TAG, "Recording file missing: ${file?.absolutePath}")
        }
        return null
    }

    fun isRecording(): Boolean = isRecording

    private fun deleteIfEmpty(file: File?) {
        if (file != null && file.exists() && file.length() == 0L) {
            file.delete()
        }
    }

    private fun createOutputFile(): File {
        val dir = getRecordingsDir()
        dir.mkdirs()
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        return File(dir, "agent_recording_$timestamp.mp4")
    }

    companion object {
        private const val TAG = "ScreenRecorder"

        fun getRecordingsDir(): File {
            return File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "AgentRelay"
            )
        }
    }
}
