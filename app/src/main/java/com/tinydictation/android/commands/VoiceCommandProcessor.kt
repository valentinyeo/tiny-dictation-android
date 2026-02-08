package com.tinydictation.android.commands

import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import com.tinydictation.android.util.DebugLog

/**
 * Processes voice commands from dictation.
 * Commands are spoken phrases that trigger actions instead of being typed.
 *
 * Note: Basic punctuation (period, comma, question mark, exclamation mark, colon,
 * new line, new paragraph) is handled by Deepgram's dictation mode server-side.
 * This processor handles commands that Deepgram doesn't support natively.
 *
 * Supported commands:
 * - "scratch that" / "delete that" → Select all and delete
 * - "undo that" → Delete last word
 * - "semicolon" → Insert ;
 * - "hyphen" / "dash" → Insert -
 * - "underscore" → Insert _
 * - "at sign" → Insert @
 * - "hashtag" → Insert #
 * - "ampersand" → Insert &
 * - "asterisk" / "star" → Insert *
 * - "open quote" / "close quote" → Insert "
 * - "single quote" → Insert '
 * - "open paren" / "close paren" → Insert ( )
 * - "open bracket" / "close bracket" → Insert [ ]
 * - "open brace" / "close brace" → Insert { }
 * - "space" → Insert space
 * - "tab" → Insert tab
 * - "select all" → Select all text
 */
object VoiceCommandProcessor {

    /**
     * Result of command processing.
     */
    sealed class CommandResult {
        /** Not a command - text should be typed normally */
        data class NotACommand(val text: String) : CommandResult()

        /** Command was executed successfully */
        object Executed : CommandResult()

        /** Command recognized but execution failed */
        data class Failed(val reason: String) : CommandResult()
    }

    // Command definitions - lowercase for matching
    // Using pairs of (variations, action)
    private data class Command(
        val phrases: List<String>,
        val action: (InputConnection, () -> Unit) -> Boolean
    )

    private val commands = listOf(
        // Deletion commands
        Command(listOf("scratch that", "delete that", "clear that")) { ic, sendKeyEvents ->
            selectAllAndDelete(ic, sendKeyEvents)
        },
        Command(listOf("undo that", "delete last word", "scratch word")) { ic, _ ->
            deleteLastWord(ic)
        },
        Command(listOf("delete", "backspace")) { ic, _ ->
            ic.deleteSurroundingText(1, 0)
            true
        },

        // Navigation/formatting
        // Note: "new line" and "new paragraph" are handled by Deepgram dictation mode
        Command(listOf("tab", "insert tab")) { ic, _ ->
            ic.commitText("\t", 1)
            true
        },
        Command(listOf("space", "insert space")) { ic, _ ->
            ic.commitText(" ", 1)
            true
        },

        // Punctuation
        // Note: period, comma, question mark, exclamation mark, colon are handled by Deepgram dictation mode
        Command(listOf("semicolon", "semi colon")) { ic, _ ->
            ic.commitText(";", 1)
            true
        },
        Command(listOf("hyphen", "dash")) { ic, _ ->
            ic.commitText("-", 1)
            true
        },
        Command(listOf("underscore")) { ic, _ ->
            ic.commitText("_", 1)
            true
        },
        Command(listOf("at sign", "at symbol")) { ic, _ ->
            ic.commitText("@", 1)
            true
        },
        Command(listOf("hashtag", "hash", "pound sign")) { ic, _ ->
            ic.commitText("#", 1)
            true
        },
        Command(listOf("ampersand", "and sign")) { ic, _ ->
            ic.commitText("&", 1)
            true
        },
        Command(listOf("asterisk", "star")) { ic, _ ->
            ic.commitText("*", 1)
            true
        },

        // Quotes and brackets
        Command(listOf("open quote", "open quotes", "begin quote")) { ic, _ ->
            ic.commitText("\"", 1)
            true
        },
        Command(listOf("close quote", "close quotes", "end quote")) { ic, _ ->
            ic.commitText("\"", 1)
            true
        },
        Command(listOf("single quote", "apostrophe")) { ic, _ ->
            ic.commitText("'", 1)
            true
        },
        Command(listOf("open paren", "open parenthesis", "left paren")) { ic, _ ->
            ic.commitText("(", 1)
            true
        },
        Command(listOf("close paren", "close parenthesis", "right paren")) { ic, _ ->
            ic.commitText(")", 1)
            true
        },
        Command(listOf("open bracket", "left bracket")) { ic, _ ->
            ic.commitText("[", 1)
            true
        },
        Command(listOf("close bracket", "right bracket")) { ic, _ ->
            ic.commitText("]", 1)
            true
        },
        Command(listOf("open brace", "left brace", "open curly")) { ic, _ ->
            ic.commitText("{", 1)
            true
        },
        Command(listOf("close brace", "right brace", "close curly")) { ic, _ ->
            ic.commitText("}", 1)
            true
        },

        // Selection
        Command(listOf("select all", "select everything")) { ic, _ ->
            selectAll(ic)
        }
    )

