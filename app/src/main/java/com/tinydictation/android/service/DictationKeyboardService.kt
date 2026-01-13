package com.tinydictation.android.service

import android.animation.ObjectAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.content.pm.PackageManager
import android.os.Build
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.widget.Toast
import com.tinydictation.android.HistoryActivity
import com.tinydictation.android.R
import com.tinydictation.android.SettingsActivity
import com.tinydictation.android.history.TranscriptionHistoryManager
import com.tinydictation.android.transcription.TranscriptionManager
import com.tinydictation.android.util.DebugLog
import com.tinydictation.android.util.PreferenceManager

/**
 * Input Method Service (IME) for Tiny Dictation.
 *
 * This provides a keyboard bar with action buttons for dictation and text manipulation.
 * Buttons: Chevron, Language, Mic, Enter, Backspace, History, Select All
 */
class DictationKeyboardService : InputMethodService() {

    companion object {
        private const val TAG = "TinyDictation"
        private const val LONG_PRESS_THRESHOLD = 500L

        /** Broadcast action for pasting text from HistoryActivity */
        const val ACTION_PASTE_TEXT = "com.tinydictation.ACTION_PASTE_TEXT"
        const val EXTRA_TEXT = "text"

        @Volatile
        private var instance: DictationKeyboardService? = null

        fun getInstance(): DictationKeyboardService? = instance

        fun isServiceRunning(): Boolean = instance != null

        fun commitText(text: String): Boolean {
            Log.d(TAG, "commitText requested: $text")
            return instance?.commitTextInternal(text) ?: run {
                Log.e(TAG, "Cannot commit text - keyboard service not running")
                false
            }
        }

        fun canCommitText(): Boolean {
            return instance?.currentInputConnection != null
        }

        /**
         * Pastes text into the current input field.
         * Called from HistoryActivity via broadcast receiver.
         */
        fun pasteText(text: String): Boolean {
            return instance?.commitTextInternal(text) ?: false
        }
    }

    private var keyboardView: View? = null
    private var micButton: FrameLayout? = null
    private var micIcon: ImageView? = null
    private var languageText: TextView? = null
    private var emojiGifPanel: View? = null
    private var emojiGifPanelManager: com.tinydictation.android.gif.EmojiGifPanelManager? = null
    private var isEmojiPanelVisible = false
    private var qwertyPanel: View? = null
    private var isQwertyPanelVisible = false
    private var isShiftActive = false
    private var isCapsLock = false

    // Pulse animation for transcribing state
    private var pulseAnimator: ObjectAnimator? = null

    // Transcription management - keyboard owns its own TranscriptionManager
    private var transcriptionManager: TranscriptionManager? = null
    private var isRecording = false
    private var isProcessing = false

