package com.tinydictation.android.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.tinydictation.android.transcription.DeepgramUsage
import com.tinydictation.android.transcription.StreamedUsage
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

class PreferenceManager(context: Context) {

    companion object {
        private val deepgramUsageLock = Any()
        private const val STREAMED_BYTES_PREFIX = "deepgram_streamed_bytes_"
        private const val STREAMED_REQUESTS_PREFIX = "deepgram_streamed_requests_"

        private const val PREFS_FILE_NAME = "tiny_dictation_secure_prefs"

        private const val KEY_API_KEY = "deepgram_api_key"
        private const val KEY_VAD_SENSITIVITY = "vad_sensitivity"
        private const val KEY_AUTO_PUNCTUATION = "auto_punctuation"
        private const val KEY_SMART_FORMAT = "smart_format"
        private const val KEY_LANGUAGE = "language"
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
        private const val KEY_SILENCE_AUTO_STOP_MS = "silence_auto_stop_ms"
        
        private const val KEY_SHOW_NUMBER_ROW = "show_number_row"
        
        private const val KEY_COMPACT_DICTATION_BAR = "compact_dictation_bar"
        private const val KEY_PREDICTION_STRIP_ENABLED = "prediction_strip_enabled"
        private const val KEY_AUTOCORRECT_ENABLED = "autocorrect_enabled"
        private const val KEY_AI_NEXT_WORD_ENABLED = "ai_next_word_enabled"
        private const val KEY_AI_WORD_COMPLETION_ENABLED = "ai_word_completion_enabled"
        private const val KEY_AUTO_FIX_ENABLED = "auto_fix_enabled"
        private const val KEY_PUSH_TO_TALK = "push_to_talk_enabled"
        private const val KEY_PANEL_CLAUDE_ENABLED = "panel_claude_enabled"
        private const val KEY_PANEL_SHELL_ENABLED = "panel_shell_enabled"
        private const val KEY_PANEL_SNIPPETS_ENABLED = "panel_snippets_enabled"
        private const val KEY_PANEL_CLIPBOARD_ENABLED = "panel_clipboard_enabled"
        private const val KEY_PANEL_HISTORY_ENABLED = "panel_history_enabled"

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

        

        const val DEFAULT_SILENCE_AUTO_STOP_MS = 3000
        const val DEFAULT_VAD_SENSITIVITY = 0.5f
        const val DEFAULT_AUTO_PUNCTUATION = true
        const val DEFAULT_SMART_FORMAT = true
        const val DEFAULT_LANGUAGE = "en"
        const val DEFAULT_ENDPOINTING_MS = 300

        const val ENGINE_DEEPGRAM = "deepgram"
        const val ENGINE_VOSK = "vosk"
        const val ENGINE_SHERPA_ONNX = "sherpa-onnx"

        const val WHISPER_TINY = "whisper-tiny"
        const val WHISPER_BASE = "whisper-base"
        const val WHISPER_SMALL = "whisper-small"
        const val DEFAULT_WHISPER_MODEL = WHISPER_TINY

        const val THEME_SYSTEM = "system"
        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        const val THEME_AMOLED = "amoled"

        const val MODE_KEYBOARD = "keyboard"
        @Deprecated("Archived - use MODE_KEYBOARD")
        const val MODE_BUBBLE = "bubble"
        @Deprecated("Archived - use MODE_KEYBOARD")
        const val MODE_COMPANION = "companion"

        @Deprecated("Archived with floating bar feature")
        const val ORIENTATION_VERTICAL = "vertical"
        @Deprecated("Archived with floating bar feature")
        const val ORIENTATION_HORIZONTAL = "horizontal"

        const val PROVIDER_DEEPGRAM = "deepgram"

        const val DEEPGRAM_NOVA_3 = "nova-3"
        const val DEEPGRAM_NOVA_2 = "nova-2"


        private const val KEY_ANTHROPIC_API_KEY = "anthropic_api_key"
        private const val KEY_DEEPGRAM_MODEL = "deepgram_model"
        private const val KEY_AUTO_DETECT_LANGUAGE = "auto_detect_language"
        private const val KEY_UNIFIED_PROVIDER = "unified_provider"
        private const val KEY_FALLBACK_MODEL = "fallback_model"

        const val FALLBACK_VOSK_EN = "vosk:en"
        const val FALLBACK_VOSK_DE = "vosk:de"
        const val FALLBACK_WHISPER_TINY = "whisper:tiny"
        const val FALLBACK_WHISPER_BASE = "whisper:base"
        const val FALLBACK_WHISPER_SMALL = "whisper:small"

        const val UNIFIED_DEEPGRAM = "cloud:deepgram"
        const val UNIFIED_VOSK = "offline:vosk"
        const val UNIFIED_WHISPER = "offline:whisper"

        private const val KEY_DEEPGRAM_NUMERALS = "deepgram_numerals"
        private const val KEY_DEEPGRAM_DICTATION = "deepgram_dictation"
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


    fun getApiKey(): String? {
        return prefs.getString(KEY_API_KEY, null)
    }

    fun setApiKey(key: String) {
        prefs.edit().putString(KEY_API_KEY, key).apply()
    }

    fun clearApiKey() {
        prefs.edit().remove(KEY_API_KEY).apply()
    }

    fun hasApiKey(): Boolean {
        return !getApiKey().isNullOrBlank()
    }


    fun getVadSensitivity(): Float {
        return prefs.getFloat(KEY_VAD_SENSITIVITY, DEFAULT_VAD_SENSITIVITY)
    }

    fun setVadSensitivity(sensitivity: Float) {
        val clampedValue = sensitivity.coerceIn(0.3f, 0.8f)
        prefs.edit().putFloat(KEY_VAD_SENSITIVITY, clampedValue).apply()
    }

    fun getSpeechThreshold(): Int {
        val sensitivity = getVadSensitivity()
        val normalized = (sensitivity - 0.3f) / 0.5f
        return (800 - (normalized * 600)).toInt().coerceIn(200, 800)
    }

    fun getSilenceThreshold(): Int {
        val sensitivity = getVadSensitivity()
        val normalized = (sensitivity - 0.3f) / 0.5f
        return (400 - (normalized * 300)).toInt().coerceIn(100, 400)
    }


    fun getAutoPunctuation(): Boolean {
        return prefs.getBoolean(KEY_AUTO_PUNCTUATION, DEFAULT_AUTO_PUNCTUATION)
    }

    fun setAutoPunctuation(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_PUNCTUATION, enabled).apply()
    }


