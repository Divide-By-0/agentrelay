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
        PLANNING
    }
}

object ConversationHistoryManager {
    private const val TAG = "ConversationHistory"
    private const val FILE_NAME = "conversation_history.json"
    private const val MAX_ITEMS = 100
    private const val MAX_PERSISTED = 50

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
            // Persist only the last N items, stripping heavy image data to keep file small
            val toSave = history.takeLast(MAX_PERSISTED).map { item ->
                item.copy(
                    screenshot = null,
                    annotatedScreenshot = null
                )
            }
            val json = gson.toJson(toSave)
            File(ctx.filesDir, FILE_NAME).writeText(json)
            Log.d(TAG, "Saved ${toSave.size} items to disk")
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
            history.clear()
            history.addAll(items)
            Log.d(TAG, "Loaded ${items.size} items from disk")
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
