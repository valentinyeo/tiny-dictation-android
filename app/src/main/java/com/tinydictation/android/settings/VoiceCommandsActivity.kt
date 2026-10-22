package com.tinydictation.android.settings

import android.os.Bundle
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.tinydictation.android.R

class VoiceCommandsActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_commands)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        setupCommands()
    }

    private fun setupCommands() {
        setCommand(R.id.cmdScratchThat, "\"scratch that\"", "Clear all text")
        setCommand(R.id.cmdUndoThat, "\"undo that\"", "Delete last word")
        setCommand(R.id.cmdDelete, "\"delete\"", "Delete one character")

        setCommand(R.id.cmdTab, "\"tab\"", "Insert tab character")
        setCommand(R.id.cmdSpace, "\"space\"", "Insert space")
        setCommand(R.id.cmdSelectAll, "\"select all\"", "Select all text")

        setCommand(R.id.cmdSemicolon, "\"semicolon\"", "Insert ;")
        setCommand(R.id.cmdHyphen, "\"hyphen\" / \"dash\"", "Insert -")
        setCommand(R.id.cmdUnderscore, "\"underscore\"", "Insert _")
        setCommand(R.id.cmdAtSign, "\"at sign\"", "Insert @")
        setCommand(R.id.cmdHashtag, "\"hashtag\"", "Insert #")
        setCommand(R.id.cmdAmpersand, "\"ampersand\"", "Insert &")
        setCommand(R.id.cmdAsterisk, "\"asterisk\" / \"star\"", "Insert *")

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
