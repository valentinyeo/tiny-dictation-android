package com.tinydictation.android.transcription

import java.time.LocalDate

data class StreamedUsage(val audioBytes: Long = 0, val requests: Int = 0) {
    val seconds: Double get() = audioBytes / 32_000.0
}

object DeepgramUsage {
    fun accumulate(
        totals: Map<String, StreamedUsage>,
        audioBytes: Long,
        today: LocalDate
    ): Map<String, StreamedUsage> {
        require(audioBytes >= 0)
        val key = today.toString()
        val oldest = today.minusDays(13).toString()
        val retained = totals.filterKeys { it >= oldest && it <= key }.toMutableMap()
        val previous = retained[key] ?: StreamedUsage()
        retained[key] = StreamedUsage(previous.audioBytes + audioBytes, previous.requests + 1)
        return retained
    }
}
