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
        private const val KEY_TRANSCRIPTION_ENGINE = "transcription_engine"
        private const val KEY_AUTO_FALLBACK = "auto_fallback_offline"
        private const val KEY_WHISPER_MODEL = "whisper_model"
        private const val KEY_USE_HEADPHONE_MIC = "use_headphone_mic"
        private const val KEY_SHOW_NOTE_BUBBLE = "show_note_bubble"
        private const val KEY_NOTE_APP_PACKAGE = "note_app_package"
        private const val KEY_SIMULATE_TYPING = "simulate_typing"

        // Default values
        const val DEFAULT_VAD_SENSITIVITY = 0.5f // 0.3-0.8 scale
        const val DEFAULT_AUTO_PUNCTUATION = true
        const val DEFAULT_SMART_FORMAT = true
        const val DEFAULT_LANGUAGE = "en"
        const val DEFAULT_MODEL = "nova-2"
        const val DEFAULT_ENDPOINTING_MS = 300

        // Engine IDs
        const val ENGINE_DEEPGRAM = "deepgram"
        const val ENGINE_VOSK = "vosk"
        const val ENGINE_SHERPA_ONNX = "sherpa-onnx"

        // Whisper model IDs
        const val WHISPER_TINY = "whisper-tiny"
        const val WHISPER_BASE = "whisper-base"
        const val WHISPER_SMALL = "whisper-small"
        const val DEFAULT_WHISPER_MODEL = WHISPER_TINY
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
     * @return Sensitivity value from 0.3 (least sensitive) to 0.8 (most sensitive)
     */
    fun getVadSensitivity(): Float {
        return prefs.getFloat(KEY_VAD_SENSITIVITY, DEFAULT_VAD_SENSITIVITY)
    }

    /**
     * Sets the VAD sensitivity.
     * @param sensitivity Value from 0.3 to 0.8
     */
    fun setVadSensitivity(sensitivity: Float) {
        val clampedValue = sensitivity.coerceIn(0.3f, 0.8f)
        prefs.edit().putFloat(KEY_VAD_SENSITIVITY, clampedValue).apply()
    }

    /**
     * Converts VAD sensitivity (0.3-0.8) to speech threshold for VoiceActivityDetector.
     * Higher sensitivity = lower threshold (easier to trigger)
     * @return Speech threshold value
     */
    fun getSpeechThreshold(): Int {
        val sensitivity = getVadSensitivity()
        // Map sensitivity (0.3-0.8) to threshold (800-200)
        // Higher sensitivity = lower threshold
        val normalized = (sensitivity - 0.3f) / 0.5f // 0.0 to 1.0
        return (800 - (normalized * 600)).toInt().coerceIn(200, 800)
    }

    /**
     * Converts VAD sensitivity to silence threshold for VoiceActivityDetector.
     * @return Silence threshold value
     */
    fun getSilenceThreshold(): Int {
        val sensitivity = getVadSensitivity()
        // Map sensitivity (0.3-0.8) to threshold (400-100)
        val normalized = (sensitivity - 0.3f) / 0.5f // 0.0 to 1.0
        return (400 - (normalized * 300)).toInt().coerceIn(100, 400)
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

    // ========== Transcription Engine ==========

    /**
     * Gets the preferred transcription engine.
     * @return Engine ID ("deepgram", "vosk", or "sherpa-onnx")
     */
    fun getTranscriptionEngine(): String {
        return prefs.getString(KEY_TRANSCRIPTION_ENGINE, ENGINE_DEEPGRAM) ?: ENGINE_DEEPGRAM
    }

    /**
     * Sets the preferred transcription engine.
     * @param engineId Engine ID
     */
    fun setTranscriptionEngine(engineId: String) {
        prefs.edit().putString(KEY_TRANSCRIPTION_ENGINE, engineId).apply()
    }

    /**
     * Gets whether auto-fallback to offline is enabled.
     * @return true if should fallback to offline when no internet
     */
    fun getAutoFallback(): Boolean {
        return prefs.getBoolean(KEY_AUTO_FALLBACK, true)
    }

    /**
     * Sets whether to auto-fallback to offline mode.
     * @param enabled true to enable auto-fallback
     */
    fun setAutoFallback(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_FALLBACK, enabled).apply()
    }

    // ========== Whisper Model ==========

    /**
     * Gets the selected Whisper model variant.
     * @return Model ID ("whisper-tiny", "whisper-base", or "whisper-small")
     */
    fun getWhisperModel(): String {
        return prefs.getString(KEY_WHISPER_MODEL, DEFAULT_WHISPER_MODEL) ?: DEFAULT_WHISPER_MODEL
    }

    /**
     * Sets the Whisper model variant.
     * @param modelId Model ID (whisper-tiny, whisper-base, whisper-small)
     */
    fun setWhisperModel(modelId: String) {
        prefs.edit().putString(KEY_WHISPER_MODEL, modelId).apply()
    }

    // ========== Headphone Microphone ==========

    /**
     * Gets whether to use headphone microphone instead of phone mic.
     * When enabled, uses DEFAULT audio source which prefers headphone mic.
     * When disabled, uses VOICE_RECOGNITION which prefers phone's built-in mic.
     * @return true if headphone microphone should be used
     */
    fun getUseHeadphoneMic(): Boolean {
        return prefs.getBoolean(KEY_USE_HEADPHONE_MIC, false)
    }

    /**
     * Sets whether to use headphone microphone.
     * @param enabled true to use headphone mic, false for phone mic
     */
    fun setUseHeadphoneMic(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_USE_HEADPHONE_MIC, enabled).apply()
    }

    // ========== Note Bubble ==========

    /**
     * Gets whether to show the note bubble.
     * @return true if the note bubble should be shown
     */
    fun getShowNoteBubble(): Boolean {
        return prefs.getBoolean(KEY_SHOW_NOTE_BUBBLE, false)
    }

    /**
     * Sets whether to show the note bubble.
     * @param enabled true to show note bubble
     */
    fun setShowNoteBubble(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_NOTE_BUBBLE, enabled).apply()
    }

    /**
     * Gets the package name of the configured note-taking app.
     * @return Package name, or null if not configured
     */
    fun getNoteAppPackage(): String? {
        return prefs.getString(KEY_NOTE_APP_PACKAGE, null)
    }

    /**
     * Sets the package name of the note-taking app to use.
     * @param packageName Package name of the app
     */
    fun setNoteAppPackage(packageName: String?) {
        if (packageName == null) {
            prefs.edit().remove(KEY_NOTE_APP_PACKAGE).apply()
        } else {
            prefs.edit().putString(KEY_NOTE_APP_PACKAGE, packageName).apply()
        }
    }

    // ========== Simulate Typing ==========

    /**
     * Gets whether to simulate typing (character-by-character) instead of pasting.
     * When enabled, text is typed character by character which inserts at cursor position.
     * When disabled, uses ACTION_SET_TEXT which replaces entire field content.
     * @return true if simulate typing is enabled
     */
    fun getSimulateTyping(): Boolean {
        return prefs.getBoolean(KEY_SIMULATE_TYPING, false)
    }

    /**
     * Sets whether to simulate typing.
     * @param enabled true to type character-by-character, false to paste
     */
    fun setSimulateTyping(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SIMULATE_TYPING, enabled).apply()
    }
}
