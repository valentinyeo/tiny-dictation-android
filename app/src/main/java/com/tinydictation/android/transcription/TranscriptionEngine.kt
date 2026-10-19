package com.tinydictation.android.transcription

import android.content.Context

/**
 * Interface for speech-to-text transcription engines.
 *
 * Implementations can be cloud-based (Deepgram) or offline (Vosk, Sherpa-ONNX).
 * All engines receive audio as PCM 16-bit, 16kHz, mono format.
 */
interface TranscriptionEngine {

    /**
     * Unique identifier for this engine (e.g., "deepgram", "vosk", "sherpa-onnx")
     */
    val engineId: String

    /**
     * Human-readable name for UI display
     */
    val displayName: String

    /**
     * Whether this engine requires internet connectivity
     */
    val requiresInternet: Boolean

    /**
     * Whether the engine is ready to use (models downloaded, initialized)
     */
    suspend fun isReady(context: Context, language: String): Boolean

    /**
     * Initialize the engine with the specified language.
     * For offline engines, this loads the model into memory.
     *
     * @param context Android context
     * @param language Language code ("en" for English, "de" for German)
     * @return Result indicating success or failure with error message
     */
    suspend fun initialize(context: Context, language: String): Result<Unit>

    /**
     * Start a transcription session.
     * Must be called before processAudio().
     *
     * @param onTranscript Callback for transcription results (text, isFinal)
     * @param onError Callback for error messages
     */
    fun startSession(
        onTranscript: (text: String, isFinal: Boolean) -> Unit,
        onError: (TranscriptionError) -> Unit
    )

    /**
     * Process an audio chunk.
     * Audio should be PCM 16-bit, 16kHz, mono (320 samples = 20ms).
     *
     * @param samples Audio samples as ShortArray
     */
    fun processAudio(samples: ShortArray)

    /**
     * End the current transcription session.
     * Finalizes any pending transcriptions and releases session resources.
     */
    fun endSession()

    /**
     * Change the transcription language.
     * May require re-initialization for offline engines.
     *
     * @param language Language code
     */
    fun setLanguage(language: String)

    /**
     * Get list of supported language codes.
     */
    fun getSupportedLanguages(): List<String>

    /**
     * Release all resources held by this engine.
     * Call when the engine is no longer needed.
     */
    fun release()

    /**
     * Check if there is any audio still being processed.
     * Used to determine if transcription is complete before copying.
     *
     * @return true if there is pending audio to be transcribed
     */
    fun hasPendingAudio(): Boolean = false

    /**
     * Flush any pending audio buffer and wait for processing to complete.
     * This is a blocking call that ensures all buffered audio has been transcribed.
     * Should be called before copying transcription to ensure completeness.
     *
     * @param timeoutMs Maximum time to wait for processing (default 10 seconds)
     * @return true if flush completed successfully, false if timed out
     */
    suspend fun flushPendingAudio(timeoutMs: Long = 10000): Boolean = true

    /**
     * Clear any buffered audio without processing it.
     * Call this when starting a fresh dictation session to discard old audio.
     * This is separate from startSession() to allow early audio to be captured
     * during the engine startup process.
     */
    fun clearAudioBuffer() { /* Default no-op for engines without buffering */ }
}
