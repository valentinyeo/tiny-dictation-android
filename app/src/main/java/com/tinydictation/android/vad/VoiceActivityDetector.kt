package com.tinydictation.android.vad

import android.util.Log
import kotlin.math.sqrt

/**
 * Result of voice activity detection on an audio chunk.
 */
enum class VADResult {
    /** No speech detected, silence or noise below threshold */
    NO_SPEECH,

    /** Speech has just started (transition from silence to speech) */
    SPEECH_START,

    /** Speech is continuing (was already speaking) */
    SPEECH_CONTINUE,

    /** Speech has ended (silence detected for required duration) */
    SPEECH_END
}

/**
 * Simple amplitude-based Voice Activity Detector.
 * Uses RMS (Root Mean Square) amplitude to detect speech.
 *
 * This is a basic implementation that will be replaced with Silero VAD later.
 */
class VoiceActivityDetector(
    /** RMS threshold above which audio is considered speech */
    private val speechThreshold: Int = DEFAULT_SPEECH_THRESHOLD,

    /** RMS threshold below which audio is considered silence */
    private val silenceThreshold: Int = DEFAULT_SILENCE_THRESHOLD,

    /** Duration of silence (in ms) required to end speech */
    private val silenceDurationMs: Int = DEFAULT_SILENCE_DURATION_MS,

    /** Duration of each audio chunk in milliseconds */
    private val chunkDurationMs: Int = DEFAULT_CHUNK_DURATION_MS
) {

    companion object {
        private const val TAG = "TinyDictation"

        /** Default RMS threshold for detecting speech (very low for maximum sensitivity) */
        const val DEFAULT_SPEECH_THRESHOLD = 100

        /** Default RMS threshold for detecting silence (very low for maximum sensitivity) */
        const val DEFAULT_SILENCE_THRESHOLD = 50

        /** Default silence duration to end speech (500ms) */
        const val DEFAULT_SILENCE_DURATION_MS = 500

        /** Default chunk duration (20ms for 320 samples at 16kHz) */
        const val DEFAULT_CHUNK_DURATION_MS = 20
    }

    /** Current state of the detector */
    private var isSpeaking = false

    /** Accumulated silence duration in milliseconds */
    private var silenceDurationAccumulated = 0

    /** Counter for periodic RMS logging */
    private var chunkCount = 0

    /**
     * Processes an audio chunk and returns the VAD result.
     *
     * @param samples Audio samples (PCM 16-bit, typically 320 samples for 20ms at 16kHz)
     * @return VADResult indicating the current speech state
     */
    fun processSamples(samples: ShortArray): VADResult {
        val rms = calculateRMS(samples)
        chunkCount++

        // Log RMS values periodically (every 50 chunks = ~1 second)
        if (chunkCount % 50 == 0) {
            Log.d(TAG, "VAD: RMS=$rms (speech threshold=$speechThreshold, silence threshold=$silenceThreshold, speaking=$isSpeaking)")
        }

        return when {
            // Currently not speaking
            !isSpeaking -> {
                if (rms >= speechThreshold) {
                    // Speech detected - start speaking
                    Log.i(TAG, "VAD: Speech detected (RMS=$rms >= threshold=$speechThreshold)")
                    isSpeaking = true
                    silenceDurationAccumulated = 0
                    VADResult.SPEECH_START
                } else {
                    // Log when we're close to threshold to help debug
                    if (rms > speechThreshold * 0.5) {
                        Log.d(TAG, "VAD: Close to threshold - RMS=$rms (need $speechThreshold)")
                    }
                    VADResult.NO_SPEECH
                }
            }

            // Currently speaking
            else -> {
                if (rms >= silenceThreshold) {
                    // Still above silence threshold - continue speaking
                    silenceDurationAccumulated = 0
                    VADResult.SPEECH_CONTINUE
                } else {
                    // Below silence threshold - accumulate silence
                    silenceDurationAccumulated += chunkDurationMs

                    if (silenceDurationAccumulated >= silenceDurationMs) {
                        // Enough silence accumulated - end speech
                        Log.i(TAG, "VAD: Silence detected (${silenceDurationAccumulated}ms accumulated, RMS=$rms)")
                        isSpeaking = false
                        silenceDurationAccumulated = 0
                        VADResult.SPEECH_END
                    } else {
                        // Not enough silence yet - continue speaking
                        Log.d(TAG, "VAD: Silence accumulating - ${silenceDurationAccumulated}ms (need ${silenceDurationMs}ms)")
                        VADResult.SPEECH_CONTINUE
                    }
                }
            }
        }
    }

    /**
     * Calculates the RMS (Root Mean Square) amplitude of audio samples.
     * RMS is a measure of the average power of the audio signal.
     *
     * @param samples Audio samples (PCM 16-bit)
     * @return RMS amplitude value
     */
    fun calculateRMS(samples: ShortArray): Int {
        if (samples.isEmpty()) return 0

        var sumOfSquares = 0.0
        for (sample in samples) {
            val sampleDouble = sample.toDouble()
            sumOfSquares += sampleDouble * sampleDouble
        }

        val meanSquare = sumOfSquares / samples.size
        return sqrt(meanSquare).toInt()
    }

    /**
     * Resets the detector to its initial state.
     * Call this when starting a new recording session.
     */
    fun reset() {
        Log.d(TAG, "VAD: Resetting detector")
        isSpeaking = false
        silenceDurationAccumulated = 0
        chunkCount = 0
    }

    /**
     * Returns whether the detector currently thinks speech is ongoing.
     */
    fun isSpeaking(): Boolean = isSpeaking

    /**
     * Returns the current accumulated silence duration in milliseconds.
     */
    fun getSilenceDuration(): Int = silenceDurationAccumulated
}
