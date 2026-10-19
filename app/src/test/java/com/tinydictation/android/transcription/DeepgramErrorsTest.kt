package com.tinydictation.android.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepgramErrorsTest {

    @Test
    fun http401RejectsKeyAndStopsRetrying() {
        val error = requireNotNull(mapDeepgramError(401, "Invalid credentials."))

        assertEquals(
            "Deepgram rejected the API key. Open Settings > API Keys and paste a new key.",
            error.message
        )
        assertTrue(error.fatal)
    }

    @Test
    fun http402ReportsOutOfCreditAndStopsRetrying() {
        val error = requireNotNull(mapDeepgramError(httpCode = 402))

        assertEquals(
            "Deepgram account is out of credit. Add credit in the Deepgram console.",
            error.message
        )
        assertTrue(error.fatal)
    }

    @Test
    fun http403ReportsMissingPermissionAndStopsRetrying() {
        val error = requireNotNull(mapDeepgramError(httpCode = 403))

        assertEquals(
            "Deepgram key has no permission for live transcription. " +
                "Create a new key with Member role.",
            error.message
        )
        assertTrue(error.fatal)
    }

    @Test
    fun nullResponseReportsNetworkErrorAndAllowsRetry() {
        val error = requireNotNull(mapDeepgramError(causeMessage = "Connection refused"))

        assertEquals("Cannot reach Deepgram. Check your internet connection.", error.message)
        assertFalse(error.fatal)
    }

    @Test
    fun close1008ReportsPolicyViolationAndStopsRetrying() {
        val error = requireNotNull(mapDeepgramError(closeCode = 1008, causeMessage = "Policy violation"))

        assertEquals("Deepgram closed the connection (1008: Policy violation)", error.message)
        assertTrue(error.fatal)
    }

    @Test
    fun close1000IsNotAnError() {
        assertNull(mapDeepgramError(closeCode = 1000, causeMessage = "Normal closure"))
    }

    @Test
    fun otherHttpErrorPrefersDeepgramHeaderAndAllowsRetry() {
        val error = requireNotNull(mapDeepgramError(503, "Service unavailable", causeMessage = "HTTP"))

        assertEquals("Deepgram error 503: Service unavailable", error.message)
        assertFalse(error.fatal)
    }

    @Test
    fun otherHttpErrorFallsBackToCauseAndAllowsRetry() {
        val error = requireNotNull(mapDeepgramError(httpCode = 500, causeMessage = "Server error"))

        assertEquals("Deepgram error 500: Server error", error.message)
        assertFalse(error.fatal)
    }

    @Test
    fun otherHttpErrorWithoutDetailsUsesFallback() {
        val error = requireNotNull(mapDeepgramError(httpCode = 500))

        assertEquals("Deepgram error 500: Unknown error", error.message)
        assertFalse(error.fatal)
    }

    @Test
    fun close1011ReportsReasonAndAllowsRetry() {
        val error = requireNotNull(mapDeepgramError(closeCode = 1011, causeMessage = "Server failure"))

        assertEquals("Deepgram closed the connection (1011: Server failure)", error.message)
        assertFalse(error.fatal)
    }
}
