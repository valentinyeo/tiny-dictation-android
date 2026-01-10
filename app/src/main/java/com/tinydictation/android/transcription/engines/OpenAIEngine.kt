package com.tinydictation.android.transcription.engines

import android.content.Context
import android.util.Log
import com.tinydictation.android.transcription.OpenAIClient
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
 * Cloud transcription engine using OpenAI's transcription API.
 *
 * OpenAI provides high-quality transcription with models like gpt-4o-transcribe.
 * Uses REST API, so we buffer audio and send at utterance boundaries.
 */
class OpenAIEngine(private val apiKey: String) : TranscriptionEngine {

    companion object {
        private const val TAG = "TinyDictation"
        private const val SAMPLE_RATE = 16000

        // Buffer settings
        private const val BUFFER_THRESHOLD_SAMPLES = SAMPLE_RATE * 2 // 2 seconds minimum
        private const val MAX_BUFFER_SAMPLES = SAMPLE_RATE * 30 // 30 seconds max

        // Silence detection threshold
        private const val SILENCE_THRESHOLD = 0.005f
        private const val SILENT_CHUNKS_THRESHOLD = 15 // ~300ms of silence
    }

    override val engineId = "openai"
    override val displayName = "OpenAI"
    override val requiresInternet = true

    private var currentLanguage: String? = null  // null = auto-detect
    private var currentModel = PreferenceManager.OPENAI_GPT4O_TRANSCRIBE
    private var onTranscriptCallback: ((String, Boolean) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null
    private var isSessionActive = false

    private val openAIClient = OpenAIClient()

    // Audio buffer
    private val audioBuffer = mutableListOf<Short>()
    private var processingJob: Job? = null
    private val processingScope = CoroutineScope(Dispatchers.Default)

    // Silence tracking
    private var consecutiveSilentChunks = 0
    private var hasDetectedSpeech = false

    /**
     * Sets the OpenAI model to use.
     */
    fun setModel(model: String) {
        currentModel = model
        Log.i(TAG, "OpenAIEngine: Model set to $model")
    }

    override suspend fun isReady(context: Context, language: String): Boolean {
        return apiKey.isNotBlank()
    }

    override suspend fun initialize(context: Context, language: String): Result<Unit> {
        currentLanguage = language
        if (apiKey.isBlank()) {
            return Result.failure(IllegalStateException("OpenAI API key not configured"))
        }
        Log.i(TAG, "OpenAIEngine: Initialized with model=$currentModel, language=$language")
        DebugLog.i("OpenAIEngine: Initialized")
        return Result.success(Unit)
    }

    override fun startSession(
        onTranscript: (text: String, isFinal: Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
        if (isSessionActive) {
            Log.d(TAG, "OpenAIEngine: Session already active")
            return
        }

        Log.i(TAG, "OpenAIEngine: Starting session")
        DebugLog.i("OpenAIEngine: Starting session")

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

        // Process when end of utterance detected
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
            Log.d(TAG, "OpenAIEngine: Skipping silent audio chunk")
            return
        }

        // Send interim "processing" indicator
        onTranscriptCallback?.invoke("...", false)

        // Encode to WAV
        val wavData = WavEncoder.encode(samples, SAMPLE_RATE)
        Log.i(TAG, "OpenAIEngine: Sending ${wavData.size} bytes to OpenAI API")
        DebugLog.i("OpenAIEngine: Sending ${samples.size} samples (${wavData.size} bytes)")

        // Call the API
        val result = openAIClient.transcribe(
            apiKey = apiKey,
            audioBytes = wavData,
            model = currentModel,
            language = currentLanguage
        )

        result.fold(
            onSuccess = { text ->
                val trimmedText = text.trim()
                if (trimmedText.isNotEmpty()) {
                    Log.i(TAG, "OpenAIEngine: Transcribed: $trimmedText")
                    DebugLog.i("OpenAIEngine: Result: $trimmedText")
                    onTranscriptCallback?.invoke(trimmedText, true)
                }
            },
            onFailure = { error ->
                Log.e(TAG, "OpenAIEngine: Transcription failed", error)
                DebugLog.e("OpenAIEngine: Error - ${error.message}")
                onErrorCallback?.invoke("OpenAI error: ${error.message}")
            }
        )
    }

    override fun endSession() {
        if (!isSessionActive) return

        Log.i(TAG, "OpenAIEngine: Ending session")
        DebugLog.i("OpenAIEngine: Ending session")

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
        currentLanguage = language
        Log.i(TAG, "OpenAIEngine: Language set to $language")
    }

    /**
     * Enable auto language detection.
     */
    fun setAutoDetectLanguage(enabled: Boolean) {
        currentLanguage = if (enabled) null else "en"
        Log.i(TAG, "OpenAIEngine: Auto-detect language = $enabled")
    }

    override fun getSupportedLanguages(): List<String> {
        return listOf("en", "de", "es", "fr", "it", "pt", "nl", "pl", "ru", "zh", "ja", "ko")
    }

    override fun clearAudioBuffer() {
        Log.d(TAG, "OpenAIEngine: Clearing audio buffer")
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
        Log.i(TAG, "OpenAIEngine: Flushing pending audio")
        DebugLog.i("OpenAIEngine: Flushing pending audio")

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
                Log.w(TAG, "OpenAIEngine: Flush timed out")
                return false
            }
        }

        Log.i(TAG, "OpenAIEngine: Flush completed")
        return true
    }

    override fun release() {
        endSession()
    }
}
