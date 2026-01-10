package com.tinydictation.android.transcription.engines

import android.content.Context
import android.util.Log
import com.tinydictation.android.audio.AudioRecorder
import com.tinydictation.android.transcription.DeepgramClient
import com.tinydictation.android.transcription.TranscriptionEngine
import com.tinydictation.android.util.DebugLog
import com.tinydictation.android.util.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Cloud-based transcription engine using Deepgram's API.
 *
 * Provides real-time streaming transcription with low latency.
 * Requires internet connectivity and a valid API key.
 */
class DeepgramEngine(private val apiKey: String) : TranscriptionEngine {

    companion object {
        private const val TAG = "TinyDictation"
    }

    override val engineId = "deepgram"
    override val displayName = "Deepgram (Cloud)"
    override val requiresInternet = true

    private val deepgramClient = DeepgramClient()
    private val audioRecorder = AudioRecorder()
    private var scope: CoroutineScope? = null
    private var currentLanguage = "en"
    private var currentModel = PreferenceManager.DEEPGRAM_NOVA_2
    private var onTranscriptCallback: ((String, Boolean) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null
    private var isSessionActive = false

    /**
     * Sets the Deepgram model to use.
     */
    fun setModel(model: String) {
        currentModel = model
        Log.i(TAG, "DeepgramEngine: Model set to $model")
    }

    override suspend fun isReady(context: Context, language: String): Boolean {
        // Deepgram is always ready if we have an API key
        return apiKey.isNotBlank()
    }

    override suspend fun initialize(context: Context, language: String): Result<Unit> {
        currentLanguage = language
        return if (apiKey.isNotBlank()) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("API key is required for Deepgram"))
        }
    }

    override fun startSession(
        onTranscript: (text: String, isFinal: Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
        if (isSessionActive) {
            Log.d(TAG, "DeepgramEngine session already active")
            return
        }

        Log.i(TAG, "DeepgramEngine starting session (language=$currentLanguage, model=$currentModel)")
        DebugLog.i("DeepgramEngine: Starting session (model=$currentModel)")

        onTranscriptCallback = onTranscript
        onErrorCallback = onError
        isSessionActive = true
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        scope?.launch {
            deepgramClient.connect(
                apiKey = apiKey,
                onInterim = { text ->
                    onTranscriptCallback?.invoke(text, false)
                },
                onFinal = { text ->
                    onTranscriptCallback?.invoke(text, true)
                },
                onError = { error ->
                    DebugLog.e("DeepgramEngine: Error - $error")
                    onErrorCallback?.invoke(error)
                },
                language = currentLanguage,
                model = currentModel
            )
        }
    }

    override fun processAudio(samples: ShortArray) {
        if (!isSessionActive) return

        if (deepgramClient.isConnected()) {
            val audioBytes = audioRecorder.shortArrayToByteArray(samples)
            scope?.launch {
                deepgramClient.sendAudio(audioBytes)
            }
        }
    }

    override fun endSession() {
        if (!isSessionActive) return

        Log.i(TAG, "DeepgramEngine ending session")
        DebugLog.i("DeepgramEngine: Ending session")

        isSessionActive = false
        deepgramClient.close()
        onTranscriptCallback = null
        onErrorCallback = null
    }

    override fun setLanguage(language: String) {
        if (currentLanguage == language) return

        Log.i(TAG, "DeepgramEngine changing language to $language")
        currentLanguage = language

        // If session is active, reconnect with new language
        if (isSessionActive) {
            val transcript = onTranscriptCallback
            val error = onErrorCallback
            if (transcript != null && error != null) {
                deepgramClient.close()
                scope?.launch {
                    kotlinx.coroutines.delay(100)
                    deepgramClient.connect(
                        apiKey = apiKey,
                        onInterim = { text -> transcript(text, false) },
                        onFinal = { text -> transcript(text, true) },
                        onError = { err -> error(err) },
                        language = currentLanguage,
                        model = currentModel
                    )
                }
            }
        }
    }

    /**
     * Enable auto language detection (only supported by nova-3).
     */
    fun setAutoDetectLanguage(enabled: Boolean) {
        currentLanguage = if (enabled && currentModel == PreferenceManager.DEEPGRAM_NOVA_3) "auto" else "en"
        Log.i(TAG, "DeepgramEngine: Auto-detect language = $enabled (actual: $currentLanguage)")
    }

    override fun getSupportedLanguages(): List<String> {
        // Deepgram supports many languages, but we focus on EN and DE
        return listOf("en", "de", "es", "fr", "it", "pt", "nl", "ja", "ko", "zh")
    }

    override fun release() {
        endSession()
        scope = null
    }
}
