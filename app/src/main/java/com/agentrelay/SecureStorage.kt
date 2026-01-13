package com.agentrelay

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureStorage(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "encrypted_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

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

    companion object {
        private const val KEY_API_KEY = "claude_api_key"
        private const val KEY_MODEL = "claude_model"
        private const val KEY_SCREENSHOT_QUALITY = "screenshot_quality"
        private const val KEY_LAST_UPLOAD_SPEED = "last_upload_speed"

        @Volatile
        private var INSTANCE: SecureStorage? = null

        fun getInstance(context: Context): SecureStorage {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: SecureStorage(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
