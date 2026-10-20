package com.tinydictation.android.commands

import android.os.Bundle
import android.os.Handler
import android.view.KeyEvent
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputContentInfo
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName

@DisplayName("VoiceCommandProcessor")
class VoiceCommandProcessorTest {

    // Simple fake InputConnection that records calls and holds text state
    private lateinit var fakeIc: FakeInputConnection
    private val keyEventsSent = mutableListOf<Int>()

    @BeforeEach
    fun setUp() {
        fakeIc = FakeInputConnection()
        keyEventsSent.clear()
    }

    // --- NotACommand cases ---

    @Test
    @DisplayName("plain text returns NotACommand with original text")
    fun plainTextReturnsNotACommand() {
        val result = process("hello world")
        assertThat(result).isInstanceOf(VoiceCommandProcessor.CommandResult.NotACommand::class.java)
        assertThat((result as VoiceCommandProcessor.CommandResult.NotACommand).text).isEqualTo("hello world")
    }

    @Test
    @DisplayName("empty string returns NotACommand")
    fun emptyStringReturnsNotACommand() {
        val result = process("")
        assertThat(result).isInstanceOf(VoiceCommandProcessor.CommandResult.NotACommand::class.java)
    }

    @Test
    @DisplayName("partial command phrase returns NotACommand")
    fun partialCommandReturnsNotACommand() {
        val result = process("scratch")
        assertThat(result).isInstanceOf(VoiceCommandProcessor.CommandResult.NotACommand::class.java)
    }

    @Test
    @DisplayName("null InputConnection returns Failed")
    fun nullInputConnectionReturnsFailed() {
        val result = VoiceCommandProcessor.process("semicolon", null) { keyEventsSent.add(it) }
        assertThat(result).isInstanceOf(VoiceCommandProcessor.CommandResult.Failed::class.java)
    }

    // --- Deletion commands ---

    @Test
    @DisplayName("'scratch that' returns Executed")
    fun scratchThatReturnsExecuted() {
        val result = process("scratch that")
        assertThat(result).isEqualTo(VoiceCommandProcessor.CommandResult.Executed)
    }

    @Test
    @DisplayName("'delete that' returns Executed")
    fun deleteThatReturnsExecuted() {
        val result = process("delete that")
        assertThat(result).isEqualTo(VoiceCommandProcessor.CommandResult.Executed)
    }

    @Test
    @DisplayName("'clear that' returns Executed")
    fun clearThatReturnsExecuted() {
        val result = process("clear that")
        assertThat(result).isEqualTo(VoiceCommandProcessor.CommandResult.Executed)
    }

    // --- Punctuation commands ---

    @Test
    @DisplayName("'semicolon' inserts semicolon character")
    fun semicolonInsertsCharacter() {
        process("semicolon")
        assertThat(fakeIc.committedText).isEqualTo(";")
    }

    @Test
    @DisplayName("'semi colon' (two words) inserts semicolon")
    fun semiColonTwoWordsInsertsCharacter() {
        process("semi colon")
        assertThat(fakeIc.committedText).isEqualTo(";")
    }

    @Test
    @DisplayName("'hyphen' inserts hyphen")
    fun hyphenInsertsCharacter() {
        process("hyphen")
        assertThat(fakeIc.committedText).isEqualTo("-")
    }

    @Test
    @DisplayName("'dash' inserts hyphen")
    fun dashInsertsCharacter() {
        process("dash")
        assertThat(fakeIc.committedText).isEqualTo("-")
    }

    @Test
    @DisplayName("'underscore' inserts underscore")
    fun underscoreInsertsCharacter() {
        process("underscore")
        assertThat(fakeIc.committedText).isEqualTo("_")
    }

    @Test
    @DisplayName("'at sign' inserts @")
    fun atSignInsertsCharacter() {
        process("at sign")
        assertThat(fakeIc.committedText).isEqualTo("@")
    }

    @Test
    @DisplayName("'hashtag' inserts #")
    fun hashtagInsertsCharacter() {
        process("hashtag")
        assertThat(fakeIc.committedText).isEqualTo("#")
    }

