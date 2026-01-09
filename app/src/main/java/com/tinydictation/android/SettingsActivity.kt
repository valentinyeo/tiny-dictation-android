package com.tinydictation.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.tinydictation.android.service.BubbleService
import com.tinydictation.android.util.DebugLog
import com.tinydictation.android.util.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class SettingsActivity : AppCompatActivity() {

    private lateinit var preferenceManager: PreferenceManager

    private lateinit var toolbar: MaterialToolbar
    private lateinit var apiKeyInput: TextInputEditText
    private lateinit var vadSensitivitySlider: Slider
    private lateinit var autoPunctuationSwitch: MaterialSwitch
    private lateinit var languageBubbleSwitch: MaterialSwitch
    private lateinit var pasteBubbleSwitch: MaterialSwitch
    private lateinit var copyDebugLogButton: MaterialButton
    private lateinit var shareDebugLogButton: MaterialButton
    private lateinit var saveButton: MaterialButton
    private lateinit var versionInfo: TextView
    private lateinit var changelog: TextView
    private lateinit var changelogHeader: LinearLayout
    private lateinit var changelogExpandIcon: ImageView
    private var isChangelogExpanded = false

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    companion object {
        // Changelog - update this with each version
        private val CHANGELOG = """
            v1.6.0:
            • Transcription History! View all past transcriptions
            • Long-press clipboard button to open history
            • History button added to main screen
            • Tap any history item to copy it

            v1.5.12:
            • Drag to close! Drag bubble to X target at bottom to close
            • Visual feedback when hovering over close target

            v1.5.11:
            • Sticky Save button (always visible at bottom)
            • Collapsible changelog (tap to expand)

            v1.5.10:
            • Fixed bubble toggle settings (now uses preference listener)
            • Bubbles hide/show automatically when settings change

            v1.5.9:
            • Clear clipboard buffer when starting new dictation session
            • Each tap-to-start gives you a fresh buffer

            v1.5.8:
            • Long-press bubble now opens main app (was compact mode)

            v1.5.7:
            • Reordered bubbles: Language → Clipboard → Mic (bottom)

            v1.5.6:
            • Fixed bubble toggle settings (was async race condition)
            • Debug logging for bubble visibility

            v1.5.5:
            • Fixed bubble toggle settings not taking effect immediately
            • Language and paste buttons now hide/show when settings change

            v1.5.4:
            • Smart clipboard button - tap to copy, tap again to paste
            • Icon changes from clipboard to paste after copying
            • Works seamlessly with accessibility service

            v1.5.3:
            • Less intrusive bubbles - now 65% opacity by default
            • Outline-style microphone icon (cleaner look)
            • Compact mode now even more translucent (40%)

            v1.5.2:
            • Long-press for compact/translucent mode (less intrusive)
            • Double-tap to close bubble (reminder: already worked!)

            v1.5.1:
            • Paste button for clipboard mode (works with Termux!)
            • Vertical bubble layout, all same size
            • Toggle bubbles on/off in Settings

            v1.5.0:
            • Language toggle! Tap EN/DE button to switch languages
            • Works mid-conversation - just tap to switch

            v1.4.2:
            • Ultra-low latency optimizations
            • Reduced endpointing from 300ms to 150ms
            • Start recording immediately (no 200ms delay)

            v1.4.1:
            • Smaller bubble (40dp instead of 56dp)

            v1.4.0:
            • Continuous streaming mode - much lower latency!
            • Connects to Deepgram immediately, streams all audio
            • No more waiting for speech detection before transcribing

            v1.3.9:
            • Wait for final transcript before closing connection
            • Reduced log spam, increased log buffer to 500 entries

            v1.3.8:
            • Fixed Deepgram audio encoding params (critical fix for transcription)

            v1.3.7:
            • Fixed Share Logs - now uses paste.rs for reliable uploads

            v1.3.6:
            • Added Share Logs button for easy log sharing

            v1.3.5:
            • Cleaner bubble indicator - mic icon turns red when recording
            • Removed text label below bubble for minimal UI

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
        languageBubbleSwitch = findViewById(R.id.switchLanguageBubble)
        pasteBubbleSwitch = findViewById(R.id.switchPasteBubble)
        copyDebugLogButton = findViewById(R.id.btnCopyDebugLog)
        shareDebugLogButton = findViewById(R.id.btnShareDebugLog)
        saveButton = findViewById(R.id.btnSave)
        versionInfo = findViewById(R.id.tvVersionInfo)
        changelog = findViewById(R.id.tvChangelog)
        changelogHeader = findViewById(R.id.changelogHeader)
        changelogExpandIcon = findViewById(R.id.ivChangelogExpand)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.settings_title)

        // Display version info and changelog
        displayVersionInfo()
        changelog.text = CHANGELOG

        // Setup expandable changelog
        changelogHeader.setOnClickListener {
            toggleChangelog()
        }
    }

    private fun toggleChangelog() {
        isChangelogExpanded = !isChangelogExpanded
        if (isChangelogExpanded) {
            changelog.maxLines = Int.MAX_VALUE
            changelogExpandIcon.rotation = 180f
        } else {
            changelog.maxLines = 4
            changelogExpandIcon.rotation = 0f
        }
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

        // Load bubble settings
        val prefs = getSharedPreferences("tiny_dictation_prefs", MODE_PRIVATE)
        languageBubbleSwitch.isChecked = prefs.getBoolean("show_language_bubble", true)
        pasteBubbleSwitch.isChecked = prefs.getBoolean("show_paste_bubble", true)
    }

    private fun setupClickListeners() {
        toolbar.setNavigationOnClickListener {
            finish()
        }

        // Bubble toggles apply immediately - no save needed
        languageBubbleSwitch.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences("tiny_dictation_prefs", MODE_PRIVATE)
                .edit()
                .putBoolean("show_language_bubble", isChecked)
                .commit()
        }

        pasteBubbleSwitch.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences("tiny_dictation_prefs", MODE_PRIVATE)
                .edit()
                .putBoolean("show_paste_bubble", isChecked)
                .commit()
        }

        copyDebugLogButton.setOnClickListener {
            copyDebugLogToClipboard()
        }

        shareDebugLogButton.setOnClickListener {
            shareDebugLog()
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

        // Note: Bubble settings are saved immediately via switch listeners

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

    private fun shareDebugLog() {
        val logText = DebugLog.exportLog()

        if (logText.isBlank()) {
            Toast.makeText(this, "No logs to share", Toast.LENGTH_SHORT).show()
            return
        }

        // Disable button and show uploading state
        shareDebugLogButton.isEnabled = false
        shareDebugLogButton.text = getString(R.string.uploading_logs)

        lifecycleScope.launch {
            try {
                val url = uploadToPaste(logText)
                if (url != null) {
                    showShareDialog(url)
                } else {
                    Toast.makeText(this@SettingsActivity, R.string.upload_failed, Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                DebugLog.e("Failed to upload logs: ${e.message}")
                Toast.makeText(this@SettingsActivity, R.string.upload_failed, Toast.LENGTH_SHORT).show()
            } finally {
                // Restore button state
                shareDebugLogButton.isEnabled = true
                shareDebugLogButton.text = getString(R.string.share_debug_log)
            }
        }
    }

    private suspend fun uploadToPaste(content: String): String? = withContext(Dispatchers.IO) {
        try {
            // Use paste.rs - simple API, just POST raw content
            val requestBody = content.toRequestBody("text/plain".toMediaType())

            val request = Request.Builder()
                .url("https://paste.rs/")
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                // paste.rs returns the URL as plain text
                response.body?.string()?.trim()
            } else {
                DebugLog.e("paste.rs upload failed: ${response.code}")
                null
            }
        } catch (e: Exception) {
            DebugLog.e("paste.rs upload exception: ${e.message}")
            null
        }
    }

    private fun showShareDialog(url: String) {
        AlertDialog.Builder(this)
            .setTitle("Logs Uploaded")
            .setMessage("Your logs have been uploaded to:\n\n$url")
            .setPositiveButton("Share") { _, _ ->
                shareUrl(url)
            }
            .setNeutralButton("Copy URL") { _, _ ->
                copyUrlToClipboard(url)
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun copyUrlToClipboard(url: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Debug Log URL", url)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, R.string.log_url_copied, Toast.LENGTH_SHORT).show()
    }

    private fun shareUrl(url: String) {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Tiny Dictation Debug Logs")
            putExtra(Intent.EXTRA_TEXT, "Debug logs: $url")
        }
        startActivity(Intent.createChooser(shareIntent, "Share log URL"))
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
