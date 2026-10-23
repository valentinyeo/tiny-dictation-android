package com.tinydictation.android.commands

import android.view.KeyEvent
import android.view.inputmethod.InputConnection
import com.tinydictation.android.util.DebugLog

object VoiceCommandProcessor {

    sealed class CommandResult {
        data class NotACommand(val text: String) : CommandResult()

        object Executed : CommandResult()

        data class Failed(val reason: String) : CommandResult()
    }

    private data class Command(
        val phrases: List<String>,
        val action: (InputConnection, () -> Unit) -> Boolean
    )

    private val commands = listOf(
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

        Command(listOf("tab", "insert tab")) { ic, _ ->
            ic.commitText("\t", 1)
            true
        },
        Command(listOf("space", "insert space")) { ic, _ ->
            ic.commitText(" ", 1)
            true
        },

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

        Command(listOf("select all", "select everything")) { ic, _ ->
            selectAll(ic)
        }
    )

    fun process(
        text: String,
        inputConnection: InputConnection?,
        sendKeyEvents: (Int) -> Unit
    ): CommandResult {
        if (inputConnection == null) {
            return CommandResult.Failed("No input connection")
        }

        val normalizedText = text.trim().lowercase().trimEnd('.', ',', '!', '?', ';', ':')

        for (command in commands) {
            if (command.phrases.any { normalizedText == it || normalizedText.endsWith(" $it") }) {
                DebugLog.i("VoiceCommandProcessor: Executing command for", "'$normalizedText'")

                val success = command.action(inputConnection) {
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
        ic.performContextMenuAction(android.R.id.selectAll)
        ic.commitText("", 1)
        DebugLog.i("VoiceCommandProcessor: Select all + delete via context menu action")
        return true
    }

    private fun deleteLastWord(ic: InputConnection): Boolean {
        val textBefore = ic.getTextBeforeCursor(100, 0)
        if (textBefore.isNullOrEmpty()) {
            return true
        }

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

    private fun selectAll(ic: InputConnection): Boolean {
        val before = ic.getTextBeforeCursor(Int.MAX_VALUE, 0) ?: ""
        val after = ic.getTextAfterCursor(Int.MAX_VALUE, 0) ?: ""
        val totalLength = before.length + after.length
        ic.setSelection(0, totalLength)
        DebugLog.i("VoiceCommandProcessor: Selected all ($totalLength chars)")
        return true
    }
}
