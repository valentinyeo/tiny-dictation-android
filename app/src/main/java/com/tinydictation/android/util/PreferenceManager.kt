package com.tinydictation.android.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Wrapper for EncryptedSharedPreferences to securely store app settings.
 *
 * Stores sensitive data like API keys using Android's EncryptedSharedPreferences,
 * which provides AES-256 encryption for both keys and values.
 */
class PreferenceManager(context: Context) {

    companion object {
        private const val PREFS_FILE_NAME = "tiny_dictation_secure_prefs"

        // Preference keys
        private const val KEY_API_KEY = "deepgram_api_key"
        private const val KEY_VAD_SENSITIVITY = "vad_sensitivity"
        private const val KEY_AUTO_PUNCTUATION = "auto_punctuation"
        private const val KEY_SMART_FORMAT = "smart_format"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_MODEL = "model"
        private const val KEY_ENDPOINTING_MS = "endpointing_ms"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"

        // Default values
        const val DEFAULT_VAD_SENSITIVITY = 50 // 0-100 scale
        const val DEFAULT_AUTO_PUNCTUATION = true
        const val DEFAULT_SMART_FORMAT = true
        const val DEFAULT_LANGUAGE = "en"
        const val DEFAULT_MODEL = "nova-2"
        const val DEFAULT_ENDPOINTING_MS = 300
    }

    private val prefs: SharedPreferences

    init {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        prefs = EncryptedSharedPreferences.create(
            context,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    // ========== API Key ==========

    /**
     * Gets the stored Deepgram API key.
     * @return The API key, or null if not set
     */
    fun getApiKey(): String? {
        return prefs.getString(KEY_API_KEY, null)
    }

    /**
     * Sets the Deepgram API key.
     * @param key The API key to store
     */
    fun setApiKey(key: String) {
        prefs.edit().putString(KEY_API_KEY, key).apply()
    }

    /**
     * Clears the stored API key.
     */
    fun clearApiKey() {
        prefs.edit().remove(KEY_API_KEY).apply()
    }

    /**
     * Checks if an API key is configured.
     * @return true if an API key is stored
     */
    fun hasApiKey(): Boolean {
        return !getApiKey().isNullOrBlank()
    }

    // ========== VAD Sensitivity ==========

    /**
     * Gets the VAD sensitivity setting.
     * @return Sensitivity value from 0 (least sensitive) to 100 (most sensitive)
     */
    fun getVadSensitivity(): Int {
        return prefs.getInt(KEY_VAD_SENSITIVITY, DEFAULT_VAD_SENSITIVITY)
    }

    /**
     * Sets the VAD sensitivity.
     * @param sensitivity Value from 0 to 100
     */
    fun setVadSensitivity(sensitivity: Int) {
        val clampedValue = sensitivity.coerceIn(0, 100)
        prefs.edit().putInt(KEY_VAD_SENSITIVITY, clampedValue).apply()
    }

    /**
     * Converts VAD sensitivity (0-100) to speech threshold for VoiceActivityDetector.
     * Higher sensitivity = lower threshold (easier to trigger)
     * @return Speech threshold value
     */
    fun getSpeechThreshold(): Int {
        val sensitivity = getVadSensitivity()
        // Map sensitivity (0-100) to threshold (1000-100)
        // Higher sensitivity = lower threshold
        return (1000 - (sensitivity * 9)).coerceIn(100, 1000)
    }

    /**
     * Converts VAD sensitivity to silence threshold for VoiceActivityDetector.
     * @return Silence threshold value
     */
    fun getSilenceThreshold(): Int {
        val sensitivity = getVadSensitivity()
        // Map sensitivity (0-100) to threshold (500-50)
        return (500 - (sensitivity * 4.5)).toInt().coerceIn(50, 500)
    }

    // ========== Auto Punctuation ==========

    /**
     * Gets whether auto-punctuation is enabled.
     * @return true if auto-punctuation is enabled
     */
    fun getAutoPunctuation(): Boolean {
        return prefs.getBoolean(KEY_AUTO_PUNCTUATION, DEFAULT_AUTO_PUNCTUATION)
    }

    /**
     * Sets whether auto-punctuation is enabled.
     * @param enabled true to enable auto-punctuation
     */
    fun setAutoPunctuation(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_PUNCTUATION, enabled).apply()
    }

    // ========== Smart Format ==========

    /**
     * Gets whether smart formatting is enabled.
     * @return true if smart formatting is enabled
     */
    fun getSmartFormat(): Boolean {
        return prefs.getBoolean(KEY_SMART_FORMAT, DEFAULT_SMART_FORMAT)
    }

    /**
     * Sets whether smart formatting is enabled.
     * @param enabled true to enable smart formatting
     */
    fun setSmartFormat(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SMART_FORMAT, enabled).apply()
    }

    // ========== Language ==========

    /**
     * Gets the selected transcription language.
     * @return Language code (e.g., "en", "es", "fr")
     */
    fun getLanguage(): String {
        return prefs.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
    }

    /**
     * Sets the transcription language.
     * @param language Language code
     */
    fun setLanguage(language: String) {
        prefs.edit().putString(KEY_LANGUAGE, language).apply()
    }

    // ========== Model ==========

    /**
     * Gets the selected Deepgram model.
     * @return Model name (e.g., "nova-2", "nova", "enhanced")
     */
    fun getModel(): String {
        return prefs.getString(KEY_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
    }

    /**
     * Sets the Deepgram model.
     * @param model Model name
     */
    fun setModel(model: String) {
        prefs.edit().putString(KEY_MODEL, model).apply()
    }

    // ========== Endpointing ==========

    /**
     * Gets the endpointing duration in milliseconds.
     * This controls how long Deepgram waits after silence to finalize speech.
     * @return Endpointing duration in milliseconds
     */
    fun getEndpointingMs(): Int {
        return prefs.getInt(KEY_ENDPOINTING_MS, DEFAULT_ENDPOINTING_MS)
    }

    /**
     * Sets the endpointing duration.
     * @param ms Duration in milliseconds (typically 100-1000)
     */
    fun setEndpointingMs(ms: Int) {
        val clampedValue = ms.coerceIn(100, 1000)
        prefs.edit().putInt(KEY_ENDPOINTING_MS, clampedValue).apply()
    }

    // ========== Onboarding ==========

    /**
     * Gets whether the user has completed onboarding.
     * @return true if onboarding is completed
     */
    fun isOnboardingCompleted(): Boolean {
        return prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
    }

    /**
     * Marks onboarding as completed.
     */
    fun setOnboardingCompleted() {
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, true).apply()
    }

    // ========== Utility ==========

    /**
     * Builds Deepgram query parameters based on current settings.
     * @return Query parameter string for Deepgram WebSocket URL
     */
    fun buildDeepgramParams(): String {
        return buildString {
            append("model=${getModel()}")
            append("&language=${getLanguage()}")
            append("&punctuate=${getAutoPunctuation()}")
            append("&smart_format=${getSmartFormat()}")
            append("&interim_results=true")
            append("&endpointing=${getEndpointingMs()}")
        }
    }

    /**
     * Clears all stored preferences.
     * Use with caution - this will delete the API key and all settings.
     */
    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
