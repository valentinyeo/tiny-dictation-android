package com.tinydictation.android.service

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class EditorPrivacyTest {

    @Test
    fun maskedTextVariationsBlockCloudText() {
        for (variation in listOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        )) {
            assertFalse(EditorPrivacy.allowsCloudText(InputType.TYPE_CLASS_TEXT or variation, 0))
        }
    }

    @Test
    fun maskedNumberVariationBlocksCloudText() {
        assertFalse(EditorPrivacy.allowsCloudText(
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD, 0
        ))
    }

    @Test
    fun noLearningOptionBlocksCloudText() {
        assertFalse(EditorPrivacy.allowsCloudText(
            InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        ))
    }

    @Test
    fun noSuggestionsBlocksCloudText() {
        assertFalse(EditorPrivacy.allowsCloudText(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, 0
        ))
    }

    @Test
    fun restrictedEditorsNeverReadText() {
        val restrictedFields = listOf(
            (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS) to 0,
            (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE) to EditorInfo.IME_ACTION_DONE,
            (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD) to 0,
            (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) to 0,
            (InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD) to 0,
            (InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD) to 0,
            InputType.TYPE_CLASS_TEXT to EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        )
        var reads = 0
        for ((inputType, imeOptions) in restrictedFields) {
            assertFalse(EditorPrivacy.allowsCloudText(inputType, imeOptions))
            assertNull(EditorPrivacy.readTextIfAllowed(inputType, imeOptions) {
                reads++
                "PRIVATE_EDITOR_657"
            })
            assertEquals(0, reads)
        }

        assertEquals("ordinary text", EditorPrivacy.readTextIfAllowed(
            InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_DONE
        ) {
            reads++
            "ordinary text"
        })
        assertEquals(1, reads)
    }

    @Test
    fun ordinaryTextVariationsAllowCloudText() {
        for (variation in listOf(
            InputType.TYPE_TEXT_VARIATION_NORMAL,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_URI
        )) {
            assertTrue(EditorPrivacy.allowsCloudText(InputType.TYPE_CLASS_TEXT or variation, 0))
        }
    }

    @Test
    fun unrelatedFlagsKeepOrdinaryTextAllowed() {
        assertTrue(EditorPrivacy.allowsCloudText(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
            EditorInfo.IME_ACTION_DONE
        ))
    }
}
