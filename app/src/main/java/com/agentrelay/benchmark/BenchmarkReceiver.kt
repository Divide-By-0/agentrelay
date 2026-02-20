package com.agentrelay.benchmark

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.agentrelay.AgentOrchestrator
import com.agentrelay.ScreenCaptureRequestActivity
import com.agentrelay.ScreenCaptureService
import com.agentrelay.SecureStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BenchmarkReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BenchmarkReceiver"
        const val ACTION_START_TASK = "com.agentrelay.benchmark.START_TASK"
        const val ACTION_STOP_TASK = "com.agentrelay.benchmark.STOP_TASK"
        const val ACTION_CONFIGURE = "com.agentrelay.benchmark.CONFIGURE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Received action: ${intent.action}")

        when (intent.action) {
            ACTION_START_TASK -> handleStartTask(context, intent)
            ACTION_STOP_TASK -> handleStopTask(context)
            ACTION_CONFIGURE -> handleConfigure(context, intent)
        }
    }

    private fun handleStartTask(context: Context, intent: Intent) {
        val task = intent.getStringExtra("task")
        val taskId = intent.getStringExtra("task_id")
        if (task.isNullOrBlank() || taskId.isNullOrBlank()) {
            Log.e(TAG, "START_TASK missing 'task' or 'task_id' extras")
            return
        }

        Log.d(TAG, "Starting benchmark task: id=$taskId, task=$task")

        // 1. Prepare result writer
        BenchmarkResultWriter.prepare(context, taskId, task)

        // 2. Set onTaskFinished callback on the orchestrator
        val orchestrator = AgentOrchestrator.getInstance(context)
        orchestrator.onTaskFinished = { status, iterations, message ->
            BenchmarkResultWriter.writeResult(status, iterations, message)
        }

        // 3. Start the task — same flow as OverlayWindow.startTaskWithCaptureCheck()
        val captureService = ScreenCaptureService.instance
        if (captureService != null && captureService.hasActiveProjection()) {
            // Screen capture active and healthy — start directly
            Log.d(TAG, "Screen capture active, starting task directly")
            CoroutineScope(Dispatchers.Main).launch {
                orchestrator.startTask(task)
            }
        } else {
            // Screen capture missing or stale — re-request permission
            Log.d(TAG, "Screen capture not active (instance=${captureService != null}), requesting permission")
            ScreenCaptureRequestActivity.launch(context, task)

            // Safety net: ScreenCaptureRequestActivity should start the task after permission is granted,
            // but sometimes the callback chain fails or the dialog isn't auto-approved.
            CoroutineScope(Dispatchers.IO).launch {
                var retriedPermission = false
                // Wait up to 45 seconds for screen capture to become available
                for (attempt in 1..45) {
                    kotlinx.coroutines.delay(1000)
                    val svc = ScreenCaptureService.instance
                    if (svc != null && svc.hasActiveProjection()) {
                        // Screen capture is ready — check if orchestrator is already running
                        if (!orchestrator.isCurrentlyRunning()) {
                            Log.w(TAG, "Safety net: Screen capture active but orchestrator not running after ${attempt}s — starting task")
                            kotlinx.coroutines.withContext(Dispatchers.Main) {
                                orchestrator.startTask(task)
                            }
                        }
                        break
                    }
                    // If screen capture isn't available after 15s, the permission dialog
                    // likely wasn't auto-approved. Re-launch ScreenCaptureRequestActivity.
                    if (attempt == 15 && !retriedPermission) {
                        Log.w(TAG, "Safety net: Screen capture still not active after ${attempt}s — re-requesting permission")
                        retriedPermission = true
                        kotlinx.coroutines.withContext(Dispatchers.Main) {
                            ScreenCaptureRequestActivity.launch(context, task)
                        }
                    }
                }
            }
        }
    }

    private fun handleStopTask(context: Context) {
        Log.d(TAG, "Stopping benchmark task")
        AgentOrchestrator.getInstance(context).stop()
    }

    private fun handleConfigure(context: Context, intent: Intent) {
        val apiKey = intent.getStringExtra("api_key")
        val geminiKey = intent.getStringExtra("gemini_api_key")
        val openaiKey = intent.getStringExtra("openai_api_key")
        val model = intent.getStringExtra("model")

        val secureStorage = SecureStorage.getInstance(context)

        if (!apiKey.isNullOrBlank()) {
            secureStorage.saveClaudeApiKey(apiKey)
            Log.d(TAG, "Claude API key configured")
        }

        if (!geminiKey.isNullOrBlank()) {
            secureStorage.saveGeminiApiKey(geminiKey)
            Log.d(TAG, "Gemini API key configured")
        }

        if (!openaiKey.isNullOrBlank()) {
            secureStorage.saveOpenAIApiKey(openaiKey)
            Log.d(TAG, "OpenAI API key configured")
        }

        if (!model.isNullOrBlank()) {
            secureStorage.saveModel(model)
            Log.d(TAG, "Model configured: $model")
        }

        val planningModel = intent.getStringExtra("planning_model")
        if (!planningModel.isNullOrBlank()) {
            secureStorage.savePlanningModel(planningModel)
            Log.d(TAG, "Planning model configured: $planningModel")
        }

        // Boolean settings: screen_recording, send_screenshots
        if (intent.hasExtra("screen_recording")) {
            val enabled = intent.getStringExtra("screen_recording") == "true"
            secureStorage.setScreenRecordingEnabled(enabled)
            Log.d(TAG, "Screen recording: $enabled")
        }

        if (intent.hasExtra("send_screenshots")) {
            val mode = intent.getStringExtra("send_screenshots") ?: "AUTO"
            secureStorage.setScreenshotMode(
                try { com.agentrelay.models.ScreenshotMode.valueOf(mode.uppercase()) }
                catch (_: Exception) { com.agentrelay.models.ScreenshotMode.AUTO }
            )
            Log.d(TAG, "Screenshot mode: $mode")
        }

        // Google Vision API key for OCR
        val visionKey = intent.getStringExtra("google_vision_api_key")
        if (!visionKey.isNullOrBlank()) {
            secureStorage.saveGoogleVisionApiKey(visionKey)
            Log.d(TAG, "Google Vision API key configured")
        }

        // OCR enabled/disabled
        if (intent.hasExtra("ocr_enabled")) {
            val enabled = intent.getStringExtra("ocr_enabled") == "true"
            secureStorage.setOcrEnabled(enabled)
            Log.d(TAG, "OCR enabled: $enabled")
        }
    }
}
