package com.tinydictation.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.tinydictation.android.util.DebugLog
import com.tinydictation.android.util.PreferenceManager

class SettingsActivity : AppCompatActivity() {

    private lateinit var preferenceManager: PreferenceManager

    private lateinit var toolbar: MaterialToolbar
    private lateinit var apiKeyInput: TextInputEditText
    private lateinit var vadSensitivitySlider: Slider
    private lateinit var autoPunctuationSwitch: MaterialSwitch
    private lateinit var copyDebugLogButton: MaterialButton
    private lateinit var saveButton: MaterialButton
    private lateinit var versionInfo: TextView
    private lateinit var changelog: TextView

    companion object {
        // Changelog - update this with each version
        private val CHANGELOG = """
            v1.3.4:
            • Fixed button contrast in dark mode
            • Improved readability for primary buttons

            v1.3.1:
            • Fixed bubble display (no more double bubble)
            • Bubble now appears on right side of screen

            v1.3.0:
            • Version displayed on main screen
            • Changelog in Settings (What's New)

            v1.2.0:
            • Auto-start listening on Start
            • Uses phone mic (not headphones)
            • Improved speech detection
            • Double-tap bubble to close
        """.trimIndent()
    }

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
        apiKeyInput = findViewById(R.id.etApiKey)
        vadSensitivitySlider = findViewById(R.id.sliderVadSensitivity)
        autoPunctuationSwitch = findViewById(R.id.switchAutoPunctuation)
        copyDebugLogButton = findViewById(R.id.btnCopyDebugLog)
        saveButton = findViewById(R.id.btnSave)
        versionInfo = findViewById(R.id.tvVersionInfo)
        changelog = findViewById(R.id.tvChangelog)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_title)

        // Display version info and changelog
        displayVersionInfo()
        changelog.text = CHANGELOG
    }

    private fun displayVersionInfo() {
        try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            val versionName = packageInfo.versionName
            val versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            }
            versionInfo.text = "Version $versionName ($versionCode)"
        } catch (e: PackageManager.NameNotFoundException) {
            versionInfo.text = "Version unknown"
        }
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

        copyDebugLogButton.setOnClickListener {
            copyDebugLogToClipboard()
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

    private fun copyDebugLogToClipboard() {
        val logText = DebugLog.exportLog()

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Tiny Dictation Debug Log", logText)
        clipboard.setPrimaryClip(clip)

        Toast.makeText(this, R.string.debug_log_copied, Toast.LENGTH_SHORT).show()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
