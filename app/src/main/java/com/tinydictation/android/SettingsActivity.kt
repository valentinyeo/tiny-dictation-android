package com.tinydictation.android

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
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
import com.google.android.material.textfield.TextInputEditText
import com.tinydictation.android.transcription.engines.VoskEngine
import com.tinydictation.android.transcription.models.ModelManager
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
    private lateinit var modelManager: ModelManager

    private lateinit var toolbar: MaterialToolbar
    private lateinit var apiKeyInput: TextInputEditText
    private lateinit var autoPunctuationSwitch: MaterialSwitch
    private lateinit var headphoneMicSwitch: MaterialSwitch
    private lateinit var languageBubbleSwitch: MaterialSwitch
    private lateinit var pasteBubbleSwitch: MaterialSwitch
    private lateinit var noteBubbleSwitch: MaterialSwitch
    private lateinit var noteAppContainer: LinearLayout
    private lateinit var tvSelectedNoteApp: TextView
    private lateinit var btnSelectNoteApp: MaterialButton
    private lateinit var copyDebugLogButton: MaterialButton
    private lateinit var shareDebugLogButton: MaterialButton
    private lateinit var saveButton: MaterialButton
    private lateinit var versionInfo: TextView
    private lateinit var changelog: TextView
    private lateinit var changelogHeader: LinearLayout
    private lateinit var changelogExpandIcon: ImageView
    private var isChangelogExpanded = false

    // Engine selection
    private lateinit var engineDropdown: AutoCompleteTextView
    private lateinit var autoFallbackSwitch: MaterialSwitch

    // Model downloads - Vosk
    private lateinit var tvVoskEnStatus: TextView
    private lateinit var tvVoskDeStatus: TextView
    private lateinit var btnDownloadVoskEn: MaterialButton
    private lateinit var btnDownloadVoskDe: MaterialButton

    // Model downloads - Whisper
    private lateinit var tvWhisperTinyStatus: TextView
    private lateinit var tvWhisperBaseStatus: TextView
    private lateinit var tvWhisperSmallStatus: TextView
    private lateinit var btnDownloadWhisperTiny: MaterialButton
    private lateinit var btnDownloadWhisperBase: MaterialButton
    private lateinit var btnDownloadWhisperSmall: MaterialButton

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
            v1.8.1:
            • Reverted clipboard recording mode - back to simpler copy/paste
            • Long-press clipboard opens history again
            • Whisper options now labeled as not working

            v1.7.8:
            • Fixed note app selection: Now shows all compatible apps
            • Added manual package name entry for note apps

            v1.7.6:
            • Improved drag-to-X close: larger target, vibration feedback, easier to hit
            • Fixed fragmented history: now saves full dictation session on clipboard tap
            • Auto-cleanup: history entries older than 2 days are removed

            v1.7.5:
            • Note Bubble: Send transcriptions directly to note apps (Keep, OneNote, etc.)
            • Multi-select in History: Long-press to select, copy multiple transcriptions
            • Headphone mic toggle: Use headphone mic instead of phone mic
            • Lock screen fix: Dictation no longer stops after unlocking
            • Long-press bubble now opens Settings

            v1.7.4:
            • Fixed drag-to-close functionality
            • Now uses actual close target position for hit detection
            • Increased hit radius for easier targeting
            • Added debug logging for drag-to-close

            v1.7.3:
            • Multiple Whisper models: Tiny (fast), Base (balanced), Small (accurate)
            • Fixed Whisper model downloads (tar.bz2 support)
            • Choose speed vs accuracy for offline transcription

            v1.7.2:
            • Reduced APK size from ~70MB to ~20MB per architecture
            • Separate APKs for ARM64 and ARM32 devices
            • Offline models still downloaded on-demand via Settings

            v1.7.1:
            • Engine selection in Settings! Choose Deepgram, Vosk, or Whisper
            • Model download manager - download offline models directly
            • Vosk English & German models available
            • Auto-fallback toggle for seamless offline mode

            v1.7.0:
            • Offline mode foundation - Vosk integration
            • Multi-engine architecture (Deepgram, Vosk, Sherpa-ONNX)
            • Auto-fallback to offline when no internet
            • Settings UI for engine selection coming soon

            v1.6.4:
            • Fixed drag-to-close - larger hit area, better positioning

            v1.6.3:
            • Auto-start dictation when app launches
            • All transcriptions saved to history (not just copied)

            v1.6.2:
            • Green checkmark when clipboard is ready
            • Vibration feedback on copy
            • Clearer ready-to-paste indicator

            v1.6.1:
            • Bubble toggles apply instantly (no Save needed)
            • Cleaner settings flow

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
        modelManager = ModelManager(this)

        initViews()
        loadSettings()
        setupClickListeners()
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)
        apiKeyInput = findViewById(R.id.etApiKey)
        autoPunctuationSwitch = findViewById(R.id.switchAutoPunctuation)
        headphoneMicSwitch = findViewById(R.id.switchHeadphoneMic)
        languageBubbleSwitch = findViewById(R.id.switchLanguageBubble)
        pasteBubbleSwitch = findViewById(R.id.switchPasteBubble)
        noteBubbleSwitch = findViewById(R.id.switchNoteBubble)
        noteAppContainer = findViewById(R.id.noteAppContainer)
        tvSelectedNoteApp = findViewById(R.id.tvSelectedNoteApp)
        btnSelectNoteApp = findViewById(R.id.btnSelectNoteApp)
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

        // Engine selection views
        engineDropdown = findViewById(R.id.actvEngine)
        autoFallbackSwitch = findViewById(R.id.switchAutoFallback)

        // Model download views
        tvVoskEnStatus = findViewById(R.id.tvVoskEnStatus)
        tvVoskDeStatus = findViewById(R.id.tvVoskDeStatus)
        btnDownloadVoskEn = findViewById(R.id.btnDownloadVoskEn)
        btnDownloadVoskDe = findViewById(R.id.btnDownloadVoskDe)

        // Whisper model views
        tvWhisperTinyStatus = findViewById(R.id.tvWhisperTinyStatus)
        tvWhisperBaseStatus = findViewById(R.id.tvWhisperBaseStatus)
        tvWhisperSmallStatus = findViewById(R.id.tvWhisperSmallStatus)
        btnDownloadWhisperTiny = findViewById(R.id.btnDownloadWhisperTiny)
        btnDownloadWhisperBase = findViewById(R.id.btnDownloadWhisperBase)
        btnDownloadWhisperSmall = findViewById(R.id.btnDownloadWhisperSmall)

        // Setup engine dropdown
        setupEngineDropdown()
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

    private fun setupEngineDropdown() {
        refreshEngineDropdown()
    }

    private fun refreshEngineDropdown() {
        // Build dynamic engine list based on downloaded models
        val engines = mutableListOf<Pair<String, String>>()

        // Always available: Deepgram (Cloud)
        engines.add("Deepgram (Cloud)" to PreferenceManager.ENGINE_DEEPGRAM)

        // Vosk (if any model downloaded - check both en and de)
        if (modelManager.isModelAvailable("vosk", "en") || modelManager.isModelAvailable("vosk", "de")) {
            engines.add("Vosk (Fast Offline)" to PreferenceManager.ENGINE_VOSK)
        }

        // Whisper models - NOT YET FUNCTIONAL (AAR not integrated)
        // Show downloaded models but warn they don't work yet
        if (modelManager.isModelAvailable("sherpa-onnx", "whisper-tiny")) {
            engines.add("Whisper Tiny - NOT WORKING" to "${PreferenceManager.ENGINE_SHERPA_ONNX}:${PreferenceManager.WHISPER_TINY}")
        }
        if (modelManager.isModelAvailable("sherpa-onnx", "whisper-base")) {
            engines.add("Whisper Base - NOT WORKING" to "${PreferenceManager.ENGINE_SHERPA_ONNX}:${PreferenceManager.WHISPER_BASE}")
        }
        if (modelManager.isModelAvailable("sherpa-onnx", "whisper-small")) {
            engines.add("Whisper Small - NOT WORKING" to "${PreferenceManager.ENGINE_SHERPA_ONNX}:${PreferenceManager.WHISPER_SMALL}")
        }

        val displayNames = engines.map { it.first }
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, displayNames)
        engineDropdown.setAdapter(adapter)

        // Set current selection
        val currentEngine = preferenceManager.getTranscriptionEngine()
        val currentWhisperModel = preferenceManager.getWhisperModel()

        // Build the key to match against
        val currentKey = if (currentEngine == PreferenceManager.ENGINE_SHERPA_ONNX) {
            "${PreferenceManager.ENGINE_SHERPA_ONNX}:$currentWhisperModel"
        } else {
            currentEngine
        }

        val currentIndex = engines.indexOfFirst { it.second == currentKey }
        if (currentIndex >= 0) {
            engineDropdown.setText(displayNames[currentIndex], false)
        } else if (engines.isNotEmpty()) {
            // Default to first available engine if current selection is invalid
            engineDropdown.setText(displayNames[0], false)
        }

        // Handle selection changes
        engineDropdown.setOnItemClickListener { _, _, position, _ ->
            val selectedValue = engines[position].second

            // Check if it's a Whisper model selection (contains ":")
            if (selectedValue.contains(":")) {
                val parts = selectedValue.split(":")
                preferenceManager.setTranscriptionEngine(parts[0])
                preferenceManager.setWhisperModel(parts[1])
                DebugLog.i("Engine changed to: ${parts[0]} with model: ${parts[1]}")

                // Warn user that Whisper is not functional yet
                Toast.makeText(
                    this,
                    "Whisper is not yet functional. Please use Vosk or Deepgram.",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                preferenceManager.setTranscriptionEngine(selectedValue)
                DebugLog.i("Engine changed to: $selectedValue")
            }
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

        // Load auto-punctuation
        autoPunctuationSwitch.isChecked = preferenceManager.getAutoPunctuation()

        // Load headphone mic setting
        headphoneMicSwitch.isChecked = preferenceManager.getUseHeadphoneMic()

        // Load bubble settings
        val prefs = getSharedPreferences("tiny_dictation_prefs", MODE_PRIVATE)
        languageBubbleSwitch.isChecked = prefs.getBoolean("show_language_bubble", true)
        pasteBubbleSwitch.isChecked = prefs.getBoolean("show_paste_bubble", true)

        // Load note bubble settings
        noteBubbleSwitch.isChecked = preferenceManager.getShowNoteBubble()
        updateNoteAppContainerVisibility()
        updateSelectedNoteAppDisplay()

        // Load auto-fallback setting
        autoFallbackSwitch.isChecked = preferenceManager.getAutoFallback()

        // Update model status
        updateModelStatus()
    }

    private fun updateModelStatus() {
        // Vosk English
        val voskEnAvailable = modelManager.isModelAvailable("vosk", "en")
        if (voskEnAvailable) {
            tvVoskEnStatus.text = "Downloaded"
            btnDownloadVoskEn.text = "Delete"
        } else {
            val info = VoskEngine.MODEL_INFO["en"]
            tvVoskEnStatus.text = "Not downloaded (${info?.sizeMB ?: 41} MB)"
            btnDownloadVoskEn.text = "Download"
        }

        // Vosk German
        val voskDeAvailable = modelManager.isModelAvailable("vosk", "de")
        if (voskDeAvailable) {
            tvVoskDeStatus.text = "Downloaded"
            btnDownloadVoskDe.text = "Delete"
        } else {
            val info = VoskEngine.MODEL_INFO["de"]
            tvVoskDeStatus.text = "Not downloaded (${info?.sizeMB ?: 45} MB)"
            btnDownloadVoskDe.text = "Download"
        }

        // Whisper Tiny (Fast)
        val whisperTinyAvailable = modelManager.isModelAvailable("sherpa-onnx", "whisper-tiny")
        if (whisperTinyAvailable) {
            tvWhisperTinyStatus.text = "Downloaded"
            btnDownloadWhisperTiny.text = "Delete"
        } else {
            val info = ModelManager.SHERPA_MODEL_INFO["whisper-tiny"]
            tvWhisperTinyStatus.text = "Not downloaded (${info?.sizeMB ?: 75} MB)"
            btnDownloadWhisperTiny.text = "Download"
        }

        // Whisper Base (Balanced)
        val whisperBaseAvailable = modelManager.isModelAvailable("sherpa-onnx", "whisper-base")
        if (whisperBaseAvailable) {
            tvWhisperBaseStatus.text = "Downloaded"
            btnDownloadWhisperBase.text = "Delete"
        } else {
            val info = ModelManager.SHERPA_MODEL_INFO["whisper-base"]
            tvWhisperBaseStatus.text = "Not downloaded (${info?.sizeMB ?: 150} MB)"
            btnDownloadWhisperBase.text = "Download"
        }

        // Whisper Small (Accurate)
        val whisperSmallAvailable = modelManager.isModelAvailable("sherpa-onnx", "whisper-small")
        if (whisperSmallAvailable) {
            tvWhisperSmallStatus.text = "Downloaded"
            btnDownloadWhisperSmall.text = "Delete"
        } else {
            val info = ModelManager.SHERPA_MODEL_INFO["whisper-small"]
            tvWhisperSmallStatus.text = "Not downloaded (${info?.sizeMB ?: 500} MB)"
            btnDownloadWhisperSmall.text = "Download"
        }
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

        // Note bubble toggle - applies immediately
        noteBubbleSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setShowNoteBubble(isChecked)
            updateNoteAppContainerVisibility()
            getSharedPreferences("tiny_dictation_prefs", MODE_PRIVATE)
                .edit()
                .putBoolean("show_note_bubble", isChecked)
                .commit()
            DebugLog.i("Note bubble setting changed to: $isChecked")
        }

        // Note app selection button
        btnSelectNoteApp.setOnClickListener {
            showNoteAppPicker()
        }

        // Headphone mic toggle applies immediately
        headphoneMicSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setUseHeadphoneMic(isChecked)
            DebugLog.i("Headphone mic setting changed to: $isChecked")
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

        // Auto-fallback toggle applies immediately
        autoFallbackSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setAutoFallback(isChecked)
        }

        // Model download/delete buttons
        btnDownloadVoskEn.setOnClickListener {
            handleModelButton("vosk", "en", btnDownloadVoskEn, tvVoskEnStatus)
        }

        btnDownloadVoskDe.setOnClickListener {
            handleModelButton("vosk", "de", btnDownloadVoskDe, tvVoskDeStatus)
        }

        // Whisper model download buttons
        btnDownloadWhisperTiny.setOnClickListener {
            handleModelButton("sherpa-onnx", "whisper-tiny", btnDownloadWhisperTiny, tvWhisperTinyStatus)
        }

        btnDownloadWhisperBase.setOnClickListener {
            handleModelButton("sherpa-onnx", "whisper-base", btnDownloadWhisperBase, tvWhisperBaseStatus)
        }

        btnDownloadWhisperSmall.setOnClickListener {
            handleModelButton("sherpa-onnx", "whisper-small", btnDownloadWhisperSmall, tvWhisperSmallStatus)
        }
    }

    private fun handleModelButton(engineId: String, language: String, button: MaterialButton, statusView: TextView) {
        val isAvailable = modelManager.isModelAvailable(engineId, language)

        if (isAvailable) {
            // Delete model
            lifecycleScope.launch {
                button.isEnabled = false
                button.text = "Deleting..."

                val result = modelManager.deleteModel(engineId, language)
                if (result.isSuccess) {
                    Toast.makeText(this@SettingsActivity, "Model deleted", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@SettingsActivity, "Failed to delete model", Toast.LENGTH_SHORT).show()
                }

                updateModelStatus()
                refreshEngineDropdown()
                button.isEnabled = true
            }
        } else {
            // Download model
            lifecycleScope.launch {
                button.isEnabled = false
                button.text = "Downloading..."
                statusView.text = "Downloading..."

                val result = modelManager.downloadModel(engineId, language)
                if (result.isSuccess) {
                    Toast.makeText(this@SettingsActivity, "Model downloaded!", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@SettingsActivity, "Download failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }

                updateModelStatus()
                refreshEngineDropdown()
                button.isEnabled = true
            }
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

    /**
     * Updates the visibility of the note app selection container.
     */
    private fun updateNoteAppContainerVisibility() {
        noteAppContainer.visibility = if (noteBubbleSwitch.isChecked) View.VISIBLE else View.GONE
    }

    /**
     * Updates the displayed name of the selected note app.
     */
    private fun updateSelectedNoteAppDisplay() {
        val packageName = preferenceManager.getNoteAppPackage()
        if (packageName != null) {
            try {
                val appInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    packageManager.getApplicationInfo(packageName, PackageManager.ApplicationInfoFlags.of(0))
                } else {
                    @Suppress("DEPRECATION")
                    packageManager.getApplicationInfo(packageName, 0)
                }
                val appName = packageManager.getApplicationLabel(appInfo).toString()
                tvSelectedNoteApp.text = appName
            } catch (e: PackageManager.NameNotFoundException) {
                tvSelectedNoteApp.text = packageName
            }
        } else {
            tvSelectedNoteApp.text = "Tap to select app"
        }
    }

    /**
     * Shows a dialog to pick a note-taking app from installed apps that can receive text.
     */
    private fun showNoteAppPicker() {
        // Find apps that can handle ACTION_SEND with text - use 0 flags to get ALL apps, not just default
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
        }

        val resolveInfoList: List<ResolveInfo> = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(sendIntent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(sendIntent, 0)
        }

        // Known note app packages (prioritized in list)
        val noteAppPackages = listOf(
            "com.google.android.keep",           // Google Keep
            "com.samsung.android.app.notes",     // Samsung Notes
            "com.microsoft.office.onenote",      // OneNote
            "com.evernote",                      // Evernote
            "md.obsidian",                       // Obsidian
            "net.gsantner.markor",               // Markor
            "com.simplemobiletools.notes.pro",   // Simple Notes Pro
            "org.standardnotes",                 // Standard Notes
            "com.automattic.simplenote",         // Simplenote
            "com.orgzly",                        // Orgzly
            "com.notion.id",                     // Notion
            "it.feio.android.omninotes",         // Omni Notes
            "com.workflowy.android"              // WorkFlowy
        )

        // Build list of available apps (note apps first, then others)
        data class AppInfo(val name: String, val packageName: String, val isNoteApp: Boolean)

        val apps = resolveInfoList.map { resolveInfo ->
            val packageName = resolveInfo.activityInfo.packageName
            val appName = resolveInfo.loadLabel(packageManager).toString()
            val isNoteApp = noteAppPackages.contains(packageName)
            AppInfo(appName, packageName, isNoteApp)
        }
            .distinctBy { it.packageName }
            .sortedWith(compareByDescending<AppInfo> { it.isNoteApp }.thenBy { it.name })

        // Build dialog options: apps + "Enter package name manually"
        val displayNames = apps.map { it.name }.toMutableList()
        displayNames.add("📝 Enter package name manually...")

        AlertDialog.Builder(this)
            .setTitle("Select Note App")
            .setItems(displayNames.toTypedArray()) { _, which ->
                if (which == apps.size) {
                    // "Enter package name manually" selected
                    showPackageNameInputDialog()
                } else {
                    val selected = apps[which]
                    preferenceManager.setNoteAppPackage(selected.packageName)
                    updateSelectedNoteAppDisplay()
                    DebugLog.i("Note app selected: ${selected.name} (${selected.packageName})")
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    /**
     * Shows a dialog to manually enter a package name for the note app.
     */
    private fun showPackageNameInputDialog() {
        val inputField = TextInputEditText(this).apply {
            hint = "com.example.noteapp"
            setSingleLine(true)
            // Pre-fill with current value if set
            preferenceManager.getNoteAppPackage()?.let { setText(it) }
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 32, 48, 0)
            addView(inputField)
        }

        AlertDialog.Builder(this)
            .setTitle("Enter Package Name")
            .setMessage("Enter the package name of your note app (e.g., com.google.android.keep)")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val packageName = inputField.text?.toString()?.trim()
                if (!packageName.isNullOrBlank()) {
                    preferenceManager.setNoteAppPackage(packageName)
                    updateSelectedNoteAppDisplay()
                    DebugLog.i("Note app package manually set: $packageName")
                    Toast.makeText(this, "Package saved: $packageName", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
