package com.tinydictation.android.transcription.engines

import android.content.Context
import android.util.Log
import com.tinydictation.android.transcription.GroqClient
import com.tinydictation.android.transcription.TranscriptionEngine
import com.tinydictation.android.transcription.WavEncoder
import com.tinydictation.android.util.DebugLog
import com.tinydictation.android.util.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Cloud transcription engine using Groq's Whisper API.
 *
 * Groq provides fast inference for Whisper models. Unlike Deepgram which uses
 * WebSocket streaming, Groq uses a REST API, so we buffer audio and send
 * at utterance boundaries.
 */
class GroqEngine(private val apiKey: String) : TranscriptionEngine {

    companion object {
        private const val TAG = "TinyDictation"
        private const val SAMPLE_RATE = 16000

        // Buffer settings - send after ~2 seconds minimum for better accuracy
        private const val BUFFER_THRESHOLD_SAMPLES = SAMPLE_RATE * 2 // 2 seconds minimum
        private const val MAX_BUFFER_SAMPLES = SAMPLE_RATE * 30 // 30 seconds max

        // Silence detection threshold (RMS value)
        private const val SILENCE_THRESHOLD = 0.005f
        private const val SILENT_CHUNKS_THRESHOLD = 15 // ~300ms of silence triggers processing
    }

    override val engineId = "groq"
    override val displayName = "Groq (Whisper)"
    override val requiresInternet = true

