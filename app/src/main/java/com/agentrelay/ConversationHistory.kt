package com.agentrelay

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
    val status: String
) {
    enum class ItemType {
        SCREENSHOT_CAPTURED,
        API_REQUEST,
        API_RESPONSE,
        ACTION_EXECUTED,
        ERROR
    }
}

object ConversationHistoryManager {
    private val history = java.util.concurrent.CopyOnWriteArrayList<ConversationItem>()
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(List<ConversationItem>) -> Unit>()

    fun add(item: ConversationItem) {
        history.add(item)
        // Keep only last 100 items
        while (history.size > 100) {
            history.removeAt(0)
        }
        notifyListeners()
    }

    fun clear() {
        history.clear()
        notifyListeners()
    }

    fun getAll(): List<ConversationItem> = history.toList()

    fun addListener(listener: (List<ConversationItem>) -> Unit) {
        listeners.add(listener)
        listener(history.toList())
    }

    fun removeListener(listener: (List<ConversationItem>) -> Unit) {
        listeners.remove(listener)
    }

    private fun notifyListeners() {
        val snapshot = history.toList()
        listeners.forEach { it(snapshot) }
    }
}