    fun getSmartFormat(): Boolean {
        return prefs.getBoolean(KEY_SMART_FORMAT, DEFAULT_SMART_FORMAT)
    }

    fun setSmartFormat(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SMART_FORMAT, enabled).apply()
    }


    fun getLanguage(): String {
        return prefs.getString(KEY_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
    }

    fun setLanguage(language: String) {
        prefs.edit().putString(KEY_LANGUAGE, language).apply()
    }


    fun getEndpointingMs(): Int {
        return prefs.getInt(KEY_ENDPOINTING_MS, DEFAULT_ENDPOINTING_MS)
    }

    fun setEndpointingMs(ms: Int) {
        val clampedValue = ms.coerceIn(100, 1000)
        prefs.edit().putInt(KEY_ENDPOINTING_MS, clampedValue).apply()
    }


    fun isOnboardingCompleted(): Boolean {
        return prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
    }

    fun setOnboardingCompleted() {
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETED, true).apply()
    }


    fun clearAll() {
        prefs.edit().clear().apply()
    }


    fun getTranscriptionEngine(): String {
        return prefs.getString(KEY_TRANSCRIPTION_ENGINE, ENGINE_DEEPGRAM) ?: ENGINE_DEEPGRAM
    }

    fun setTranscriptionEngine(engineId: String) {
        prefs.edit().putString(KEY_TRANSCRIPTION_ENGINE, engineId).apply()
    }

    fun getAutoFallback(): Boolean {
        return prefs.getBoolean(KEY_AUTO_FALLBACK, true)
    }

    fun setAutoFallback(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_FALLBACK, enabled).apply()
    }


    fun getWhisperModel(): String {
        return prefs.getString(KEY_WHISPER_MODEL, DEFAULT_WHISPER_MODEL) ?: DEFAULT_WHISPER_MODEL
    }

    fun setWhisperModel(modelId: String) {
        prefs.edit().putString(KEY_WHISPER_MODEL, modelId).apply()
    }


    fun getUseHeadphoneMic(): Boolean {
        return prefs.getBoolean(KEY_USE_HEADPHONE_MIC, false)
    }

    fun setUseHeadphoneMic(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_USE_HEADPHONE_MIC, enabled).apply()
    }


    fun getShowNoteBubble(): Boolean {
        return prefs.getBoolean(KEY_SHOW_NOTE_BUBBLE, false)
    }

    fun setShowNoteBubble(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_NOTE_BUBBLE, enabled).apply()
    }

    fun getNoteAppPackage(): String? {
        return prefs.getString(KEY_NOTE_APP_PACKAGE, null)
    }

    fun setNoteAppPackage(packageName: String?) {
        if (packageName == null) {
            prefs.edit().remove(KEY_NOTE_APP_PACKAGE).apply()
        } else {
            prefs.edit().putString(KEY_NOTE_APP_PACKAGE, packageName).apply()
        }
    }


    fun getSimulateTyping(): Boolean {
        return prefs.getBoolean(KEY_SIMULATE_TYPING, false)
    }

    fun setSimulateTyping(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SIMULATE_TYPING, enabled).apply()
    }


    fun getHideBubbles(): Boolean {
        return prefs.getBoolean(KEY_HIDE_BUBBLES, false)
    }

    fun setHideBubbles(hidden: Boolean) {
        prefs.edit().putBoolean(KEY_HIDE_BUBBLES, hidden).apply()
    }


    fun getKeyboardTheme(): String {
        return prefs.getString(KEY_KEYBOARD_THEME, THEME_SYSTEM) ?: THEME_SYSTEM
    }

    fun setKeyboardTheme(theme: String) {
        prefs.edit().putString(KEY_KEYBOARD_THEME, theme).apply()
    }


    fun getDictationMode(): String {
        return MODE_KEYBOARD
    }

    @Deprecated("Only keyboard mode is supported")
    fun setDictationMode(mode: String) {
        prefs.edit().putString(KEY_DICTATION_MODE, MODE_KEYBOARD).apply()
    }


    fun getPauseMediaDuringDictation(): Boolean {
        return prefs.getBoolean(KEY_PAUSE_MEDIA_DURING_DICTATION, true)
    }

    fun setPauseMediaDuringDictation(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PAUSE_MEDIA_DURING_DICTATION, enabled).apply()
    }


    @Deprecated("Floating bar feature is archived")
    fun getBarOrientation(): String {
        return prefs.getString(KEY_BAR_ORIENTATION, ORIENTATION_VERTICAL) ?: ORIENTATION_VERTICAL
    }

    @Deprecated("Floating bar feature is archived")
    fun setBarOrientation(orientation: String) {
        prefs.edit().putString(KEY_BAR_ORIENTATION, orientation).apply()
    }


    @Deprecated("Floating bar feature is archived")
    fun getBarTransparency(): Float {
        return prefs.getFloat(KEY_BAR_TRANSPARENCY, 0f)
    }

    @Deprecated("Floating bar feature is archived")
    fun setBarTransparency(value: Float) {
        prefs.edit().putFloat(KEY_BAR_TRANSPARENCY, value.coerceIn(0f, 50f)).apply()
    }


    fun getAnthropicApiKey(): String? {
        return prefs.getString(KEY_ANTHROPIC_API_KEY, null)
    }

    fun setAnthropicApiKey(key: String) {
        prefs.edit().putString(KEY_ANTHROPIC_API_KEY, key).apply()
    }

    fun hasAnthropicApiKey(): Boolean {
        return !getAnthropicApiKey().isNullOrBlank()
    }


    fun getDeepgramModel(): String {
        return prefs.getString(KEY_DEEPGRAM_MODEL, DEEPGRAM_NOVA_2) ?: DEEPGRAM_NOVA_2
    }

    fun setDeepgramModel(model: String) {
        prefs.edit().putString(KEY_DEEPGRAM_MODEL, model).apply()
    }


    fun getAutoDetectLanguage(): Boolean {
        return prefs.getBoolean(KEY_AUTO_DETECT_LANGUAGE, false)
    }

    fun setAutoDetectLanguage(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_DETECT_LANGUAGE, enabled).apply()
    }

    fun currentProviderSupportsAutoDetect(): Boolean {
        val unified = getUnifiedProvider()
        return when {
            unified == UNIFIED_VOSK -> false
            unified == UNIFIED_WHISPER -> true
            unified == UNIFIED_DEEPGRAM -> getDeepgramModel() == DEEPGRAM_NOVA_3
            else -> false
        }
    }

    fun currentProviderNeedsLanguageToggle(): Boolean {
        return true
    }


    fun getUnifiedProvider(): String {
        val stored = prefs.getString(KEY_UNIFIED_PROVIDER, null)
        if (stored != null) return stored

        val engine = getTranscriptionEngine()
        return when (engine) {
            ENGINE_VOSK -> UNIFIED_VOSK
            ENGINE_SHERPA_ONNX -> UNIFIED_WHISPER
            else -> UNIFIED_DEEPGRAM
        }
    }

    fun setUnifiedProvider(provider: String) {
        prefs.edit().putString(KEY_UNIFIED_PROVIDER, provider).apply()

        if (provider.startsWith("cloud:")) {
            setTranscriptionEngine(ENGINE_DEEPGRAM)
        } else if (provider == UNIFIED_VOSK) {
            setTranscriptionEngine(ENGINE_VOSK)
        } else if (provider == UNIFIED_WHISPER) {
            setTranscriptionEngine(ENGINE_SHERPA_ONNX)
        }
    }

    fun isCloudProvider(): Boolean {
        return getUnifiedProvider().startsWith("cloud:")
    }

    fun isOfflineProvider(): Boolean {
        return getUnifiedProvider().startsWith("offline:")
    }

    fun getFallbackModel(): String? {
        return prefs.getString(KEY_FALLBACK_MODEL, null)
    }

    fun setFallbackModel(model: String?) {
        if (model == null) {
            prefs.edit().remove(KEY_FALLBACK_MODEL).apply()
        } else {
            prefs.edit().putString(KEY_FALLBACK_MODEL, model).apply()
        }
    }

    fun hasFallbackModel(): Boolean {
        return !getFallbackModel().isNullOrBlank()
    }


    fun getSilenceAutoStopMs(): Int {
        return prefs.getInt(KEY_SILENCE_AUTO_STOP_MS, DEFAULT_SILENCE_AUTO_STOP_MS)
    }

    fun setSilenceAutoStopMs(ms: Int) {
        prefs.edit().putInt(KEY_SILENCE_AUTO_STOP_MS, ms).apply()
    }

    fun isSilenceAutoStopEnabled(): Boolean {
        return getSilenceAutoStopMs() > 0
    }


    fun getShowNumberRow(): Boolean {
        return prefs.getBoolean(KEY_SHOW_NUMBER_ROW, false)
    }

    fun setShowNumberRow(show: Boolean) {
        prefs.edit().putBoolean(KEY_SHOW_NUMBER_ROW, show).apply()
    }


    fun getCompactDictationBar(): Boolean {
        return prefs.getBoolean(KEY_COMPACT_DICTATION_BAR, false)
    }

    fun setCompactDictationBar(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_COMPACT_DICTATION_BAR, enabled).apply()
    }


    fun getPredictionStripEnabled(): Boolean {
        return prefs.getBoolean(KEY_PREDICTION_STRIP_ENABLED, true)
    }

    fun setPredictionStripEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PREDICTION_STRIP_ENABLED, enabled).apply()
    }

    fun getAutocorrectEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTOCORRECT_ENABLED, true)
    }

    fun setAutocorrectEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTOCORRECT_ENABLED, enabled).apply()
    }

    fun getAiNextWordEnabled(): Boolean {
        return prefs.getBoolean(KEY_AI_NEXT_WORD_ENABLED, true)
    }

    fun setAiNextWordEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AI_NEXT_WORD_ENABLED, enabled).apply()
    }

    fun getAiWordCompletionEnabled(): Boolean {
        return prefs.getBoolean(KEY_AI_WORD_COMPLETION_ENABLED, true)
    }

    fun setAiWordCompletionEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AI_WORD_COMPLETION_ENABLED, enabled).apply()
    }

    fun getAutoFixEnabled(): Boolean {
        return prefs.getBoolean(KEY_AUTO_FIX_ENABLED, true)
    }

    fun setAutoFixEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_AUTO_FIX_ENABLED, enabled).apply()
    }


    fun getPushToTalkEnabled(): Boolean {
        return prefs.getBoolean(KEY_PUSH_TO_TALK, false)
    }

    fun setPushToTalkEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PUSH_TO_TALK, enabled).apply()
    }


    fun getPanelClaudeEnabled(): Boolean {
        return prefs.getBoolean(KEY_PANEL_CLAUDE_ENABLED, true)
    }

    fun setPanelClaudeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PANEL_CLAUDE_ENABLED, enabled).apply()
    }

    fun getPanelShellEnabled(): Boolean {
        return prefs.getBoolean(KEY_PANEL_SHELL_ENABLED, true)
    }

    fun setPanelShellEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PANEL_SHELL_ENABLED, enabled).apply()
    }

    fun getPanelSnippetsEnabled(): Boolean {
        return prefs.getBoolean(KEY_PANEL_SNIPPETS_ENABLED, true)
    }

    fun setPanelSnippetsEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PANEL_SNIPPETS_ENABLED, enabled).apply()
    }

    fun getPanelClipboardEnabled(): Boolean {
        return prefs.getBoolean(KEY_PANEL_CLIPBOARD_ENABLED, true)
    }

    fun setPanelClipboardEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PANEL_CLIPBOARD_ENABLED, enabled).apply()
    }

    fun getPanelHistoryEnabled(): Boolean {
        return prefs.getBoolean(KEY_PANEL_HISTORY_ENABLED, true)
    }

    fun setPanelHistoryEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PANEL_HISTORY_ENABLED, enabled).apply()
    }


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


    fun getDeepgramNumerals(): Boolean {
        return prefs.getBoolean(KEY_DEEPGRAM_NUMERALS, true)
    }

    fun setDeepgramNumerals(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEEPGRAM_NUMERALS, enabled).apply()
    }

    fun getDeepgramDictation(): Boolean {
        return prefs.getBoolean(KEY_DEEPGRAM_DICTATION, false)
    }

    fun setDeepgramDictation(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DEEPGRAM_DICTATION, enabled).apply()
    }

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

    fun recordDeepgramUsage(
        audioBytes: Long,
        today: LocalDate = LocalDate.now()
    ): StreamedUsage = synchronized(deepgramUsageLock) {
        val previous = prefs.all.keys.filter { it.startsWith(STREAMED_BYTES_PREFIX) }
            .associate { key ->
                val date = key.removePrefix(STREAMED_BYTES_PREFIX)
                date to StreamedUsage(
                    prefs.getLong(key, 0),
                    prefs.getInt("$STREAMED_REQUESTS_PREFIX$date", 0)
                )
            }
        val totals = DeepgramUsage.accumulate(previous, audioBytes, today)
        val editor = prefs.edit()
        for (date in previous.keys - totals.keys) {
            editor.remove("$STREAMED_BYTES_PREFIX$date")
            editor.remove("$STREAMED_REQUESTS_PREFIX$date")
        }
        for ((date, usage) in totals) {
            editor.putLong("$STREAMED_BYTES_PREFIX$date", usage.audioBytes)
            editor.putInt("$STREAMED_REQUESTS_PREFIX$date", usage.requests)
        }
        editor.apply()
        totals.getValue(today.toString())
    }
}
