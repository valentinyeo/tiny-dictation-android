package com.tinydictation.android.audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Wrapper around Android's AudioRecord API for capturing audio.
 * Configured for Deepgram's requirements: 16kHz, mono, PCM 16-bit.
 */
class AudioRecorder {

    companion object {
        /** Sample rate required by Deepgram */
        const val SAMPLE_RATE = 16000

        /** Mono channel configuration */
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO

        /** PCM 16-bit audio format */
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        /** 20ms chunks at 16kHz = 320 samples */
        const val SAMPLES_PER_CHUNK = 320

        /** 320 samples * 2 bytes per sample = 640 bytes */
        const val BYTES_PER_CHUNK = SAMPLES_PER_CHUNK * 2
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var isRecording = false

    /**
     * Calculates the minimum buffer size required for AudioRecord.
     * Returns the larger of the minimum required size or our chunk size.
     */
    private fun getMinBufferSize(): Int {
        val minSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )
        // Use at least 4x our chunk size for the internal buffer to prevent overflow
        return maxOf(minSize, BYTES_PER_CHUNK * 4)
    }

    /**
     * Starts audio recording and delivers audio chunks via the callback.
     *
     * @param scope CoroutineScope to run the recording loop in
     * @param onAudioChunk Callback invoked with each audio chunk (320 samples / 20ms)
     * @throws SecurityException if RECORD_AUDIO permission is not granted
     * @throws IllegalStateException if AudioRecord fails to initialize
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startRecording(
        scope: CoroutineScope,
        onAudioChunk: (ShortArray) -> Unit
    ) {
        if (isRecording) {
            return
        }

        val bufferSize = getMinBufferSize()

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT,
            bufferSize
        ).also { record ->
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                throw IllegalStateException("Failed to initialize AudioRecord")
            }
        }

        isRecording = true

        recordingJob = scope.launch {
            withContext(Dispatchers.IO) {
                audioRecord?.startRecording()

                val buffer = ShortArray(SAMPLES_PER_CHUNK)

                while (isActive && isRecording) {
                    val readResult = audioRecord?.read(
                        buffer,
                        0,
                        SAMPLES_PER_CHUNK
                    ) ?: AudioRecord.ERROR

                    when {
                        readResult == AudioRecord.ERROR_INVALID_OPERATION -> {
                            // AudioRecord is not properly initialized
                            break
                        }
                        readResult == AudioRecord.ERROR_BAD_VALUE -> {
                            // Invalid parameters
                            break
                        }
                        readResult == AudioRecord.ERROR_DEAD_OBJECT -> {
                            // AudioRecord has become invalid
                            break
                        }
                        readResult > 0 -> {
                            // Successfully read audio data
                            if (readResult == SAMPLES_PER_CHUNK) {
                                onAudioChunk(buffer.copyOf())
                            } else {
                                // Partial read - still deliver what we got
                                onAudioChunk(buffer.copyOf(readResult))
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Stops audio recording and releases resources.
     */
    fun stopRecording() {
        isRecording = false

        recordingJob?.cancel()
        recordingJob = null

        audioRecord?.let { record ->
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                }
            } catch (e: IllegalStateException) {
                // Already stopped, ignore
            }
            record.release()
        }
        audioRecord = null
    }

    /**
     * Returns whether the recorder is currently recording.
     */
    fun isRecording(): Boolean = isRecording

    /**
     * Converts a ShortArray of audio samples to a ByteArray in little-endian format.
     * This is the format expected by Deepgram.
     */
    fun shortArrayToByteArray(samples: ShortArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val sample = samples[i].toInt()
            bytes[i * 2] = (sample and 0xFF).toByte()
            bytes[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return bytes
    }
}
