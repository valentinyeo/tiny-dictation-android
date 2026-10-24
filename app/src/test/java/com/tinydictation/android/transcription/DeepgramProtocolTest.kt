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
    fun nova3UsesKeytermsAndMultilingualLanguage() {
        val params = DeepgramClient.buildParams(
            language = "auto",
            model = "nova-3",
            keywords = listOf("hello world"),
            replacements = listOf("old word" to "new word"),
            numerals = true,
            dictation = true
        )
        assertTrue(params.contains("&language=multi"))
        assertTrue(params.contains("model=nova-3"))
        assertTrue(params.contains("&keyterm=hello+world"))
        assertFalse(params.contains("&keywords="))
        assertTrue(params.contains("&replace=old+word:new+word"))
        assertTrue(params.contains("&numerals=true&dictation=true"))
    }

    @Test
    fun nova2PreservesKeywordsAndExplicitLanguage() {
        val params = DeepgramClient.buildParams(
            language = "de",
            model = "nova-2",
            keywords = listOf("Deepgram", "hello world")
        )
        assertTrue(params.contains("&language=de"))
        assertTrue(params.contains("&keywords=Deepgram&keywords=hello+world"))
        assertFalse(params.contains("&keyterm="))
    }

    @Test
    fun formattingTogglesRemainIndependentWithSpeechOnlyStreaming() {
        for (smartFormat in listOf(false, true)) {
            for (punctuate in listOf(false, true)) {
                val params = DeepgramClient.buildParams(
                    smartFormat = smartFormat,
                    punctuate = punctuate
                )
                assertTrue(params.contains("&smart_format=$smartFormat"))
                assertTrue(params.contains("&punctuate=$punctuate"))
                assertTrue(params.contains("&interim_results=true"))
                assertTrue(params.contains("&tag=tiny-dictation"))
            }
        }
    }

    @Test
    fun terminalModeOverridesEnabledFormattingToggles() {
        val params = DeepgramClient.buildParams(
            terminalMode = true,
            smartFormat = true,
            punctuate = true
        )
        assertTrue(params.contains("&smart_format=false"))
        assertTrue(params.contains("&punctuate=false"))
        assertFalse(params.contains("&smart_format=true"))
        assertFalse(params.contains("&punctuate=true"))
    }
}
