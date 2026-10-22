package com.tinydictation.android.transcription

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.tinydictation.android.audio.AudioRecorder
import com.tinydictation.android.transcription.engines.DeepgramEngine
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

    enum class TranscriptionState {
        IDLE,
        STANDBY,
        LISTENING,
        TRANSCRIBING,
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

    private var silenceTimeoutJob: Job? = null

    private var isEngineConnected = false
    private val audioBuffer = mutableListOf<ShortArray>()
    private val AUDIO_BUFFER_MAX_CHUNKS = 25
    private val streamGate = StreamGate()
    private var directStreamingStartedAt: Long? = null

    private val engines = mutableMapOf<String, TranscriptionEngine>()

    init {
        engines[PreferenceManager.ENGINE_DEEPGRAM] = DeepgramEngine(apiKey)
        engines[PreferenceManager.ENGINE_VOSK] = VoskEngine()
        engines[PreferenceManager.ENGINE_SHERPA_ONNX] = SherpaOnnxEngine()

        currentLanguage = preferenceManager?.getLanguage() ?: "en"
    }

    private fun resetSilenceTimeout() {
        val timeoutMs = preferenceManager?.getSilenceAutoStopMs() ?: 0
        if (timeoutMs <= 0) return

        val currentScope = scope ?: return

        silenceTimeoutJob?.cancel()

        silenceTimeoutJob = currentScope.launch(Dispatchers.Main) {
            delay(timeoutMs.toLong())

            if (isListening) {
                Log.i(TAG, "Silence auto-stop triggered after ${timeoutMs}ms of silence")
                DebugLog.i("TranscriptionManager: Auto-stopping after ${timeoutMs}ms of silence")
                onSilenceAutoStop?.invoke()
            }
        }
    }

    private fun cancelSilenceTimeout() {
        silenceTimeoutJob?.cancel()
        silenceTimeoutJob = null
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startListening() {
        if (isListening) {
            Log.d(TAG, "TranscriptionManager already listening")
            return
        }

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

        currentLanguage = preferenceManager?.getLanguage() ?: "en"

        Log.i(TAG, "TranscriptionManager starting in STANDBY mode (language: $currentLanguage)")
        DebugLog.i("TranscriptionManager: Starting in STANDBY mode (language: $currentLanguage)")
        isListening = true
        isEngineConnected = false
        audioChunkCount = 0
        streamGate.start()
        directStreamingStartedAt = null
        audioBuffer.clear()

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

            val initResult = engine.initialize(context!!, currentLanguage)
            if (initResult.isFailure) {
                currentScope.launch(Dispatchers.Main) {
                    updateState(TranscriptionState.ERROR)
                    onError?.invoke("Failed to initialize engine: ${initResult.exceptionOrNull()?.message}")
                }
                return@launch
            }

            currentScope.launch(Dispatchers.Main) {
                updateState(TranscriptionState.STANDBY)
            }

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

    @Synchronized
    fun stopListening() {
        if (!isListening) {
            Log.d(TAG, "TranscriptionManager not listening")
            return
        }

        Log.i(TAG, "TranscriptionManager stopping listening")
        DebugLog.i("TranscriptionManager: Stopping listening")
        isListening = false

        cancelSilenceTimeout()

        streamGate.stop()
        isEngineConnected = false
        directStreamingStartedAt = null
        audioRecorder.stopRecording()
        currentEngine?.endSession()

        audioFocusManager?.abandonAudioFocus()

        scope?.cancel()
        scope = null

        updateState(TranscriptionState.IDLE)
    }

    @Synchronized
    private fun processAudioChunk(samples: ShortArray) {
        if (!isListening) return

        audioChunkCount++
        val rms = calculateRMS(samples)

        synchronized(audioBuffer) {
            audioBuffer.add(samples.copyOf())
            while (audioBuffer.size > AUDIO_BUFFER_MAX_CHUNKS) {
                audioBuffer.removeAt(0)
            }
        }

        when (streamGate.process(rms, SystemClock.elapsedRealtime())) {
            StreamGate.Action.OPEN -> connectAndStartStreaming()
            StreamGate.Action.SEND -> {
                if (streamGate.resumeChunks > 0) {
                    synchronized(audioBuffer) {
                        audioBuffer.takeLast(streamGate.resumeChunks).forEach {
                            currentEngine?.processAudio(it)
                        }
                    }
                } else {
                    currentEngine?.processAudio(samples)
                }
            }
            StreamGate.Action.KEEPALIVE -> currentEngine?.sendKeepAlive()
            StreamGate.Action.CLOSE -> disconnectAndStandby()
            StreamGate.Action.NONE -> Unit
        }
        if (isEngineConnected) resetSilenceTimeout()

        if (audioChunkCount % 100 == 0) {
            val state = if (isEngineConnected) "STREAMING" else "STANDBY"
            DebugLog.d("TranscriptionManager: chunks=$audioChunkCount, RMS=$rms, state=$state")
        }
    }

    private fun calculateRMS(samples: ShortArray): Int {
        if (samples.isEmpty()) return 0
        var sum = 0L
        for (sample in samples) {
            sum += sample.toLong() * sample.toLong()
        }
        return kotlin.math.sqrt(sum.toDouble() / samples.size).toInt()
    }

    private fun connectAndStartStreaming() {
        val engine = currentEngine ?: return
        val currentScope = scope ?: return

        isEngineConnected = true
        updateState(TranscriptionState.TRANSCRIBING)

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
                        DebugLog.e("TranscriptionManager: Engine error - ${error.message}")
                        if (error.fatal) {
                            isEngineConnected = false
                            synchronized(audioBuffer) { audioBuffer.clear() }
                            stopListening()
                            onError?.invoke(error.message)
                            return@launch
                        }

                        isEngineConnected = false
                        streamGate.start()
                        currentEngine?.endSession()

                        synchronized(audioBuffer) {
                            audioBuffer.clear()
                        }

                        updateState(TranscriptionState.STANDBY)
                        DebugLog.i("TranscriptionManager: Returned to STANDBY after error (will auto-reconnect)")

                        onError?.invoke(error.message)
                    }
                }
            }
        )

        synchronized(audioBuffer) {
            DebugLog.d("TranscriptionManager: Sending ${audioBuffer.size} buffered chunks")
            for (chunk in audioBuffer) {
                engine.processAudio(chunk)
            }
            audioBuffer.clear()
        }
    }

    private fun disconnectAndStandby() {
        isEngineConnected = false
        cancelSilenceTimeout()

        currentEngine?.endSession()
        updateState(TranscriptionState.STANDBY)

        DebugLog.i("TranscriptionManager: Returned to STANDBY mode")
    }


    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startBufferingOnly() {
        if (isListening) {
            Log.d(TAG, "TranscriptionManager already listening (PTT buffer)")
            return
        }

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

        currentLanguage = preferenceManager?.getLanguage() ?: "en"

        Log.i(TAG, "TranscriptionManager starting PTT buffering (language: $currentLanguage)")
        DebugLog.i("TranscriptionManager: Starting PTT buffering")
        isListening = true
        isEngineConnected = false
        streamGate.stop()
        directStreamingStartedAt = null
        audioChunkCount = 0
        audioBuffer.clear()

        if (preferenceManager?.getPauseMediaDuringDictation() == true) {
            audioFocusManager?.requestAudioFocus(duck = false)
        }

        scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        val currentScope = scope ?: run {
            updateState(TranscriptionState.ERROR)
            onError?.invoke("Failed to initialize transcription manager")
            return
        }

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
            Log.i(TAG, "PTT: Pre-selected engine: ${engine.displayName}")

            val initResult = engine.initialize(context!!, currentLanguage)
            if (initResult.isFailure) {
                currentScope.launch(Dispatchers.Main) {
                    updateState(TranscriptionState.ERROR)
                    onError?.invoke("Failed to initialize engine: ${initResult.exceptionOrNull()?.message}")
                }
                return@launch
            }

            currentScope.launch(Dispatchers.Main) {
                updateState(TranscriptionState.STANDBY)
            }

            currentScope.launch(Dispatchers.Main) {
                try {
                    val useHeadphoneMic = preferenceManager?.getUseHeadphoneMic() ?: false
                    audioRecorder.startRecording(currentScope, context!!, useHeadphoneMic) { samples ->
                        processAudioChunkPTT(samples)
                    }
                    DebugLog.i("TranscriptionManager: PTT audio recording started (buffering)")
                } catch (e: Exception) {
                    Log.e(TAG, "PTT: Failed to start audio recording", e)
                    updateState(TranscriptionState.ERROR)
                    onError?.invoke("Failed to start audio recording: ${e.message}")
                }
            }
        }
    }

    @Synchronized
    private fun processAudioChunkPTT(samples: ShortArray) {
        if (!isListening) return

        audioChunkCount++
        val startedAt = directStreamingStartedAt
        if (startedAt != null && SystemClock.elapsedRealtime() - startedAt >= StreamGate.MAX_OPEN_MS) {
            stopBuffering()
            return
        }

        if (!isEngineConnected) {
            synchronized(audioBuffer) {
                audioBuffer.add(samples.copyOf())
                while (audioBuffer.size > 500) {
                    audioBuffer.removeAt(0)
                }
            }
        } else {
            currentEngine?.processAudio(samples)
        }

        if (audioChunkCount % 100 == 0) {
            val state = if (isEngineConnected) "PTT_STREAMING" else "PTT_BUFFERING"
            DebugLog.d("TranscriptionManager: PTT chunks=$audioChunkCount, state=$state")
        }
    }

    @Synchronized
    fun startDirectStreaming() {
        val engine = currentEngine ?: run {
            Log.e(TAG, "PTT: No engine available for streaming")
            return
        }
        val currentScope = scope ?: return

        if (!isListening || isEngineConnected) return
        directStreamingStartedAt = SystemClock.elapsedRealtime()
        Log.i(TAG, "PTT: Starting direct streaming")
        DebugLog.i("TranscriptionManager: PTT streaming started")

        isEngineConnected = true
        updateState(TranscriptionState.TRANSCRIBING)

        engine.clearAudioBuffer()

        engine.startSession(
            onTranscript = { text, isFinal ->
                currentScope.launch(Dispatchers.Main) {
                    if (isListening) {
                        val filteredText = TranscriptionFilter.filter(text)
                        if (filteredText != null) {
                            onTranscript(filteredText, isFinal)
                        }
                    }
                }
            },
            onError = { error ->
                currentScope.launch(Dispatchers.Main) {
                    if (isListening) {
                        DebugLog.e("TranscriptionManager: PTT engine error - ${error.message}")
                        if (error.fatal) {
                            isEngineConnected = false
                            synchronized(audioBuffer) { audioBuffer.clear() }
                            stopListening()
                            onError?.invoke(error.message)
                            return@launch
                        }
                        isEngineConnected = false
                        currentEngine?.endSession()
                        synchronized(audioBuffer) { audioBuffer.clear() }
                        updateState(TranscriptionState.STANDBY)
                        onError?.invoke(error.message)
                    }
                }
            }
        )

        synchronized(audioBuffer) {
            DebugLog.d("TranscriptionManager: PTT flushing ${audioBuffer.size} buffered chunks")
            for (chunk in audioBuffer) {
                engine.processAudio(chunk)
            }
            audioBuffer.clear()
        }
    }

    @Synchronized
    fun stopBuffering(discard: Boolean = false) {
        if (!isListening) return

        Log.i(TAG, "TranscriptionManager stopping PTT (discard=$discard, wasStreaming=$isEngineConnected)")
        DebugLog.i("TranscriptionManager: Stopping PTT (discard=$discard)")

        val wasStreaming = isEngineConnected
        streamGate.stop()
        directStreamingStartedAt = null
        cancelSilenceTimeout()
        isListening = false
        isEngineConnected = false

        audioRecorder.stopRecording()

        if (wasStreaming && !discard) {
            currentEngine?.endSession()
        } else {
            currentEngine?.endSession()
            synchronized(audioBuffer) { audioBuffer.clear() }
        }

        audioFocusManager?.abandonAudioFocus()

        scope?.cancel()
        scope = null

        updateState(TranscriptionState.IDLE)
    }

    private suspend fun selectEngine(): TranscriptionEngine? {
        val ctx = context ?: return engines[PreferenceManager.ENGINE_DEEPGRAM]

        val preferenceManager = PreferenceManager(ctx)
        val preferredEngineId = preferenceManager.getTranscriptionEngine()
        val autoFallback = preferenceManager.getAutoFallback()
        val whisperModel = preferenceManager.getWhisperModel()

        Log.d(TAG, "Preferred engine: $preferredEngineId, autoFallback: $autoFallback, whisperModel: $whisperModel")

        if (preferredEngineId == PreferenceManager.ENGINE_DEEPGRAM) {
            val autoDetect = preferenceManager.getAutoDetectLanguage()

            if (autoFallback && !isNetworkAvailable(ctx)) {
                Log.i(TAG, "No network, attempting fallback to offline engine")
                DebugLog.i("TranscriptionManager: No network, falling back to offline")

                val voskEngine = engines[PreferenceManager.ENGINE_VOSK]
                if (voskEngine != null && voskEngine.isReady(ctx, currentLanguage)) {
                    return voskEngine
                }

                val sherpaEngine = engines[PreferenceManager.ENGINE_SHERPA_ONNX]
                if (sherpaEngine != null && sherpaEngine.isReady(ctx, whisperModel)) {
                    (sherpaEngine as? SherpaOnnxEngine)?.setModelVariant(whisperModel)
                    return sherpaEngine
                }

                Log.w(TAG, "No offline models available for fallback")
                DebugLog.w("TranscriptionManager: No offline models available")
            }

            if (apiKey.isBlank()) {
                Log.e(TAG, "Deepgram selected but no API key")
                return null
            }
            val engine = engines[PreferenceManager.ENGINE_DEEPGRAM] as? DeepgramEngine
            engine?.setModel(preferenceManager.getDeepgramModel())
            if (autoDetect) engine?.setAutoDetectLanguage(true)
            engine?.smartFormat = preferenceManager.getSmartFormat()
            engine?.punctuate = preferenceManager.getAutoPunctuation()
            engine?.keywords = preferenceManager.getDeepgramKeywords(currentLanguage)
            engine?.replacements = preferenceManager.getDeepgramReplacements(currentLanguage)
            engine?.numerals = preferenceManager.getDeepgramNumerals()
            engine?.dictation = preferenceManager.getDeepgramDictation()
            Log.i(TAG, "Using Deepgram engine with model ${preferenceManager.getDeepgramModel()}")
            return engine
        }

        val engine = engines[preferredEngineId]

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

    private fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

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

    fun setLanguage(language: String) {
        if (currentLanguage == language) return

        Log.i(TAG, "Changing language from $currentLanguage to $language")
        DebugLog.i("TranscriptionManager: Language changed to $language")
        currentLanguage = language

        currentEngine?.setLanguage(language)
    }

    fun getActiveEngineId(): String? = currentEngine?.engineId

    fun setTerminalMode(enabled: Boolean) {
        val engine = currentEngine ?: engines.values.firstOrNull()
        if (engine is com.tinydictation.android.transcription.engines.DeepgramEngine) {
            engine.terminalMode = enabled
            DebugLog.i("TranscriptionManager: Terminal mode ${if (enabled) "enabled" else "disabled"}")
        }
    }

    fun hasPendingAudio(): Boolean {
        return currentEngine?.hasPendingAudio() == true
    }

    suspend fun flushPendingTranscription(timeoutMs: Long = 10000): Boolean {
        val engine = currentEngine ?: return true
        Log.i(TAG, "TranscriptionManager: Flushing pending transcription")
        DebugLog.i("TranscriptionManager: Flushing pending transcription")
        return engine.flushPendingAudio(timeoutMs)
    }

    fun clearEngineAudioBuffer() {
        Log.d(TAG, "TranscriptionManager: Clearing engine audio buffer")
        DebugLog.d("TranscriptionManager: Clearing engine audio buffer")
        currentEngine?.clearAudioBuffer()
    }

    fun release() {
        stopListening()
        engines.values.forEach { it.release() }
        engines.clear()
        audioFocusManager?.release()
    }
}
