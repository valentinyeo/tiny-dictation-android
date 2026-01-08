package com.tinydictation.android.transcription

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.tinydictation.android.audio.AudioRecorder
import com.tinydictation.android.vad.VADResult
import com.tinydictation.android.vad.VoiceActivityDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Orchestrates audio recording, voice activity detection, and transcription.
 *
 * This manager coordinates the AudioRecorder, VoiceActivityDetector, and DeepgramClient
 * to provide a seamless speech-to-text experience. It only connects to Deepgram
 * when speech is detected, saving API costs and bandwidth.
 *
 * Flow:
 * 1. Audio recording starts immediately when startListening() is called
 * 2. VAD monitors audio for speech
 * 3. When speech starts, connect to Deepgram and start streaming
 * 4. When speech ends, close Deepgram connection
 * 5. Repeat until stopListening() is called
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
        /** Not listening, idle state */
        IDLE,

        /** Listening for speech but not currently detecting any */
        LISTENING,

        /** Speech detected, streaming to Deepgram */
        TRANSCRIBING,

        /** Error occurred */
        ERROR
    }

    private val audioRecorder = AudioRecorder()
    private val voiceActivityDetector = VoiceActivityDetector()
    private val deepgramClient = DeepgramClient()

    private var scope: CoroutineScope? = null
    private var isListening = false
    private var currentState = TranscriptionState.IDLE

    // Buffer for audio during connection
    private val pendingAudioBuffer = mutableListOf<ShortArray>()
    private var isConnecting = false

    /**
     * Starts listening for speech and transcribing.
     *
     * Audio recording begins immediately. When speech is detected,
     * a connection to Deepgram is established and audio is streamed.
     *
     * @throws SecurityException if RECORD_AUDIO permission is not granted
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

        if (apiKey.isBlank()) {
            Log.e(TAG, "Cannot start listening - API key is blank")
            updateState(TranscriptionState.ERROR)
            onError?.invoke("API key is not set")
            return
        }

        Log.i(TAG, "TranscriptionManager starting listening")
        isListening = true
        voiceActivityDetector.reset()
        pendingAudioBuffer.clear()

        scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        updateState(TranscriptionState.LISTENING)

        val currentScope = scope
        if (currentScope == null) {
            Log.e(TAG, "Failed to create coroutine scope")
            updateState(TranscriptionState.ERROR)
            onError?.invoke("Failed to initialize transcription manager")
            return
        }

        try {
            audioRecorder.startRecording(currentScope) { samples ->
                processAudioChunk(samples)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio recording", e)
            updateState(TranscriptionState.ERROR)
            onError?.invoke("Failed to start audio recording: ${e.message}")
            scope?.cancel()
            scope = null
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
        isListening = false

        audioRecorder.stopRecording()
        deepgramClient.close()
        voiceActivityDetector.reset()
        pendingAudioBuffer.clear()
        isConnecting = false

        scope?.cancel()
        scope = null

        updateState(TranscriptionState.IDLE)
    }

    /**
     * Processes an audio chunk through VAD and handles state transitions.
     */
    private fun processAudioChunk(samples: ShortArray) {
        if (!isListening) return

        val vadResult = voiceActivityDetector.processSamples(samples)

        when (vadResult) {
            VADResult.NO_SPEECH -> {
                // Nothing to do - waiting for speech
            }

            VADResult.SPEECH_START -> {
                Log.i(TAG, "Speech start detected")
                handleSpeechStart(samples)
            }

            VADResult.SPEECH_CONTINUE -> {
                handleSpeechContinue(samples)
            }

            VADResult.SPEECH_END -> {
                Log.i(TAG, "Speech end detected")
                handleSpeechEnd()
            }
        }
    }

    /**
     * Handles the start of speech - connects to Deepgram.
     */
    private fun handleSpeechStart(samples: ShortArray) {
        Log.i(TAG, "Handling speech start - connecting to Deepgram")
        updateState(TranscriptionState.TRANSCRIBING)

        // Buffer this chunk while we connect
        pendingAudioBuffer.add(samples.copyOf())
        isConnecting = true

        scope?.launch(Dispatchers.IO) {
            deepgramClient.connect(
                apiKey = apiKey,
                onInterim = { text ->
                    scope?.launch(Dispatchers.Main) {
                        if (isListening) {
                            onTranscript(text, false)
                        }
                    }
                },
                onFinal = { text ->
                    scope?.launch(Dispatchers.Main) {
                        if (isListening) {
                            onTranscript(text, true)
                        }
                    }
                },
                onError = { error ->
                    scope?.launch(Dispatchers.Main) {
                        if (isListening) {
                            onError?.invoke(error)
                            // Don't stop listening on error - just go back to listening state
                            updateState(TranscriptionState.LISTENING)
                        }
                    }
                }
            )

            // Small delay to ensure connection is established
            kotlinx.coroutines.delay(100)

            // Send buffered audio
            scope?.launch(Dispatchers.Main) {
                isConnecting = false

                if (deepgramClient.isConnected()) {
                    Log.d(TAG, "Deepgram connected, sending ${pendingAudioBuffer.size} buffered chunks")
                    // Send all buffered audio
                    for (bufferedSamples in pendingAudioBuffer) {
                        sendAudioToDeepgram(bufferedSamples)
                    }
                    pendingAudioBuffer.clear()
                } else {
                    Log.e(TAG, "Deepgram connection failed, discarding buffered audio")
                    pendingAudioBuffer.clear()
                }
            }
        }
    }

    /**
     * Handles continuing speech - streams audio to Deepgram.
     */
    private fun handleSpeechContinue(samples: ShortArray) {
        if (isConnecting) {
            // Still connecting - buffer the audio
            pendingAudioBuffer.add(samples.copyOf())

            // Limit buffer size to prevent memory issues
            if (pendingAudioBuffer.size > 100) { // ~2 seconds of audio
                pendingAudioBuffer.removeAt(0)
            }
        } else if (deepgramClient.isConnected()) {
            // Send audio to Deepgram
            sendAudioToDeepgram(samples)
        }
    }

    /**
     * Handles end of speech - closes Deepgram connection.
     */
    private fun handleSpeechEnd() {
        Log.i(TAG, "Handling speech end - closing Deepgram connection")
        isConnecting = false
        pendingAudioBuffer.clear()

        scope?.launch(Dispatchers.IO) {
            deepgramClient.close()
        }

        updateState(TranscriptionState.LISTENING)
    }

    /**
     * Sends audio samples to Deepgram.
     */
    private fun sendAudioToDeepgram(samples: ShortArray) {
        val audioBytes = audioRecorder.shortArrayToByteArray(samples)

        scope?.launch(Dispatchers.IO) {
            deepgramClient.sendAudio(audioBytes)
        }
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

    /**
     * Returns the current state of the transcription manager.
     */
    fun getState(): TranscriptionState = currentState

    /**
     * Returns whether the manager is currently listening.
     */
    fun isListening(): Boolean = isListening

    /**
     * Returns whether speech is currently being transcribed.
     */
    fun isTranscribing(): Boolean = currentState == TranscriptionState.TRANSCRIBING

    /**
     * Updates the VAD sensitivity settings.
     *
     * @param speechThreshold RMS threshold for speech detection
     * @param silenceThreshold RMS threshold for silence detection
     */
    fun updateVadSettings(speechThreshold: Int, silenceThreshold: Int) {
        // Note: In a future update, we could make VoiceActivityDetector
        // configurable at runtime. For now, you'd need to create a new
        // TranscriptionManager with a custom VAD.
    }
}
