package com.tinydictation.android.transcription

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavEncoder {

    private const val SAMPLE_RATE = 16000
    private const val BITS_PER_SAMPLE = 16
    private const val NUM_CHANNELS = 1

    fun encode(samples: ShortArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val audioData = samplesToBytes(samples)
        return createWavFile(audioData, sampleRate)
    }

    fun encodeFromBytes(pcmData: ByteArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        return createWavFile(pcmData, sampleRate)
    }

    private fun samplesToBytes(samples: ShortArray): ByteArray {
        val buffer = ByteBuffer.allocate(samples.size * 2)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) {
            buffer.putShort(sample)
        }
        return buffer.array()
    }

    private fun createWavFile(audioData: ByteArray, sampleRate: Int): ByteArray {
        val output = ByteArrayOutputStream()

        val byteRate = sampleRate * NUM_CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign = NUM_CHANNELS * BITS_PER_SAMPLE / 8
        val dataSize = audioData.size
        val fileSize = 36 + dataSize

        output.write("RIFF".toByteArray())
        output.write(intToLittleEndian(fileSize))
        output.write("WAVE".toByteArray())

        output.write("fmt ".toByteArray())
        output.write(intToLittleEndian(16))
        output.write(shortToLittleEndian(1))
        output.write(shortToLittleEndian(NUM_CHANNELS.toShort()))
        output.write(intToLittleEndian(sampleRate))
        output.write(intToLittleEndian(byteRate))
        output.write(shortToLittleEndian(blockAlign.toShort()))
        output.write(shortToLittleEndian(BITS_PER_SAMPLE.toShort()))

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
