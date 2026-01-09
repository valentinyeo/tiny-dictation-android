package com.tinydictation.android.transcription

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.content.ContextCompat
import com.tinydictation.android.audio.AudioRecorder
import com.tinydictation.android.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Orchestrates audio recording and transcription with continuous streaming.
 *
 * This manager coordinates the AudioRecorder and DeepgramClient to provide
 * low-latency speech-to-text. It connects to Deepgram immediately and streams
 * all audio continuously - Deepgram handles speech detection internally.
 *
 * Flow:
 * 1. Connect to Deepgram immediately when startListening() is called
 * 2. Stream all audio continuously to Deepgram
 * 3. Deepgram returns transcriptions as it detects speech
 * 4. Connection stays open until stopListening() is called
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
    private val deepgramClient = DeepgramClient()

    private var scope: CoroutineScope? = null
    private var isListening = false
    private var currentState = TranscriptionState.IDLE

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
            DebugLog.e("TranscriptionManager: Cannot start - API key is blank")
            updateState(TranscriptionState.ERROR)
            onError?.invoke("API key is not set")
            return
        }

        Log.i(TAG, "TranscriptionManager starting listening (continuous mode)")
        DebugLog.i("TranscriptionManager: Starting continuous listening")
        isListening = true
        audioChunkCount = 0
        audioSentCount = 0

        scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        val currentScope = scope
        if (currentScope == null) {
            Log.e(TAG, "Failed to create coroutine scope")
            updateState(TranscriptionState.ERROR)
            onError?.invoke("Failed to initialize transcription manager")
            return
        }

        // Connect to Deepgram immediately for low-latency streaming
        updateState(TranscriptionState.TRANSCRIBING)
        DebugLog.i("TranscriptionManager: Connecting to Deepgram immediately")

        currentScope.launch(Dispatchers.IO) {
            deepgramClient.connect(
                apiKey = apiKey,
                onInterim = { text ->
                    currentScope.launch(Dispatchers.Main) {
                        if (isListening) {
                            onTranscript(text, false)
                        }
                    }
                },
                onFinal = { text ->
                    currentScope.launch(Dispatchers.Main) {
                        if (isListening) {
                            onTranscript(text, true)
                        }
                    }
                },
                onError = { error ->
                    currentScope.launch(Dispatchers.Main) {
                        if (isListening) {
                            DebugLog.e("TranscriptionManager: Deepgram error - $error")
                            onError?.invoke(error)
                        }
                    }
                }
            )
        }

        // Start recording immediately - no delay for lowest latency
        // Audio will be buffered briefly until connection establishes
        try {
            audioRecorder.startRecording(currentScope) { samples ->
                processAudioChunk(samples)
            }
            DebugLog.i("TranscriptionManager: Audio recording started immediately")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start audio recording", e)
            DebugLog.e("TranscriptionManager: Failed to start audio recording - ${e.message}")
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
        DebugLog.i("TranscriptionManager: Stopping continuous listening")
        isListening = false

        audioRecorder.stopRecording()
        deepgramClient.close()

        scope?.cancel()
        scope = null

        updateState(TranscriptionState.IDLE)
    }

    /** Counter for tracking audio chunks */
    private var audioChunkCount = 0

    /** Counter for audio chunks sent to Deepgram */
    private var audioSentCount = 0

    /**
     * Processes an audio chunk - sends directly to Deepgram for continuous streaming.
     */
    private fun processAudioChunk(samples: ShortArray) {
        if (!isListening) return

        audioChunkCount++

        // Log periodically (every 100 chunks = ~2 seconds)
        if (audioChunkCount % 100 == 0) {
            DebugLog.d("TranscriptionManager: Streamed $audioChunkCount chunks, sent $audioSentCount to Deepgram")
        }

        // Send directly to Deepgram - continuous streaming mode
        if (deepgramClient.isConnected()) {
            sendAudioToDeepgram(samples)
        }
    }

    /**
     * Sends audio samples to Deepgram.
     */
    private fun sendAudioToDeepgram(samples: ShortArray) {
        val audioBytes = audioRecorder.shortArrayToByteArray(samples)
        audioSentCount++

        // Log first few sends to confirm it's working
        if (audioSentCount <= 3) {
            Log.d(TAG, "Sending audio chunk #$audioSentCount to Deepgram (${audioBytes.size} bytes)")
            DebugLog.d("TranscriptionManager: Sending chunk #$audioSentCount to Deepgram")
        }

        scope?.launch(Dispatchers.IO) {
            val sent = deepgramClient.sendAudio(audioBytes)
            if (!sent && audioSentCount <= 5) {
                Log.w(TAG, "Failed to send audio chunk #$audioSentCount")
                DebugLog.w("TranscriptionManager: Failed to send chunk #$audioSentCount")
            }
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
}
