package com.tinydictation.android.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

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

        private const val KEY_SMART_FORMAT = "smart_format"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_ENDPOINTING_MS = "endpointing_ms"
        private const val KEY_ONBOARDING_COMPLETED = "onboarding_completed"
        private const val KEY_TRANSCRIPTION_ENGINE = "transcription_engine"
        private const val KEY_AUTO_FALLBACK = "auto_fallback_offline"
        private const val KEY_WHISPER_MODEL = "whisper_model"
        private const val KEY_USE_HEADPHONE_MIC = "use_headphone_mic"
        private const val KEY_SIMULATE_TYPING = "simulate_typing"
        private const val KEY_KEYBOARD_THEME = "keyboard_theme"
        private const val KEY_PAUSE_MEDIA_DURING_DICTATION = "pause_media_during_dictation"
        private const val KEY_SILENCE_AUTO_STOP_MS = "silence_auto_stop_ms"
        
        private const val KEY_SHOW_NUMBER_ROW = "show_number_row"
        
        private const val KEY_COMPACT_DICTATION_BAR = "compact_dictation_bar"
        private const val KEY_PUSH_TO_TALK = "push_to_talk_enabled"
        private const val KEY_PANEL_CLAUDE_ENABLED = "panel_claude_enabled"
        private const val KEY_PANEL_SHELL_ENABLED = "panel_shell_enabled"
        private const val KEY_PANEL_SNIPPETS_ENABLED = "panel_snippets_enabled"
        private const val KEY_PANEL_CLIPBOARD_ENABLED = "panel_clipboard_enabled"
        private const val KEY_PANEL_HISTORY_ENABLED = "panel_history_enabled"

        // Dictation bar button visibility keys
        private const val KEY_BTN_SWITCH_BACK = "btn_switch_back_enabled"
        private const val KEY_BTN_SELECT_ALL = "btn_select_all_enabled"
        private const val KEY_BTN_EMOJI = "btn_emoji_enabled"
        private const val KEY_BTN_SPACE = "btn_space_enabled"
        private const val KEY_BTN_LANGUAGE = "btn_language_enabled"
        private const val KEY_BTN_BACKSPACE = "btn_backspace_enabled"
        private const val KEY_BTN_ENTER = "btn_enter_enabled"
        private const val KEY_BTN_KEYBOARD = "btn_keyboard_enabled"
        private const val KEY_BTN_HISTORY = "btn_history_enabled"
        private const val KEY_BTN_IMPROVE = "btn_improve_enabled"

        

        // Default values
        const val DEFAULT_SILENCE_AUTO_STOP_MS = 3000  // 3 seconds default, 0 = disabled
        const val DEFAULT_VAD_SENSITIVITY = 0.5f // 0.3-0.8 scale

        const val DEFAULT_SMART_FORMAT = true
        const val DEFAULT_LANGUAGE = "en"
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
        const val THEME_AMOLED = "amoled"


        // Cloud provider constants
        const val PROVIDER_DEEPGRAM = "deepgram"

        // Deepgram model constants
        const val DEEPGRAM_NOVA_3 = "nova-3"
        const val DEEPGRAM_NOVA_2 = "nova-2"
        const val DEEPGRAM_ENHANCED = "enhanced"
        const val DEEPGRAM_BASE = "base"

        // API key preference keys
        private const val KEY_ANTHROPIC_API_KEY = "anthropic_api_key"
        private const val KEY_DEEPGRAM_MODEL = "deepgram_model"
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
        const val UNIFIED_VOSK = "offline:vosk"
        const val UNIFIED_WHISPER = "offline:whisper"

        // Deepgram feature keys (per-language keywords/replacements, global toggles)
        private const val KEY_DEEPGRAM_NUMERALS = "deepgram_numerals"
        private const val KEY_DEEPGRAM_DICTATION = "deepgram_dictation"
        // Per-language keys use pattern: "deepgram_keywords_$language", "deepgram_replace_$language"
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

    // ========== Anthropic API Key ==========

    /**
     * Gets the Anthropic API key for direct Claude API access.
     * @return The stored API key, or null if not set
     */
    fun getAnthropicApiKey(): String? {
        return prefs.getString(KEY_ANTHROPIC_API_KEY, null)
    }

    /**
     * Sets the Anthropic API key.
     * @param key The API key to store
     */
    fun setAnthropicApiKey(key: String) {
        prefs.edit().putString(KEY_ANTHROPIC_API_KEY, key).apply()
    }

    /**
     * Checks if an Anthropic API key is configured.
     * @return true if an API key is stored
     */
    fun hasAnthropicApiKey(): Boolean {
        return !getAnthropicApiKey().isNullOrBlank()
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
            unified == UNIFIED_DEEPGRAM -> getDeepgramModel() == DEEPGRAM_NOVA_3  // Only nova-3 supports multilingual
            else -> false
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

    // ========== Silence Auto-Stop ==========

    /**
     * Gets the silence auto-stop timeout in milliseconds.
     * When recording and silence is detected for this duration, recording stops automatically.
     * @return Timeout in milliseconds (2000, 3000, 5000), or 0 if disabled
     */
    fun getSilenceAutoStopMs(): Int {
        return prefs.getInt(KEY_SILENCE_AUTO_STOP_MS, DEFAULT_SILENCE_AUTO_STOP_MS)
    }

    /**
     * Sets the silence auto-stop timeout.
     * @param ms Timeout in milliseconds (0 to disable, or 2000/3000/5000)
     */
    fun setSilenceAutoStopMs(ms: Int) {
        prefs.edit().putInt(KEY_SILENCE_AUTO_STOP_MS, ms).apply()
    }

    /**
     * Checks if silence auto-stop is enabled.
     * @return true if auto-stop after silence is enabled
     */
    fun isSilenceAutoStopEnabled(): Boolean {
        return getSilenceAutoStopMs() > 0
    }

    // ========== Number Row Setting ==========

    /**
     * Gets whether to show the number row on the QWERTY keyboard.
     * @return true if number row should be shown
     */
    fun getShowNumberRow(): Boolean {
        return prefs.getBoolean(KEY_SHOW_NUMBER_ROW, false)
    }

    /**
     * Sets whether to show the number row on the QWERTY keyboard.
     * @param show true to show number row
     */
    fun setShowNumberRow(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_NUMBER_ROW, show).apply()
    }

    // ========== Compact Dictation Bar Setting ==========

    /**
     * Gets whether compact dictation bar mode is enabled.
     * When enabled, the dictation bar is ~50% smaller for a minimalist look.
     * @return true if compact mode is enabled (default: false)
     */
    fun getCompactDictationBar(): Boolean {
        return prefs.getBoolean(KEY_COMPACT_DICTATION_BAR, false)
    }

    /**
     * Sets whether compact dictation bar mode is enabled.
     * @param enabled true to enable compact mode, false for normal size
     */
    fun setCompactDictationBar(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_COMPACT_DICTATION_BAR, enabled).apply()
    }

    // ========== Push-to-Talk Mode ==========

    fun getPushToTalkEnabled(): Boolean {
        return prefs.getBoolean(KEY_PUSH_TO_TALK, false)
    }

    fun setPushToTalkEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PUSH_TO_TALK, enabled).apply()
    }

    // ========== Panel Visibility Settings ==========

    /**
     * Gets whether the Claude panel tab is enabled.
     * @return true if enabled (default: true)
     */
    fun getPanelClaudeEnabled(): Boolean {
        return prefs.getBoolean(KEY_PANEL_CLAUDE_ENABLED, true)
    }

    fun setPanelClaudeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PANEL_CLAUDE_ENABLED, enabled).apply()
    }

    /**
     * Gets whether the Shell panel tab is enabled.
     * @return true if enabled (default: true)
     */
    fun getPanelShellEnabled(): Boolean {
        return prefs.getBoolean(KEY_PANEL_SHELL_ENABLED, true)
    }

    fun setPanelShellEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PANEL_SHELL_ENABLED, enabled).apply()
    }

    /**
     * Gets whether the Snippets panel tab is enabled.
     * @return true if enabled (default: true)
     */
    fun getPanelSnippetsEnabled(): Boolean {
        return prefs.getBoolean(KEY_PANEL_SNIPPETS_ENABLED, true)
    }

    fun setPanelSnippetsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PANEL_SNIPPETS_ENABLED, enabled).apply()
    }

    /**
     * Gets whether the Clipboard panel tab is enabled.
     * @return true if enabled (default: true)
     */
    fun getPanelClipboardEnabled(): Boolean {
        return prefs.getBoolean(KEY_PANEL_CLIPBOARD_ENABLED, true)
    }

    fun setPanelClipboardEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PANEL_CLIPBOARD_ENABLED, enabled).apply()
    }

    /**
     * Gets whether the History panel tab is enabled.
     * @return true if enabled (default: true)
     */
    fun getPanelHistoryEnabled(): Boolean {
        return prefs.getBoolean(KEY_PANEL_HISTORY_ENABLED, true)
    }

    fun setPanelHistoryEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PANEL_HISTORY_ENABLED, enabled).apply()
    }

    // ========== Dictation Bar Button Visibility Settings ==========

    fun getBtnSwitchBackEnabled(): Boolean = prefs.getBoolean(KEY_BTN_SWITCH_BACK, true)
    fun setBtnSwitchBackEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_BTN_SWITCH_BACK, enabled).apply()

    fun getBtnSelectAllEnabled(): Boolean = prefs.getBoolean(KEY_BTN_SELECT_ALL, true)
    fun setBtnSelectAllEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_BTN_SELECT_ALL, enabled).apply()

    fun getBtnEmojiEnabled(): Boolean = prefs.getBoolean(KEY_BTN_EMOJI, true)
    fun setBtnEmojiEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_BTN_EMOJI, enabled).apply()

    fun getBtnSpaceEnabled(): Boolean = prefs.getBoolean(KEY_BTN_SPACE, true)
    fun setBtnSpaceEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_BTN_SPACE, enabled).apply()

    fun getBtnLanguageEnabled(): Boolean = prefs.getBoolean(KEY_BTN_LANGUAGE, true)
    fun setBtnLanguageEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_BTN_LANGUAGE, enabled).apply()

    fun getBtnBackspaceEnabled(): Boolean = prefs.getBoolean(KEY_BTN_BACKSPACE, true)
    fun setBtnBackspaceEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_BTN_BACKSPACE, enabled).apply()

    fun getBtnEnterEnabled(): Boolean = prefs.getBoolean(KEY_BTN_ENTER, true)
    fun setBtnEnterEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_BTN_ENTER, enabled).apply()

    fun getBtnKeyboardEnabled(): Boolean = prefs.getBoolean(KEY_BTN_KEYBOARD, true)
    fun setBtnKeyboardEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_BTN_KEYBOARD, enabled).apply()

    fun getBtnHistoryEnabled(): Boolean = prefs.getBoolean(KEY_BTN_HISTORY, true)
    fun setBtnHistoryEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_BTN_HISTORY, enabled).apply()

    fun getBtnImproveEnabled(): Boolean = prefs.getBoolean(KEY_BTN_IMPROVE, true)
    fun setBtnImproveEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_BTN_IMPROVE, enabled).apply()

    // ========== Deepgram Features (Keywords, Replace, Numerals, Dictation) ==========

    /**
     * Gets whether Deepgram numerals feature is enabled.
     * When enabled, Deepgram converts spoken numbers to digits (e.g., "twenty one" -> "21").
     * @return true if enabled (default: true)
     */
    fun getDeepgramNumerals(): Boolean {
        return prefs.getBoolean(KEY_DEEPGRAM_NUMERALS, true)
    }

    fun setDeepgramNumerals(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEEPGRAM_NUMERALS, enabled).apply()
    }

    /**
     * Gets whether Deepgram dictation feature is enabled.
     * When enabled, Deepgram processes spoken punctuation commands (e.g., "period", "comma").
     * Only meaningful for English language.
     * @return true if enabled (default: true)
     */
    fun getDeepgramDictation(): Boolean {
        return prefs.getBoolean(KEY_DEEPGRAM_DICTATION, true)
    }

    fun setDeepgramDictation(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEEPGRAM_DICTATION, enabled).apply()
    }

    /**
     * Gets Deepgram keywords for a specific language.
     * Keywords boost recognition of specific terms.
     * Stored as JSON array in SharedPreferences.
     * @param language Language code (e.g., "en", "de")
     * @return List of keyword strings
     */
    fun getDeepgramKeywords(language: String): List<String> {
        val json = prefs.getString("deepgram_keywords_$language", null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setDeepgramKeywords(language: String, keywords: List<String>) {
        val arr = JSONArray()
        keywords.forEach { arr.put(it) }
        prefs.edit().putString("deepgram_keywords_$language", arr.toString()).apply()
    }

    /**
     * Gets Deepgram replacement rules for a specific language.
     * Replacements substitute one term for another in transcription output.
     * Stored as JSON array of objects: [{"find":"wrong","replace":"right"}, ...]
     * @param language Language code (e.g., "en", "de")
     * @return List of Pair(find, replace) rules
     */
    fun getDeepgramReplacements(language: String): List<Pair<String, String>> {
        val json = prefs.getString("deepgram_replace_$language", null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val obj = arr.getJSONObject(i)
                Pair(obj.getString("find"), obj.getString("replace"))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun setDeepgramReplacements(language: String, replacements: List<Pair<String, String>>) {
        val arr = JSONArray()
        replacements.forEach { (find, replace) ->
            val obj = JSONObject()
            obj.put("find", find)
            obj.put("replace", replace)
            arr.put(obj)
        }
        prefs.edit().putString("deepgram_replace_$language", arr.toString()).apply()
    }
}
