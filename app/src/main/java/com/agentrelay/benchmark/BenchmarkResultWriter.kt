package com.agentrelay.benchmark

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import java.io.File

object BenchmarkResultWriter {
    private const val TAG = "BenchmarkResultWriter"
    private const val RESULT_FILE = "benchmark_result.json"

    private var context: Context? = null
    private var taskId: String? = null
    private var task: String? = null
    private var startTimeMs: Long = 0

    fun prepare(ctx: Context, taskId: String, task: String) {
        this.context = ctx.applicationContext
        this.taskId = taskId
        this.task = task
        this.startTimeMs = System.currentTimeMillis()
        // Delete any previous result file
        File(ctx.filesDir, RESULT_FILE).delete()
        Log.d(TAG, "Prepared for task_id=$taskId")
    }

    fun writeResult(status: String, iterations: Int, message: String) {
        val ctx = context ?: return
        val id = taskId ?: return
        val t = task ?: return

        val durationMs = System.currentTimeMillis() - startTimeMs
        val result = mapOf(
            "task_id" to id,
            "task" to t,
            "status" to status,
            "duration_ms" to durationMs,
            "iterations" to iterations,
            "final_message" to message
        )

        try {
            val json = Gson().toJson(result)
            File(ctx.filesDir, RESULT_FILE).writeText(json)
            Log.d(TAG, "Result written: $json")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write result", e)
        }

        // Reset state
        context = null
        taskId = null
        task = null
    }
}