    private var currentLanguage: String? = null  // null = auto-detect
    private var currentModel = PreferenceManager.GROQ_WHISPER_LARGE_V3_TURBO
    private var onTranscriptCallback: ((String, Boolean) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null
    private var isSessionActive = false

    private val groqClient = GroqClient()

    // Audio buffer (store as shorts for WAV encoding)
    private val audioBuffer = mutableListOf<Short>()
    private var processingJob: Job? = null
    private val processingScope = CoroutineScope(Dispatchers.Default)

    // Silence tracking
    private var consecutiveSilentChunks = 0
    private var hasDetectedSpeech = false

    /**
     * Sets the Groq model to use.
     */
    fun setModel(model: String) {
        currentModel = model
        Log.i(TAG, "GroqEngine: Model set to $model")
    }

    override suspend fun isReady(context: Context, language: String): Boolean {
        return apiKey.isNotBlank()
    }

    override suspend fun initialize(context: Context, language: String): Result<Unit> {
        currentLanguage = language
        if (apiKey.isBlank()) {
            return Result.failure(IllegalStateException("Groq API key not configured"))
        }
        Log.i(TAG, "GroqEngine: Initialized with model=$currentModel, language=$language")
        DebugLog.i("GroqEngine: Initialized")
        return Result.success(Unit)
    }

    override fun startSession(
        onTranscript: (text: String, isFinal: Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
        if (isSessionActive) {
            Log.d(TAG, "GroqEngine: Session already active")
            return
        }

        Log.i(TAG, "GroqEngine: Starting session")
        DebugLog.i("GroqEngine: Starting session")

        onTranscriptCallback = onTranscript
        onErrorCallback = onError
        isSessionActive = true
        consecutiveSilentChunks = 0
        hasDetectedSpeech = false
    }

    override fun processAudio(samples: ShortArray) {
        if (!isSessionActive) return

        // Calculate RMS to detect speech vs silence
        var sumSquares = 0.0
        for (sample in samples) {
            val normalized = sample / 32768.0f
            sumSquares += normalized * normalized
        }
        val rms = kotlin.math.sqrt(sumSquares / samples.size).toFloat()

        // Track speech/silence
        val isSilent = rms < SILENCE_THRESHOLD
        if (isSilent) {
            consecutiveSilentChunks++
        } else {
            consecutiveSilentChunks = 0
            hasDetectedSpeech = true
        }

        // Buffer the audio
        synchronized(audioBuffer) {
            audioBuffer.addAll(samples.toList())
        }

        // Process when:
        // 1. We have enough audio AND detected speech AND now have silence (end of utterance)
        // 2. OR buffer is getting too large
        val shouldProcess = (audioBuffer.size >= BUFFER_THRESHOLD_SAMPLES &&
                hasDetectedSpeech &&
                consecutiveSilentChunks >= SILENT_CHUNKS_THRESHOLD &&
                processingJob?.isActive != true)

        if (shouldProcess) {
            processBufferAsync()
            hasDetectedSpeech = false
            consecutiveSilentChunks = 0
        }

        // Prevent buffer from growing too large
        if (audioBuffer.size > MAX_BUFFER_SAMPLES && processingJob?.isActive != true) {
            processBufferAsync()
            hasDetectedSpeech = false
            consecutiveSilentChunks = 0
        }
    }

    private fun processBufferAsync() {
        processingJob = processingScope.launch {
            processBuffer()
        }
    }

    private suspend fun processBuffer() {
        // Get samples to process
        val samples: ShortArray
        synchronized(audioBuffer) {
            if (audioBuffer.isEmpty()) return
            samples = audioBuffer.toShortArray()
            audioBuffer.clear()
        }

        // Check if audio has enough energy
        var sumSquares = 0.0
        for (sample in samples) {
            val normalized = sample / 32768.0f
            sumSquares += normalized * normalized
        }
        val rms = kotlin.math.sqrt(sumSquares / samples.size).toFloat()
        if (rms < SILENCE_THRESHOLD) {
            Log.d(TAG, "GroqEngine: Skipping silent audio chunk")
            return
        }

        // Send interim "processing" indicator
        onTranscriptCallback?.invoke("...", false)

        // Encode to WAV
        val wavData = WavEncoder.encode(samples, SAMPLE_RATE)
        Log.i(TAG, "GroqEngine: Sending ${wavData.size} bytes to Groq API")
        DebugLog.i("GroqEngine: Sending ${samples.size} samples (${wavData.size} bytes)")

        // Call the API
        val result = groqClient.transcribe(
            apiKey = apiKey,
            audioBytes = wavData,
            model = currentModel,
            language = currentLanguage
        )

        result.fold(
            onSuccess = { text ->
                val trimmedText = text.trim()
                if (trimmedText.isNotEmpty()) {
                    Log.i(TAG, "GroqEngine: Transcribed: $trimmedText")
                    DebugLog.i("GroqEngine: Result: $trimmedText")
                    onTranscriptCallback?.invoke(trimmedText, true)
                }
            },
            onFailure = { error ->
                Log.e(TAG, "GroqEngine: Transcription failed", error)
                DebugLog.e("GroqEngine: Error - ${error.message}")
                onErrorCallback?.invoke("Groq error: ${error.message}")
            }
        )
    }

    override fun endSession() {
        if (!isSessionActive) return

        Log.i(TAG, "GroqEngine: Ending session")
        DebugLog.i("GroqEngine: Ending session")

        isSessionActive = false

        // Process any remaining audio
        if (audioBuffer.isNotEmpty()) {
            processingScope.launch {
                processBuffer()
            }
        }

        processingJob?.cancel()
        processingJob = null
        onTranscriptCallback = null
        onErrorCallback = null
    }

    override fun setLanguage(language: String) {
        // For Whisper, null = auto-detect, otherwise use specified language
        currentLanguage = language
        Log.i(TAG, "GroqEngine: Language set to $language")
    }

    /**
     * Enable auto language detection (Whisper feature).
     */
    fun setAutoDetectLanguage(enabled: Boolean) {
        currentLanguage = if (enabled) null else "en"
        Log.i(TAG, "GroqEngine: Auto-detect language = $enabled")
    }

    override fun getSupportedLanguages(): List<String> {
        // Whisper supports many languages
        return listOf("en", "de", "es", "fr", "it", "pt", "nl", "pl", "ru", "zh", "ja", "ko")
    }

    override fun clearAudioBuffer() {
        Log.d(TAG, "GroqEngine: Clearing audio buffer")
        synchronized(audioBuffer) {
            audioBuffer.clear()
        }
        consecutiveSilentChunks = 0
        hasDetectedSpeech = false
    }

    override fun hasPendingAudio(): Boolean {
        val hasBufferedAudio = synchronized(audioBuffer) { audioBuffer.isNotEmpty() }
        val isProcessing = processingJob?.isActive == true
        return hasBufferedAudio || isProcessing
    }

    override suspend fun flushPendingAudio(timeoutMs: Long): Boolean {
        Log.i(TAG, "GroqEngine: Flushing pending audio")
        DebugLog.i("GroqEngine: Flushing pending audio")

        // Wait for any current processing
        processingJob?.let { job ->
            withTimeoutOrNull(timeoutMs / 2) {
                job.join()
            }
        }

        // Process remaining buffer
        val hasBufferedAudio = synchronized(audioBuffer) { audioBuffer.isNotEmpty() }
        if (hasBufferedAudio) {
            processBuffer()
        }

        // Wait for that processing
        processingJob?.let { job ->
            val completed = withTimeoutOrNull(timeoutMs / 2) {
                job.join()
                true
            } ?: false

            if (!completed) {
                Log.w(TAG, "GroqEngine: Flush timed out")
                return false
            }
        }

        Log.i(TAG, "GroqEngine: Flush completed")
        return true
    }

    override fun release() {
        endSession()
    }
}
