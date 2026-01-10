package com.tinydictation.android.transcription.engines

import android.content.Context
import android.util.Log
import com.tinydictation.android.transcription.GeminiClient
import com.tinydictation.android.transcription.TranscriptionEngine
import com.tinydictation.android.transcription.WavEncoder
import com.tinydictation.android.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.sqrt

/**
 * Cloud-based transcription engine using Google's Gemini API.
 *
 * Uses Gemini 2.5 Flash for audio transcription.
 * This engine buffers audio and sends it when silence is detected,
 * similar to the Groq and OpenAI engines.
 */
class GeminiEngine(private val apiKey: String) : TranscriptionEngine {

    companion object {
        private const val TAG = "TinyDictation"

        // Silence detection thresholds
        private const val SILENCE_THRESHOLD_RMS = 300
        private const val SILENCE_DURATION_MS = 800
        private const val MIN_AUDIO_DURATION_MS = 500
        private const val MAX_AUDIO_DURATION_MS = 30000

        // Sample rate
        private const val SAMPLE_RATE = 16000
    }

    override val engineId = "gemini"
    override val displayName = "Gemini (Google)"
    override val requiresInternet = true

    private val client = GeminiClient()
    private var scope: CoroutineScope? = null
    private var onTranscriptCallback: ((String, Boolean) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null
    private var isSessionActive = false
    private var currentLanguage: String? = null
    private var autoDetectLanguage = true // Gemini auto-detects by default

    // Audio buffering
    private val audioBuffer = mutableListOf<Short>()
    private var lastSpeechTime = 0L
    private var isInSpeech = false
    private var isProcessing = false

    override suspend fun isReady(context: Context, language: String): Boolean {
        return apiKey.isNotBlank()
    }

    override suspend fun initialize(context: Context, language: String): Result<Unit> {
        currentLanguage = if (autoDetectLanguage) null else language
        return if (apiKey.isNotBlank()) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("API key is required for Gemini"))
        }
    }

    override fun startSession(
        onTranscript: (text: String, isFinal: Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
        if (isSessionActive) {
            Log.d(TAG, "GeminiEngine session already active")
            return
        }

        Log.i(TAG, "GeminiEngine starting session")
        DebugLog.i("GeminiEngine: Starting session (autoDetect=$autoDetectLanguage)")

        onTranscriptCallback = onTranscript
        onErrorCallback = onError
        isSessionActive = true
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        audioBuffer.clear()
        lastSpeechTime = System.currentTimeMillis()
        isInSpeech = false
        isProcessing = false
    }

    override fun processAudio(samples: ShortArray) {
        if (!isSessionActive) return

        // Calculate RMS for silence detection
        val rms = calculateRMS(samples)
        val now = System.currentTimeMillis()

        // Detect speech/silence
        if (rms > SILENCE_THRESHOLD_RMS) {
            if (!isInSpeech) {
                isInSpeech = true
                DebugLog.d("GeminiEngine: Speech started")
            }
            lastSpeechTime = now
        }

        // Add samples to buffer
        synchronized(audioBuffer) {
            audioBuffer.addAll(samples.toList())
        }

        val bufferDurationMs = (audioBuffer.size * 1000L) / SAMPLE_RATE

        // Check if we should send audio
        val silenceDuration = now - lastSpeechTime
        val shouldSend = isInSpeech && !isProcessing && (
            (silenceDuration > SILENCE_DURATION_MS && bufferDurationMs >= MIN_AUDIO_DURATION_MS) ||
            bufferDurationMs >= MAX_AUDIO_DURATION_MS
        )

        if (shouldSend) {
            sendBufferedAudio()
        }
    }

    private fun sendBufferedAudio() {
        val samplesToSend: ShortArray
        synchronized(audioBuffer) {
            if (audioBuffer.isEmpty()) return
            samplesToSend = audioBuffer.toShortArray()
            audioBuffer.clear()
        }

        isProcessing = true
        isInSpeech = false

        val durationMs = (samplesToSend.size * 1000L) / SAMPLE_RATE
        Log.i(TAG, "GeminiEngine: Sending ${samplesToSend.size} samples (${durationMs}ms)")
        DebugLog.i("GeminiEngine: Sending audio (${durationMs}ms)")

        scope?.launch {
            try {
                // Convert to WAV
                val wavBytes = WavEncoder.encode(samplesToSend, SAMPLE_RATE)
                DebugLog.d("GeminiEngine: WAV encoded (${wavBytes.size} bytes)")

                // Send to Gemini
                val result = client.transcribe(
                    apiKey = apiKey,
                    audioBytes = wavBytes,
                    language = currentLanguage
                )

                if (result.isSuccess) {
                    val text = result.getOrNull()
                    if (!text.isNullOrBlank()) {
                        onTranscriptCallback?.invoke(text, true)
                    }
                } else {
                    val error = result.exceptionOrNull()?.message ?: "Transcription failed"
                    DebugLog.e("GeminiEngine: $error")
                    onErrorCallback?.invoke(error)
                }
            } catch (e: Exception) {
                Log.e(TAG, "GeminiEngine: Error processing audio", e)
                DebugLog.e("GeminiEngine: Error - ${e.message}")
                onErrorCallback?.invoke(e.message ?: "Unknown error")
            } finally {
                isProcessing = false
            }
        }
    }

    private fun calculateRMS(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        var sum = 0.0
        for (sample in samples) {
            sum += sample.toDouble() * sample.toDouble()
        }
        return sqrt(sum / samples.size)
    }

    override fun endSession() {
        if (!isSessionActive) return

        Log.i(TAG, "GeminiEngine ending session")
        DebugLog.i("GeminiEngine: Ending session")

        // Send any remaining audio
        if (audioBuffer.isNotEmpty() && !isProcessing) {
            sendBufferedAudio()
        }

        isSessionActive = false
        onTranscriptCallback = null
        onErrorCallback = null
    }

    override fun setLanguage(language: String) {
        currentLanguage = if (autoDetectLanguage) null else language
        Log.i(TAG, "GeminiEngine: Language set to $currentLanguage")
    }

    /**
     * Enable auto language detection.
     * Gemini auto-detects language by default.
     */
    fun setAutoDetectLanguage(enabled: Boolean) {
        autoDetectLanguage = enabled
        currentLanguage = if (enabled) null else "en"
        Log.i(TAG, "GeminiEngine: Auto-detect language = $enabled")
    }

    override fun getSupportedLanguages(): List<String> {
        // Gemini supports many languages
        return listOf("en", "es", "fr", "de", "it", "pt", "nl", "ja", "ko", "zh", "auto")
    }

    override fun release() {
        endSession()
        scope = null
    }

    override fun hasPendingAudio(): Boolean {
        return audioBuffer.isNotEmpty() || isProcessing
    }

    override suspend fun flushPendingAudio(timeoutMs: Long): Boolean {
        if (audioBuffer.isNotEmpty() && !isProcessing) {
            sendBufferedAudio()
        }

        // Wait for processing to complete
        val startTime = System.currentTimeMillis()
        while (isProcessing && (System.currentTimeMillis() - startTime) < timeoutMs) {
            delay(100)
        }
        return !isProcessing
    }

    override fun clearAudioBuffer() {
        synchronized(audioBuffer) {
            audioBuffer.clear()
        }
        isInSpeech = false
        Log.d(TAG, "GeminiEngine: Audio buffer cleared")
    }
}
