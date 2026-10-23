package com.tinydictation.android.util

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
    fun textFieldExportsOnlyItsCharacterCount() {
        DebugLog.d("Editor text", "SECRET_MARKER_123")

        val exported = DebugLog.exportLog()

        assertFalse(exported.contains("SECRET_MARKER_123"))
        assertTrue(exported.contains("Editor text (17 chars)"))
    }

    @Test
    fun plainMessageExportsInFull() {
        DebugLog.i("Model ready for dictation")

        assertTrue(DebugLog.exportLog().contains("Model ready for dictation"))
    }
}
