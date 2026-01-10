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
        private const val KEY_HIDE_BUBBLES = "hide_bubbles"
        private const val KEY_KEYBOARD_THEME = "keyboard_theme"
        private const val KEY_DICTATION_MODE = "dictation_mode"
        private const val KEY_PAUSE_MEDIA_DURING_DICTATION = "pause_media_during_dictation"
        private const val KEY_BAR_ORIENTATION = "bar_orientation"
        private const val KEY_BAR_TRANSPARENCY = "bar_transparency"

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

        // Keyboard theme constants
        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"

        // Dictation mode constants
        const val MODE_BUBBLE = "bubble"              // Floating bubble (default)
        const val MODE_COMPANION = "companion"        // Keyboard companion bar (above system keyboard)
        const val MODE_KEYBOARD = "keyboard"          // Full IME keyboard

        // Bar orientation constants
        const val ORIENTATION_VERTICAL = "vertical"
        const val ORIENTATION_HORIZONTAL = "horizontal"

        // Cloud provider constants
        const val PROVIDER_DEEPGRAM = "deepgram"
        const val PROVIDER_GROQ = "groq"
        const val PROVIDER_OPENAI = "openai"
        const val PROVIDER_ASSEMBLYAI = "assemblyai"
        const val PROVIDER_GEMINI = "gemini"

        // Deepgram model constants
        const val DEEPGRAM_NOVA_3 = "nova-3"
        const val DEEPGRAM_NOVA_2 = "nova-2"
        const val DEEPGRAM_ENHANCED = "enhanced"
        const val DEEPGRAM_BASE = "base"

        // Groq model constants
        const val GROQ_WHISPER_LARGE_V3 = "whisper-large-v3"
        const val GROQ_WHISPER_LARGE_V3_TURBO = "whisper-large-v3-turbo"
        const val GROQ_DISTIL_WHISPER = "distil-whisper-large-v3-en"

        // OpenAI model constants
        const val OPENAI_GPT4O_TRANSCRIBE = "gpt-4o-transcribe"
        const val OPENAI_GPT4O_MINI = "gpt-4o-mini-transcribe"
        const val OPENAI_WHISPER_1 = "whisper-1"

        // New preference keys for multi-provider support
        private const val KEY_GROQ_API_KEY = "groq_api_key"
        private const val KEY_OPENAI_API_KEY = "openai_api_key"
        private const val KEY_ASSEMBLYAI_API_KEY = "assemblyai_api_key"
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_CLOUD_PROVIDER = "cloud_provider"
        private const val KEY_DEEPGRAM_MODEL = "deepgram_model"
        private const val KEY_GROQ_MODEL = "groq_model"
        private const val KEY_OPENAI_MODEL = "openai_model"
        private const val KEY_AUTO_DETECT_LANGUAGE = "auto_detect_language"
        private const val KEY_UNIFIED_PROVIDER = "unified_provider"  // Combined cloud/offline selection
        private const val KEY_FALLBACK_MODEL = "fallback_model"  // Which offline model to use as fallback

        // Fallback model IDs (used when cloud provider fails)
        const val FALLBACK_VOSK_EN = "vosk:en"
        const val FALLBACK_VOSK_DE = "vosk:de"
        const val FALLBACK_WHISPER_TINY = "whisper:tiny"
        const val FALLBACK_WHISPER_BASE = "whisper:base"
        const val FALLBACK_WHISPER_SMALL = "whisper:small"

        // Unified provider IDs (combines cloud providers and offline engines)
        const val UNIFIED_DEEPGRAM = "cloud:deepgram"
        const val UNIFIED_GROQ = "cloud:groq"
        const val UNIFIED_OPENAI = "cloud:openai"
        const val UNIFIED_ASSEMBLYAI = "cloud:assemblyai"
        const val UNIFIED_GEMINI = "cloud:gemini"
        const val UNIFIED_VOSK = "offline:vosk"
        const val UNIFIED_WHISPER = "offline:whisper"
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

    // ========== Hide Bubbles ==========

    /**
     * Gets whether to hide the floating bubbles.
     * Useful when using keyboard mode exclusively.
     * @return true if bubbles should be hidden
     */
    fun getHideBubbles(): Boolean {
        return prefs.getBoolean(KEY_HIDE_BUBBLES, false)
    }

    /**
     * Sets whether to hide the floating bubbles.
     * @param hidden true to hide bubbles
     */
    fun setHideBubbles(hidden: Boolean) {
        prefs.edit().putBoolean(KEY_HIDE_BUBBLES, hidden).apply()
    }

    // ========== Keyboard Theme ==========

    /**
     * Gets the keyboard theme setting.
     * @return Theme ID ("system", "light", or "dark")
     */
    fun getKeyboardTheme(): String {
        return prefs.getString(KEY_KEYBOARD_THEME, THEME_SYSTEM) ?: THEME_SYSTEM
    }

    /**
     * Sets the keyboard theme.
     * @param theme Theme ID (system, light, dark)
     */
    fun setKeyboardTheme(theme: String) {
        prefs.edit().putString(KEY_KEYBOARD_THEME, theme).apply()
    }

    // ========== Dictation Mode ==========

    /**
     * Gets the dictation mode setting.
     * @return Mode ID ("bubble", "companion", or "keyboard")
     */
    fun getDictationMode(): String {
        return prefs.getString(KEY_DICTATION_MODE, MODE_BUBBLE) ?: MODE_BUBBLE
    }

    /**
     * Sets the dictation mode.
     * @param mode Mode ID (bubble, companion, keyboard)
     */
    fun setDictationMode(mode: String) {
        prefs.edit().putString(KEY_DICTATION_MODE, mode).apply()
    }

    // ========== Pause Media During Dictation ==========

    /**
     * Gets whether to pause media playback during dictation.
     * When enabled, requests audio focus which pauses other audio apps (YouTube, Spotify, etc.)
     * @return true if media should be paused during dictation
     */
    fun getPauseMediaDuringDictation(): Boolean {
        return prefs.getBoolean(KEY_PAUSE_MEDIA_DURING_DICTATION, true)
    }

    /**
     * Sets whether to pause media playback during dictation.
     * @param enabled true to pause media during dictation
     */
    fun setPauseMediaDuringDictation(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PAUSE_MEDIA_DURING_DICTATION, enabled).apply()
    }

    // ========== Bar Orientation ==========

    /**
     * Gets the floating bar orientation setting.
     * @return Orientation ID ("vertical" or "horizontal")
     */
    fun getBarOrientation(): String {
        return prefs.getString(KEY_BAR_ORIENTATION, ORIENTATION_VERTICAL) ?: ORIENTATION_VERTICAL
    }

    /**
     * Sets the floating bar orientation.
     * @param orientation Orientation ID (vertical or horizontal)
     */
    fun setBarOrientation(orientation: String) {
        prefs.edit().putString(KEY_BAR_ORIENTATION, orientation).apply()
    }

    // ========== Bar Transparency ==========

    /**
     * Gets the floating bar transparency setting (0-50).
     * 0 means fully opaque, 50 means 50% transparent.
     * @return Transparency percentage (0-50)
     */
    fun getBarTransparency(): Float {
        return prefs.getFloat(KEY_BAR_TRANSPARENCY, 0f)
    }

    /**
     * Sets the floating bar transparency.
     * @param value Transparency percentage (0-50)
     */
    fun setBarTransparency(value: Float) {
        prefs.edit().putFloat(KEY_BAR_TRANSPARENCY, value.coerceIn(0f, 50f)).apply()
    }

    // ========== Cloud Provider ==========

    /**
     * Gets the selected cloud transcription provider.
     * @return Provider ID ("deepgram", "groq", or "openai")
     */
    fun getCloudProvider(): String {
        return prefs.getString(KEY_CLOUD_PROVIDER, PROVIDER_DEEPGRAM) ?: PROVIDER_DEEPGRAM
    }

    /**
     * Sets the cloud transcription provider.
     * @param provider Provider ID
     */
    fun setCloudProvider(provider: String) {
        prefs.edit().putString(KEY_CLOUD_PROVIDER, provider).apply()
    }

    // ========== Groq API Key ==========

    /**
     * Gets the stored Groq API key.
     * @return The API key, or null if not set
     */
    fun getGroqApiKey(): String? {
        return prefs.getString(KEY_GROQ_API_KEY, null)
    }

    /**
     * Sets the Groq API key.
     * @param key The API key to store
     */
    fun setGroqApiKey(key: String) {
        prefs.edit().putString(KEY_GROQ_API_KEY, key).apply()
    }

    /**
     * Checks if a Groq API key is configured.
     * @return true if an API key is stored
     */
    fun hasGroqApiKey(): Boolean {
        return !getGroqApiKey().isNullOrBlank()
    }

    // ========== OpenAI API Key ==========

    /**
     * Gets the stored OpenAI API key.
     * @return The API key, or null if not set
     */
    fun getOpenAIApiKey(): String? {
        return prefs.getString(KEY_OPENAI_API_KEY, null)
    }

    /**
     * Sets the OpenAI API key.
     * @param key The API key to store
     */
    fun setOpenAIApiKey(key: String) {
        prefs.edit().putString(KEY_OPENAI_API_KEY, key).apply()
    }

    /**
     * Checks if an OpenAI API key is configured.
     * @return true if an API key is stored
     */
    fun hasOpenAIApiKey(): Boolean {
        return !getOpenAIApiKey().isNullOrBlank()
    }

    /**
     * Gets the stored AssemblyAI API key.
     * @return The API key, or null if not set
     */
    fun getAssemblyAIApiKey(): String? {
        return prefs.getString(KEY_ASSEMBLYAI_API_KEY, null)
    }

    /**
     * Sets the AssemblyAI API key.
     * @param key The API key to store
     */
    fun setAssemblyAIApiKey(key: String) {
        prefs.edit().putString(KEY_ASSEMBLYAI_API_KEY, key).apply()
    }

    /**
     * Checks if an AssemblyAI API key is configured.
     * @return true if an API key is stored
     */
    fun hasAssemblyAIApiKey(): Boolean {
        return !getAssemblyAIApiKey().isNullOrBlank()
    }

    /**
     * Gets the stored Gemini API key.
     * @return The API key, or null if not set
     */
    fun getGeminiApiKey(): String? {
        return prefs.getString(KEY_GEMINI_API_KEY, null)
    }

    /**
     * Sets the Gemini API key.
     * @param key The API key to store
     */
    fun setGeminiApiKey(key: String) {
        prefs.edit().putString(KEY_GEMINI_API_KEY, key).apply()
    }

    /**
     * Checks if a Gemini API key is configured.
     * @return true if an API key is stored
     */
    fun hasGeminiApiKey(): Boolean {
        return !getGeminiApiKey().isNullOrBlank()
    }

    // ========== Provider Model Selection ==========

    /**
     * Gets the selected Deepgram model.
     * @return Model ID ("nova-3", "nova-2", "enhanced", or "base")
     */
    fun getDeepgramModel(): String {
        return prefs.getString(KEY_DEEPGRAM_MODEL, DEEPGRAM_NOVA_2) ?: DEEPGRAM_NOVA_2
    }

    /**
     * Sets the Deepgram model.
     * @param model Model ID
     */
    fun setDeepgramModel(model: String) {
        prefs.edit().putString(KEY_DEEPGRAM_MODEL, model).apply()
    }

    /**
     * Gets the selected Groq model.
     * @return Model ID
     */
    fun getGroqModel(): String {
        return prefs.getString(KEY_GROQ_MODEL, GROQ_WHISPER_LARGE_V3_TURBO) ?: GROQ_WHISPER_LARGE_V3_TURBO
    }

    /**
     * Sets the Groq model.
     * @param model Model ID
     */
    fun setGroqModel(model: String) {
        prefs.edit().putString(KEY_GROQ_MODEL, model).apply()
    }

    /**
     * Gets the selected OpenAI model.
     * @return Model ID
     */
    fun getOpenAIModel(): String {
        return prefs.getString(KEY_OPENAI_MODEL, OPENAI_GPT4O_TRANSCRIBE) ?: OPENAI_GPT4O_TRANSCRIBE
    }

    /**
     * Sets the OpenAI model.
     * @param model Model ID
     */
    fun setOpenAIModel(model: String) {
        prefs.edit().putString(KEY_OPENAI_MODEL, model).apply()
    }

    // ========== Auto-Detect Language ==========

    /**
     * Gets whether auto language detection is enabled.
     * When enabled, the transcription service will detect the language automatically.
     * @return true if auto-detect is enabled
     */
    fun getAutoDetectLanguage(): Boolean {
        return prefs.getBoolean(KEY_AUTO_DETECT_LANGUAGE, false)
    }

    /**
     * Sets whether to auto-detect language.
     * @param enabled true to enable auto-detection
     */
    fun setAutoDetectLanguage(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_DETECT_LANGUAGE, enabled).apply()
    }

    /**
     * Checks if the current provider/model combination supports automatic language detection.
     * @return true if auto-detect is supported
     */
    fun currentProviderSupportsAutoDetect(): Boolean {
        val unified = getUnifiedProvider()
        return when {
            unified == UNIFIED_VOSK -> false  // Vosk needs specific language models
            unified == UNIFIED_WHISPER -> true  // Whisper auto-detects language
            unified == UNIFIED_GROQ -> true  // All Whisper models support auto-detect
            unified == UNIFIED_OPENAI -> true  // All OpenAI models support auto-detect
            unified == UNIFIED_ASSEMBLYAI -> true  // AssemblyAI auto-detects by default
            unified == UNIFIED_GEMINI -> true  // Gemini auto-detects by default
            unified == UNIFIED_DEEPGRAM -> getDeepgramModel() == DEEPGRAM_NOVA_3  // Only nova-3 supports multilingual
            else -> false
        }
    }

    /**
     * Checks if the current provider needs a manual language selection.
     * Vosk needs language selection (different models per language).
     * Whisper and cloud providers auto-detect or have it built-in.
     * @return true if manual language selection should be shown
     */
    fun currentProviderNeedsLanguageToggle(): Boolean {
        val unified = getUnifiedProvider()
        return when {
            unified == UNIFIED_VOSK -> true  // Vosk needs language-specific models
            unified == UNIFIED_WHISPER -> false  // Whisper auto-detects, no toggle needed
            // For cloud providers, show toggle only if auto-detect is disabled
            unified.startsWith("cloud:") -> !getAutoDetectLanguage() && !currentProviderSupportsAutoDetect()
            else -> true
        }
    }

    // ========== Unified Provider ==========

    /**
     * Gets the unified provider selection (cloud or offline).
     * @return Unified provider ID (e.g., "cloud:deepgram", "offline:vosk")
     */
    fun getUnifiedProvider(): String {
        val stored = prefs.getString(KEY_UNIFIED_PROVIDER, null)
        if (stored != null) return stored

        // Migration: convert old settings to unified format
        val engine = getTranscriptionEngine()
        return when (engine) {
            ENGINE_VOSK -> UNIFIED_VOSK
            ENGINE_SHERPA_ONNX -> UNIFIED_WHISPER
            ENGINE_DEEPGRAM -> {
                // Map cloud provider to unified format
                when (getCloudProvider()) {
                    PROVIDER_GROQ -> UNIFIED_GROQ
                    PROVIDER_OPENAI -> UNIFIED_OPENAI
                    PROVIDER_ASSEMBLYAI -> UNIFIED_ASSEMBLYAI
                    PROVIDER_GEMINI -> UNIFIED_GEMINI
                    else -> UNIFIED_DEEPGRAM
                }
            }
            else -> UNIFIED_DEEPGRAM
        }
    }

    /**
     * Sets the unified provider selection.
     * @param provider Unified provider ID
     */
    fun setUnifiedProvider(provider: String) {
        prefs.edit().putString(KEY_UNIFIED_PROVIDER, provider).apply()

        // Also update legacy settings for backwards compatibility
        if (provider.startsWith("cloud:")) {
            setTranscriptionEngine(ENGINE_DEEPGRAM)
            val cloudProvider = provider.removePrefix("cloud:")
            setCloudProvider(cloudProvider)
        } else if (provider == UNIFIED_VOSK) {
            setTranscriptionEngine(ENGINE_VOSK)
        } else if (provider == UNIFIED_WHISPER) {
            setTranscriptionEngine(ENGINE_SHERPA_ONNX)
        }
    }

    /**
     * Checks if the current provider is a cloud provider.
     * @return true if cloud provider is selected
     */
    fun isCloudProvider(): Boolean {
        return getUnifiedProvider().startsWith("cloud:")
    }

    /**
     * Checks if the current provider is an offline provider.
     * @return true if offline provider is selected
     */
    fun isOfflineProvider(): Boolean {
        return getUnifiedProvider().startsWith("offline:")
    }

    /**
     * Gets the selected fallback model for offline use when cloud fails.
     * @return Fallback model ID (e.g., "vosk:en", "whisper:tiny")
     */
    fun getFallbackModel(): String? {
        return prefs.getString(KEY_FALLBACK_MODEL, null)
    }

    /**
     * Sets the fallback model for offline use.
     * @param model Fallback model ID
     */
    fun setFallbackModel(model: String?) {
        if (model == null) {
            prefs.edit().remove(KEY_FALLBACK_MODEL).apply()
        } else {
            prefs.edit().putString(KEY_FALLBACK_MODEL, model).apply()
        }
    }

    /**
     * Checks if a fallback model is configured.
     * @return true if a fallback model is set
     */
    fun hasFallbackModel(): Boolean {
        return !getFallbackModel().isNullOrBlank()
    }
}
