package com.tinydictation.android.settings

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.tinydictation.android.R
import com.tinydictation.android.service.DictationKeyboardService
import com.tinydictation.android.util.DebugLog
import com.tinydictation.android.util.PreferenceManager

/**
 * Activity for configuring appearance settings including keyboard theme and GIF settings.
 */
class AppearanceSettingsActivity : AppCompatActivity() {

    private lateinit var preferenceManager: PreferenceManager

    private lateinit var keyboardThemeDropdown: AutoCompleteTextView
    private lateinit var giphyApiKeyInput: TextInputEditText
    private lateinit var numberRowSwitch: MaterialSwitch
    private lateinit var predictionsSwitch: MaterialSwitch

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_appearance_settings)

        preferenceManager = PreferenceManager(this)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        initViews()
        loadSettings()
        setupClickListeners()
    }

    private fun initViews() {
        keyboardThemeDropdown = findViewById(R.id.actvKeyboardTheme)
        giphyApiKeyInput = findViewById(R.id.etGiphyApiKey)
        numberRowSwitch = findViewById(R.id.switchNumberRow)
        predictionsSwitch = findViewById(R.id.switchPredictions)

        setupKeyboardThemeDropdown()
    }

    private fun loadSettings() {
        preferenceManager.getGiphyApiKey()?.let { giphyApiKeyInput.setText(it) }
        numberRowSwitch.isChecked = preferenceManager.getShowNumberRow()
        predictionsSwitch.isChecked = preferenceManager.getPredictionsEnabled()
    }

    private fun setupClickListeners() {
        // Number row toggle
        numberRowSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setShowNumberRow(isChecked)
            DebugLog.i("Number row setting changed to: $isChecked")
            // Note: Keyboard needs to be reopened to see the change
        }

        // AI predictions toggle
        predictionsSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setPredictionsEnabled(isChecked)
            DebugLog.i("AI predictions setting changed to: $isChecked")
            // Effect is immediate when keyboard next opens
        }

        // Giphy API key saves on text change
        giphyApiKeyInput.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) {
                val apiKey = giphyApiKeyInput.text?.toString()?.trim()
                if (!apiKey.isNullOrEmpty()) {
                    preferenceManager.setGiphyApiKey(apiKey)
                    DebugLog.i("Giphy API key saved")
                }
            }
        }
    }

    private fun setupKeyboardThemeDropdown() {
        val themes = listOf(
            getString(R.string.keyboard_theme_system) to PreferenceManager.THEME_SYSTEM,
            getString(R.string.keyboard_theme_light) to PreferenceManager.THEME_LIGHT,
            getString(R.string.keyboard_theme_dark) to PreferenceManager.THEME_DARK,
            getString(R.string.keyboard_theme_amoled) to PreferenceManager.THEME_AMOLED
        )

        val displayNames = themes.map { it.first }
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, displayNames)
        keyboardThemeDropdown.setAdapter(adapter)

        val currentTheme = preferenceManager.getKeyboardTheme()
        val currentIndex = themes.indexOfFirst { it.second == currentTheme }
        if (currentIndex >= 0) {
            keyboardThemeDropdown.setText(displayNames[currentIndex], false)
        } else {
            keyboardThemeDropdown.setText(displayNames[0], false)
        }

        keyboardThemeDropdown.setOnItemClickListener { _, _, position, _ ->
            val selectedValue = themes[position].second
            preferenceManager.setKeyboardTheme(selectedValue)
            DebugLog.i("Keyboard theme changed to: $selectedValue")
            DictationKeyboardService.getInstance()?.refreshTheme()
        }
    }

    override fun onPause() {
        super.onPause()
        // Save Giphy API key when leaving
        val apiKey = giphyApiKeyInput.text?.toString()?.trim()
        if (!apiKey.isNullOrEmpty()) {
            preferenceManager.setGiphyApiKey(apiKey)
        }
    }
}