    @Test
    @DisplayName("'ampersand' inserts &")
    fun ampersandInsertsCharacter() {
        process("ampersand")
        assertThat(fakeIc.committedText).isEqualTo("&")
    }

    @Test
    @DisplayName("'asterisk' inserts *")
    fun asteriskInsertsCharacter() {
        process("asterisk")
        assertThat(fakeIc.committedText).isEqualTo("*")
    }

    @Test
    @DisplayName("'star' inserts *")
    fun starInsertsCharacter() {
        process("star")
        assertThat(fakeIc.committedText).isEqualTo("*")
    }

    // --- Quote commands ---

    @Test
    @DisplayName("'open quote' inserts double-quote character")
    fun openQuoteInsertsDoubleQuote() {
        process("open quote")
        assertThat(fakeIc.committedText).isEqualTo("\"")
    }

    @Test
    @DisplayName("'close quote' inserts double-quote character")
    fun closeQuoteInsertsDoubleQuote() {
        process("close quote")
        assertThat(fakeIc.committedText).isEqualTo("\"")
    }

    @Test
    @DisplayName("'single quote' inserts single-quote character")
    fun singleQuoteInsertsSingleQuote() {
        process("single quote")
        assertThat(fakeIc.committedText).isEqualTo("'")
    }

    @Test
    @DisplayName("'apostrophe' inserts single-quote character")
    fun apostropheInsertsSingleQuote() {
        process("apostrophe")
        assertThat(fakeIc.committedText).isEqualTo("'")
    }

    // --- Bracket commands ---

    @Test
    @DisplayName("'open paren' inserts (")
    fun openParenInsertsCharacter() {
        process("open paren")
        assertThat(fakeIc.committedText).isEqualTo("(")
    }

    @Test
    @DisplayName("'close paren' inserts )")
    fun closeParenInsertsCharacter() {
        process("close paren")
        assertThat(fakeIc.committedText).isEqualTo(")")
    }

    @Test
    @DisplayName("'open bracket' inserts [")
    fun openBracketInsertsCharacter() {
        process("open bracket")
        assertThat(fakeIc.committedText).isEqualTo("[")
    }

    @Test
    @DisplayName("'close bracket' inserts ]")
    fun closeBracketInsertsCharacter() {
        process("close bracket")
        assertThat(fakeIc.committedText).isEqualTo("]")
    }

    @Test
    @DisplayName("'open brace' inserts {")
    fun openBraceInsertsCharacter() {
        process("open brace")
        assertThat(fakeIc.committedText).isEqualTo("{")
    }

    @Test
    @DisplayName("'close brace' inserts }")
    fun closeBraceInsertsCharacter() {
        process("close brace")
        assertThat(fakeIc.committedText).isEqualTo("}")
    }

    // --- Navigation ---

    @Test
    @DisplayName("'tab' inserts tab character")
    fun tabInsertsTabCharacter() {
        process("tab")
        assertThat(fakeIc.committedText).isEqualTo("\t")
    }

    @Test
    @DisplayName("'space' inserts space character")
    fun spaceInsertsSpaceCharacter() {
        process("space")
        assertThat(fakeIc.committedText).isEqualTo(" ")
    }

    // --- Case insensitivity ---

    @Test
    @DisplayName("commands are case-insensitive (UPPERCASE)")
    fun commandsAreCaseInsensitiveUppercase() {
        val result = process("SEMICOLON")
        assertThat(result).isEqualTo(VoiceCommandProcessor.CommandResult.Executed)
        assertThat(fakeIc.committedText).isEqualTo(";")
    }

    @Test
    @DisplayName("commands are case-insensitive (Mixed Case)")
    fun commandsAreCaseInsensitiveMixed() {
        val result = process("Scratch That")
        assertThat(result).isEqualTo(VoiceCommandProcessor.CommandResult.Executed)
    }

