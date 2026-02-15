package com.agentrelay

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureStorage(context: Context) {

    private val sharedPreferences: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        EncryptedSharedPreferences.create(
            context,
            "encrypted_prefs",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // KeyStore can get corrupted after OS updates or backup/restore.
        // Delete the corrupted prefs file and retry once.
        android.util.Log.e("SecureStorage", "EncryptedSharedPreferences failed, resetting", e)
        context.getSharedPreferences("encrypted_prefs", Context.MODE_PRIVATE).edit().clear().apply()
        try {
            val file = java.io.File(context.filesDir?.parent, "shared_prefs/encrypted_prefs.xml")
            if (file.exists()) file.delete()
        } catch (_: Exception) {}
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "encrypted_prefs",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e2: Exception) {
            // Last resort: fall back to unencrypted prefs so the app doesn't crash
            android.util.Log.e("SecureStorage", "Falling back to plain SharedPreferences", e2)
            context.getSharedPreferences("agent_relay_fallback_prefs", Context.MODE_PRIVATE)
        }
    }

    // Per-provider API key storage
    fun saveApiKey(apiKey: String) {
        // Legacy: also saves to old key for backward compat
        sharedPreferences.edit().putString(KEY_API_KEY, apiKey).apply()
    }

    fun getApiKey(): String? {
        return sharedPreferences.getString(KEY_API_KEY, null)
    }

    fun saveClaudeApiKey(key: String) {
        sharedPreferences.edit().putString(KEY_CLAUDE_API_KEY, key).apply()
    }

    fun getClaudeApiKey(): String? {
        // Fall back to legacy key for migration
        return sharedPreferences.getString(KEY_CLAUDE_API_KEY, null)
            ?: sharedPreferences.getString(KEY_API_KEY, null)
    }

    fun saveOpenAIApiKey(key: String) {
        sharedPreferences.edit().putString(KEY_OPENAI_API_KEY, key).apply()
    }

    fun getOpenAIApiKey(): String? {
        return sharedPreferences.getString(KEY_OPENAI_API_KEY, null)
    }

    fun saveGeminiApiKey(key: String) {
        sharedPreferences.edit().putString(KEY_GEMINI_API_KEY, key).apply()
    }

    fun getGeminiApiKey(): String? {
        return sharedPreferences.getString(KEY_GEMINI_API_KEY, null)
    }

    fun getApiKeyForModel(model: String): String? {
        return when (providerForModel(model)) {
            Provider.CLAUDE -> getClaudeApiKey()
            Provider.OPENAI -> getOpenAIApiKey()
            Provider.GEMINI -> getGeminiApiKey()
        }
    }

    fun hasApiKeyForModel(model: String): Boolean {
        return !getApiKeyForModel(model).isNullOrEmpty()
    }

    fun hasApiKey(): Boolean {
        return !getClaudeApiKey().isNullOrEmpty()
            || !getOpenAIApiKey().isNullOrEmpty()
            || !getGeminiApiKey().isNullOrEmpty()
    }

    fun clearApiKey() {
        sharedPreferences.edit()
            .remove(KEY_API_KEY)
            .remove(KEY_CLAUDE_API_KEY)
            .remove(KEY_OPENAI_API_KEY)
            .remove(KEY_GEMINI_API_KEY)
            .apply()
    }

    fun isValidApiKey(apiKey: String): Boolean {
        return isValidClaudeKey(apiKey) || isValidOpenAIKey(apiKey) || isValidGeminiKey(apiKey)
    }

    fun isValidClaudeKey(key: String): Boolean {
        return key.startsWith("sk-ant-") && key.length > 20
    }

    fun isValidOpenAIKey(key: String): Boolean {
        return key.startsWith("sk-") && !key.startsWith("sk-ant-") && key.length > 20
    }

    fun isValidGeminiKey(key: String): Boolean {
        return key.startsWith("AIza") && key.length > 20
    }

    enum class Provider { CLAUDE, OPENAI, GEMINI }

    fun saveModel(model: String) {
        sharedPreferences.edit().putString(KEY_MODEL, model).apply()
    }

    fun getModel(): String {
        return sharedPreferences.getString(KEY_MODEL, "claude-haiku-4-5-20251001") ?: "claude-haiku-4-5-20251001"
    }

    fun saveScreenshotQuality(quality: Int) {
        sharedPreferences.edit().putInt(KEY_SCREENSHOT_QUALITY, quality).apply()
    }

    fun getScreenshotQuality(): Int {
        return sharedPreferences.getInt(KEY_SCREENSHOT_QUALITY, -1) // Default -1 = auto
    }

    fun saveLastUploadTime(bytes: Int, milliseconds: Long) {
        // Calculate KB/s
        val kbps = (bytes.toFloat() / 1024f) / (milliseconds.toFloat() / 1000f)
        sharedPreferences.edit().putFloat(KEY_LAST_UPLOAD_SPEED, kbps).apply()
    }

    fun getLastUploadSpeed(): Float {
        return sharedPreferences.getFloat(KEY_LAST_UPLOAD_SPEED, 0f)
    }

    fun saveGoogleVisionApiKey(key: String) {
        sharedPreferences.edit().putString(KEY_GOOGLE_VISION_API_KEY, key).apply()
    }

    fun getGoogleVisionApiKey(): String? {
        return sharedPreferences.getString(KEY_GOOGLE_VISION_API_KEY, null)
    }

    fun saveReplicateApiToken(token: String) {
        sharedPreferences.edit().putString(KEY_REPLICATE_API_TOKEN, token).apply()
    }

    fun getReplicateApiToken(): String? {
        return sharedPreferences.getString(KEY_REPLICATE_API_TOKEN, null)
    }

    fun setOcrEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_OCR_ENABLED, enabled).apply()
    }

    fun getOcrEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_OCR_ENABLED, false) // Default off until keys configured
    }

    fun setFloatingBubbleEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_FLOATING_BUBBLE, enabled).apply()
    }

    fun getFloatingBubbleEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_FLOATING_BUBBLE, true)
    }

    fun setVerificationEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_VERIFICATION_ENABLED, enabled).apply()
    }

    fun getVerificationEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_VERIFICATION_ENABLED, true)
    }

    fun setPlanningEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_PLANNING_ENABLED, enabled).apply()
    }

    fun getPlanningEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_PLANNING_ENABLED, true)
    }

    fun savePlanningModel(model: String) {
        sharedPreferences.edit().putString(KEY_PLANNING_MODEL, model).apply()
    }

    fun getPlanningModel(): String {
        return sharedPreferences.getString(KEY_PLANNING_MODEL, "claude-opus-4-6") ?: "claude-opus-4-6"
    }

    companion object {
        private const val KEY_API_KEY = "claude_api_key" // legacy
        private const val KEY_CLAUDE_API_KEY = "claude_api_key_v2"
        private const val KEY_OPENAI_API_KEY = "openai_api_key"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_MODEL = "claude_model"
        private const val KEY_SCREENSHOT_QUALITY = "screenshot_quality"
        private const val KEY_LAST_UPLOAD_SPEED = "last_upload_speed"
        private const val KEY_GOOGLE_VISION_API_KEY = "google_vision_api_key"
        private const val KEY_REPLICATE_API_TOKEN = "replicate_api_token"
        private const val KEY_OCR_ENABLED = "ocr_enabled"
        private const val KEY_VERIFICATION_ENABLED = "verification_enabled"
        private const val KEY_FLOATING_BUBBLE = "floating_bubble_enabled"
        private const val KEY_PLANNING_ENABLED = "planning_enabled"
        private const val KEY_PLANNING_MODEL = "planning_model"

        fun providerForModel(model: String): Provider {
            return when {
                model.startsWith("claude") -> Provider.CLAUDE
                model.startsWith("gpt") || model.startsWith("o1") || model.startsWith("o3") || model.startsWith("o4") -> Provider.OPENAI
                model.startsWith("gemini") -> Provider.GEMINI
                else -> Provider.CLAUDE
            }
        }

        @Volatile
        private var INSTANCE: SecureStorage? = null

        fun getInstance(context: Context): SecureStorage {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SecureStorage(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
