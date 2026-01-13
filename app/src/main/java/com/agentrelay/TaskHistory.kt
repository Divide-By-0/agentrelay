package com.agentrelay

import android.content.Context
import android.content.SharedPreferences

object TaskHistory {
    private const val PREFS_NAME = "agent_task_history"
    private const val KEY_TASKS = "recent_tasks"
    private const val KEY_LAST_TASK = "last_task"
    private const val MAX_HISTORY = 10

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun addTask(context: Context, task: String) {
        val prefs = getPrefs(context)
        val currentTasks = getTasks(context).toMutableList()

        // Remove if already exists (move to front)
        currentTasks.remove(task)

        // Add to front
        currentTasks.add(0, task)

        // Keep only last MAX_HISTORY items
        val tasksToSave = currentTasks.take(MAX_HISTORY)

        prefs.edit()
            .putStringSet(KEY_TASKS, tasksToSave.toSet())
            .putString(KEY_LAST_TASK, task)
            .apply()
    }

    fun getTasks(context: Context): List<String> {
        val prefs = getPrefs(context)
        val tasksSet = prefs.getStringSet(KEY_TASKS, emptySet()) ?: emptySet()
        return tasksSet.toList().sortedBy { task ->
            // This is a simple ordering - you might want to add timestamps for better ordering
            tasksSet.indexOf(task)
        }
    }

    fun getLastTask(context: Context): String? {
        return getPrefs(context).getString(KEY_LAST_TASK, null)
    }

    fun clear(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
}
