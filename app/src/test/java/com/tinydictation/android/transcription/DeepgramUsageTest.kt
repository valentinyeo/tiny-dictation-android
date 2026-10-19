package com.tinydictation.android.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZonedDateTime

class DeepgramUsageTest {
    @Test
    fun accumulatesActualBytesWithoutRoundingEachConnection() {
        val date = LocalDate.of(2026, 8, 9)
        var totals = DeepgramUsage.accumulate(emptyMap(), 640, date)
        totals = DeepgramUsage.accumulate(totals, 32_000, date)
        val usage = totals.getValue("2026-08-09")
        assertEquals(32_640L, usage.audioBytes)
        assertEquals(1.02, usage.seconds, 0.000001)
        assertEquals(2, usage.requests)
    }

    @Test
    fun anOpenedConnectionWithNoAudioStillCountsOneRequest() {
        val totals = DeepgramUsage.accumulate(emptyMap(), 0, LocalDate.of(2026, 8, 9))
        assertEquals(StreamedUsage(0, 1), totals.getValue("2026-08-09"))
    }

    @Test
    fun localMidnightStartsANewDay() {
        val before = ZonedDateTime.parse("2026-08-09T23:59:59-07:00[America/Los_Angeles]")
        val after = before.plusSeconds(2)
        val first = DeepgramUsage.accumulate(emptyMap(), 32_000, before.toLocalDate())
        val totals = DeepgramUsage.accumulate(first, 64_000, after.toLocalDate())
        assertEquals(StreamedUsage(32_000, 1), totals["2026-08-09"])
        assertEquals(StreamedUsage(64_000, 1), totals["2026-08-10"])
        assertEquals(1, first.size)
    }

    @Test
    fun keepsOnlyTheLastFourteenCalendarDaysAcrossMonthBoundary() {
        var totals = emptyMap<String, StreamedUsage>()
        val first = LocalDate.of(2026, 7, 20)
        repeat(30) { offset ->
            totals = DeepgramUsage.accumulate(totals, 32_000, first.plusDays(offset.toLong()))
        }
        assertEquals(14, totals.size)
        assertEquals("2026-08-05", totals.keys.minOrNull())
        assertEquals("2026-08-18", totals.keys.maxOrNull())
        assertTrue(totals.values.all { it.seconds == 1.0 && it.requests == 1 })
    }

    @Test
    fun oldEntriesExpireEvenWithoutRecentRequests() {
        val old = mapOf("2025-01-01" to StreamedUsage(32_000, 1))
        val totals = DeepgramUsage.accumulate(old, 640, LocalDate.of(2026, 8, 9))
        assertFalse(totals.containsKey("2025-01-01"))
        assertEquals(setOf("2026-08-09"), totals.keys)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeByteCountsAreRejected() {
        DeepgramUsage.accumulate(emptyMap(), -1, LocalDate.of(2026, 8, 9))
    }
}
