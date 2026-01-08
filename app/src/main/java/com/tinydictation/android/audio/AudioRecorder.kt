package com.tinydictation.android.audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import com.tinydictation.android.util.DebugLog
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
        private const val TAG = "TinyDictation"

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
    private var chunksDelivered = 0

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
            Log.d(TAG, "AudioRecorder already recording, ignoring start request")
            return
        }

        Log.i(TAG, "Starting audio recording at $SAMPLE_RATE Hz")
        DebugLog.i("AudioRecorder: Starting at $SAMPLE_RATE Hz with VOICE_RECOGNITION source (phone mic)")
        val bufferSize = getMinBufferSize()
        Log.d(TAG, "Audio buffer size: $bufferSize bytes")

        try {
            // Use VOICE_RECOGNITION to prefer phone's built-in mic over headphones
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            ).also { record ->
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    Log.e(TAG, "Failed to initialize AudioRecord - state: ${record.state}")
                    throw IllegalStateException("Failed to initialize AudioRecord - microphone may be in use by another app")
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Permission denied for audio recording", e)
            throw SecurityException("Microphone permission not granted", e)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid audio configuration", e)
            throw IllegalStateException("Invalid audio recording configuration", e)
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error initializing AudioRecord", e)
            throw IllegalStateException("Failed to initialize audio recording: ${e.message}", e)
        }

        isRecording = true
        chunksDelivered = 0

        recordingJob = scope.launch {
            withContext(Dispatchers.IO) {
                try {
                    val record = audioRecord
                    if (record == null) {
                        Log.e(TAG, "AudioRecord is null, cannot start recording")
                        return@withContext
                    }

                    record.startRecording()
                    Log.i(TAG, "AudioRecord started successfully - recording state: ${record.recordingState}")
                    DebugLog.i("AudioRecorder: Recording started successfully")

                    val buffer = ShortArray(SAMPLES_PER_CHUNK)

                    while (isActive && isRecording) {
                        val readResult = record.read(
                            buffer,
                            0,
                            SAMPLES_PER_CHUNK
                        )

                        when {
                            readResult == AudioRecord.ERROR_INVALID_OPERATION -> {
                                Log.e(TAG, "AudioRecord read error: INVALID_OPERATION")
                                // AudioRecord is not properly initialized
                                break
                            }
                            readResult == AudioRecord.ERROR_BAD_VALUE -> {
                                Log.e(TAG, "AudioRecord read error: BAD_VALUE")
                                // Invalid parameters
                                break
                            }
                            readResult == AudioRecord.ERROR_DEAD_OBJECT -> {
                                Log.e(TAG, "AudioRecord read error: DEAD_OBJECT")
                                // AudioRecord has become invalid
                                break
                            }
                            readResult > 0 -> {
                                // Successfully read audio data
                                chunksDelivered++

                                // Log first chunk to confirm audio is flowing
                                if (chunksDelivered == 1) {
                                    Log.i(TAG, "AudioRecorder: First audio chunk delivered ($readResult samples)")
                                    DebugLog.i("AudioRecorder: First audio chunk delivered")
                                }

                                // Log periodically to confirm audio is flowing
                                if (chunksDelivered % 100 == 0) {
                                    Log.d(TAG, "AudioRecorder: Delivered $chunksDelivered audio chunks")
                                    DebugLog.d("AudioRecorder: Delivered $chunksDelivered chunks")
                                }

                                if (readResult == SAMPLES_PER_CHUNK) {
                                    onAudioChunk(buffer.copyOf())
                                } else {
                                    // Partial read - still deliver what we got
                                    Log.d(TAG, "Partial audio read: $readResult samples")
                                    onAudioChunk(buffer.copyOf(readResult))
                                }
                            }
                        }
                    }
                    Log.i(TAG, "Audio recording loop ended - delivered $chunksDelivered total chunks")
                } catch (e: IllegalStateException) {
                    Log.e(TAG, "AudioRecord illegal state during recording", e)
                } catch (e: Exception) {
                    Log.e(TAG, "Unexpected error during audio recording", e)
                }
            }
        }
    }

    /**
     * Stops audio recording and releases resources.
     */
    fun stopRecording() {
        if (!isRecording) {
            Log.d(TAG, "AudioRecorder not recording, ignoring stop request")
            return
        }

        Log.i(TAG, "Stopping audio recording")
        isRecording = false

        recordingJob?.cancel()
        recordingJob = null

        audioRecord?.let { record ->
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                    Log.i(TAG, "AudioRecord stopped successfully")
                }
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Error stopping AudioRecord", e)
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
