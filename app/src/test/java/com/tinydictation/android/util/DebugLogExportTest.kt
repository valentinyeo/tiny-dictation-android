package com.tinydictation.android.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DebugLogExportTest {

    @BeforeEach
    fun clearLog() {
        DebugLog.clear()
    }

    @Test
    fun textFieldExportsOnlyMetadata() {
        DebugLog.d("Editor text", "SECRET_MARKER_123")

        val exported = DebugLog.exportLog()

        assertFalse(exported.contains("SECRET_MARKER_123"))
        assertFalse(exported.contains("Editor text"))
        assertFalse(exported.contains("chars"))
        assertTrue(exported.contains("[DEBUG]"))
    }

    @Test
    fun plainMessageIsExcludedFromExport() {
        DebugLog.i("Model ready for dictation")

        val exported = DebugLog.exportLog()

        assertFalse(exported.contains("Model ready for dictation"))
        assertTrue(exported.contains("[INFO]"))
    }

    @Test
    fun freeFormEditorContentIsRedacted() {
        val markers = listOf(
            "PRIVATE_EDITOR_657", "PRIVATE_PARTIAL_657",
            "PRIVATE_CORRECTION_657", "PRIVATE_TRANSCRIPT_657"
        )
        DebugLog.d("Editor context: ${markers[0]}")
        DebugLog.i("Partial word: ${markers[1]}")
        DebugLog.w("Sentence correction: ${markers[2]}")
        DebugLog.e("Transcript: ${markers[3]}", markers[0])

        val exported = DebugLog.exportLog()

        markers.forEach { marker -> assertFalse(exported.contains(marker), marker) }
        assertTrue(exported.contains("Total entries: 4"))
        for (level in DebugLog.Level.values()) {
            assertTrue(exported.contains("[$level]"))
        }
        val entryLines = exported.lineSequence().filter { it.matches(Regex("[0-9]+ \\[[A-Z]+]")) }
        assertEquals(4, entryLines.count())
    }
}
