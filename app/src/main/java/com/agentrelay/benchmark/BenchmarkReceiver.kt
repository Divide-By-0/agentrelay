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
        if (ScreenCaptureService.instance != null) {
            // Screen capture already active — start directly
            CoroutineScope(Dispatchers.Main).launch {
                orchestrator.startTask(task)
            }
        } else {
            // Need screen capture permission — launch transparent activity
            ScreenCaptureRequestActivity.launch(context, task)
        }
    }

    private fun handleStopTask(context: Context) {
        Log.d(TAG, "Stopping benchmark task")
        AgentOrchestrator.getInstance(context).stop()
    }

    private fun handleConfigure(context: Context, intent: Intent) {
        val apiKey = intent.getStringExtra("api_key")
        val model = intent.getStringExtra("model")

        val secureStorage = SecureStorage.getInstance(context)

        if (!apiKey.isNullOrBlank()) {
            secureStorage.saveClaudeApiKey(apiKey)
            Log.d(TAG, "API key configured")
        }

        if (!model.isNullOrBlank()) {
            secureStorage.saveModel(model)
            Log.d(TAG, "Model configured: $model")
        }
    }
}
