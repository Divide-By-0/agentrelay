package com.agentrelay

import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.*

/**
 * Pre-fetches and caches slow device context (installed apps list) so it's
 * available instantly when a task starts. The app list is refreshed in the
 * background on service connect and can be manually refreshed.
 */
object DeviceContextCache {

    private const val TAG = "DeviceContextCache"

    @Volatile
    var installedApps: List<AppInfo> = emptyList()
        private set

    @Volatile
    var lastRefreshTime: Long = 0
        private set

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var refreshJob: Job? = null

    /**
     * Kicks off a background refresh of the installed apps list.
     * Safe to call multiple times — deduplicates concurrent requests.
     */
    fun refreshAsync(context: Context) {
        if (refreshJob?.isActive == true) return
        refreshJob = scope.launch {
            try {
                val t0 = System.currentTimeMillis()
                val apps = queryInstalledApps(context)
                installedApps = apps
                lastRefreshTime = System.currentTimeMillis()
                val elapsed = lastRefreshTime - t0
                Log.d(TAG, "Cached ${apps.size} installed apps in ${elapsed}ms")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to refresh app list", e)
            }
        }
    }

    /**
     * Blocking fetch — use only from a coroutine / IO thread.
     * Returns cached list if fresh (< 5 min), otherwise re-queries.
     */
    suspend fun getInstalledApps(context: Context): List<AppInfo> {
        val age = System.currentTimeMillis() - lastRefreshTime
        if (installedApps.isNotEmpty() && age < 5 * 60 * 1000) {
            return installedApps
        }
        return withContext(Dispatchers.IO) {
            val apps = queryInstalledApps(context)
            installedApps = apps
            lastRefreshTime = System.currentTimeMillis()
            apps
        }
    }

    private fun queryInstalledApps(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN, null)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)
        val resolveInfos = pm.queryIntentActivities(intent, 0)
        return resolveInfos.map { ri ->
            AppInfo(
                name = ri.loadLabel(pm).toString(),
                packageName = ri.activityInfo.packageName
            )
        }.distinctBy { it.packageName }.sortedBy { it.name.lowercase() }
    }
}
