package com.tinydictation.android.vad

import android.util.Log
import kotlin.math.sqrt

enum class VADResult {
    NO_SPEECH,

    SPEECH_START,

    SPEECH_CONTINUE,

    SPEECH_END
}

class VoiceActivityDetector(
    private val speechThreshold: Int = DEFAULT_SPEECH_THRESHOLD,

    private val silenceThreshold: Int = DEFAULT_SILENCE_THRESHOLD,

    private val silenceDurationMs: Int = DEFAULT_SILENCE_DURATION_MS,

    private val chunkDurationMs: Int = DEFAULT_CHUNK_DURATION_MS
) {

    companion object {
        private const val TAG = "TinyDictation"

        const val DEFAULT_SPEECH_THRESHOLD = 100

        const val DEFAULT_SILENCE_THRESHOLD = 50

        const val DEFAULT_SILENCE_DURATION_MS = 500

        const val DEFAULT_CHUNK_DURATION_MS = 20
    }

    private var isSpeaking = false

    private var silenceDurationAccumulated = 0

    private var chunkCount = 0

    fun processSamples(samples: ShortArray): VADResult {
        val rms = calculateRMS(samples)
        chunkCount++

        if (chunkCount % 50 == 0) {
            Log.d(TAG, "VAD: RMS=$rms (speech threshold=$speechThreshold, silence threshold=$silenceThreshold, speaking=$isSpeaking)")
        }

        return when {
            !isSpeaking -> {
                if (rms >= speechThreshold) {
                    Log.i(TAG, "VAD: Speech detected (RMS=$rms >= threshold=$speechThreshold)")
                    isSpeaking = true
                    silenceDurationAccumulated = 0
                    VADResult.SPEECH_START
                } else {
                    if (rms > speechThreshold * 0.5) {
                        Log.d(TAG, "VAD: Close to threshold - RMS=$rms (need $speechThreshold)")
                    }
                    VADResult.NO_SPEECH
                }
            }

            else -> {
                if (rms >= silenceThreshold) {
                    silenceDurationAccumulated = 0
                    VADResult.SPEECH_CONTINUE
                } else {
                    silenceDurationAccumulated += chunkDurationMs

                    if (silenceDurationAccumulated >= silenceDurationMs) {
                        Log.i(TAG, "VAD: Silence detected (${silenceDurationAccumulated}ms accumulated, RMS=$rms)")
                        isSpeaking = false
                        silenceDurationAccumulated = 0
                        VADResult.SPEECH_END
                    } else {
                        Log.d(TAG, "VAD: Silence accumulating - ${silenceDurationAccumulated}ms (need ${silenceDurationMs}ms)")
                        VADResult.SPEECH_CONTINUE
                    }
                }
            }
        }
    }

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

    fun reset() {
        Log.d(TAG, "VAD: Resetting detector")
        isSpeaking = false
        silenceDurationAccumulated = 0
        chunkCount = 0
    }

    fun isSpeaking(): Boolean = isSpeaking

    fun getSilenceDuration(): Int = silenceDurationAccumulated
}
