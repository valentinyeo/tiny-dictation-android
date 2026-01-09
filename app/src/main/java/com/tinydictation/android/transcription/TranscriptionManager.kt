package com.tinydictation.android.transcription

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.tinydictation.android.audio.AudioRecorder
import com.tinydictation.android.transcription.engines.DeepgramEngine
import com.tinydictation.android.transcription.engines.SherpaOnnxEngine
import com.tinydictation.android.transcription.engines.VoskEngine
import com.tinydictation.android.util.DebugLog
import com.tinydictation.android.util.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Orchestrates audio recording and transcription with multiple engine support.
 *
 * Supports cloud-based (Deepgram) and offline (Vosk, Sherpa-ONNX) transcription.
 * Can auto-fallback to offline when no internet is available.
 */
class TranscriptionManager(
    private val apiKey: String,
    private val onTranscript: (text: String, isFinal: Boolean) -> Unit,
    private val onError: ((String) -> Unit)? = null,
    private val onStateChange: ((TranscriptionState) -> Unit)? = null,
    private val context: Context? = null
) {

    companion object {
        private const val TAG = "TinyDictation"
    }

    /**
     * Current state of the transcription manager.
     */
    enum class TranscriptionState {
        IDLE,
        LISTENING,
        TRANSCRIBING,
        ERROR
    }

    private val audioRecorder = AudioRecorder()
    private val preferenceManager = context?.let { PreferenceManager(it) }
    private var currentEngine: TranscriptionEngine? = null
    private var scope: CoroutineScope? = null
    private var isListening = false
    private var currentState = TranscriptionState.IDLE
    private var currentLanguage = "en"
    private var audioChunkCount = 0

    // Available engines
    private val engines = mutableMapOf<String, TranscriptionEngine>()

    init {
        // Initialize available engines
        engines[PreferenceManager.ENGINE_DEEPGRAM] = DeepgramEngine(apiKey)
        engines[PreferenceManager.ENGINE_VOSK] = VoskEngine()
        engines[PreferenceManager.ENGINE_SHERPA_ONNX] = SherpaOnnxEngine()
    }

    /**
     * Starts listening for speech and transcribing.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startListening() {
        if (isListening) {
            Log.d(TAG, "TranscriptionManager already listening")
            return
        }

        // Runtime permission check
        if (context != null) {
            if (ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.e(TAG, "RECORD_AUDIO permission not granted")
                updateState(TranscriptionState.ERROR)
                onError?.invoke("Microphone permission not granted")
                return
            }
        }

        Log.i(TAG, "TranscriptionManager starting listening")
        DebugLog.i("TranscriptionManager: Starting listening")
        isListening = true
        audioChunkCount = 0

        scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        val currentScope = scope
        if (currentScope == null) {
            Log.e(TAG, "Failed to create coroutine scope")
            updateState(TranscriptionState.ERROR)
            onError?.invoke("Failed to initialize transcription manager")
            return
        }

        // Select and initialize the appropriate engine
        currentScope.launch(Dispatchers.IO) {
            val engine = selectEngine()
            if (engine == null) {
                currentScope.launch(Dispatchers.Main) {
                    updateState(TranscriptionState.ERROR)
                    onError?.invoke("No transcription engine available")
                }
                return@launch
            }

            currentEngine = engine
            Log.i(TAG, "Selected engine: ${engine.displayName}")
            DebugLog.i("TranscriptionManager: Using ${engine.displayName}")

            // Initialize engine
            val initResult = engine.initialize(context!!, currentLanguage)
            if (initResult.isFailure) {
                currentScope.launch(Dispatchers.Main) {
                    updateState(TranscriptionState.ERROR)
                    onError?.invoke("Failed to initialize engine: ${initResult.exceptionOrNull()?.message}")
                }
                return@launch
            }

            // Start engine session
            currentScope.launch(Dispatchers.Main) {
                updateState(TranscriptionState.TRANSCRIBING)
            }

            engine.startSession(
                onTranscript = { text, isFinal ->
                    currentScope.launch(Dispatchers.Main) {
                        if (isListening) {
                            onTranscript(text, isFinal)
                        }
                    }
                },
                onError = { error ->
                    currentScope.launch(Dispatchers.Main) {
                        if (isListening) {
                            DebugLog.e("TranscriptionManager: Engine error - $error")
                            onError?.invoke(error)
                        }
                    }
                }
            )

            // Start audio recording
            currentScope.launch(Dispatchers.Main) {
                try {
                    // Get headphone mic preference (default to false if no context)
                    val useHeadphoneMic = preferenceManager?.getUseHeadphoneMic() ?: false
                    audioRecorder.startRecording(currentScope, useHeadphoneMic) { samples ->
                        processAudioChunk(samples)
                    }
                    DebugLog.i("TranscriptionManager: Audio recording started (headphoneMic=$useHeadphoneMic)")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start audio recording", e)
                    DebugLog.e("TranscriptionManager: Failed to start recording - ${e.message}")
                    updateState(TranscriptionState.ERROR)
                    onError?.invoke("Failed to start audio recording: ${e.message}")
                }
            }
        }
    }

    /**
     * Stops listening and closes all connections.
     */
    fun stopListening() {
        if (!isListening) {
            Log.d(TAG, "TranscriptionManager not listening")
            return
        }

        Log.i(TAG, "TranscriptionManager stopping listening")
        DebugLog.i("TranscriptionManager: Stopping listening")
        isListening = false

        audioRecorder.stopRecording()
        currentEngine?.endSession()

        scope?.cancel()
        scope = null

        updateState(TranscriptionState.IDLE)
    }

    /**
     * Processes an audio chunk - sends to the active engine.
     */
    private fun processAudioChunk(samples: ShortArray) {
        if (!isListening) return

        audioChunkCount++

        // Log periodically
        if (audioChunkCount % 100 == 0) {
            DebugLog.d("TranscriptionManager: Processed $audioChunkCount chunks")
        }

        currentEngine?.processAudio(samples)
    }

    /**
     * Selects the appropriate transcription engine based on preferences and availability.
     */
    private suspend fun selectEngine(): TranscriptionEngine? {
        val ctx = context ?: return engines[PreferenceManager.ENGINE_DEEPGRAM]

        val preferenceManager = PreferenceManager(ctx)
        val preferredEngineId = preferenceManager.getTranscriptionEngine()
        val autoFallback = preferenceManager.getAutoFallback()
        val whisperModel = preferenceManager.getWhisperModel()

        Log.d(TAG, "Preferred engine: $preferredEngineId, autoFallback: $autoFallback, whisperModel: $whisperModel")

        // If preferred is Deepgram and auto-fallback is enabled, check connectivity
        if (preferredEngineId == PreferenceManager.ENGINE_DEEPGRAM && autoFallback) {
            if (!isNetworkAvailable(ctx)) {
                Log.i(TAG, "No network, attempting fallback to offline engine")
                DebugLog.i("TranscriptionManager: No network, falling back to offline")

                // Try Vosk first
                val voskEngine = engines[PreferenceManager.ENGINE_VOSK]
                if (voskEngine != null && voskEngine.isReady(ctx, currentLanguage)) {
                    return voskEngine
                }

                // Try Sherpa-ONNX with selected Whisper model
                val sherpaEngine = engines[PreferenceManager.ENGINE_SHERPA_ONNX]
                if (sherpaEngine != null && sherpaEngine.isReady(ctx, whisperModel)) {
                    // Set the model for Sherpa-ONNX
                    (sherpaEngine as? SherpaOnnxEngine)?.setModelVariant(whisperModel)
                    return sherpaEngine
                }

                // No offline engine available
                Log.w(TAG, "No offline models available for fallback")
                DebugLog.w("TranscriptionManager: No offline models available")
            }
        }

        // Use preferred engine
        val engine = engines[preferredEngineId]

        // For Sherpa-ONNX (Whisper), set the selected model variant
        if (preferredEngineId == PreferenceManager.ENGINE_SHERPA_ONNX) {
            (engine as? SherpaOnnxEngine)?.setModelVariant(whisperModel)
            // Check if the selected Whisper model is ready
            if (engine != null && !engine.isReady(ctx, whisperModel)) {
                Log.e(TAG, "Whisper model $whisperModel not downloaded")
                return null
            }
        } else if (engine != null && !engine.requiresInternet) {
            // For other offline engines (Vosk), check if model is ready
            if (!engine.isReady(ctx, currentLanguage)) {
                Log.e(TAG, "Offline engine not ready (model not downloaded)")
                return null
            }
        }

        // For Deepgram, check API key
        if (preferredEngineId == PreferenceManager.ENGINE_DEEPGRAM && apiKey.isBlank()) {
            Log.e(TAG, "Deepgram selected but no API key")
            return null
        }

        return engine
    }

    /**
     * Checks if network is available.
     */
    private fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Updates the current state and notifies the listener.
     */
    private fun updateState(newState: TranscriptionState) {
        if (currentState != newState) {
            Log.d(TAG, "Transcription state changed: $currentState -> $newState")
            currentState = newState
            onStateChange?.invoke(newState)
        }
    }

    fun getState(): TranscriptionState = currentState
    fun isListening(): Boolean = isListening
    fun isTranscribing(): Boolean = currentState == TranscriptionState.TRANSCRIBING

    /**
     * Changes the transcription language.
     */
    fun setLanguage(language: String) {
        if (currentLanguage == language) return

        Log.i(TAG, "Changing language from $currentLanguage to $language")
        DebugLog.i("TranscriptionManager: Language changed to $language")
        currentLanguage = language

        currentEngine?.setLanguage(language)
    }

    /**
     * Gets the currently active engine ID.
     */
    fun getActiveEngineId(): String? = currentEngine?.engineId

    /**
     * Checks if there is pending audio still being processed.
     *
     * @return true if there is pending audio to be transcribed
     */
    fun hasPendingAudio(): Boolean {
        return currentEngine?.hasPendingAudio() == true
    }

    /**
     * Flushes any pending audio and waits for processing to complete.
     * This ensures all buffered audio is transcribed before returning.
     * Use this before copying transcription to clipboard to ensure completeness.
     *
     * @param timeoutMs Maximum time to wait for processing
     * @return true if flush completed successfully, false if timed out
     */
    suspend fun flushPendingTranscription(timeoutMs: Long = 10000): Boolean {
        val engine = currentEngine ?: return true
        Log.i(TAG, "TranscriptionManager: Flushing pending transcription")
        DebugLog.i("TranscriptionManager: Flushing pending transcription")
        return engine.flushPendingAudio(timeoutMs)
    }

    /**
     * Releases all resources.
     */
    fun release() {
        stopListening()
        engines.values.forEach { it.release() }
        engines.clear()
    }
}