    /**
     * Processes the transcribed text and executes any recognized commands.
     *
     * @param text The transcribed text to check for commands
     * @param inputConnection The input connection to use for text manipulation
     * @param sendKeyEvents Function to send key events (for select all)
     * @return CommandResult indicating whether a command was executed or not
     */
    fun process(
        text: String,
        inputConnection: InputConnection?,
        sendKeyEvents: (Int) -> Unit
    ): CommandResult {
        if (inputConnection == null) {
            return CommandResult.Failed("No input connection")
        }

        // Normalize text: lowercase and strip trailing punctuation
        val normalizedText = text.trim().lowercase().trimEnd('.', ',', '!', '?', ';', ':')

        // Find matching command
        for (command in commands) {
            if (command.phrases.any { normalizedText == it || normalizedText.endsWith(" $it") }) {
                DebugLog.i("VoiceCommandProcessor: Executing command for '$normalizedText'")

                val success = command.action(inputConnection) {
                    // Wrapper for sending key events - used by select all
                    sendKeyEvents(KeyEvent.KEYCODE_A)
                }

                return if (success) {
                    CommandResult.Executed
                } else {
                    CommandResult.Failed("Command execution failed")
                }
            }
        }

        return CommandResult.NotACommand(text)
    }

    private fun selectAllAndDelete(ic: InputConnection, sendCtrlA: () -> Unit): Boolean {
        // Use performContextMenuAction for reliable select-all across all apps
        // (getTextBeforeCursor + setSelection is unreliable in many apps)
        ic.performContextMenuAction(android.R.id.selectAll)
        ic.commitText("", 1)
        DebugLog.i("VoiceCommandProcessor: Select all + delete via context menu action")
        return true
    }

    /**
     * Deletes the last word before the cursor.
     */
    private fun deleteLastWord(ic: InputConnection): Boolean {
        val textBefore = ic.getTextBeforeCursor(100, 0)
        if (textBefore.isNullOrEmpty()) {
            return true // Nothing to delete
        }

        // Find word boundary - skip trailing spaces, then find start of word
        var endIndex = textBefore.length
        while (endIndex > 0 && textBefore[endIndex - 1] == ' ') {
            endIndex--
        }
        var startIndex = endIndex
        while (startIndex > 0 && textBefore[startIndex - 1] != ' ') {
            startIndex--
        }

        val charsToDelete = textBefore.length - startIndex
        if (charsToDelete > 0) {
            ic.deleteSurroundingText(charsToDelete, 0)
            DebugLog.i("VoiceCommandProcessor: Deleted last word ($charsToDelete chars)")
        }
        return true
    }

    /**
     * Selects all text in the input field.
     */
    private fun selectAll(ic: InputConnection): Boolean {
        val before = ic.getTextBeforeCursor(Int.MAX_VALUE, 0) ?: ""
        val after = ic.getTextAfterCursor(Int.MAX_VALUE, 0) ?: ""
        val totalLength = before.length + after.length
        ic.setSelection(0, totalLength)
        DebugLog.i("VoiceCommandProcessor: Selected all ($totalLength chars)")
        return true
    }
}
