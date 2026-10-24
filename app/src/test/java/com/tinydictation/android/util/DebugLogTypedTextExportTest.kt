package com.tinydictation.android.util

import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DebugLogTypedTextExportTest {

    @BeforeEach
    fun clearLog() {
        DebugLog.clear()
    }

    @Test
    fun typedTextChannelsAndSelectedKeysStayOutOfExport() {
        val markers = listOf("PRIVATE_TYPED_657", "PRIVATE_DICTATED_657", "PRIVATE_CLIPBOARD_657")
        val messages = listOf(
            "DictationKeyboardService: Claude command inserted",
            "DictationKeyboardService: A key popup selected",
            "DictationKeyboardService: Punctuation popup selected"
        )
        val sourcePath = "src/main/java/com/tinydictation/android/service/DictationKeyboardService.kt"
        val source = listOf(File(sourcePath), File("app/$sourcePath"))
            .first { it.isFile }
            .readLines()
        val logCalls = source.map { it.trim() }.filter { line ->
            listOf("Claude command", "A key popup selected", "Punctuation popup selected").any {
                line.startsWith("DebugLog.d(\"DictationKeyboardService: $it")
            }
        }

        assertEquals(messages.map { "DebugLog.d(\"$it\")" }, logCalls)
        logCalls.zip(markers).forEach { (call, marker) ->
            val message = call.removePrefix("DebugLog.d(\"").removeSuffix("\")")
            DebugLog.d(message, marker)
        }
        DebugLog.i("Dictated transcript", markers[1])
        DebugLog.w("Clipboard content", markers[2])
        DebugLog.e("Sentence correction", markers[0])
        DebugLog.i("Model ready for dictation")

        val exported = DebugLog.exportLog()

        markers.forEach { marker -> assertFalse(exported.contains(marker), marker) }
        messages.forEach { message -> assertTrue(exported.contains(message), message) }
        assertTrue(exported.contains("Model ready for dictation"))
    }
}
