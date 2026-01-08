package com.tinydictation.android

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.tinydictation.android.util.PreferenceManager

class SettingsActivity : AppCompatActivity() {

    private lateinit var preferenceManager: PreferenceManager

    private lateinit var toolbar: MaterialToolbar
    private lateinit var apiKeyInput: TextInputEditText
    private lateinit var vadSensitivitySlider: Slider
    private lateinit var autoPunctuationSwitch: SwitchMaterial
    private lateinit var saveButton: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        preferenceManager = PreferenceManager(this)

        initViews()
        loadSettings()
        setupClickListeners()
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        apiKeyInput = findViewById(R.id.apiKeyInput)
        vadSensitivitySlider = findViewById(R.id.vadSensitivitySlider)
        autoPunctuationSwitch = findViewById(R.id.autoPunctuationSwitch)
        saveButton = findViewById(R.id.saveButton)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_title)
    }

    private fun loadSettings() {
        // Load API key
        preferenceManager.getApiKey()?.let { apiKey ->
            apiKeyInput.setText(apiKey)
        }

        // Load VAD sensitivity (0.3 - 0.8 range)
        vadSensitivitySlider.value = preferenceManager.getVadSensitivity()

        // Load auto-punctuation
        autoPunctuationSwitch.isChecked = preferenceManager.getAutoPunctuation()
    }

    private fun setupClickListeners() {
        toolbar.setNavigationOnClickListener {
            finish()
        }

        saveButton.setOnClickListener {
            saveSettings()
        }
    }

    private fun saveSettings() {
        val apiKey = apiKeyInput.text?.toString()?.trim()

        if (apiKey.isNullOrEmpty()) {
            apiKeyInput.error = "API key is required"
            return
        }

        // Save API key
        preferenceManager.setApiKey(apiKey)

        // Save VAD sensitivity
        preferenceManager.setVadSensitivity(vadSensitivitySlider.value)

        // Save auto-punctuation
        preferenceManager.setAutoPunctuation(autoPunctuationSwitch.isChecked)

        Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        finish()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
