package com.tinydictation.android.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepgramSocketEventsTest {
    @Test
    fun oldSocketEventsAfterReconnectAreDropped() {
        val oldSocket = Any()
        val newSocket = Any()
        var connected = true
        var newSessionErrors = 0
        for (event in listOf("open", "message", "binary", "closing", "closed", "failure")) {
            if (acceptsDeepgramEvent(oldSocket, newSocket, closedByClient = false)) {
                connected = event == "open"
                newSessionErrors++
            }
        }
        assertTrue(connected)
        assertEquals(0, newSessionErrors)
    }

    @Test
    fun currentSocketEventsAreAccepted() {
        val socket = Any()
        assertTrue(acceptsDeepgramEvent(socket, socket, closedByClient = false))
    }

    @Test
    fun clientClosedSocketIsIgnoredEvenIfStillCurrent() {
        val socket = Any()
        assertFalse(acceptsDeepgramEvent(socket, socket, closedByClient = true))
    }

    @Test
    fun cleanupWithoutAReplacementIgnoresOldEvents() {
        assertFalse(acceptsDeepgramEvent(Any(), null, closedByClient = false))
    }

    @Test
    fun identityNotEqualityDeterminesCurrentSocket() {
        val socket = listOf(1)
        assertFalse(acceptsDeepgramEvent(socket, listOf(1), closedByClient = false))
    }

    @Test
    fun currentSocket1011CloseIsReported() {
        val socket = Any()
        val error = if (acceptsDeepgramEvent(socket, socket, closedByClient = false)) {
            mapDeepgramError(closeCode = 1011, causeMessage = "Server failure")
        } else null
        assertNotNull(error)
        assertEquals("Deepgram closed the connection (1011: Server failure)", error?.message)
    }

    @Test
    fun normalCloseCodesDoNotReportErrors() {
        assertNull(mapDeepgramError(closeCode = 1000))
        assertNull(mapDeepgramError(closeCode = 1005))
    }
}
