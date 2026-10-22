package com.tinydictation.android.audio

import android.Manifest
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresPermission
import com.tinydictation.android.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AudioRecorder {

    companion object {
        private const val TAG = "TinyDictation"

        const val SAMPLE_RATE = 16000

        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO

        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT

        const val SAMPLES_PER_CHUNK = 320

        const val BYTES_PER_CHUNK = SAMPLES_PER_CHUNK * 2
    }

    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var isRecording = false
    private var chunksDelivered = 0

    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    private var audioManager: AudioManager? = null
    private var bluetoothScoStarted = false
    private var previousAudioMode = AudioManager.MODE_NORMAL

    private fun enableAudioEffects(audioSessionId: Int) {
        if (AcousticEchoCanceler.isAvailable()) {
            try {
                echoCanceler = AcousticEchoCanceler.create(audioSessionId)?.also { aec ->
                    aec.enabled = true
                    Log.i(TAG, "AudioRecorder: Acoustic Echo Cancellation enabled")
                    DebugLog.i("AudioRecorder: AEC enabled - will filter speaker audio")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to enable AEC", e)
                DebugLog.w("AudioRecorder: Failed to enable AEC - ${e.message}")
            }
        } else {
            Log.i(TAG, "AudioRecorder: AEC not available on this device")
            DebugLog.i("AudioRecorder: AEC not available")
        }

        if (NoiseSuppressor.isAvailable()) {
            try {
                noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.also { ns ->
                    ns.enabled = true
                    Log.i(TAG, "AudioRecorder: Noise Suppression enabled")
                    DebugLog.i("AudioRecorder: Noise suppression enabled")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to enable NS", e)
                DebugLog.w("AudioRecorder: Failed to enable NS - ${e.message}")
            }
        } else {
            Log.i(TAG, "AudioRecorder: Noise Suppression not available on this device")
        }
    }

    private fun releaseAudioEffects() {
        echoCanceler?.let {
            try {
                it.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing AEC", e)
            }
        }
        echoCanceler = null

        noiseSuppressor?.let {
            try {
                it.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing NS", e)
            }
        }
        noiseSuppressor = null
    }

    private fun isBluetoothDevice(device: AudioDeviceInfo?): Boolean {
        if (device == null) return false
        return device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
               device.type == AudioDeviceInfo.TYPE_BLE_HEADSET
    }

    @Suppress("DEPRECATION")
    private suspend fun startBluetoothSco(context: Context): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager = am

        if (am.isBluetoothScoOn) {
            Log.i(TAG, "AudioRecorder: Bluetooth SCO already on")
            DebugLog.i("AudioRecorder: Bluetooth SCO already active")
            return true
        }

        previousAudioMode = am.mode

        try {
            Log.i(TAG, "AudioRecorder: Starting Bluetooth SCO...")
            DebugLog.i("AudioRecorder: Starting Bluetooth SCO for headset mic")
            am.startBluetoothSco()
            bluetoothScoStarted = true

            am.mode = AudioManager.MODE_IN_COMMUNICATION

            var attempts = 0
            while (!am.isBluetoothScoOn && attempts < 20) {
                delay(100)
                attempts++
            }

            if (am.isBluetoothScoOn) {
                Log.i(TAG, "AudioRecorder: Bluetooth SCO connected successfully")
                DebugLog.i("AudioRecorder: Bluetooth SCO connected")
                return true
            } else {
                Log.w(TAG, "AudioRecorder: Bluetooth SCO failed to connect after ${attempts * 100}ms")
                DebugLog.w("AudioRecorder: Bluetooth SCO connection timeout")
                stopBluetoothSco()
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecorder: Error starting Bluetooth SCO", e)
            DebugLog.e("AudioRecorder: Bluetooth SCO error - ${e.message}")
            stopBluetoothSco()
            return false
        }
    }

    @Suppress("DEPRECATION")
    private fun stopBluetoothSco() {
        if (!bluetoothScoStarted) return

        try {
            audioManager?.let { am ->
                if (am.isBluetoothScoOn) {
                    am.stopBluetoothSco()
                    Log.i(TAG, "AudioRecorder: Bluetooth SCO stopped")
                    DebugLog.i("AudioRecorder: Bluetooth SCO stopped")
                }
                am.mode = previousAudioMode
            }
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecorder: Error stopping Bluetooth SCO", e)
        }

        bluetoothScoStarted = false
        audioManager = null
    }

    private fun getMinBufferSize(): Int {
        val minSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )
        return maxOf(minSize, BYTES_PER_CHUNK * 4)
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startRecording(
        scope: CoroutineScope,
        context: Context,
        useHeadphoneMic: Boolean = false,
        onAudioChunk: (ShortArray) -> Unit
    ) {
        if (isRecording) {
            Log.d(TAG, "AudioRecorder already recording, ignoring start request")
            return
        }

        Log.i(TAG, "Starting audio recording at $SAMPLE_RATE Hz")
        DebugLog.i("AudioRecorder: Starting at $SAMPLE_RATE Hz, useHeadphoneMic=$useHeadphoneMic")
        val bufferSize = getMinBufferSize()
        Log.d(TAG, "Audio buffer size: $bufferSize bytes")

        val headsetMic = if (useHeadphoneMic) findHeadsetMic(context) else null
        val needsBluetoothSco = useHeadphoneMic && isBluetoothDevice(headsetMic)

        isRecording = true
        chunksDelivered = 0

        recordingJob = scope.launch {
            if (needsBluetoothSco) {
                val scoStarted = startBluetoothSco(context)
                if (!scoStarted) {
                    Log.w(TAG, "AudioRecorder: Bluetooth SCO failed, falling back to default mic")
                    DebugLog.w("AudioRecorder: Bluetooth SCO failed, using default mic")
                }
            }

            val audioSource = if (useHeadphoneMic && headsetMic != null) {
                MediaRecorder.AudioSource.DEFAULT
            } else {
                MediaRecorder.AudioSource.VOICE_RECOGNITION
            }

            try {
                audioRecord = AudioRecord(
                    audioSource,
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

                    if (useHeadphoneMic && headsetMic != null) {
                        val success = record.setPreferredDevice(headsetMic)
                        val deviceName = headsetMic.productName?.toString() ?: getDeviceTypeName(headsetMic.type)
                        if (success) {
                            Log.i(TAG, "AudioRecorder: Set preferred device to headset mic: $deviceName")
                            DebugLog.i("AudioRecorder: Using headset mic: $deviceName")
                        } else {
                            Log.w(TAG, "AudioRecorder: Failed to set preferred device to headset mic")
                            DebugLog.w("AudioRecorder: Failed to set headset mic, using default")
                        }
                    } else if (useHeadphoneMic) {
                        Log.w(TAG, "AudioRecorder: Headset mic requested but no headset connected")
                        DebugLog.w("AudioRecorder: No headset mic found, using built-in mic")
                    } else {
                        Log.i(TAG, "AudioRecorder: Using built-in microphone")
                        DebugLog.i("AudioRecorder: Using built-in microphone")
                    }

                    enableAudioEffects(record.audioSessionId)
                }
            } catch (e: SecurityException) {
                Log.e(TAG, "Permission denied for audio recording", e)
                stopBluetoothSco()
                isRecording = false
                throw SecurityException("Microphone permission not granted", e)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "Invalid audio configuration", e)
                stopBluetoothSco()
                isRecording = false
                throw IllegalStateException("Invalid audio recording configuration", e)
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error initializing AudioRecord", e)
                stopBluetoothSco()
                isRecording = false
                throw IllegalStateException("Failed to initialize audio recording: ${e.message}", e)
            }

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
                                break
                            }
                            readResult == AudioRecord.ERROR_BAD_VALUE -> {
                                Log.e(TAG, "AudioRecord read error: BAD_VALUE")
                                break
                            }
                            readResult == AudioRecord.ERROR_DEAD_OBJECT -> {
                                Log.e(TAG, "AudioRecord read error: DEAD_OBJECT")
                                break
                            }
                            readResult > 0 -> {
                                chunksDelivered++

                                if (chunksDelivered == 1) {
                                    Log.i(TAG, "AudioRecorder: First audio chunk delivered ($readResult samples)")
                                    DebugLog.i("AudioRecorder: First audio chunk delivered")
                                }

                                if (chunksDelivered % 100 == 0) {
                                    Log.d(TAG, "AudioRecorder: Delivered $chunksDelivered audio chunks")
                                    DebugLog.d("AudioRecorder: Delivered $chunksDelivered chunks")
                                }

                                if (readResult == SAMPLES_PER_CHUNK) {
                                    onAudioChunk(buffer.copyOf())
                                } else {
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

    fun stopRecording() {
        if (!isRecording) {
            Log.d(TAG, "AudioRecorder not recording, ignoring stop request")
            return
        }

        Log.i(TAG, "Stopping audio recording")
        isRecording = false

        recordingJob?.cancel()
        recordingJob = null

        releaseAudioEffects()

        audioRecord?.let { record ->
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    record.stop()
                    Log.i(TAG, "AudioRecord stopped successfully")
                }
            } catch (e: IllegalStateException) {
                Log.e(TAG, "Error stopping AudioRecord", e)
            }
            record.release()
        }
        audioRecord = null

        stopBluetoothSco()
    }

    fun isRecording(): Boolean = isRecording

    fun shortArrayToByteArray(samples: ShortArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val sample = samples[i].toInt()
            bytes[i * 2] = (sample and 0xFF).toByte()
            bytes[i * 2 + 1] = ((sample shr 8) and 0xFF).toByte()
        }
        return bytes
    }

    private fun findHeadsetMic(context: Context): AudioDeviceInfo? {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)

        val headsetTypes = listOf(
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO
        )

        for (preferredType in headsetTypes) {
            for (device in devices) {
                if (device.type == preferredType) {
                    val name = device.productName?.toString() ?: getDeviceTypeName(device.type)
                    Log.d(TAG, "Found headset mic: $name (type=${device.type})")
                    return device
                }
            }
        }

        Log.d(TAG, "No headset mic found. Available input devices:")
        for (device in devices) {
            val name = device.productName?.toString() ?: "Unknown"
            Log.d(TAG, "  - $name (type=${getDeviceTypeName(device.type)})")
        }

        return null
    }

    private fun getDeviceTypeName(type: Int): String {
        return when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in mic"
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB headset"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB device"
            AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE headset"
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth SCO"
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
            AudioDeviceInfo.TYPE_TELEPHONY -> "Telephony"
            else -> "Unknown ($type)"
        }
    }
}
