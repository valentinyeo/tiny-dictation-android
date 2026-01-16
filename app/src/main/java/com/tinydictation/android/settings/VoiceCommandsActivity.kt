package com.tinydictation.android.settings

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.tinydictation.android.R

/**
 * Activity displaying all available voice commands organized by category.
 * Users can see what commands they can speak while dictating.
 */
class VoiceCommandsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_commands)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        setupCommands()
    }

    private fun setupCommands() {
        // Deletion commands
        setCommand(R.id.cmdScratchThat, "\"scratch that\"", "Clear all text")
        setCommand(R.id.cmdUndoThat, "\"undo that\"", "Delete last word")
        setCommand(R.id.cmdDelete, "\"delete\"", "Delete one character")

        // Navigation commands
        setCommand(R.id.cmdNewLine, "\"new line\"", "Insert line break")
        setCommand(R.id.cmdNewParagraph, "\"new paragraph\"", "Insert two line breaks")
        setCommand(R.id.cmdTab, "\"tab\"", "Insert tab character")
        setCommand(R.id.cmdSpace, "\"space\"", "Insert space")
        setCommand(R.id.cmdSelectAll, "\"select all\"", "Select all text")

        // Punctuation commands
        setCommand(R.id.cmdPeriod, "\"period\"", "Insert .")
        setCommand(R.id.cmdComma, "\"comma\"", "Insert ,")
        setCommand(R.id.cmdQuestion, "\"question mark\"", "Insert ?")
        setCommand(R.id.cmdExclamation, "\"exclamation mark\"", "Insert !")
        setCommand(R.id.cmdColon, "\"colon\"", "Insert :")
        setCommand(R.id.cmdSemicolon, "\"semicolon\"", "Insert ;")

        // Symbol commands
        setCommand(R.id.cmdHyphen, "\"hyphen\" / \"dash\"", "Insert -")
        setCommand(R.id.cmdUnderscore, "\"underscore\"", "Insert _")
        setCommand(R.id.cmdAtSign, "\"at sign\"", "Insert @")
        setCommand(R.id.cmdHashtag, "\"hashtag\"", "Insert #")
        setCommand(R.id.cmdAmpersand, "\"ampersand\"", "Insert &")
        setCommand(R.id.cmdAsterisk, "\"asterisk\" / \"star\"", "Insert *")

        // Quotes & brackets
        setCommand(R.id.cmdOpenQuote, "\"open quote\"", "Insert \"")
        setCommand(R.id.cmdCloseQuote, "\"close quote\"", "Insert \"")
        setCommand(R.id.cmdSingleQuote, "\"single quote\"", "Insert '")
        setCommand(R.id.cmdOpenParen, "\"open paren\"", "Insert (")
        setCommand(R.id.cmdCloseParen, "\"close paren\"", "Insert )")
        setCommand(R.id.cmdOpenBracket, "\"open bracket\"", "Insert [")
        setCommand(R.id.cmdCloseBracket, "\"close bracket\"", "Insert ]")
        setCommand(R.id.cmdOpenBrace, "\"open brace\"", "Insert {")
        setCommand(R.id.cmdCloseBrace, "\"close brace\"", "Insert }")
    }

    private fun setCommand(viewId: Int, command: String, result: String) {
        val view = findViewById<View>(viewId)
        view.findViewById<TextView>(R.id.tvCommand).text = command
        view.findViewById<TextView>(R.id.tvResult).text = result
    }
}
