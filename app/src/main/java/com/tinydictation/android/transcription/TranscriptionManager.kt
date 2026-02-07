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
import com.tinydictation.android.transcription.engines.AssemblyAIEngine
import com.tinydictation.android.transcription.engines.DeepgramEngine
import com.tinydictation.android.transcription.engines.GeminiEngine
import com.tinydictation.android.transcription.engines.GroqEngine
import com.tinydictation.android.transcription.engines.OpenAIEngine
import com.tinydictation.android.transcription.engines.SherpaOnnxEngine
import com.tinydictation.android.transcription.engines.VoskEngine
import com.tinydictation.android.util.AudioFocusManager
import com.tinydictation.android.util.DebugLog
import com.tinydictation.android.util.PreferenceManager
import com.tinydictation.android.util.TranscriptionFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
    private val context: Context? = null,
    private val onSilenceAutoStop: (() -> Unit)? = null
) {

    companion object {
        private const val TAG = "TinyDictation"
    }

    /**
     * Current state of the transcription manager.
     *
     * IDLE = Mic off, not listening
     * STANDBY = Mic on, listening locally with VAD, NOT streaming (no cost)
     * TRANSCRIBING = Speech detected, actively streaming to cloud (incurs cost)
     * ERROR = Something went wrong
     */
    enum class TranscriptionState {
        IDLE,
        STANDBY,      // Listening locally, waiting for speech (gray mic)
        LISTENING,    // Legacy - kept for compatibility
        TRANSCRIBING, // Actively streaming to cloud (red mic)
        ERROR
    }

    private val audioRecorder = AudioRecorder()
    private val preferenceManager = context?.let { PreferenceManager(it) }
    private val audioFocusManager = context?.let { AudioFocusManager(it) }
    private var currentEngine: TranscriptionEngine? = null
    private var scope: CoroutineScope? = null
    private var isListening = false
    private var currentState = TranscriptionState.IDLE
    private var currentLanguage = "en"
    private var audioChunkCount = 0

    // Silence auto-stop: timer that triggers when no audio chunks received for configured duration
    private var silenceTimeoutJob: Job? = null

    // Smart VAD for standby mode - only stream when speech detected
    private var isEngineConnected = false
    private val audioBuffer = mutableListOf<ShortArray>()
    private val AUDIO_BUFFER_MAX_CHUNKS = 25  // ~500ms of audio at 20ms chunks
    private val SPEECH_RMS_THRESHOLD = 800    // RMS threshold to detect speech start
    private val SILENCE_RMS_THRESHOLD = 300   // RMS threshold to detect silence
    private var consecutiveSilenceChunks = 0
    private val SILENCE_CHUNKS_TO_STOP = 75   // ~1.5 seconds of silence to stop streaming
    private var consecutiveSpeechChunks = 0
    private val SPEECH_CHUNKS_TO_START = 3    // ~60ms of speech to start streaming

    // Available engines
    private val engines = mutableMapOf<String, TranscriptionEngine>()

    init {
        // Initialize available engines
        engines[PreferenceManager.ENGINE_DEEPGRAM] = DeepgramEngine(apiKey)
        engines[PreferenceManager.ENGINE_VOSK] = VoskEngine()
        engines[PreferenceManager.ENGINE_SHERPA_ONNX] = SherpaOnnxEngine()

        // Initialize language from user preferences (fixes bug where language reset to "en" after sleep)
        currentLanguage = preferenceManager?.getLanguage() ?: "en"
    }

    /**
     * Starts or resets the silence auto-stop timeout.
     * When this timer expires without being reset, recording stops automatically.
     */
    private fun resetSilenceTimeout() {
        val timeoutMs = preferenceManager?.getSilenceAutoStopMs() ?: 0
        if (timeoutMs <= 0) return  // Auto-stop disabled

        val currentScope = scope ?: return

        // Cancel any existing timeout
        silenceTimeoutJob?.cancel()

        // Start new timeout
        silenceTimeoutJob = currentScope.launch(Dispatchers.Main) {
            delay(timeoutMs.toLong())

            // Timer expired - trigger auto-stop
            if (isListening) {
                Log.i(TAG, "Silence auto-stop triggered after ${timeoutMs}ms of silence")
                DebugLog.i("TranscriptionManager: Auto-stopping after ${timeoutMs}ms of silence")
                onSilenceAutoStop?.invoke()
            }
        }
    }

    /**
     * Cancels the silence timeout (called when stopping listening).
     */
    private fun cancelSilenceTimeout() {
        silenceTimeoutJob?.cancel()
        silenceTimeoutJob = null
    }

    /**
     * Starts listening for speech and transcribing.
     * Begins in STANDBY mode - recording locally with VAD but NOT streaming to cloud.
     * Only connects to cloud when speech is detected (saves API costs).
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

        // Refresh language from preferences before starting (in case it changed)
        currentLanguage = preferenceManager?.getLanguage() ?: "en"

        Log.i(TAG, "TranscriptionManager starting in STANDBY mode (language: $currentLanguage)")
        DebugLog.i("TranscriptionManager: Starting in STANDBY mode (language: $currentLanguage)")
        isListening = true
        isEngineConnected = false
        audioChunkCount = 0
        consecutiveSilenceChunks = 0
        consecutiveSpeechChunks = 0
        audioBuffer.clear()

        // Request audio focus to pause other media apps (if enabled)
        if (preferenceManager?.getPauseMediaDuringDictation() == true) {
            val musicWasActive = audioFocusManager?.isMusicActive() == true
            if (musicWasActive) {
                DebugLog.i("TranscriptionManager: Media was playing, requesting audio focus to pause it")
            }
            val focusGranted = audioFocusManager?.requestAudioFocus(duck = false) == true
            if (focusGranted) {
                Log.i(TAG, "Audio focus granted - other media should pause")
            } else {
                Log.w(TAG, "Audio focus not granted - other media may continue playing")
            }
        }

        scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        val currentScope = scope
        if (currentScope == null) {
            Log.e(TAG, "Failed to create coroutine scope")
            updateState(TranscriptionState.ERROR)
            onError?.invoke("Failed to initialize transcription manager")
            return
        }

        // Pre-select engine (but don't connect yet - that happens when speech detected)
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
            Log.i(TAG, "Selected engine: ${engine.displayName} (will connect when speech detected)")
            DebugLog.i("TranscriptionManager: Using ${engine.displayName} (standby)")

            // Initialize engine (but don't start session yet)
            val initResult = engine.initialize(context!!, currentLanguage)
            if (initResult.isFailure) {
                currentScope.launch(Dispatchers.Main) {
                    updateState(TranscriptionState.ERROR)
                    onError?.invoke("Failed to initialize engine: ${initResult.exceptionOrNull()?.message}")
                }
                return@launch
            }

            // Enter STANDBY state - listening locally but not streaming
            currentScope.launch(Dispatchers.Main) {
                updateState(TranscriptionState.STANDBY)
            }

            // Start audio recording
            currentScope.launch(Dispatchers.Main) {
                try {
                    val useHeadphoneMic = preferenceManager?.getUseHeadphoneMic() ?: false
                    audioRecorder.startRecording(currentScope, context!!, useHeadphoneMic) { samples ->
                        processAudioChunk(samples)
                    }
                    DebugLog.i("TranscriptionManager: Audio recording started in STANDBY mode")
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

        // Cancel silence auto-stop timer
        cancelSilenceTimeout()

        audioRecorder.stopRecording()
        currentEngine?.endSession()

        // Abandon audio focus to allow other media apps to resume
        audioFocusManager?.abandonAudioFocus()

        scope?.cancel()
        scope = null

        updateState(TranscriptionState.IDLE)
    }

    /**
     * Processes an audio chunk with smart VAD.
     *
     * In STANDBY mode: buffers audio, runs VAD, connects to engine when speech detected
     * In TRANSCRIBING mode: streams to engine, runs VAD, disconnects when silence detected
     */
    private fun processAudioChunk(samples: ShortArray) {
        if (!isListening) return

        audioChunkCount++
        val rms = calculateRMS(samples)

        // Always buffer audio (for sending when speech starts)
        synchronized(audioBuffer) {
            audioBuffer.add(samples.copyOf())
            while (audioBuffer.size > AUDIO_BUFFER_MAX_CHUNKS) {
                audioBuffer.removeAt(0)
            }
        }

        if (!isEngineConnected) {
            // STANDBY mode - waiting for speech
            if (rms > SPEECH_RMS_THRESHOLD) {
                consecutiveSpeechChunks++
                consecutiveSilenceChunks = 0

                if (consecutiveSpeechChunks >= SPEECH_CHUNKS_TO_START) {
                    // Speech detected! Connect to engine and start streaming
                    Log.i(TAG, "Speech detected (RMS=$rms), connecting to engine")
                    DebugLog.i("TranscriptionManager: Speech detected, connecting to cloud")
                    connectAndStartStreaming()
                }
            } else {
                consecutiveSpeechChunks = 0
            }
        } else {
            // TRANSCRIBING mode - actively streaming
            currentEngine?.processAudio(samples)

            // Reset silence auto-stop timer
            resetSilenceTimeout()

            if (rms < SILENCE_RMS_THRESHOLD) {
                consecutiveSilenceChunks++
                consecutiveSpeechChunks = 0

                if (consecutiveSilenceChunks >= SILENCE_CHUNKS_TO_STOP) {
                    // Silence detected! Disconnect from engine, return to standby
                    Log.i(TAG, "Silence detected, returning to standby")
                    DebugLog.i("TranscriptionManager: Silence detected, returning to standby")
                    disconnectAndStandby()
                }
            } else {
                consecutiveSilenceChunks = 0
                consecutiveSpeechChunks++
            }
        }

        // Log periodically
        if (audioChunkCount % 100 == 0) {
            val state = if (isEngineConnected) "STREAMING" else "STANDBY"
            DebugLog.d("TranscriptionManager: chunks=$audioChunkCount, RMS=$rms, state=$state")
        }
    }

    /**
     * Calculates RMS (root mean square) of audio samples for VAD.
     */
    private fun calculateRMS(samples: ShortArray): Int {
        if (samples.isEmpty()) return 0
        var sum = 0L
        for (sample in samples) {
            sum += sample.toLong() * sample.toLong()
        }
        return kotlin.math.sqrt(sum.toDouble() / samples.size).toInt()
    }

    /**
     * Connects to the transcription engine and starts streaming.
     * Called when speech is detected in STANDBY mode.
     */
    private fun connectAndStartStreaming() {
        val engine = currentEngine ?: return
        val currentScope = scope ?: return

        isEngineConnected = true
        consecutiveSilenceChunks = 0
        updateState(TranscriptionState.TRANSCRIBING)

        // Clear any stale audio and start session
        engine.clearAudioBuffer()

        engine.startSession(
            onTranscript = { text, isFinal ->
                currentScope.launch(Dispatchers.Main) {
                    if (isListening) {
                        val filteredText = TranscriptionFilter.filter(text)
                        if (filteredText != null) {
                            onTranscript(filteredText, isFinal)
                        } else {
                            DebugLog.d("TranscriptionManager: Filtered out noise: ${text.take(50)}...")
                        }
                    }
                }
            },
            onError = { error ->
                currentScope.launch(Dispatchers.Main) {
                    if (isListening) {
                        DebugLog.e("TranscriptionManager: Engine error - $error")

                        // Connection failed (e.g., went offline) - reset state and return to standby
                        // This allows automatic reconnection when speech is detected again
                        isEngineConnected = false
                        consecutiveSpeechChunks = 0
                        consecutiveSilenceChunks = 0
                        currentEngine?.endSession()

                        // Clear audio buffer to avoid sending stale data on reconnect
                        synchronized(audioBuffer) {
                            audioBuffer.clear()
                        }

                        updateState(TranscriptionState.STANDBY)
                        DebugLog.i("TranscriptionManager: Returned to STANDBY after error (will auto-reconnect)")

                        onError?.invoke(error)
                    }
                }
            }
        )

        // Send buffered audio (so we don't miss the start of speech)
        synchronized(audioBuffer) {
            DebugLog.d("TranscriptionManager: Sending ${audioBuffer.size} buffered chunks")
            for (chunk in audioBuffer) {
                engine.processAudio(chunk)
            }
            audioBuffer.clear()
        }
    }

    /**
     * Disconnects from the transcription engine and returns to STANDBY.
     * Called when silence is detected in TRANSCRIBING mode.
     */
    private fun disconnectAndStandby() {
        isEngineConnected = false
        consecutiveSpeechChunks = 0
        cancelSilenceTimeout()

        currentEngine?.endSession()
        updateState(TranscriptionState.STANDBY)

        DebugLog.i("TranscriptionManager: Returned to STANDBY mode")
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

        // Handle cloud providers (Deepgram is the engine ID for all cloud providers)
        if (preferredEngineId == PreferenceManager.ENGINE_DEEPGRAM) {
            val cloudProvider = preferenceManager.getCloudProvider()
            val autoDetect = preferenceManager.getAutoDetectLanguage()

            Log.d(TAG, "Cloud provider: $cloudProvider, autoDetect: $autoDetect")

            // If auto-fallback is enabled, check connectivity
            if (autoFallback && !isNetworkAvailable(ctx)) {
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
                    (sherpaEngine as? SherpaOnnxEngine)?.setModelVariant(whisperModel)
                    return sherpaEngine
                }

                Log.w(TAG, "No offline models available for fallback")
                DebugLog.w("TranscriptionManager: No offline models available")
            }

            // Select cloud provider
            return when (cloudProvider) {
                PreferenceManager.PROVIDER_GROQ -> {
                    val groqApiKey = preferenceManager.getGroqApiKey()
                    if (groqApiKey.isNullOrBlank()) {
                        Log.e(TAG, "Groq selected but no API key")
                        return null
                    }
                    val engine = GroqEngine(groqApiKey)
                    engine.setModel(preferenceManager.getGroqModel())
                    if (autoDetect) engine.setAutoDetectLanguage(true)
                    Log.i(TAG, "Using Groq engine with model ${preferenceManager.getGroqModel()}")
                    engine
                }
                PreferenceManager.PROVIDER_OPENAI -> {
                    val openaiApiKey = preferenceManager.getOpenAIApiKey()
                    if (openaiApiKey.isNullOrBlank()) {
                        Log.e(TAG, "OpenAI selected but no API key")
                        return null
                    }
                    val engine = OpenAIEngine(openaiApiKey)
                    engine.setModel(preferenceManager.getOpenAIModel())
                    if (autoDetect) engine.setAutoDetectLanguage(true)
                    Log.i(TAG, "Using OpenAI engine with model ${preferenceManager.getOpenAIModel()}")
                    engine
                }
                PreferenceManager.PROVIDER_ASSEMBLYAI -> {
                    val assemblyaiApiKey = preferenceManager.getAssemblyAIApiKey()
                    if (assemblyaiApiKey.isNullOrBlank()) {
                        Log.e(TAG, "AssemblyAI selected but no API key")
                        return null
                    }
                    val engine = AssemblyAIEngine(assemblyaiApiKey)
                    if (autoDetect) engine.setAutoDetectLanguage(true)
                    Log.i(TAG, "Using AssemblyAI engine")
                    engine
                }
                PreferenceManager.PROVIDER_GEMINI -> {
                    val geminiApiKey = preferenceManager.getGeminiApiKey()
                    if (geminiApiKey.isNullOrBlank()) {
                        Log.e(TAG, "Gemini selected but no API key")
                        return null
                    }
                    val engine = GeminiEngine(geminiApiKey)
                    if (autoDetect) engine.setAutoDetectLanguage(true)
                    Log.i(TAG, "Using Gemini engine")
                    engine
                }
                else -> {
                    // Default to Deepgram
                    if (apiKey.isBlank()) {
                        Log.e(TAG, "Deepgram selected but no API key")
                        return null
                    }
                    val engine = engines[PreferenceManager.ENGINE_DEEPGRAM] as? DeepgramEngine
                    engine?.setModel(preferenceManager.getDeepgramModel())
                    if (autoDetect) engine?.setAutoDetectLanguage(true)
                    Log.i(TAG, "Using Deepgram engine with model ${preferenceManager.getDeepgramModel()}")
                    engine
                }
            }
        }

        // Use preferred offline engine
        val engine = engines[preferredEngineId]

        // For Sherpa-ONNX (Whisper), set the selected model variant
        if (preferredEngineId == PreferenceManager.ENGINE_SHERPA_ONNX) {
            (engine as? SherpaOnnxEngine)?.setModelVariant(whisperModel)
            if (engine != null && !engine.isReady(ctx, whisperModel)) {
                Log.e(TAG, "Whisper model $whisperModel not downloaded")
                return null
            }
        } else if (engine != null && !engine.requiresInternet) {
            if (!engine.isReady(ctx, currentLanguage)) {
                Log.e(TAG, "Offline engine not ready (model not downloaded)")
                return null
            }
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
     * Sets terminal mode on the Deepgram engine.
     * In terminal mode, Deepgram disables smart_format, punctuate, and interim_results
     * to provide raw transcription output suitable for terminal apps.
     */
    fun setTerminalMode(enabled: Boolean) {
        val engine = currentEngine ?: engines.values.firstOrNull()
        if (engine is com.tinydictation.android.transcription.engines.DeepgramEngine) {
            engine.terminalMode = enabled
            DebugLog.i("TranscriptionManager: Terminal mode ${if (enabled) "enabled" else "disabled"}")
        }
    }

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
     * Clears any buffered audio in the current engine.
     * Call this when starting a fresh dictation session.
     */
    fun clearEngineAudioBuffer() {
        Log.d(TAG, "TranscriptionManager: Clearing engine audio buffer")
        DebugLog.d("TranscriptionManager: Clearing engine audio buffer")
        currentEngine?.clearAudioBuffer()
    }

    /**
     * Releases all resources.
     */
    fun release() {
        stopListening()
        engines.values.forEach { it.release() }
        engines.clear()
        audioFocusManager?.release()
    }
}
