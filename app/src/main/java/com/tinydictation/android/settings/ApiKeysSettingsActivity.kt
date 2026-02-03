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
 * Activity for managing all API keys in one place.
 * Provides a centralized location to enter and manage API keys for:
 * - Transcription services (Deepgram, Groq, OpenAI, AssemblyAI, Gemini)
 * - AI services (OpenRouter, Anthropic)
 */
class ApiKeysSettingsActivity : AppCompatActivity() {

    private lateinit var preferenceManager: PreferenceManager

    // Transcription API key inputs
    private lateinit var deepgramApiKeyInput: TextInputEditText
    private lateinit var groqApiKeyInput: TextInputEditText
    private lateinit var openAIApiKeyInput: TextInputEditText
    private lateinit var assemblyAIApiKeyInput: TextInputEditText
    private lateinit var geminiApiKeyInput: TextInputEditText

    // AI service API key inputs
    private lateinit var openRouterApiKeyInput: TextInputEditText
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
        // Transcription keys
        deepgramApiKeyInput = findViewById(R.id.etDeepgramApiKey)
        groqApiKeyInput = findViewById(R.id.etGroqApiKey)
        openAIApiKeyInput = findViewById(R.id.etOpenAIApiKey)
        assemblyAIApiKeyInput = findViewById(R.id.etAssemblyAIApiKey)
        geminiApiKeyInput = findViewById(R.id.etGeminiApiKey)

        // AI service keys
        openRouterApiKeyInput = findViewById(R.id.etOpenRouterApiKey)
        anthropicApiKeyInput = findViewById(R.id.etAnthropicApiKey)

        saveButton = findViewById(R.id.btnSave)
    }

    private fun loadSettings() {
        // Load transcription API keys
        preferenceManager.getApiKey()?.let { deepgramApiKeyInput.setText(it) }
        preferenceManager.getGroqApiKey()?.let { groqApiKeyInput.setText(it) }
        preferenceManager.getOpenAIApiKey()?.let { openAIApiKeyInput.setText(it) }
        preferenceManager.getAssemblyAIApiKey()?.let { assemblyAIApiKeyInput.setText(it) }
        preferenceManager.getGeminiApiKey()?.let { geminiApiKeyInput.setText(it) }

        // Load AI service API keys
        preferenceManager.getOpenRouterApiKey()?.let { openRouterApiKeyInput.setText(it) }
        preferenceManager.getAnthropicApiKey()?.let { anthropicApiKeyInput.setText(it) }
    }

    private fun setupClickListeners() {
        saveButton.setOnClickListener {
            saveSettings()
        }
    }

    private fun saveSettings() {
        // Get all input values
        val deepgramApiKey = deepgramApiKeyInput.text?.toString()?.trim()
        val groqApiKey = groqApiKeyInput.text?.toString()?.trim()
        val openAIApiKey = openAIApiKeyInput.text?.toString()?.trim()
        val assemblyAIApiKey = assemblyAIApiKeyInput.text?.toString()?.trim()
        val geminiApiKey = geminiApiKeyInput.text?.toString()?.trim()
        val openRouterApiKey = openRouterApiKeyInput.text?.toString()?.trim()
        val anthropicApiKey = anthropicApiKeyInput.text?.toString()?.trim()

        // Save all non-empty keys
        if (!deepgramApiKey.isNullOrEmpty()) {
            preferenceManager.setApiKey(deepgramApiKey)
        }
        if (!groqApiKey.isNullOrEmpty()) {
            preferenceManager.setGroqApiKey(groqApiKey)
        }
        if (!openAIApiKey.isNullOrEmpty()) {
            preferenceManager.setOpenAIApiKey(openAIApiKey)
        }
        if (!assemblyAIApiKey.isNullOrEmpty()) {
            preferenceManager.setAssemblyAIApiKey(assemblyAIApiKey)
        }
        if (!geminiApiKey.isNullOrEmpty()) {
            preferenceManager.setGeminiApiKey(geminiApiKey)
        }
        if (!openRouterApiKey.isNullOrEmpty()) {
            preferenceManager.setOpenRouterApiKey(openRouterApiKey)
        }
        if (!anthropicApiKey.isNullOrEmpty()) {
            preferenceManager.setAnthropicApiKey(anthropicApiKey)
        }

        Toast.makeText(this, "API keys saved", Toast.LENGTH_SHORT).show()
        finish()
    }
}