    // Broadcast receiver for paste requests from HistoryActivity
    private val pasteTextReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_PASTE_TEXT) {
                val text = intent.getStringExtra(EXTRA_TEXT)
                if (!text.isNullOrEmpty()) {
                    Log.i(TAG, "Received paste request: ${text.take(50)}...")
                    DebugLog.i("DictationKeyboardService: Pasting ${text.length} chars from history")
                    commitTextInternal(text)
                }
            }
        }
    }

    // Long press handling for mic button
    private var longPressHandler: Handler? = null
    private var longPressRunnable: Runnable? = null
    private var isLongPressTriggered = false

    // Backspace repeat handling - uses persistent handler and flag-based control
    private val backspaceHandler = Handler(Looper.getMainLooper())
    @Volatile private var isBackspaceHeld = false    // Flag to track if button is held
    private val BACKSPACE_INITIAL_DELAY = 400L       // Delay before repeat starts
    private val BACKSPACE_REPEAT_DELAY_START = 100L  // Initial repeat delay (slower)
    private val BACKSPACE_REPEAT_DELAY_MIN = 25L     // Minimum repeat delay (fastest)
    private val BACKSPACE_ACCELERATION_TIME = 1500L  // Time to reach max speed (ms)
    private val BACKSPACE_WORD_DELETE_TIME = 2000L   // Time before switching to word deletion (ms)
    private val BACKSPACE_WORD_DELETE_DELAY = 80L    // Delay between word deletions
    private var backspaceStartTime = 0L              // When backspace hold started

    // Counter to track how many backspaces have been sent (for debugging)
    private var backspaceCount = 0

    // Single runnable instance that checks the flag to decide whether to continue
    private val backspaceRepeatRunnable = object : Runnable {
        override fun run() {
            // Check flag - if button was released, stop immediately
            if (!isBackspaceHeld) {
                DebugLog.d("DictationKeyboardService: Backspace released after $backspaceCount deletions, stopping repeat")
                return
            }

            val holdDuration = System.currentTimeMillis() - backspaceStartTime

            if (holdDuration >= BACKSPACE_WORD_DELETE_TIME) {
                // After 2 seconds, delete words instead of characters
                deleteWord()
                backspaceCount++
                // Continue word deletion as long as button is held
                backspaceHandler.postDelayed(this, BACKSPACE_WORD_DELETE_DELAY)
            } else {
                // Delete single character
                sendBackspace()
                backspaceCount++

                // Calculate accelerated delay based on hold duration
                // Linear interpolation from REPEAT_DELAY_START to REPEAT_DELAY_MIN
                val accelerationProgress = (holdDuration.toFloat() / BACKSPACE_ACCELERATION_TIME).coerceIn(0f, 1f)
                val currentDelay = (BACKSPACE_REPEAT_DELAY_START -
                    (BACKSPACE_REPEAT_DELAY_START - BACKSPACE_REPEAT_DELAY_MIN) * accelerationProgress).toLong()

                // Log every 10 deletions to avoid spam but track progress
                if (backspaceCount % 10 == 0) {
                    DebugLog.d("DictationKeyboardService: Backspace count=$backspaceCount, delay=${currentDelay}ms, held=${holdDuration}ms")
                }

                // Continue character deletion with current delay
                backspaceHandler.postDelayed(this, currentDelay)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "DictationKeyboardService onCreate")
        DebugLog.i("DictationKeyboardService: Service created")
        instance = this

        // Register broadcast receiver for paste requests from HistoryActivity
        val filter = IntentFilter(ACTION_PASTE_TEXT)
        LocalBroadcastManager.getInstance(this).registerReceiver(pasteTextReceiver, filter)

        // Initialize TranscriptionManager
        initializeTranscriptionManager()
    }

    /**
     * Initializes the TranscriptionManager with callbacks.
     */
    private fun initializeTranscriptionManager() {
        val prefs = PreferenceManager(this)
        val apiKey = prefs.getApiKey()
        if (apiKey.isNullOrBlank()) {
            Log.w(TAG, "No API key configured, TranscriptionManager not initialized")
            DebugLog.w("DictationKeyboardService: No API key, skipping TranscriptionManager init")
            return
        }

        Log.i(TAG, "Initializing TranscriptionManager")
        DebugLog.i("DictationKeyboardService: Initializing TranscriptionManager")

        transcriptionManager = TranscriptionManager(
            apiKey = apiKey,
            onTranscript = { text, isFinal ->
                Log.d(TAG, "Transcript received - isFinal: $isFinal, text: $text")
                if (isFinal && text.isNotBlank()) {
                    // Commit text to the current input field
                    Handler(Looper.getMainLooper()).post {
                        commitTextInternal(text + " ")
                    }
                    // Save to transcription history
                    val language = prefs.getLanguage()
                    TranscriptionHistoryManager.getInstance(this).save(text, language)
                    DebugLog.i("DictationKeyboardService: Saved transcription to history")
                }
            },
            onError = { error ->
                Log.e(TAG, "Transcription error: $error")
                DebugLog.e("DictationKeyboardService: Transcription error - $error")
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
                    isRecording = false
                    isProcessing = false
                    updateMicButton()
                }
            },
            onStateChange = { state ->
                Log.d(TAG, "Transcription state changed: $state")
                Handler(Looper.getMainLooper()).post {
                    when (state) {
                        TranscriptionManager.TranscriptionState.IDLE -> {
                            isRecording = false
                            isProcessing = false
                        }
                        TranscriptionManager.TranscriptionState.LISTENING -> {
                            isRecording = true
                            isProcessing = false
                        }
                        TranscriptionManager.TranscriptionState.TRANSCRIBING -> {
                            isRecording = true
                            isProcessing = true
                        }
                        TranscriptionManager.TranscriptionState.ERROR -> {
                            isRecording = false
                            isProcessing = false
                        }
                    }
                    updateMicButton()
                }
            },
            context = this,
            onSilenceAutoStop = {
                // Called when silence auto-stop timeout expires
                Log.i(TAG, "Silence auto-stop triggered")
                DebugLog.i("DictationKeyboardService: Silence auto-stop triggered")
                Handler(Looper.getMainLooper()).post {
                    transcriptionManager?.stopListening()
                    isRecording = false
                    isProcessing = false
                    updateMicButton()
                }
            }
        )
    }

    override fun onDestroy() {
        Log.i(TAG, "DictationKeyboardService onDestroy")
        DebugLog.i("DictationKeyboardService: Service destroyed")

        // Stop transcription if running
        transcriptionManager?.stopListening()
        transcriptionManager = null

        // Unregister broadcast receiver
        LocalBroadcastManager.getInstance(this).unregisterReceiver(pasteTextReceiver)

        instance = null
        longPressHandler?.removeCallbacksAndMessages(null)
        isBackspaceHeld = false
        backspaceHandler.removeCallbacksAndMessages(null)
        pulseAnimator?.cancel()
        pulseAnimator = null
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        Log.i(TAG, "DictationKeyboardService onCreateInputView")
        DebugLog.d("DictationKeyboardService: Creating input view")

        return try {
            keyboardView = layoutInflater.inflate(R.layout.view_keyboard, null)

            micButton = keyboardView?.findViewById(R.id.btnKeyboardMic)
            micIcon = keyboardView?.findViewById(R.id.ivKeyboardMic)
            languageText = keyboardView?.findViewById(R.id.tvLanguage)
            emojiGifPanel = keyboardView?.findViewById(R.id.emojiGifPanel)
            qwertyPanel = keyboardView?.findViewById(R.id.qwertyPanel)

            setupButtonHandlers()
            setupEmojiGifPanel()
            setupQwertyKeyboard()
            applyTheme()
            updateMicButton()
            updateLanguageButton()
            setupVersionDisplay()
            scrollDictationBarToDefault()

            keyboardView!!
        } catch (e: Exception) {
            Log.e(TAG, "Error creating keyboard view", e)
            DebugLog.e("DictationKeyboardService: Error creating view - ${e.message}")
            TextView(this).apply {
                text = "Tiny Dictation Keyboard"
                setPadding(16, 16, 16, 16)
                textSize = 16f
            }
        }
    }

    /**
     * Applies the selected theme colors to the keyboard.
     */
    private fun applyTheme() {
        val prefs = PreferenceManager(this)
        val themeSetting = prefs.getKeyboardTheme()

        // Define colors based on theme
        val backgroundColor: Int
        val buttonColor: Int
        val iconColor: Int

        when (themeSetting) {
            PreferenceManager.THEME_AMOLED -> {
                // AMOLED black theme - pure black saves battery on OLED screens
                backgroundColor = Color.parseColor("#000000")  // Pure black
                buttonColor = Color.parseColor("#1A1A1A")      // Very dark gray
                iconColor = Color.parseColor("#FFFFFF")        // White
            }
            PreferenceManager.THEME_DARK -> {
                // Dark theme - use existing dark colors from colors.xml
                backgroundColor = ContextCompat.getColor(this, R.color.surface_variant)
                buttonColor = ContextCompat.getColor(this, R.color.surface)
                iconColor = ContextCompat.getColor(this, R.color.on_surface)
            }
            PreferenceManager.THEME_LIGHT -> {
                // Light theme - white background, gray buttons, black icons
                backgroundColor = Color.parseColor("#F5F5F5")
                buttonColor = Color.parseColor("#E0E0E0")
                iconColor = Color.parseColor("#1C1B1F")
            }
            else -> {
                // THEME_SYSTEM - follow system setting
                val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                if (nightModeFlags == Configuration.UI_MODE_NIGHT_YES) {
                    backgroundColor = ContextCompat.getColor(this, R.color.surface_variant)
                    buttonColor = ContextCompat.getColor(this, R.color.surface)
                    iconColor = ContextCompat.getColor(this, R.color.on_surface)
                } else {
                    backgroundColor = Color.parseColor("#F5F5F5")
                    buttonColor = Color.parseColor("#E0E0E0")
                    iconColor = Color.parseColor("#1C1B1F")
                }
            }
        }

        // Apply background color to root and keyboard container
        keyboardView?.findViewById<LinearLayout>(R.id.keyboardRoot)?.setBackgroundColor(backgroundColor)
        keyboardView?.findViewById<LinearLayout>(R.id.keyboardContainer)?.setBackgroundColor(backgroundColor)

        // Apply button and icon colors to all buttons
        applyButtonTheme(keyboardView?.findViewById(R.id.btnKeyboardMic), buttonColor, iconColor)
        applyButtonTheme(keyboardView?.findViewById(R.id.btnLanguage), buttonColor, iconColor)
        applyButtonTheme(keyboardView?.findViewById(R.id.btnBackspace), buttonColor, iconColor)
        applyButtonTheme(keyboardView?.findViewById(R.id.btnEnter), buttonColor, iconColor)
        applyButtonTheme(keyboardView?.findViewById(R.id.btnEmoji), buttonColor, iconColor)
        applyButtonTheme(keyboardView?.findViewById(R.id.btnKeyboard), buttonColor, iconColor)
        applyButtonTheme(keyboardView?.findViewById(R.id.btnSelectAll), buttonColor, iconColor)
        applyButtonTheme(keyboardView?.findViewById(R.id.btnSwitchBack), buttonColor, iconColor)

        // Apply text color to language text
        languageText?.setTextColor(iconColor)

        // Apply text color to "All" button text
        keyboardView?.findViewById<FrameLayout>(R.id.btnSelectAll)?.let { button ->
            val textView = button.getChildAt(0) as? TextView
            textView?.setTextColor(iconColor)
        }

        DebugLog.d("DictationKeyboardService: Applied theme - $themeSetting")
    }

    /**
     * Applies theme colors to a button.
     */
    private fun applyButtonTheme(button: FrameLayout?, buttonColor: Int, iconColor: Int) {
        button ?: return

        // Create new background drawable with themed color
        val backgroundDrawable = GradientDrawable().apply {
            setColor(buttonColor)
            cornerRadius = 12 * resources.displayMetrics.density
        }

        // Wrap in ripple for touch feedback
        // Use mutate() to ensure each button has its own independent drawable state
        // This prevents visual state from "bleeding" between buttons
        val rippleDrawable = RippleDrawable(
            android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary)),
            backgroundDrawable,
            null
        ).mutate() as RippleDrawable
        button.background = rippleDrawable

        // Apply icon tint to ImageView children using a non-stateful ColorStateList
        // This ensures the icon color doesn't change on press/active states
        val iconColorStateList = android.content.res.ColorStateList.valueOf(iconColor)
        for (i in 0 until button.childCount) {
            val child = button.getChildAt(i)
            if (child is ImageView) {
                child.imageTintList = iconColorStateList
            }
        }
    }

    private fun setupButtonHandlers() {
        // Mic button - short press toggle, long press settings
        micButton?.setOnTouchListener { _, event ->
            handleMicTouch(event)
        }

        // Language button
        keyboardView?.findViewById<FrameLayout>(R.id.btnLanguage)?.setOnClickListener {
            toggleLanguage()
        }

        // Backspace button - with hold-to-repeat
        keyboardView?.findViewById<FrameLayout>(R.id.btnBackspace)?.setOnTouchListener { _, event ->
            handleBackspaceTouch(event)
        }

        // Select All button
        keyboardView?.findViewById<FrameLayout>(R.id.btnSelectAll)?.setOnClickListener {
            selectAll()
        }

        // Enter button
        keyboardView?.findViewById<FrameLayout>(R.id.btnEnter)?.setOnClickListener {
            sendEnterKey()
        }

        // Emoji button - toggles emoji panel
        keyboardView?.findViewById<FrameLayout>(R.id.btnEmoji)?.setOnClickListener {
            toggleEmojiPanel()
        }

        // Keyboard button - toggles QWERTY keyboard panel
        keyboardView?.findViewById<FrameLayout>(R.id.btnKeyboard)?.setOnClickListener {
            toggleQwertyPanel()
        }

        // Switch back to previous keyboard button
        keyboardView?.findViewById<FrameLayout>(R.id.btnSwitchBack)?.setOnClickListener {
            switchToPreviousKeyboard()
        }
    }

    /**
     * Handles touch events for mic button with long-press detection.
     */
    private fun handleMicTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isLongPressTriggered = false
                longPressHandler = Handler(Looper.getMainLooper())
                longPressRunnable = Runnable {
                    isLongPressTriggered = true
                    openSettings()
                }
                longPressHandler?.postDelayed(longPressRunnable!!, LONG_PRESS_THRESHOLD)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                longPressRunnable?.let { longPressHandler?.removeCallbacks(it) }
                if (!isLongPressTriggered && event.action == MotionEvent.ACTION_UP) {
                    toggleDictation()
                }
                return true
            }
        }
        return false
    }

    private fun openSettings() {
        Log.i(TAG, "DictationKeyboardService: Opening settings")
        DebugLog.i("DictationKeyboardService: Long press - opening settings")

        val intent = Intent(this, SettingsActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun openTranscriptionHistory() {
        Log.i(TAG, "DictationKeyboardService: Opening transcription history")
        DebugLog.i("DictationKeyboardService: Opening history")

        val intent = Intent(this, HistoryActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun toggleDictation() {
        Log.i(TAG, "DictationKeyboardService: Toggle dictation")
        DebugLog.i("DictationKeyboardService: Toggling dictation")

        // Initialize TranscriptionManager if not already done
        if (transcriptionManager == null) {
            initializeTranscriptionManager()
        }

        transcriptionManager?.let { manager ->
            if (isRecording) {
                Log.i(TAG, "Stopping dictation")
                manager.stopListening()
            } else {
                Log.i(TAG, "Starting dictation")
                try {
                    manager.startListening()
                } catch (e: SecurityException) {
                    Log.e(TAG, "Security exception starting listening", e)
                    DebugLog.e("DictationKeyboardService: Security exception - ${e.message}")
                    Toast.makeText(this, "Microphone permission denied", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting listening", e)
                    DebugLog.e("DictationKeyboardService: Error starting listening - ${e.message}")
                    Toast.makeText(this, "Failed to start recording: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
            updateMicButton()
        } ?: run {
            Log.w(TAG, "TranscriptionManager not initialized")
            DebugLog.w("DictationKeyboardService: TranscriptionManager not initialized - check API key")
            Toast.makeText(this, "Please configure an API key in Settings", Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleLanguage() {
        Log.i(TAG, "DictationKeyboardService: Toggle language")
        DebugLog.i("DictationKeyboardService: Toggling language")

        val prefs = PreferenceManager(this)
        val currentLang = prefs.getLanguage()

        // Toggle between en and de (or extend for more languages)
        val newLang = if (currentLang == "en") "de" else "en"
        prefs.setLanguage(newLang)

        // If currently recording, restart mic to apply new language immediately
        if (isRecording || isProcessing) {
            Log.i(TAG, "Restarting mic to apply language change")
            DebugLog.i("DictationKeyboardService: Restarting mic for language change")
            transcriptionManager?.stopListening()
            isRecording = false
            isProcessing = false
            updateMicButton()

            // Brief delay then restart with new language
            Handler(Looper.getMainLooper()).postDelayed({
                transcriptionManager?.startListening()
            }, 100)
        } else {
            // Not recording, just update the engine's language setting
            transcriptionManager?.setLanguage(newLang)
        }

        updateLanguageButton()
        Log.i(TAG, "Language changed to: $newLang")
    }

    private fun updateMicButton() {
        val color = if (isRecording || isProcessing) {
            ContextCompat.getColor(this, R.color.bubble_recording)
        } else {
            // Use themed icon color when not recording
            getThemedIconColor()
        }
        // Use imageTintList with a non-stateful ColorStateList to prevent color changes on press
        micIcon?.imageTintList = android.content.res.ColorStateList.valueOf(color)

        // Handle pulse animation for processing state
        if (isProcessing) {
            startPulseAnimation(micIcon)
        } else {
            stopPulseAnimation(micIcon)
        }
    }

    /**
     * Starts a pulsing alpha animation on the mic icon to indicate transcription processing.
     */
    private fun startPulseAnimation(view: ImageView?) {
        view ?: return
        if (pulseAnimator?.isRunning == true) return // Already running
        pulseAnimator?.cancel()
        pulseAnimator = ObjectAnimator.ofFloat(view, "alpha", 1f, 0.4f).apply {
            duration = 500
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            start()
        }
    }

    /**
     * Stops the pulsing animation and resets the view to full opacity.
     */
    private fun stopPulseAnimation(view: ImageView?) {
        pulseAnimator?.cancel()
        pulseAnimator = null
        view?.alpha = 1f
    }

    /**
     * Gets the appropriate icon color based on the current theme setting.
     */
    private fun getThemedIconColor(): Int {
        val prefs = PreferenceManager(this)
        val themeSetting = prefs.getKeyboardTheme()

        return when (themeSetting) {
            PreferenceManager.THEME_AMOLED -> Color.parseColor("#FFFFFF")  // White for AMOLED
            PreferenceManager.THEME_DARK -> ContextCompat.getColor(this, R.color.on_surface)
            PreferenceManager.THEME_LIGHT -> Color.parseColor("#1C1B1F")
            else -> { // THEME_SYSTEM
                val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                if (nightModeFlags == Configuration.UI_MODE_NIGHT_YES) {
                    ContextCompat.getColor(this, R.color.on_surface)
                } else {
                    Color.parseColor("#1C1B1F")
                }
            }
        }
    }

    private fun updateLanguageButton() {
        val prefs = PreferenceManager(this)
        val languageButton = keyboardView?.findViewById<FrameLayout>(R.id.btnLanguage)

        // Hide language button if provider doesn't need manual language selection
        // This includes: Whisper (auto-detects), cloud providers with auto-detect enabled
        val needsLanguageToggle = prefs.currentProviderNeedsLanguageToggle()
        if (!needsLanguageToggle) {
            languageButton?.visibility = View.GONE
            DebugLog.d("DictationKeyboardService: Language button hidden (provider auto-detects)")
        } else {
            languageButton?.visibility = View.VISIBLE
            val lang = prefs.getLanguage().uppercase()
            languageText?.text = lang
        }
    }

    /**
     * Sets up the tiny version display in the corner of the keyboard.
     * Shows version dynamically for dev/testing purposes.
     */
    private fun setupVersionDisplay() {
        val versionText = keyboardView?.findViewById<TextView>(R.id.tvKeyboardVersion)
        try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            val versionName = packageInfo.versionName
            versionText?.text = "v$versionName"
        } catch (e: Exception) {
            DebugLog.e("DictationKeyboardService: Failed to get version - ${e.message}")
            versionText?.visibility = View.GONE
        }
    }

    /**
     * Scrolls the dictation bar to default position.
     * Current order: SelectAll | Backspace | Enter | Mic | Language | Emoji | Keyboard | Chevron
     * Default position 0 shows the main buttons on the left; scroll right to see Emoji/Keyboard/Chevron.
     */
    private fun scrollDictationBarToDefault() {
        // Start at position 0 (leftmost) - no scrolling needed
        // Main buttons (SelectAll, Backspace, Enter, Mic, Language) are visible by default
    }

    /**
     * Sets up the tabbed Emoji/GIF panel with the panel manager.
     */
    private fun setupEmojiGifPanel() {
        emojiGifPanel?.let { panel ->
            emojiGifPanelManager = com.tinydictation.android.gif.EmojiGifPanelManager(
                context = this,
                panelView = panel,
                onEmojiSelected = { emoji ->
                    currentInputConnection?.commitText(emoji, 1)
                    Log.d(TAG, "Emoji inserted: $emoji")
                },
                onGifSelected = { gif ->
                    Log.d(TAG, "GIF selected: ${gif.id}")
                },
                getInputConnection = { currentInputConnection }
            )
            Log.i(TAG, "EmojiGifPanelManager initialized")
            DebugLog.i("DictationKeyboardService: EmojiGifPanelManager initialized")
        }
    }

    /**
     * Toggles the emoji/GIF panel visibility.
     * Hides QWERTY panel if visible.
     */
    private fun toggleEmojiPanel() {
        // Hide QWERTY panel if showing
        if (isQwertyPanelVisible) {
            isQwertyPanelVisible = false
            qwertyPanel?.visibility = View.GONE
        }

        isEmojiPanelVisible = !isEmojiPanelVisible
        emojiGifPanel?.visibility = if (isEmojiPanelVisible) View.VISIBLE else View.GONE
        Log.d(TAG, "Emoji/GIF panel visibility: $isEmojiPanelVisible")
        DebugLog.i("DictationKeyboardService: Emoji/GIF panel ${if (isEmojiPanelVisible) "shown" else "hidden"}")
    }

    /**
     * Toggles the QWERTY keyboard panel visibility.
     * Hides emoji panel if visible.
     */
    private fun toggleQwertyPanel() {
        // Hide emoji panel if showing
        if (isEmojiPanelVisible) {
            isEmojiPanelVisible = false
            emojiGifPanel?.visibility = View.GONE
        }

        isQwertyPanelVisible = !isQwertyPanelVisible
        qwertyPanel?.visibility = if (isQwertyPanelVisible) View.VISIBLE else View.GONE
        Log.d(TAG, "QWERTY panel visibility: $isQwertyPanelVisible")
        DebugLog.i("DictationKeyboardService: QWERTY panel ${if (isQwertyPanelVisible) "shown" else "hidden"}")
    }

    /**
     * Sets up the QWERTY keyboard key handlers.
     */
    private fun setupQwertyKeyboard() {
        qwertyPanel ?: return

        // Letter keys - map button IDs to characters
        val letterKeys = mapOf(
            R.id.key_q to "q", R.id.key_w to "w", R.id.key_e to "e", R.id.key_r to "r",
            R.id.key_t to "t", R.id.key_y to "y", R.id.key_u to "u", R.id.key_i to "i",
            R.id.key_o to "o", R.id.key_p to "p", R.id.key_a to "a", R.id.key_s to "s",
            R.id.key_d to "d", R.id.key_f to "f", R.id.key_g to "g", R.id.key_h to "h",
            R.id.key_j to "j", R.id.key_k to "k", R.id.key_l to "l", R.id.key_z to "z",
            R.id.key_x to "x", R.id.key_c to "c", R.id.key_v to "v", R.id.key_b to "b",
            R.id.key_n to "n", R.id.key_m to "m"
        )

        for ((id, char) in letterKeys) {
            qwertyPanel?.findViewById<android.widget.Button>(id)?.setOnClickListener {
                val textToCommit = if (isShiftActive || isCapsLock) char.uppercase() else char
                currentInputConnection?.commitText(textToCommit, 1)

                // Turn off shift after one character (unless caps lock)
                if (isShiftActive && !isCapsLock) {
                    isShiftActive = false
                    updateShiftKeyDisplay()
                }
            }
        }

        // Punctuation keys
        qwertyPanel?.findViewById<android.widget.Button>(R.id.key_comma)?.setOnClickListener {
            currentInputConnection?.commitText(",", 1)
        }
        qwertyPanel?.findViewById<android.widget.Button>(R.id.key_period)?.setOnClickListener {
            currentInputConnection?.commitText(".", 1)
        }

        // Space bar
        qwertyPanel?.findViewById<android.widget.Button>(R.id.key_space)?.setOnClickListener {
            currentInputConnection?.commitText(" ", 1)
        }

        // Enter key
        qwertyPanel?.findViewById<android.widget.Button>(R.id.key_enter)?.setOnClickListener {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }

        // Backspace key
        qwertyPanel?.findViewById<android.widget.Button>(R.id.key_backspace)?.setOnClickListener {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        }

        // Shift key - tap for shift, double-tap for caps lock
        val shiftButton = qwertyPanel?.findViewById<android.widget.Button>(R.id.key_shift)
        var lastShiftTapTime = 0L
        shiftButton?.setOnClickListener {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastShiftTapTime < 300) {
                // Double tap - toggle caps lock
                isCapsLock = !isCapsLock
                isShiftActive = isCapsLock
                DebugLog.d("DictationKeyboardService: Caps lock ${if (isCapsLock) "ON" else "OFF"}")
            } else {
                // Single tap - toggle shift
                if (isCapsLock) {
                    // Turn off caps lock
                    isCapsLock = false
                    isShiftActive = false
                } else {
                    isShiftActive = !isShiftActive
                }
            }
            lastShiftTapTime = currentTime
            updateShiftKeyDisplay()
        }

        // 123/numbers key - placeholder for future numeric keyboard
        qwertyPanel?.findViewById<android.widget.Button>(R.id.key_numbers)?.setOnClickListener {
            // TODO: Switch to numeric keyboard panel
            DebugLog.d("DictationKeyboardService: Numbers key tapped (not implemented)")
        }
    }

    /**
     * Updates the shift key display based on current state.
     */
    private fun updateShiftKeyDisplay() {
        val shiftButton = qwertyPanel?.findViewById<android.widget.Button>(R.id.key_shift)
        shiftButton?.text = when {
            isCapsLock -> "⇪"  // Caps lock indicator
            isShiftActive -> "⇧"  // Shift active (filled)
            else -> "⇧"  // Normal shift
        }

        // Update letter key displays to show uppercase when shift/caps active
        updateLetterKeyDisplays()
    }

    /**
     * Updates all letter keys to show uppercase or lowercase.
     */
    private fun updateLetterKeyDisplays() {
        val letterKeys = mapOf(
            R.id.key_q to "q", R.id.key_w to "w", R.id.key_e to "e", R.id.key_r to "r",
            R.id.key_t to "t", R.id.key_y to "y", R.id.key_u to "u", R.id.key_i to "i",
            R.id.key_o to "o", R.id.key_p to "p", R.id.key_a to "a", R.id.key_s to "s",
            R.id.key_d to "d", R.id.key_f to "f", R.id.key_g to "g", R.id.key_h to "h",
            R.id.key_j to "j", R.id.key_k to "k", R.id.key_l to "l", R.id.key_z to "z",
            R.id.key_x to "x", R.id.key_c to "c", R.id.key_v to "v", R.id.key_b to "b",
            R.id.key_n to "n", R.id.key_m to "m"
        )

        val useUppercase = isShiftActive || isCapsLock
        for ((id, char) in letterKeys) {
            qwertyPanel?.findViewById<android.widget.Button>(id)?.text =
                if (useUppercase) char.uppercase() else char
        }
    }

    /**
     * Handles touch events for backspace button with hold-to-repeat.
     *
     * Behavior:
     * - Tap: delete one character
     * - Hold (0-1.5s): delete characters with accelerating speed (starts at 100ms, goes to 25ms)
     * - Hold (after 2s): switch to word-by-word deletion (continues indefinitely)
     *
     * Uses flag-based control to ensure the repeat loop continues as long as
     * the button is held, and stops immediately when released.
     */
    private fun handleBackspaceTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                DebugLog.d("DictationKeyboardService: Backspace ACTION_DOWN")

                // Reset counter for this hold session
                backspaceCount = 0

                // Send first backspace immediately
                sendBackspace()
                backspaceCount++

                // Record start time and set flag to indicate button is held
                backspaceStartTime = System.currentTimeMillis()
                isBackspaceHeld = true

                // Remove any pending callbacks (in case of race condition)
                backspaceHandler.removeCallbacks(backspaceRepeatRunnable)

                // Start the repeat loop after initial delay
                backspaceHandler.postDelayed(backspaceRepeatRunnable, BACKSPACE_INITIAL_DELAY)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val holdDuration = System.currentTimeMillis() - backspaceStartTime
                DebugLog.d("DictationKeyboardService: Backspace ACTION_UP/CANCEL after ${holdDuration}ms, $backspaceCount deletions")

                // Clear the flag - this will cause the runnable to stop on next iteration
                isBackspaceHeld = false

                // Also remove pending callbacks immediately for faster response
                backspaceHandler.removeCallbacks(backspaceRepeatRunnable)
                return true
            }
        }
        return false
    }

    /**
     * Deletes the word before the cursor.
     * Finds the last word boundary (space) and deletes everything from there to cursor.
     */
    private fun deleteWord() {
        Log.i(TAG, "DictationKeyboardService: Delete word")
        DebugLog.d("DictationKeyboardService: Deleting word")

        currentInputConnection?.let { ic ->
            // Get text before cursor to find word boundary
            val textBefore = ic.getTextBeforeCursor(50, 0)
            if (textBefore.isNullOrEmpty()) {
                // No text to delete
                return
            }

            // Find the last word boundary
            // First, skip any trailing spaces
            var endIndex = textBefore.length
            while (endIndex > 0 && textBefore[endIndex - 1] == ' ') {
                endIndex--
            }

            // Then find the start of the word (last space before end)
            var startIndex = endIndex
            while (startIndex > 0 && textBefore[startIndex - 1] != ' ') {
                startIndex--
            }

            // Calculate how many characters to delete
            val charsToDelete = textBefore.length - startIndex
            if (charsToDelete > 0) {
                ic.deleteSurroundingText(charsToDelete, 0)
                DebugLog.d("DictationKeyboardService: Deleted $charsToDelete chars (word)")
            }
        }
    }

    /**
     * Sends a backspace/delete key event.
     *
     * Uses sendDownUpKeyEvents() which is InputMethodService's built-in method.
     * This is more reliable for terminal/SSH apps (like Connectbot) than manually
     * constructing KeyEvents, as the system handles the proper event dispatch.
     *
     * If that fails, falls back to deleteSurroundingText() which works for regular apps.
     */
    private fun sendBackspace() {
        // Use the built-in InputMethodService method - more reliable for terminals
        // This sends proper key events through the system's input dispatch chain
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
    }

    private fun selectAll() {
        Log.i(TAG, "DictationKeyboardService: Select all")
        DebugLog.d("DictationKeyboardService: Selecting all text")

        currentInputConnection?.let { ic ->
            val before = ic.getTextBeforeCursor(Int.MAX_VALUE, 0) ?: ""
            val after = ic.getTextAfterCursor(Int.MAX_VALUE, 0) ?: ""
            val totalLength = before.length + after.length
            ic.setSelection(0, totalLength)
        }
    }

    private fun sendEnterKey() {
        Log.i(TAG, "DictationKeyboardService: Enter key")
        DebugLog.d("DictationKeyboardService: Sending enter key")

        // Use the built-in InputMethodService method for consistency with sendBackspace
        sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
    }

    /**
     * Switches back to the previously used keyboard.
     * Uses switchToNextInputMethod() which cycles through available keyboards.
     * Falls back to showing the keyboard picker if that fails.
     */
    private fun switchToPreviousKeyboard() {
        Log.i(TAG, "DictationKeyboardService: Switching to previous keyboard")
        DebugLog.i("DictationKeyboardService: Switching to previous keyboard")

        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val token = window?.window?.attributes?.token

            DebugLog.d("DictationKeyboardService: Window token present: ${token != null}")

            if (token != null) {
                // Try switchToLastInputMethod first (switches to previously used keyboard)
                var switched = imm.switchToLastInputMethod(token)
                DebugLog.d("DictationKeyboardService: switchToLastInputMethod result: $switched")

                if (!switched) {
                    // If no "last" keyboard, try switching to the next available keyboard
                    switched = imm.switchToNextInputMethod(token, false)
                    DebugLog.d("DictationKeyboardService: switchToNextInputMethod result: $switched")
                }

                if (switched) {
                    Log.i(TAG, "Successfully switched keyboard")
                    DebugLog.i("DictationKeyboardService: Switched to another keyboard")
                } else {
                    Log.w(TAG, "Both switch methods returned false, showing picker")
                    DebugLog.w("DictationKeyboardService: Switch failed, showing picker")
                    showInputMethodPicker()
                }
            } else {
                Log.w(TAG, "No window token, showing picker")
                DebugLog.w("DictationKeyboardService: No window token, showing picker")
                showInputMethodPicker()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error switching keyboard", e)
            DebugLog.e("DictationKeyboardService: Error switching keyboard - ${e.message}")
            showInputMethodPicker()
        }
    }

    private fun showInputMethodPicker() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing keyboard picker", e)
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Log.d(TAG, "DictationKeyboardService onStartInputView - field: ${info?.fieldName}")
        DebugLog.d("DictationKeyboardService: Started input for field: ${info?.fieldName}")

        updateMicButton()
        updateLanguageButton()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        Log.d(TAG, "DictationKeyboardService onStartInput - inputType: ${attribute?.inputType}")
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        Log.d(TAG, "DictationKeyboardService onFinishInputView")
        DebugLog.d("DictationKeyboardService: Finished input view")

        // Stop transcription when keyboard is hidden to save battery
        if (isRecording || isProcessing) {
            Log.i(TAG, "DictationKeyboardService: Stopping transcription - keyboard hidden")
            DebugLog.i("DictationKeyboardService: Stopping transcription (keyboard hidden)")
            transcriptionManager?.stopListening()
            isRecording = false
            isProcessing = false
        }
    }

    /**
     * Computes the insets that the keyboard occupies.
     * This tells apps how much space the keyboard takes so they can resize content.
     *
     * We use the default implementation which properly calculates insets based on
     * the keyboard view's height. The system handles this correctly for both
     * regular apps and PWAs.
     */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        // Let the system handle inset calculation based on view height
        // Don't override with custom values - that breaks regular apps
    }

    private fun commitTextInternal(text: String): Boolean {
        val ic = currentInputConnection
        if (ic == null) {
            Log.e(TAG, "Cannot commit text - no input connection")
            DebugLog.e("DictationKeyboardService: No input connection for commit")
            return false
        }

        return try {
            val result = ic.commitText(text, 1)
            if (result) {
                Log.d(TAG, "Text committed successfully: ${text.take(50)}...")
                DebugLog.d("DictationKeyboardService: Committed ${text.length} chars")
            } else {
                Log.e(TAG, "commitText returned false")
                DebugLog.e("DictationKeyboardService: commitText returned false")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error committing text", e)
            DebugLog.e("DictationKeyboardService: Error committing - ${e.message}")
            false
        }
    }

    fun refreshStatus() {
        updateMicButton()
        updateLanguageButton()
    }

    /**
     * Refreshes the theme immediately. Called when user changes theme in settings.
     */
    fun refreshTheme() {
        DebugLog.i("DictationKeyboardService: Refreshing theme from settings change")
        applyTheme()
    }
}
