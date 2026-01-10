package com.tinydictation.android

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.tinydictation.android.service.BubbleService
import com.tinydictation.android.service.DictationAccessibilityService
import com.tinydictation.android.service.DictationKeyboardService
import com.tinydictation.android.service.KeyboardCompanionService
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

    companion object {
        private const val TAG = "TinyDictation"

        // Changelog - update this with each version
        private val CHANGELOG = """
            v1.10.23:
            • Removed redundant Start/Stop Dictation button from Settings
            • Users now control dictation from the floating bar or keyboard
            • Floating bar has drag-to-close, companion bar will have close button
            • Cleaner Settings screen focused on configuration

            v1.10.22:
            • Fixed keyboard overlaying PWA content instead of pushing it up
            • Properly reports keyboard insets so apps can resize content
            • PWAs now correctly adjust their layout when keyboard opens
            • Added debug logging for keyboard insets troubleshooting

            v1.10.21:
            • NEW: Bar transparency slider in Floating Bar Settings
            • Adjust floating bar transparency from 0% (solid) to 50% (semi-transparent)
            • Renamed "bubble" references to "floating bar" throughout the UI
            • Cleaner, more consistent terminology

            v1.10.20:
            • Fixed floating bar appearing when Horizontal Bar or Keyboard mode selected
            • Floating bar UI only shows when "Vertical Floating Bar" mode is active
            • BubbleService still runs for transcription backend in other modes (without UI)

            v1.10.19:
            • Fixed keyboard theme not applying immediately when changed in Settings
            • Theme changes now take effect instantly without switching keyboards
            • No longer need to switch away and back for theme to apply

            v1.10.18:
            • Seamless switching between Vertical and Horizontal floating bar modes
            • Vertical bar: tap horizontal bar icon to switch to horizontal mode
            • Horizontal bar: tap vertical bar icon to switch to vertical mode
            • Instant mode switching without restarting dictation
            • New icons for horizontal/vertical bar switch buttons

            v1.10.17:
            • NEW: Pulsing mic icon animation while processing transcription
            • After stopping dictation, mic icon pulses red while awaiting final transcript
            • Visual feedback shows when audio is being transcribed (not just recording)
            • Pulse animation appears in floating bar, keyboard, and companion bar

            v1.10.16:
            • Fixed keyboard switch button not working on floating bar and companion bar
            • Added toast feedback when button is pressed ("Switching keyboard...")
            • Added fallback to open keyboard settings if picker fails
            • Better debug logging for keyboard switch troubleshooting

            v1.10.15:
            • Fixed companion bar NOT hiding when Tiny Dictation keyboard opens
            • Now stops KeyboardCompanionService entirely (not just hiding the bar)
            • Previous fix in v1.10.11 only hid the bar temporarily
            • Service listener would show it again - now service is fully stopped
            • Also updates dictation mode preference to keyboard mode automatically

            v1.10.14:
            • Fixed backspace button stopping after 1-2 seconds
            • Backspace now keeps deleting as long as button is held
            • Accelerates from slow to fast over 1.5 seconds
            • After 2 seconds switches to faster word-by-word deletion
            • Uses robust flag-based control to prevent premature stopping

            v1.10.13:
            • Long-press paste button now opens transcription history
            • Quick access to past dictations from keyboard bar

            v1.10.12:
            • Added proper Select All icon to keyboard bar
            • Icon shows nested rectangles with dashed selection border
            • Matches the visual style of other keyboard icons

            v1.10.11:
            • Fixed mutual exclusivity between dictation modes
            • Companion bar now hides when Tiny Dictation keyboard opens
            • Added floating bar button to companion bar for quick mode switching
            • Tap the new sidebar icon to switch from companion mode to floating bar

            v1.10.10:
            • Fixed Keyboard Companion bar overlaying keyboard suggestions
            • Raised bar position to sit above SwiftKey/Gboard suggestion row
            • Right-aligned buttons for easier right-thumb access
            • Added debug logging to keyboard switch button

            v1.10.9:
            • Reordered keyboard buttons for better ergonomics
            • New order: Chevron | Language, Mic, Enter, Backspace, Paste, All

            v1.10.8:
            • Enhanced backspace button hold behavior
            • Hold to delete: starts slow, accelerates over 1.5 seconds
            • After 2 seconds of holding, switches to word-by-word deletion
            • Faster and more intuitive text deletion

            v1.10.7:
            • Fixed Keyboard Companion bar not appearing when keyboard opens
            • Now uses AccessibilityService to detect keyboard visibility
            • More reliable detection works with Gboard, SwiftKey, Samsung keyboard, etc.
            • Previous overlay-based detection didn't work (overlays don't get window insets)

            v1.10.6:
            • Fixed button state bleed bug in keyboard bar
            • Pressing Enter no longer causes Chevron button to show activated state
            • Each button now has fully independent visual state

            v1.10.5:
            • Re-enabled Keyboard Companion mode
            • Companion bar sits above your keyboard (between text and keyboard)
            • Appears automatically when any keyboard opens
            • Contains Mic, Language, and Keyboard switch buttons

            v1.10.4:
            • Fixed keyboard icon colors staying constant on press
            • Icons no longer change color when buttons are pressed/active

            v1.10.3:
            • NEW: Bar orientation setting - choose Vertical or Horizontal
            • Horizontal bar is perfect for use alongside keyboards
            • Replaced unreliable Keyboard Companion mode with simpler horizontal bar
            • Users who had Companion mode will be auto-migrated to horizontal bar

            v1.10.2:
            • NEW: Pause media during dictation - automatically pauses YouTube, music, etc.
            • When enabled, requests audio focus which pauses other audio apps
            • Toggle on/off in Voice Settings
            • Media playback resumes when dictation stops

            v1.10.1:
            • Fixed: Dictation modes are now mutually exclusive
            • Only ONE UI appears based on selected mode (Floating Bar, Companion, or Keyboard)
            • No more floating bars appearing in Companion or Keyboard modes

            v1.10.0:
            • NEW: Keyboard Companion Mode - horizontal bar above your keyboard
            • Works with Gboard, SwiftKey, or any keyboard
            • Automatically appears when keyboard is visible
            • Quick access to Mic, Language, and Expand buttons
            • Choose mode in Settings: Floating Bar, Companion, or Full Keyboard

            v1.9.26:
            • Fixed chevron icon color to match other keyboard icons consistently

            v1.9.25:
            • Redesigned floating bar as vertical bar matching keyboard styling
            • Unified look with keyboard bar (same colors, buttons, icons)
            • Simplified to 3 core buttons: Mic, Language, Keyboard switch
            • Removed paste/note buttons for cleaner interface

            v1.9.24:
            • Backspace now supports hold-to-delete like SwiftKey
            • Hold down backspace to continuously delete characters

            v1.9.23:
            • Moved switch-back button to left side with small chevron icon
            • New button order: Chevron, Mic, Lang | Backspace, Enter, Paste, All

            v1.9.22:
            • Added keyboard theme setting: System (Auto), Light, or Dark
            • Keyboard appearance now matches your preference
            • Light theme: white background, gray buttons, black icons
            • Mic button still turns red when recording regardless of theme

            v1.9.21:
            • Fixed keyboard insets so apps resize content properly
            • PWA and web apps should no longer be hidden behind keyboard

            v1.9.20:
            • All keyboard icons now white, only mic turns red when recording

            v1.9.19:
            • Reordered keyboard buttons: Backspace, Enter, Paste, All, Switch

            v1.9.18:
            • Keyboard button now switches directly to Tiny Dictation keyboard
            • Grant WRITE_SECURE_SETTINGS via ADB for direct switching
            • Falls back to picker if permission not granted

            v1.9.17:
            • Added switch back button to return to previous keyboard instantly
            • No more keyboard picker - one tap to switch back to Gboard/other

            v1.9.16:
            • Added language switch button to keyboard bar
            • Added backspace button to keyboard bar
            • Long-press mic button opens settings
            • Floating bar auto-hides when keyboard is visible
            • Fixed dark mode icons (now visible on dark backgrounds)
            • Removed redundant keyboard switch button

            v1.9.15:
            • Added keyboard action buttons: Enter, Select All, Paste, Switch Keyboard
            • Larger keyboard bar with 56dp buttons for easier tapping
            • Mic button on keyboard toggles dictation like the floating bar
            • Recording state syncs between floating bar and keyboard

            v1.9.14:
            • Added error handling to keyboard service to prevent crashes
            • Better logging for keyboard initialization issues

            v1.9.13:
            • Added keyboard input method for direct text entry
            • Fixed all mipmap icons to use new adaptive icon design
            • Keyboard works in apps where accessibility paste fails

            v1.9.12:
            • Fixed first part of sentence sometimes going missing
            • Audio buffer no longer cleared during session startup race condition
            • Added explicit audio buffer clearing when starting new dictation session

            v1.9.11:
            • Redesigned app icons with modern Lucide-inspired stroke style
            • New professional launcher icon with microphone and audio waves
            • Unified in-app icons: mic, check, warning, close, delete, history, copy, note
            • Added distinct icons for overlay and accessibility permissions

            v1.9.10:
            • Fixed CI build issue

            v1.9.9:
            • Fixed Share Debug Log - now uses dpaste.com with paste.rs fallback
            • More reliable log sharing across different networks

            v1.9.8:
            • Save button now closes settings and returns to previous app
            • New Type-instead-of-paste mode: inserts text at cursor position
            • Useful for editing in the middle of existing text

            v1.9.7:
            • Consolidated app into single screen
            • Permissions, Start/Stop, and Settings all in one place
            • Removed separate main screen for simpler navigation

            v1.9.6:
            • Fixed language switching crash with Whisper engines

            v1.9.5:
            • Fixed Start/Stop button showing inverted state

            v1.9.4:
            • Improved silence detection sensitivity
            • Better error logging for model issues

            v1.9.3:
            • Fixed duplicate text bug in Whisper transcription

            v1.9.2:
            • Fixed Whisper Base and Small models not working
            • All Whisper model sizes now supported

            v1.9.1:
            • Fixed Whisper hallucinations (filters Music, Bell, etc.)
            • Better silence detection - processes on speech pauses
            • Increased buffer for more accurate transcription

            v1.9.0:
            • Whisper transcription now fully working with Sherpa-ONNX
            • High-quality offline speech recognition with Whisper models
            • Supports Whisper Tiny, Base, and Small models

            v1.8.1:
            • Reverted clipboard recording mode - back to simpler copy/paste
            • Long-press clipboard opens history again

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
            • Reordered bubbles: Language -> Clipboard -> Mic (bottom)

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

    private lateinit var preferenceManager: PreferenceManager
    private lateinit var modelManager: ModelManager

    // Permission cards
    private lateinit var microphoneCard: MaterialCardView
    private lateinit var overlayCard: MaterialCardView
    private lateinit var accessibilityCard: MaterialCardView

    // Status icons
    private lateinit var microphoneIcon: ImageView
    private lateinit var overlayIcon: ImageView
    private lateinit var accessibilityIcon: ImageView

    // Permission buttons
    private lateinit var microphoneButton: MaterialButton
    private lateinit var overlayButton: MaterialButton
    private lateinit var accessibilityButton: MaterialButton
    private lateinit var historyButton: MaterialButton

    private lateinit var toolbar: MaterialToolbar
    private lateinit var apiKeyInput: TextInputEditText
    private lateinit var autoPunctuationSwitch: MaterialSwitch
    private lateinit var headphoneMicSwitch: MaterialSwitch
    private lateinit var simulateTypingSwitch: MaterialSwitch
    private lateinit var pauseMediaSwitch: MaterialSwitch
    private lateinit var languageBubbleSwitch: MaterialSwitch
    private lateinit var pasteBubbleSwitch: MaterialSwitch
    private lateinit var noteBubbleSwitch: MaterialSwitch
    private lateinit var noteAppContainer: LinearLayout
    private lateinit var tvSelectedNoteApp: TextView
    private lateinit var btnSelectNoteApp: MaterialButton
    private lateinit var keyboardBubbleSwitch: MaterialSwitch
    private lateinit var btnSetupKeyboard: MaterialButton
    private lateinit var keyboardThemeDropdown: AutoCompleteTextView
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

    // Dictation mode selection
    private lateinit var dictationModeDropdown: AutoCompleteTextView
    private lateinit var barOrientationDropdown: AutoCompleteTextView

    // Transparency slider
    private lateinit var transparencySlider: Slider
    private lateinit var transparencyValueText: TextView

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

    private val requestMicrophonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.i(TAG, "Microphone permission result: $isGranted")
        updatePermissionStatus()
        if (!isGranted) {
            Log.e(TAG, "Microphone permission denied")
            Toast.makeText(this, R.string.error_no_permission, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "SettingsActivity onCreate")
        setContentView(R.layout.activity_settings)

        preferenceManager = PreferenceManager(this)
        modelManager = ModelManager(this)

        initViews()
        loadSettings()
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "SettingsActivity onResume")
        updatePermissionStatus()

        // Auto-start dictation if all permissions granted and API key configured
        autoStartDictationIfReady()
    }

    private fun autoStartDictationIfReady() {
        // Only auto-start if not already running
        if (isDictationRunning()) {
            return
        }

        // Check all conditions
        if (!allPermissionsGranted()) {
            Log.d(TAG, "Auto-start skipped: missing permissions")
            return
        }

        if (!preferenceManager.hasApiKey()) {
            Log.d(TAG, "Auto-start skipped: no API key")
            return
        }

        Log.i(TAG, "Auto-starting dictation")
        startDictation()
    }

    private fun updatePermissionStatus() {
        // Microphone permission
        val hasMicrophone = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "Permission status - Microphone: $hasMicrophone")

        updatePermissionCard(microphoneIcon, microphoneButton, hasMicrophone)

        // Overlay permission
        val hasOverlay = Settings.canDrawOverlays(this)
        Log.d(TAG, "Permission status - Overlay: $hasOverlay")
        updatePermissionCard(overlayIcon, overlayButton, hasOverlay)

        // Accessibility service
        val hasAccessibility = isAccessibilityServiceEnabled()
        Log.d(TAG, "Permission status - Accessibility: $hasAccessibility")
        updatePermissionCard(accessibilityIcon, accessibilityButton, hasAccessibility)
    }

    private fun updatePermissionCard(icon: ImageView, button: MaterialButton, isEnabled: Boolean) {
        if (isEnabled) {
            icon.setImageResource(R.drawable.ic_check)
            icon.setColorFilter(ContextCompat.getColor(this, R.color.bubble_listening))
            button.visibility = View.GONE
        } else {
            icon.setImageResource(R.drawable.ic_warning)
            icon.setColorFilter(ContextCompat.getColor(this, R.color.bubble_recording))
            button.visibility = View.VISIBLE
        }
    }

    private fun allPermissionsGranted(): Boolean {
        val hasMicrophone = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasAccessibility = isAccessibilityServiceEnabled()

        return hasMicrophone && hasOverlay && hasAccessibility
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_GENERIC
        )
        return enabledServices.any {
            it.resolveInfo.serviceInfo.packageName == packageName &&
            it.resolveInfo.serviceInfo.name == DictationAccessibilityService::class.java.name
        }
    }

    /**
     * Starts dictation by launching the appropriate service based on mode.
     * TranscriptionManager is now owned by BubbleService to survive activity lifecycle
     * and properly handle lock screen events.
     */
    private fun startDictation() {
        Log.i(TAG, "Starting dictation service")

        // Verify microphone permission before starting
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Microphone permission not granted")
            Toast.makeText(this, R.string.error_no_permission, Toast.LENGTH_SHORT).show()
            return
        }

        // Verify API key before starting
        if (!preferenceManager.hasApiKey()) {
            Log.e(TAG, "API key is null")
            Toast.makeText(this, R.string.error_no_api_key, Toast.LENGTH_SHORT).show()
            return
        }

        // Start the appropriate service based on mode
        val mode = preferenceManager.getDictationMode()

        when (mode) {
            PreferenceManager.MODE_BUBBLE -> {
                BubbleService.start(this)
                Log.i(TAG, "BubbleService started")
            }
            PreferenceManager.MODE_COMPANION -> {
                // Start KeyboardCompanionService which shows bar above keyboard
                KeyboardCompanionService.start(this)
                // Also start BubbleService for transcription (without UI)
                BubbleService.start(this)
                Log.i(TAG, "KeyboardCompanionService started")
            }
            PreferenceManager.MODE_KEYBOARD -> {
                // For keyboard mode, DictationKeyboardService is activated by the system as IME
                // We start BubbleService in background for transcription (without UI)
                BubbleService.start(this)
                Log.i(TAG, "Keyboard mode: BubbleService started for transcription (no UI)")
            }
        }
    }

    /**
     * Checks if any dictation service is running.
     */
    private fun isDictationRunning(): Boolean {
        return BubbleService.isRunning() || KeyboardCompanionService.isRunning()
    }

    private fun initViews() {
        toolbar = findViewById(R.id.toolbar)

        // Permission cards
        microphoneCard = findViewById(R.id.cardMicrophone)
        overlayCard = findViewById(R.id.cardOverlay)
        accessibilityCard = findViewById(R.id.cardAccessibility)

        // Permission icons
        microphoneIcon = findViewById(R.id.iconMicrophone)
        overlayIcon = findViewById(R.id.iconOverlay)
        accessibilityIcon = findViewById(R.id.iconAccessibility)

        // Permission buttons
        microphoneButton = findViewById(R.id.btnEnableMicrophone)
        overlayButton = findViewById(R.id.btnEnableOverlay)
        accessibilityButton = findViewById(R.id.btnEnableAccessibility)

        // History button
        historyButton = findViewById(R.id.btnHistory)

        // Settings views
        apiKeyInput = findViewById(R.id.etApiKey)
        autoPunctuationSwitch = findViewById(R.id.switchAutoPunctuation)
        headphoneMicSwitch = findViewById(R.id.switchHeadphoneMic)
        simulateTypingSwitch = findViewById(R.id.switchSimulateTyping)
        pauseMediaSwitch = findViewById(R.id.switchPauseMedia)
        languageBubbleSwitch = findViewById(R.id.switchLanguageBubble)
        pasteBubbleSwitch = findViewById(R.id.switchPasteBubble)
        noteBubbleSwitch = findViewById(R.id.switchNoteBubble)
        noteAppContainer = findViewById(R.id.noteAppContainer)
        tvSelectedNoteApp = findViewById(R.id.tvSelectedNoteApp)
        btnSelectNoteApp = findViewById(R.id.btnSelectNoteApp)
        keyboardBubbleSwitch = findViewById(R.id.switchKeyboardBubble)
        btnSetupKeyboard = findViewById(R.id.btnSetupKeyboard)
        keyboardThemeDropdown = findViewById(R.id.actvKeyboardTheme)
        copyDebugLogButton = findViewById(R.id.btnCopyDebugLog)
        shareDebugLogButton = findViewById(R.id.btnShareDebugLog)
        saveButton = findViewById(R.id.btnSave)
        versionInfo = findViewById(R.id.tvVersionInfo)
        changelog = findViewById(R.id.tvChangelog)
        changelogHeader = findViewById(R.id.changelogHeader)
        changelogExpandIcon = findViewById(R.id.ivChangelogExpand)

        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(false)
        supportActionBar?.title = getString(R.string.app_name)

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

        // Setup dictation mode dropdown
        dictationModeDropdown = findViewById(R.id.actvDictationMode)
        setupDictationModeDropdown()

        // Setup bar orientation dropdown
        barOrientationDropdown = findViewById(R.id.actvBarOrientation)
        setupBarOrientationDropdown()

        // Setup keyboard theme dropdown
        setupKeyboardThemeDropdown()

        // Setup transparency slider
        transparencySlider = findViewById(R.id.sliderTransparency)
        transparencyValueText = findViewById(R.id.tvTransparencyValue)
        setupTransparencySlider()
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

        // Whisper models - show downloaded ones
        if (modelManager.isModelAvailable("sherpa-onnx", "whisper-tiny")) {
            engines.add("Whisper Tiny (Fast)" to "${PreferenceManager.ENGINE_SHERPA_ONNX}:${PreferenceManager.WHISPER_TINY}")
        }
        if (modelManager.isModelAvailable("sherpa-onnx", "whisper-base")) {
            engines.add("Whisper Base (Balanced)" to "${PreferenceManager.ENGINE_SHERPA_ONNX}:${PreferenceManager.WHISPER_BASE}")
        }
        if (modelManager.isModelAvailable("sherpa-onnx", "whisper-small")) {
            engines.add("Whisper Small (Accurate)" to "${PreferenceManager.ENGINE_SHERPA_ONNX}:${PreferenceManager.WHISPER_SMALL}")
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
            } else {
                preferenceManager.setTranscriptionEngine(selectedValue)
                DebugLog.i("Engine changed to: $selectedValue")
            }
        }
    }

    private fun setupKeyboardThemeDropdown() {
        // Theme options: display name to preference value
        val themes = listOf(
            getString(R.string.keyboard_theme_system) to PreferenceManager.THEME_SYSTEM,
            getString(R.string.keyboard_theme_light) to PreferenceManager.THEME_LIGHT,
            getString(R.string.keyboard_theme_dark) to PreferenceManager.THEME_DARK
        )

        val displayNames = themes.map { it.first }
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, displayNames)
        keyboardThemeDropdown.setAdapter(adapter)

        // Set current selection
        val currentTheme = preferenceManager.getKeyboardTheme()
        val currentIndex = themes.indexOfFirst { it.second == currentTheme }
        if (currentIndex >= 0) {
            keyboardThemeDropdown.setText(displayNames[currentIndex], false)
        } else {
            // Default to System
            keyboardThemeDropdown.setText(displayNames[0], false)
        }

        // Handle selection changes - apply immediately
        keyboardThemeDropdown.setOnItemClickListener { _, _, position, _ ->
            val selectedValue = themes[position].second
            preferenceManager.setKeyboardTheme(selectedValue)
            DebugLog.i("Keyboard theme changed to: $selectedValue")

            // Notify the running keyboard service to refresh its theme immediately
            DictationKeyboardService.getInstance()?.refreshTheme()
        }
    }

    private fun setupTransparencySlider() {
        // Load current transparency value
        val currentTransparency = preferenceManager.getBarTransparency()
        transparencySlider.value = currentTransparency
        transparencyValueText.text = "${currentTransparency.toInt()}%"

        // Handle slider changes - apply immediately
        transparencySlider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                transparencyValueText.text = "${value.toInt()}%"
                preferenceManager.setBarTransparency(value)
                DebugLog.i("Bar transparency changed to: ${value.toInt()}%")

                // Notify BubbleService to apply new transparency
                BubbleService.refreshTransparency()
            }
        }
    }

    private fun setupDictationModeDropdown() {
        // Mode options: display name to preference value
        val modes = listOf(
            "Vertical Floating Bar" to PreferenceManager.MODE_BUBBLE,
            "Horizontal Floating Bar" to PreferenceManager.MODE_COMPANION,
            "Full Keyboard (IME)" to PreferenceManager.MODE_KEYBOARD
        )

        val displayNames = modes.map { it.first }
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, displayNames)
        dictationModeDropdown.setAdapter(adapter)

        // Set current selection
        val currentMode = preferenceManager.getDictationMode()
        val currentIndex = modes.indexOfFirst { it.second == currentMode }
        if (currentIndex >= 0) {
            dictationModeDropdown.setText(displayNames[currentIndex], false)
        } else {
            // Default to Bubble mode
            dictationModeDropdown.setText(displayNames[0], false)
        }

        // Handle selection changes - switch service immediately
        dictationModeDropdown.setOnItemClickListener { _, _, position, _ ->
            val selectedValue = modes[position].second
            val previousMode = preferenceManager.getDictationMode()
            preferenceManager.setDictationMode(selectedValue)
            DebugLog.i("Dictation mode changed from $previousMode to: $selectedValue")

            // Stop current service and start appropriate one
            switchDictationMode(previousMode, selectedValue)
        }
    }

    private fun setupBarOrientationDropdown() {
        // Orientation options: display name to preference value
        val orientations = listOf(
            "Vertical (Side bar)" to PreferenceManager.ORIENTATION_VERTICAL,
            "Horizontal (Bottom bar)" to PreferenceManager.ORIENTATION_HORIZONTAL
        )

        val displayNames = orientations.map { it.first }
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, displayNames)
        barOrientationDropdown.setAdapter(adapter)

        // Set current selection
        val currentOrientation = preferenceManager.getBarOrientation()
        val currentIndex = orientations.indexOfFirst { it.second == currentOrientation }
        if (currentIndex >= 0) {
            barOrientationDropdown.setText(displayNames[currentIndex], false)
        } else {
            // Default to Vertical
            barOrientationDropdown.setText(displayNames[0], false)
        }

        // Handle selection changes - apply immediately
        barOrientationDropdown.setOnItemClickListener { _, _, position, _ ->
            val selectedValue = orientations[position].second
            preferenceManager.setBarOrientation(selectedValue)
            DebugLog.i("Bar orientation changed to: $selectedValue")

            // Refresh the bubble service orientation
            BubbleService.refreshBarOrientation()
        }
    }

    /**
     * Switches between dictation modes by stopping the old service and starting the new one.
     */
    private fun switchDictationMode(fromMode: String, toMode: String) {
        Log.i(TAG, "Switching dictation mode from $fromMode to $toMode")

        // Stop all services first to ensure clean slate
        if (BubbleService.isRunning()) {
            BubbleService.stop(this)
        }
        if (KeyboardCompanionService.isRunning()) {
            KeyboardCompanionService.stop(this)
        }

        // Give a moment for the services to stop
        android.os.Handler(mainLooper).postDelayed({
            // Start the appropriate service(s) if permissions are granted
            if (allPermissionsGranted() && preferenceManager.hasApiKey()) {
                when (toMode) {
                    PreferenceManager.MODE_BUBBLE -> {
                        BubbleService.start(this)
                        Toast.makeText(this, "Switched to vertical floating bar mode", Toast.LENGTH_SHORT).show()
                    }
                    PreferenceManager.MODE_KEYBOARD -> {
                        // Start BubbleService for transcription (no UI), IME is activated by system
                        BubbleService.start(this)
                        // Open keyboard settings to help user enable the IME
                        openKeyboardSettings()
                        Toast.makeText(this, "Enable Tiny Dictation keyboard in settings", Toast.LENGTH_LONG).show()
                    }
                    PreferenceManager.MODE_COMPANION -> {
                        // Start KeyboardCompanionService for companion mode
                        KeyboardCompanionService.start(this)
                        // Also start BubbleService in background for transcription (without UI)
                        BubbleService.start(this)
                        Toast.makeText(this, "Horizontal floating bar mode active - bar appears with keyboard", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }, 300)
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

        // Load simulate typing setting
        simulateTypingSwitch.isChecked = preferenceManager.getSimulateTyping()

        // Load pause media during dictation setting
        pauseMediaSwitch.isChecked = preferenceManager.getPauseMediaDuringDictation()

        // Load bubble settings
        val prefs = getSharedPreferences("tiny_dictation_prefs", MODE_PRIVATE)
        languageBubbleSwitch.isChecked = prefs.getBoolean("show_language_bubble", true)
        pasteBubbleSwitch.isChecked = prefs.getBoolean("show_paste_bubble", true)

        // Load note bubble settings
        noteBubbleSwitch.isChecked = preferenceManager.getShowNoteBubble()
        updateNoteAppContainerVisibility()
        updateSelectedNoteAppDisplay()

        // Load keyboard bubble setting
        keyboardBubbleSwitch.isChecked = prefs.getBoolean("show_keyboard_bubble", false)
        updateKeyboardSetupButtonVisibility()

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
        // Permission buttons
        microphoneButton.setOnClickListener {
            Log.d(TAG, "Microphone button clicked - requesting permission")
            requestMicrophonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        overlayButton.setOnClickListener {
            Log.d(TAG, "Overlay button clicked - opening settings")
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        accessibilityButton.setOnClickListener {
            Log.d(TAG, "Accessibility button clicked - opening settings")
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(
                this,
                "Find 'Tiny Dictation' and enable it",
                Toast.LENGTH_LONG
            ).show()
        }

        // History button
        historyButton.setOnClickListener {
            Log.d(TAG, "History button clicked")
            startActivity(Intent(this, HistoryActivity::class.java))
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

        // Keyboard bubble toggle - applies immediately
        keyboardBubbleSwitch.setOnCheckedChangeListener { _, isChecked ->
            getSharedPreferences("tiny_dictation_prefs", MODE_PRIVATE)
                .edit()
                .putBoolean("show_keyboard_bubble", isChecked)
                .commit()
            updateKeyboardSetupButtonVisibility()
            DebugLog.i("Keyboard bubble setting changed to: $isChecked")
        }

        // Setup keyboard button - opens system keyboard settings
        btnSetupKeyboard.setOnClickListener {
            openKeyboardSettings()
        }

        // Headphone mic toggle applies immediately
        headphoneMicSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setUseHeadphoneMic(isChecked)
            DebugLog.i("Headphone mic setting changed to: $isChecked")
        }

        // Simulate typing toggle applies immediately
        simulateTypingSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setSimulateTyping(isChecked)
            DebugLog.i("Simulate typing setting changed to: $isChecked")
        }

        // Pause media toggle applies immediately
        pauseMediaSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setPauseMediaDuringDictation(isChecked)
            DebugLog.i("Pause media during dictation setting changed to: $isChecked")
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

        // API key only required for Deepgram (cloud) engine
        val engine = preferenceManager.getTranscriptionEngine()
        if (engine == PreferenceManager.ENGINE_DEEPGRAM && apiKey.isNullOrEmpty()) {
            apiKeyInput.error = "API key is required for Deepgram"
            return
        }

        // Save API key if provided
        if (!apiKey.isNullOrEmpty()) {
            preferenceManager.setApiKey(apiKey)
        }

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
        // Try dpaste.com first (more reliable), then paste.rs as fallback
        uploadToDpaste(content) ?: uploadToPasteRs(content)
    }

    private fun uploadToDpaste(content: String): String? {
        return try {
            // dpaste.com uses form-urlencoded POST
            val formBody = okhttp3.FormBody.Builder()
                .add("content", content)
                .build()

            val request = Request.Builder()
                .url("https://dpaste.com/api/")
                .post(formBody)
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                // dpaste.com returns the URL as plain text
                val url = response.body?.string()?.trim()
                DebugLog.i("dpaste.com upload successful: $url")
                url
            } else {
                DebugLog.e("dpaste.com upload failed: ${response.code}")
                null
            }
        } catch (e: Exception) {
            DebugLog.e("dpaste.com upload exception: ${e.message}")
            null
        }
    }

    private fun uploadToPasteRs(content: String): String? {
        return try {
            // paste.rs - simple API, just POST raw content
            val requestBody = content.toRequestBody("text/plain".toMediaType())

            val request = Request.Builder()
                .url("https://paste.rs/")
                .post(requestBody)
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful) {
                // paste.rs returns the URL as plain text
                val url = response.body?.string()?.trim()
                DebugLog.i("paste.rs upload successful: $url")
                url
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
     * Updates visibility of the keyboard setup button based on keyboard bubble toggle.
     */
    private fun updateKeyboardSetupButtonVisibility() {
        btnSetupKeyboard.visibility = if (keyboardBubbleSwitch.isChecked) View.VISIBLE else View.GONE
    }

    /**
     * Opens the system keyboard settings so user can enable Tiny Dictation keyboard.
     */
    private fun openKeyboardSettings() {
        try {
            val intent = Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Enable 'Tiny Dictation' in the keyboard list", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error opening keyboard settings", e)
            Toast.makeText(this, "Could not open keyboard settings", Toast.LENGTH_SHORT).show()
        }
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
