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

    fun saveApiKey(apiKey: String) {
        sharedPreferences.edit().putString(KEY_API_KEY, apiKey).apply()
    }

    fun getApiKey(): String? {
        return sharedPreferences.getString(KEY_API_KEY, null)
    }

    fun hasApiKey(): Boolean {
        return !getApiKey().isNullOrEmpty()
    }

    fun clearApiKey() {
        sharedPreferences.edit().remove(KEY_API_KEY).apply()
    }

    fun isValidApiKey(apiKey: String): Boolean {
        return apiKey.startsWith("sk-ant-api") && apiKey.length > 20
    }

    fun saveModel(model: String) {
        sharedPreferences.edit().putString(KEY_MODEL, model).apply()
    }

    fun getModel(): String {
        return sharedPreferences.getString(KEY_MODEL, "gemini-2.0-flash-exp") ?: "gemini-2.0-flash-exp"
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

    fun setVerificationEnabled(enabled: Boolean) {
        sharedPreferences.edit().putBoolean(KEY_VERIFICATION_ENABLED, enabled).apply()
    }

    fun getVerificationEnabled(): Boolean {
        return sharedPreferences.getBoolean(KEY_VERIFICATION_ENABLED, true)
    }

    companion object {
        private const val KEY_API_KEY = "claude_api_key"
        private const val KEY_MODEL = "claude_model"
        private const val KEY_SCREENSHOT_QUALITY = "screenshot_quality"
        private const val KEY_LAST_UPLOAD_SPEED = "last_upload_speed"
        private const val KEY_GOOGLE_VISION_API_KEY = "google_vision_api_key"
        private const val KEY_REPLICATE_API_TOKEN = "replicate_api_token"
        private const val KEY_OCR_ENABLED = "ocr_enabled"
        private const val KEY_VERIFICATION_ENABLED = "verification_enabled"

        @Volatile
        private var INSTANCE: SecureStorage? = null

        fun getInstance(context: Context): SecureStorage {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SecureStorage(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
