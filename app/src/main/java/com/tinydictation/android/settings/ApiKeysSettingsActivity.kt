package com.tinydictation.android.settings

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.tinydictation.android.R
import com.tinydictation.android.util.PreferenceManager

/**
 * Activity for managing API keys.
 * Supports Deepgram (transcription) and Anthropic (text improvement).
 */
class ApiKeysSettingsActivity : AppCompatActivity() {

    private lateinit var preferenceManager: PreferenceManager

    private lateinit var deepgramApiKeyInput: TextInputEditText
    private lateinit var anthropicApiKeyInput: TextInputEditText
    private lateinit var saveButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_api_keys_settings)

        preferenceManager = PreferenceManager(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        initViews()
        loadSettings()
        setupClickListeners()
    }

    private fun initViews() {
        deepgramApiKeyInput = findViewById(R.id.etDeepgramApiKey)
        anthropicApiKeyInput = findViewById(R.id.etAnthropicApiKey)
        saveButton = findViewById(R.id.btnSave)
    }

    private fun loadSettings() {
        preferenceManager.getApiKey()?.let { deepgramApiKeyInput.setText(it) }
        preferenceManager.getAnthropicApiKey()?.let { anthropicApiKeyInput.setText(it) }
    }

    private fun setupClickListeners() {
        saveButton.setOnClickListener {
            saveSettings()
        }
    }

    private fun saveSettings() {
        val deepgramApiKey = deepgramApiKeyInput.text?.toString()?.trim()
        val anthropicApiKey = anthropicApiKeyInput.text?.toString()?.trim()

        if (!deepgramApiKey.isNullOrEmpty()) {
            preferenceManager.setApiKey(deepgramApiKey)
        }
        if (!anthropicApiKey.isNullOrEmpty()) {
            preferenceManager.setAnthropicApiKey(anthropicApiKey)
        }

        Toast.makeText(this, "API keys saved", Toast.LENGTH_SHORT).show()
        finish()
    }
}