    @Test
    @DisplayName("commands are case-insensitive (Title Case)")
    fun openQuoteTitleCaseWorks() {
        val result = process("Open Quote")
        assertThat(result).isEqualTo(VoiceCommandProcessor.CommandResult.Executed)
        assertThat(fakeIc.committedText).isEqualTo("\"")
    }

    // --- Trailing punctuation stripping ---

    @Test
    @DisplayName("commands with trailing period are recognized")
    fun commandWithTrailingPeriodRecognized() {
        val result = process("semicolon.")
        assertThat(result).isEqualTo(VoiceCommandProcessor.CommandResult.Executed)
    }

    @Test
    @DisplayName("commands with trailing comma are recognized")
    fun commandWithTrailingCommaRecognized() {
        val result = process("scratch that,")
        assertThat(result).isEqualTo(VoiceCommandProcessor.CommandResult.Executed)
    }

    // --- Leading/trailing whitespace ---

    @Test
    @DisplayName("commands with surrounding whitespace are recognized")
    fun commandWithSurroundingWhitespace() {
        val result = process("  semicolon  ")
        assertThat(result).isEqualTo(VoiceCommandProcessor.CommandResult.Executed)
        assertThat(fakeIc.committedText).isEqualTo(";")
    }

    // --- Command as suffix (Deepgram sometimes prepends words) ---

    @Test
    @DisplayName("command appearing as suffix of longer text is recognized")
    fun commandAsSuffixIsRecognized() {
        // The processor checks endsWith(" command") so "okay semicolon" should match
        val result = process("okay semicolon")
        assertThat(result).isEqualTo(VoiceCommandProcessor.CommandResult.Executed)
        assertThat(fakeIc.committedText).isEqualTo(";")
    }

    // --- Helper ---

    private fun process(text: String): VoiceCommandProcessor.CommandResult =
        VoiceCommandProcessor.process(text, fakeIc) { keyEventsSent.add(it) }
}

/**
 * Minimal fake InputConnection that records commitText calls and supports
 * getTextBeforeCursor / deleteSurroundingText for the deleteLastWord tests.
 *
 * All other methods are no-ops returning safe defaults.
 */
private class FakeInputConnection : InputConnection {

    /** Accumulates all text committed via commitText (excluding empty-string deletions). */
    val committedText: String get() = _committed.toString()
    private val _committed = StringBuilder()

    /** Simulated text buffer before the cursor, for deleteLastWord tests. */
    var textBeforeCursor: String = ""

    private var selectionStart = 0
    private var selectionEnd = 0

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (text != null) _committed.append(text)
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (textBeforeCursor.length >= beforeLength) {
            textBeforeCursor = textBeforeCursor.dropLast(beforeLength)
        }
        return true
    }

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = textBeforeCursor

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = ""

    override fun setSelection(start: Int, end: Int): Boolean {
        selectionStart = start
        selectionEnd = end
        return true
    }

    override fun performContextMenuAction(id: Int): Boolean = true
    override fun commitCompletion(text: CompletionInfo?): Boolean = true
    override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean = true

    // --- Remaining InputConnection methods — safe no-op stubs ---

    override fun getSelectedText(flags: Int): CharSequence? = null
    override fun getCursorCapsMode(reqModes: Int): Int = 0
    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText? = null
    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean = true
    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean = true
    override fun setComposingRegion(start: Int, end: Int): Boolean = true
    override fun finishComposingText(): Boolean = true
    override fun beginBatchEdit(): Boolean = true
    override fun endBatchEdit(): Boolean = true
    override fun sendKeyEvent(event: KeyEvent?): Boolean = true
    override fun clearMetaKeyStates(states: Int): Boolean = true
    override fun reportFullscreenMode(enabled: Boolean): Boolean = true
    override fun performEditorAction(editorAction: Int): Boolean = true
    override fun performPrivateCommand(action: String?, data: Bundle?): Boolean = true
    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean = true
    override fun getHandler(): Handler? = null
    override fun closeConnection() {}
    override fun commitContent(inputContentInfo: InputContentInfo, flags: Int, opts: Bundle?): Boolean = true
}
