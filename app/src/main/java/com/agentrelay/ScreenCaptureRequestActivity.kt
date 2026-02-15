package com.agentrelay

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Transparent activity that requests MediaProjection permission,
 * starts ScreenCaptureService, and optionally kicks off an agent task.
 *
 * Launched from overlay / service contexts that don't have an Activity
 * for startActivityForResult.
 */
class ScreenCaptureRequestActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // If screen capture is already available, skip the permission request
        if (ScreenCaptureService.instance != null) {
            Log.d(TAG, "Screen capture already active, starting task directly")
            startPendingTask()
            finish()
            return
        }

        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_CODE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == REQUEST_CODE && resultCode == RESULT_OK && data != null) {
            // Start the capture service with the projection token
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_INIT_PROJECTION
                putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, resultCode)
                putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
            }
            startForegroundService(serviceIntent)
            Toast.makeText(this, "Screen capture enabled!", Toast.LENGTH_SHORT).show()

            // Give the service a moment to initialize, then start the task
            window.decorView.postDelayed({
                startPendingTask()
                finish()
            }, 500)
        } else {
            Log.w(TAG, "Screen capture permission denied")
            Toast.makeText(this, "Screen capture permission required", Toast.LENGTH_SHORT).show()

            // Still try to start the task in accessibility-only mode
            startPendingTask()
            finish()
        }
    }

    private fun startPendingTask() {
        val task = intent?.getStringExtra(EXTRA_TASK) ?: return
        Log.d(TAG, "Starting pending task: $task")
        TaskHistory.addTask(this, task)
        CoroutineScope(Dispatchers.Main).launch {
            AgentOrchestrator.getInstance(applicationContext).startTask(task)
        }
    }

    companion object {
        private const val TAG = "ScreenCaptureRequest"
        private const val REQUEST_CODE = 9001
        const val EXTRA_TASK = "extra_task"

        /**
         * Launch this activity to ensure screen capture is available, then start the given task.
         */
        fun launch(context: Context, task: String) {
            val intent = Intent(context, ScreenCaptureRequestActivity::class.java).apply {
                putExtra(EXTRA_TASK, task)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
