package com.tinydictation.android.transcription

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Utility for encoding PCM audio samples to WAV format.
 * WAV format is required by cloud transcription APIs.
 */
object WavEncoder {

    private const val SAMPLE_RATE = 16000
    private const val BITS_PER_SAMPLE = 16
    private const val NUM_CHANNELS = 1

    /**
     * Encodes PCM 16-bit mono samples to WAV format.
     *
     * @param samples Array of PCM samples (16-bit signed)
     * @param sampleRate Sample rate in Hz (default 16000)
     * @return WAV file data as byte array
     */
    fun encode(samples: ShortArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val audioData = samplesToBytes(samples)
        return createWavFile(audioData, sampleRate)
    }

    /**
     * Encodes raw PCM bytes to WAV format.
     *
     * @param pcmData Raw PCM data (16-bit little-endian)
     * @param sampleRate Sample rate in Hz (default 16000)
     * @return WAV file data as byte array
     */
    fun encodeFromBytes(pcmData: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        return createWavFile(pcmData, sampleRate)
    }

    /**
     * Converts ShortArray samples to byte array (little-endian).
     */
    private fun samplesToBytes(samples: ShortArray): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * 2)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) {
            buffer.putShort(sample)
        }
        return buffer.array()
    }

    /**
     * Creates a complete WAV file with headers.
     */
    private fun createWavFile(audioData: ByteArray, sampleRate: Int): ByteArray {
        val output = ByteArrayOutputStream()

        val byteRate = sampleRate * NUM_CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign = NUM_CHANNELS * BITS_PER_SAMPLE / 8
        val dataSize = audioData.size
        val fileSize = 36 + dataSize

        // RIFF header
        output.write("RIFF".toByteArray())
        output.write(intToLittleEndian(fileSize))
        output.write("WAVE".toByteArray())

        // fmt subchunk
        output.write("fmt ".toByteArray())
        output.write(intToLittleEndian(16)) // Subchunk1Size (16 for PCM)
        output.write(shortToLittleEndian(1)) // AudioFormat (1 for PCM)
        output.write(shortToLittleEndian(NUM_CHANNELS.toShort()))
        output.write(intToLittleEndian(sampleRate))
        output.write(intToLittleEndian(byteRate))
        output.write(shortToLittleEndian(blockAlign.toShort()))
        output.write(shortToLittleEndian(BITS_PER_SAMPLE.toShort()))

        // data subchunk
        output.write("data".toByteArray())
        output.write(intToLittleEndian(dataSize))
        output.write(audioData)

        return output.toByteArray()
    }

    private fun intToLittleEndian(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }

    private fun shortToLittleEndian(value: Short): ByteArray {
        return byteArrayOf(
            (value.toInt() and 0xFF).toByte(),
            ((value.toInt() shr 8) and 0xFF).toByte()
        )
    }
}
