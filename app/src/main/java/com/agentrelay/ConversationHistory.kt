package com.agentrelay

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

data class ScreenshotInfo(
    val base64Data: String,
    val actualWidth: Int,
    val actualHeight: Int,
    val scaledWidth: Int,
    val scaledHeight: Int,
    val statusBarHeight: Int = 0,
    val mediaType: String = "image/jpeg" // "image/jpeg" or "image/png"
)

data class ConversationItem(
    val timestamp: Long,
    val type: ItemType,
    val screenshot: String? = null,
    val screenshotWidth: Int? = null,
    val screenshotHeight: Int? = null,
    val prompt: String? = null,
    val response: String? = null,
    val action: String? = null,
    val actionDescription: String? = null,
    val status: String,
    // Semantic pipeline debug data
    val elementMapText: String? = null,
    val chosenElementId: String? = null,
    val chosenElementText: String? = null,
    val clickX: Int? = null,
    val clickY: Int? = null,
    val annotatedScreenshot: String? = null
) {
    enum class ItemType {
        SCREENSHOT_CAPTURED,
        API_REQUEST,
        API_RESPONSE,
        ACTION_EXECUTED,
        ERROR,
        PLANNING,
        REASONING
    }
}

object ConversationHistoryManager {
    private const val TAG = "ConversationHistory"
    private const val FILE_NAME = "conversation_history.json"
    private const val MAX_ITEMS = 100
    private const val MAX_PERSISTED = 50
    private const val MAX_PERSISTED_SCREENSHOTS = 10

    private val history = java.util.concurrent.CopyOnWriteArrayList<ConversationItem>()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(List<ConversationItem>) -> Unit>()
    private var appContext: Context? = null
    private val gson = Gson()

    fun init(context: Context) {
        appContext = context.applicationContext
        loadFromDisk()
    }

    fun add(item: ConversationItem) {
        history.add(item)
        while (history.size > MAX_ITEMS) {
            history.removeAt(0)
        }
        notifyListeners()
        saveToDisk()
    }

    fun clear() {
        history.clear()
        notifyListeners()
        saveToDisk()
    }

    fun getAll(): List<ConversationItem> = history.toList()

    fun addListener(listener: (List<ConversationItem>) -> Unit) {
        listeners.add(listener)
        listener(history.toList())
    }

    fun removeListener(listener: (List<ConversationItem>) -> Unit) {
        listeners.remove(listener)
    }

    fun saveToDisk() {
        val ctx = appContext ?: return
        try {
            val screenshotDir = File(ctx.filesDir, "screenshots")
            screenshotDir.mkdirs()

            // Find the last 10 items that have screenshots or annotated screenshots
            val itemsWithImages = history.filter { it.screenshot != null || it.annotatedScreenshot != null }
            val recentImageTimestamps = itemsWithImages.takeLast(MAX_PERSISTED_SCREENSHOTS).map { it.timestamp }.toSet()

            // Clean up old screenshot files not in the keep set
            screenshotDir.listFiles()?.forEach { file ->
                val ts = file.nameWithoutExtension.removeSuffix("_annotated").toLongOrNull()
                if (ts != null && ts !in recentImageTimestamps) {
                    file.delete()
                }
            }

            // Save screenshots for items we want to keep
            val toSave = history.takeLast(MAX_PERSISTED).map { item ->
                val keepImage = item.timestamp in recentImageTimestamps
                if (keepImage) {
                    // Save screenshot to file, store filename reference
                    item.screenshot?.let { data ->
                        try {
                            File(screenshotDir, "${item.timestamp}.jpg").writeText(data)
                        } catch (_: Exception) {}
                    }
                    item.annotatedScreenshot?.let { data ->
                        try {
                            File(screenshotDir, "${item.timestamp}_annotated.jpg").writeText(data)
                        } catch (_: Exception) {}
                    }
                    // Store a marker so we know to load from file
                    item.copy(
                        screenshot = if (item.screenshot != null) "file:${item.timestamp}.jpg" else null,
                        annotatedScreenshot = if (item.annotatedScreenshot != null) "file:${item.timestamp}_annotated.jpg" else null
                    )
                } else {
                    item.copy(screenshot = null, annotatedScreenshot = null)
                }
            }
            val json = gson.toJson(toSave)
            File(ctx.filesDir, FILE_NAME).writeText(json)
            Log.d(TAG, "Saved ${toSave.size} items to disk (${recentImageTimestamps.size} with screenshots)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save history", e)
        }
    }

    private fun loadFromDisk() {
        val ctx = appContext ?: return
        try {
            val file = File(ctx.filesDir, FILE_NAME)
            if (!file.exists()) return
            val json = file.readText()
            if (json.isBlank()) return
            val type = object : TypeToken<List<ConversationItem>>() {}.type
            val items: List<ConversationItem> = gson.fromJson(json, type)

            // Restore screenshots from files
            val screenshotDir = File(ctx.filesDir, "screenshots")
            val restored = items.map { item ->
                var screenshot = item.screenshot
                var annotated = item.annotatedScreenshot

                if (screenshot != null && screenshot.startsWith("file:")) {
                    val filename = screenshot.removePrefix("file:")
                    screenshot = try {
                        File(screenshotDir, filename).readText()
                    } catch (_: Exception) { null }
                }
                if (annotated != null && annotated.startsWith("file:")) {
                    val filename = annotated.removePrefix("file:")
                    annotated = try {
                        File(screenshotDir, filename).readText()
                    } catch (_: Exception) { null }
                }

                item.copy(screenshot = screenshot, annotatedScreenshot = annotated)
            }

            history.clear()
            history.addAll(restored)
            Log.d(TAG, "Loaded ${restored.size} items from disk")
            notifyListeners()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load history", e)
        }
    }

    private fun notifyListeners() {
        val snapshot = history.toList()
        listeners.forEach { it(snapshot) }
    }
}
