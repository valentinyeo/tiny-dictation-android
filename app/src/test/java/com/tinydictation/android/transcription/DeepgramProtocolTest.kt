package com.tinydictation.android.transcription

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepgramProtocolTest {
    @Test
    fun defaultParamsContainRequestTag() {
        assertTrue(DeepgramClient.buildParams().contains("&tag=tiny-dictation"))
    }

    @Test
    fun terminalParamsContainRequestTagAndPreserveRawFinalResults() {
        val params = DeepgramClient.buildParams(terminalMode = true)
        assertTrue(params.contains("&tag=tiny-dictation"))
        assertTrue(params.contains("&interim_results=false"))
        assertTrue(params.contains("&smart_format=false"))
        assertTrue(params.contains("&punctuate=false"))
    }

    @Test
    fun keepAliveIsTheDeepgramTextControlFrame() {
        assertEquals("{\"type\":\"KeepAlive\"}", DeepgramClient.KEEP_ALIVE_MESSAGE)
    }

    @Test
    fun featureParamsAndAutoLanguageArePreserved() {
        val params = DeepgramClient.buildParams(
            language = "auto",
            model = "nova-3",
            keywords = listOf("hello world"),
            replacements = listOf("old word" to "new word"),
            numerals = true,
            dictation = true
        )
        assertFalse(params.contains("&language="))
        assertTrue(params.contains("model=nova-3"))
        assertTrue(params.contains("&keywords=hello+world"))
        assertTrue(params.contains("&replace=old+word:new+word"))
        assertTrue(params.contains("&numerals=true&dictation=true"))
    }
}
