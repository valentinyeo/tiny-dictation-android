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
import android.view.ViewStub
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
import com.tinydictation.android.commands.VoiceCommandProcessor
import com.tinydictation.android.history.ClipboardHistoryManager
import com.tinydictation.android.history.TranscriptionHistoryManager
import com.tinydictation.android.transcription.TranscriptionManager
import com.tinydictation.android.util.DebugLog
import com.tinydictation.android.util.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

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

        /**
         * Pending text to paste when keyboard regains focus.
         * Used when paste is requested while keyboard is hidden (e.g., from HistoryActivity).
         */
        @Volatile
        var pendingPasteText: String? = null
    }

    private var keyboardView: View? = null
    private var micButton: FrameLayout? = null
    private var micIcon: ImageView? = null
    private var languageText: TextView? = null
    private var emojiPanelStub: ViewStub? = null
    private var emojiPanel: View? = null
    private var isEmojiPanelVisible = false
    private var isEmojiPanelInflated = false

    // Tabbed keyboard panel (5 tabs: ABC, 123, Shell, Clipboard, History)
    private var keyboardTabbedPanel: View? = null
    private var tabABC: TextView? = null
    private var tabNumpad: TextView? = null
    private var tabShell: TextView? = null
    private var tabClipboard: TextView? = null
    private var tabHistory: TextView? = null
    private var tabIndicator: View? = null
    private var qwertyPanel: View? = null
    private var cursorNumpadPanel: View? = null
    private var shellPanel: View? = null
    private var clipboardPanel: View? = null
    private var historyTabPanel: View? = null  // Named differently to avoid conflict with historyPanel
    private var isKeyboardPanelVisible = false
    private var currentKeyboardTab = 0  // 0=ABC, 1=123, 2=Shell, 3=Clipboard, 4=History

    // Lazy loading flags for new panels
    private var isShellPanelInflated = false
    private var isClipboardPanelInflated = false
    private var isHistoryTabPanelInflated = false

    private var isShiftActive = false
    private var isCapsLock = false
    private var isNumbersMode = false  // Numbers/symbols keyboard mode

    // Button references for expanded state indicators
    private var keyboardButton: FrameLayout? = null
    private var keyboardIcon: ImageView? = null
    private var emojiButton: FrameLayout? = null
    private var emojiIcon: ImageView? = null

    // Pulse animation for transcribing state
    private var pulseAnimator: ObjectAnimator? = null

    // Transcription management - keyboard owns its own TranscriptionManager
    private var transcriptionManager: TranscriptionManager? = null
    private var isRecording = false
    private var isProcessing = false
    private var isStandby = false  // True when in standby/hibernation mode

    // Clipboard history management
    private var clipboardHistoryManager: ClipboardHistoryManager? = null

    // Prediction management
    private var predictionManager: com.tinydictation.android.prediction.PredictionManager? = null
    private var prediction1: TextView? = null
    private var prediction2: TextView? = null
    private var prediction3: TextView? = null
    private var currentPredictions: List<com.tinydictation.android.prediction.Prediction> = emptyList()

    // Broadcast receiver for paste requests from HistoryActivity
    private val pasteTextReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_PASTE_TEXT) {
                val text = intent.getStringExtra(EXTRA_TEXT)
                if (!text.isNullOrEmpty()) {
                    Log.i(TAG, "Received paste request: ${text.take(50)}...")
                    DebugLog.i("DictationKeyboardService: Pasting ${text.length} chars from history")
                    // Try to paste immediately if input connection is available
                    if (currentInputConnection != null) {
                        commitTextInternal(text)
                    } else {
                        // Store for later - will paste when keyboard regains focus
                        Log.i(TAG, "No input connection, storing for pending paste")
                        DebugLog.i("DictationKeyboardService: Storing pending paste (keyboard hidden)")
                        pendingPasteText = text
                    }
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

    // Cursor key repeat handling - similar to backspace but without word deletion
    private val cursorHandler = Handler(Looper.getMainLooper())
    @Volatile private var isCursorKeyHeld = false
    private var cursorStartTime = 0L
    private var cursorKeyCode = 0  // The key code of the currently held cursor key
    private var cursorKeyCount = 0

    // Single runnable for cursor key repeat
    private val cursorRepeatRunnable = object : Runnable {
        override fun run() {
            if (!isCursorKeyHeld) {
                DebugLog.d("DictationKeyboardService: Cursor key released after $cursorKeyCount presses, stopping repeat")
                return
            }

            val holdDuration = System.currentTimeMillis() - cursorStartTime

            // Send the cursor key event
            sendDownUpKeyEvents(cursorKeyCode)
            cursorKeyCount++

            // Calculate accelerated delay (same as backspace)
            val accelerationProgress = (holdDuration.toFloat() / BACKSPACE_ACCELERATION_TIME).coerceIn(0f, 1f)
            val currentDelay = (BACKSPACE_REPEAT_DELAY_START -
                (BACKSPACE_REPEAT_DELAY_START - BACKSPACE_REPEAT_DELAY_MIN) * accelerationProgress).toLong()

            // Log every 10 presses
            if (cursorKeyCount % 10 == 0) {
                DebugLog.d("DictationKeyboardService: Cursor key count=$cursorKeyCount, delay=${currentDelay}ms, held=${holdDuration}ms")
            }

            cursorHandler.postDelayed(this, currentDelay)
        }
    }

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

        // Initialize and start clipboard history manager
        clipboardHistoryManager = ClipboardHistoryManager.getInstance(this)
        clipboardHistoryManager?.startListening()

        // Initialize prediction manager - auto-detect engine based on API key availability
        predictionManager = com.tinydictation.android.prediction.PredictionManager(this)
        CoroutineScope(Dispatchers.Main).launch {
            val prefs = com.tinydictation.android.util.PreferenceManager(this@DictationKeyboardService)
            // Use Groq if API key is configured, otherwise fall back to local ngram
            val engineId = if (prefs.hasGroqApiKey()) "groq" else "ngram"
            DebugLog.i("DictationKeyboardService: Using prediction engine '$engineId'")
            val result = predictionManager?.initialize(engineId)
            if (result?.isFailure == true && engineId == "groq") {
                // Fall back to ngram if Groq fails
                DebugLog.w("DictationKeyboardService: Groq engine failed, falling back to ngram")
                predictionManager?.initialize("ngram")
            }
        }
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
                    Handler(Looper.getMainLooper()).post {
                        // Check for voice commands first
                        val commandResult = VoiceCommandProcessor.process(
                            text,
                            currentInputConnection
                        ) { keyCode -> sendDownUpKeyEvents(keyCode) }

                        when (commandResult) {
                            is VoiceCommandProcessor.CommandResult.Executed -> {
                                DebugLog.i("DictationKeyboardService: Voice command executed for '$text'")
                                // Don't save commands to history
                            }
                            is VoiceCommandProcessor.CommandResult.Failed -> {
                                DebugLog.w("DictationKeyboardService: Voice command failed - ${commandResult.reason}")
                            }
                            is VoiceCommandProcessor.CommandResult.NotACommand -> {
                                // Normal text - apply word replacements and commit
                                val processedText = com.tinydictation.android.dictionary.WordReplacementManager
                                    .getInstance(this@DictationKeyboardService)
                                    .applyReplacements(text)
                                commitTextInternal(processedText + " ")

                                // Save to transcription history
                                val language = prefs.getLanguage()
                                TranscriptionHistoryManager.getInstance(this@DictationKeyboardService).save(text, language)
                                DebugLog.i("DictationKeyboardService: Saved transcription to history")
                            }
                        }
                    }
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
                            isStandby = false
                        }
                        TranscriptionManager.TranscriptionState.STANDBY -> {
                            isRecording = true
                            isProcessing = false
                            isStandby = true
                        }
                        TranscriptionManager.TranscriptionState.LISTENING -> {
                            isRecording = true
                            isProcessing = false
                            isStandby = false
                        }
                        TranscriptionManager.TranscriptionState.TRANSCRIBING -> {
                            isRecording = true
                            isProcessing = true
                            isStandby = false
                        }
                        TranscriptionManager.TranscriptionState.ERROR -> {
                            isRecording = false
                            isProcessing = false
                            isStandby = false
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

        // Stop clipboard listening
        clipboardHistoryManager?.stopListening()
        clipboardHistoryManager = null

        // Unregister broadcast receiver
        LocalBroadcastManager.getInstance(this).unregisterReceiver(pasteTextReceiver)

        instance = null
        longPressHandler?.removeCallbacksAndMessages(null)
        isBackspaceHeld = false
        backspaceHandler.removeCallbacksAndMessages(null)
        isCursorKeyHeld = false
        cursorHandler.removeCallbacksAndMessages(null)
        pulseAnimator?.cancel()
        pulseAnimator = null

        // Release prediction manager
        predictionManager?.release()
        predictionManager = null

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
            emojiPanelStub = keyboardView?.findViewById(R.id.emojiPanelStub)

            // Tabbed keyboard panel (5 tabs)
            keyboardTabbedPanel = keyboardView?.findViewById(R.id.keyboardTabbedPanel)
            tabABC = keyboardTabbedPanel?.findViewById(R.id.tabABC)
            tabNumpad = keyboardTabbedPanel?.findViewById(R.id.tabNumpad)
            tabShell = keyboardTabbedPanel?.findViewById(R.id.tabShell)
            tabClipboard = keyboardTabbedPanel?.findViewById(R.id.tabClipboard)
            tabHistory = keyboardTabbedPanel?.findViewById(R.id.tabHistory)
            tabIndicator = keyboardTabbedPanel?.findViewById(R.id.tabIndicator)
            qwertyPanel = keyboardTabbedPanel?.findViewById(R.id.qwertyPanel)
            cursorNumpadPanel = keyboardTabbedPanel?.findViewById(R.id.cursorNumpadPanel)
            // Shell, Clipboard, History panels are lazy-loaded via ViewStub

            keyboardButton = keyboardView?.findViewById(R.id.btnKeyboard)
            keyboardIcon = keyboardView?.findViewById(R.id.ivKeyboard)
            emojiButton = keyboardView?.findViewById(R.id.btnEmoji)
            emojiIcon = keyboardView?.findViewById(R.id.ivEmoji)

            setupButtonHandlers()
            // Note: Emoji panel is lazy-loaded on first use
            setupQwertyKeyboard()
            setupCursorNumpadPanel()
            setupKeyboardTabs()
            applyTheme()
            updateMicButton()
            updateLanguageButton()
            setupVersionDisplay()
            scrollDictationBarToDefault()

            keyboardView!!
        } catch (e: Exception) {
            Log.e(TAG, "Error creating keyboard view", e)
            val errorDetails = "Error: ${e.message}\n\n${e.stackTraceToString()}"
            DebugLog.e("DictationKeyboardService: Error creating view - ${e.message}\n${e.stackTraceToString()}")

            // Create error view with copy button
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 16, 16, 16)
                setBackgroundColor(android.graphics.Color.BLACK)

                // Error message
                addView(TextView(this@DictationKeyboardService).apply {
                    text = "Error: ${e.message}"
                    textSize = 12f
                    setTextColor(android.graphics.Color.RED)
                    setPadding(0, 0, 0, 16)
                })

                // Copy button
                addView(android.widget.Button(this@DictationKeyboardService).apply {
                    text = "Copy Error Details"
                    setOnClickListener {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        val clip = android.content.ClipData.newPlainText("Keyboard Error", errorDetails)
                        clipboard.setPrimaryClip(clip)
                        Toast.makeText(this@DictationKeyboardService, "Error copied to clipboard", Toast.LENGTH_SHORT).show()
                    }
                })
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
        applyButtonTheme(keyboardView?.findViewById(R.id.btnHistory), buttonColor, iconColor)
        applyButtonTheme(keyboardView?.findViewById(R.id.btnSpace), buttonColor, iconColor)
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

        // History button - shows keyboard panel and switches to History tab
        keyboardView?.findViewById<FrameLayout>(R.id.btnHistory)?.setOnClickListener {
            // Show keyboard panel if hidden, then switch to History tab
            if (!isKeyboardPanelVisible) {
                toggleKeyboardPanel()
            }
            switchToTab(4)  // History tab
        }

        // Enter button
        keyboardView?.findViewById<FrameLayout>(R.id.btnEnter)?.setOnClickListener {
            sendEnterKey()
        }

        // Space button - insert space
        keyboardView?.findViewById<FrameLayout>(R.id.btnSpace)?.setOnClickListener {
            currentInputConnection?.commitText(" ", 1)
        }

        // Emoji button - toggles emoji panel
        keyboardView?.findViewById<FrameLayout>(R.id.btnEmoji)?.setOnClickListener {
            toggleEmojiPanel()
        }

        // Keyboard button - toggles tabbed keyboard panel
        keyboardView?.findViewById<FrameLayout>(R.id.btnKeyboard)?.setOnClickListener {
            toggleKeyboardPanel()
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
            isStandby = false
            updateMicButton()

            // Brief delay then restart with new language
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    transcriptionManager?.startListening()
                    isRecording = true
                    isStandby = true
                    updateMicButton()
                    DebugLog.i("DictationKeyboardService: Restarted after language change")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to restart after language change", e)
                    DebugLog.e("DictationKeyboardService: Restart failed - ${e.message}")
                }
            }, 150)
        } else {
            // Not recording, just update the engine's language setting
            transcriptionManager?.setLanguage(newLang)
        }

        updateLanguageButton()
        Log.i(TAG, "Language changed to: $newLang")
    }

    private fun updateMicButton() {
        // Three visual states:
        // - Active (transcribing): Imperial red #ED2939
        // - Inactive (standby/hibernation): Darker imperial red #8B1C26
        // - Off: White/themed
        val color = when {
            isProcessing -> Color.parseColor("#ED2939")  // Imperial red - actively transcribing
            isStandby -> Color.parseColor("#8B1C26")  // Darker imperial red - standby
            isRecording -> Color.parseColor("#ED2939")  // Imperial red (legacy)
            else -> getThemedIconColor()  // White/themed - off
        }
        // Use imageTintList with a non-stateful ColorStateList to prevent color changes on press
        micIcon?.imageTintList = android.content.res.ColorStateList.valueOf(color)

        // No pulse animation - just static colors
        stopPulseAnimation(micIcon)
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

    /**
     * Updates the keyboard button visual state based on QWERTY panel visibility.
     * When expanded: shows up-arrow icon variant and uses primary color for active look.
     * When collapsed: shows normal icon with themed color.
     */
    private fun updateKeyboardButtonState() {
        val iconRes = if (isKeyboardPanelVisible) R.drawable.ic_keyboard_expanded else R.drawable.ic_keyboard
        keyboardIcon?.setImageResource(iconRes)

        // Apply active color (primary) when expanded, themed color when collapsed
        val iconColor = if (isKeyboardPanelVisible) {
            ContextCompat.getColor(this, R.color.primary)
        } else {
            getThemedIconColor()
        }
        keyboardIcon?.imageTintList = android.content.res.ColorStateList.valueOf(iconColor)
    }

    /**
     * Updates the emoji button visual state based on emoji panel visibility.
     * When expanded: shows up-arrow icon variant and uses primary color for active look.
     * When collapsed: shows normal icon with themed color.
     */
    private fun updateEmojiButtonState() {
        val iconRes = if (isEmojiPanelVisible) R.drawable.ic_emoji_expanded else R.drawable.ic_emoji
        emojiIcon?.setImageResource(iconRes)

        // Apply active color (primary) when expanded, themed color when collapsed
        val iconColor = if (isEmojiPanelVisible) {
            ContextCompat.getColor(this, R.color.primary)
        } else {
            getThemedIconColor()
        }
        emojiIcon?.imageTintList = android.content.res.ColorStateList.valueOf(iconColor)
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
     * Scrolls the dictation bar to center the MIC button.
     * Button order: Chevron | Keyboard | Emoji | Clipboard | Enter | MIC | Backspace | Language | SelectAll
     */
    private fun scrollDictationBarToDefault() {
        val scrollView = keyboardView?.findViewById<HorizontalScrollView>(R.id.dictationBarScrollView)
        val micButton = keyboardView?.findViewById<FrameLayout>(R.id.btnKeyboardMic)

        scrollView?.post {
            micButton?.let { mic ->
                // Calculate scroll position to center MIC button
                val scrollViewWidth = scrollView.width
                val micLeft = mic.left
                val micWidth = mic.width
                val micCenter = micLeft + micWidth / 2
                val scrollTo = micCenter - scrollViewWidth / 2
                scrollView.smoothScrollTo(scrollTo.coerceAtLeast(0), 0)
            }
        }
    }

    /**
     * Sets up the emoji picker panel.
     */
    private fun setupEmojiPanel() {
        emojiPanel?.let { panel ->
            val emojiPicker = panel.findViewById<androidx.emoji2.emojipicker.EmojiPickerView>(R.id.emojiPicker)
            emojiPicker?.setOnEmojiPickedListener { emojiViewItem ->
                currentInputConnection?.commitText(emojiViewItem.emoji, 1)
                Log.d(TAG, "Emoji inserted: ${emojiViewItem.emoji}")
            }
            Log.i(TAG, "Emoji panel initialized")
            DebugLog.i("DictationKeyboardService: Emoji panel initialized")
        }
    }

    /**
     * Toggles the emoji panel visibility.
     * Hides keyboard panel if visible.
     * Lazy-loads the panel on first use for fast keyboard startup.
     */
    private fun toggleEmojiPanel() {
        // Hide keyboard panel if showing
        if (isKeyboardPanelVisible) {
            isKeyboardPanelVisible = false
            keyboardTabbedPanel?.visibility = View.GONE
            updateKeyboardButtonState()
        }

        // Lazy inflate the panel on first use
        if (!isEmojiPanelInflated) {
            DebugLog.i("DictationKeyboardService: Lazy-inflating emoji panel...")
            emojiPanel = emojiPanelStub?.inflate()
            isEmojiPanelInflated = true
            setupEmojiPanel()
        }

        isEmojiPanelVisible = !isEmojiPanelVisible
        emojiPanel?.visibility = if (isEmojiPanelVisible) View.VISIBLE else View.GONE
        updateEmojiButtonState()
        Log.d(TAG, "Emoji panel visibility: $isEmojiPanelVisible")
        DebugLog.i("DictationKeyboardService: Emoji panel ${if (isEmojiPanelVisible) "shown" else "hidden"}")
    }

    /**
     * Toggles the tabbed keyboard panel visibility.
     * Hides emoji panel if visible.
     */
    private fun toggleKeyboardPanel() {
        // Hide emoji panel if showing
        if (isEmojiPanelVisible) {
            isEmojiPanelVisible = false
            emojiPanel?.visibility = View.GONE
            updateEmojiButtonState()
        }

        isKeyboardPanelVisible = !isKeyboardPanelVisible
        keyboardTabbedPanel?.visibility = if (isKeyboardPanelVisible) View.VISIBLE else View.GONE
        updateKeyboardButtonState()

        // Reset to letters mode when closing the keyboard
        if (!isKeyboardPanelVisible && isNumbersMode) {
            isNumbersMode = false
            isShiftActive = false
            updateKeyboardMode()
        }

        // Update predictions when showing QWERTY panel, clear when hiding
        if (isKeyboardPanelVisible && currentKeyboardTab == 0) {
            updatePredictions()
        } else if (!isKeyboardPanelVisible) {
            displayPredictions(emptyList())
        }

        Log.d(TAG, "Keyboard panel visibility: $isKeyboardPanelVisible")
        DebugLog.i("DictationKeyboardService: Keyboard panel ${if (isKeyboardPanelVisible) "shown" else "hidden"}")
    }

    /**
     * Sets up the keyboard tab bar and swipe gestures.
     */
    private fun setupKeyboardTabs() {
        // Set initial visibility - QWERTY visible, others hidden
        qwertyPanel?.visibility = View.VISIBLE
        cursorNumpadPanel?.visibility = View.GONE
        shellPanel?.visibility = View.GONE
        clipboardPanel?.visibility = View.GONE
        historyTabPanel?.visibility = View.GONE

        // Tab click listeners for all 5 tabs
        tabABC?.setOnClickListener { switchToTab(0) }
        tabNumpad?.setOnClickListener { switchToTab(1) }
        tabShell?.setOnClickListener { switchToTab(2) }
        tabClipboard?.setOnClickListener { switchToTab(3) }
        tabHistory?.setOnClickListener { switchToTab(4) }

        // Set up swipe gesture detection on transparent overlay
        var startX = 0f
        var startY = 0f
        var startTime = 0L
        var isHorizontalSwipe = false
        var isVerticalScroll = false
        var hasDeterminedGesture = false

        val swipeOverlay = keyboardTabbedPanel?.findViewById<View>(R.id.swipeOverlay)
        swipeOverlay?.setOnTouchListener { view, event ->
            // Get the target panel for passing through events
            val targetPanel = when (currentKeyboardTab) {
                0 -> qwertyPanel
                1 -> cursorNumpadPanel
                2 -> shellPanel
                3 -> clipboardPanel
                4 -> historyTabPanel
                else -> null
            }

            when (event.action) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    startX = event.x
                    startY = event.y
                    startTime = System.currentTimeMillis()
                    isHorizontalSwipe = false
                    isVerticalScroll = false
                    hasDeterminedGesture = false
                    // Pass down event to target panel
                    targetPanel?.dispatchTouchEvent(event)
                    true
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    val diffX = kotlin.math.abs(event.x - startX)
                    val diffY = kotlin.math.abs(event.y - startY)

                    // Determine gesture type once we have enough movement
                    if (!hasDeterminedGesture && (diffX > 20 || diffY > 20)) {
                        hasDeterminedGesture = true
                        if (diffX > diffY * 1.2) {
                            // Horizontal swipe - we handle this
                            isHorizontalSwipe = true
                            // Cancel the touch on target panel
                            val cancelEvent = android.view.MotionEvent.obtain(event)
                            cancelEvent.action = android.view.MotionEvent.ACTION_CANCEL
                            targetPanel?.dispatchTouchEvent(cancelEvent)
                            cancelEvent.recycle()
                        } else {
                            // Vertical scroll - pass to target panel
                            isVerticalScroll = true
                        }
                    }

                    // If vertical scroll, pass move events to target
                    if (isVerticalScroll) {
                        targetPanel?.dispatchTouchEvent(event)
                    }
                    true
                }
                android.view.MotionEvent.ACTION_UP -> {
                    val diffX = event.x - startX
                    val diffTime = System.currentTimeMillis() - startTime

                    when {
                        // Horizontal swipe - navigate between tabs
                        isHorizontalSwipe && diffTime < 500 && kotlin.math.abs(diffX) > 80 -> {
                            if (diffX > 0 && currentKeyboardTab > 0) {
                                switchToTab(currentKeyboardTab - 1)
                                Log.d(TAG, "Swiped right to tab $currentKeyboardTab")
                            } else if (diffX < 0 && currentKeyboardTab < 4) {
                                switchToTab(currentKeyboardTab + 1)
                                Log.d(TAG, "Swiped left to tab $currentKeyboardTab")
                            }
                        }
                        // Vertical scroll or tap - pass through
                        else -> {
                            targetPanel?.dispatchTouchEvent(event)
                        }
                    }
                    true
                }
                android.view.MotionEvent.ACTION_CANCEL -> {
                    isHorizontalSwipe = false
                    isVerticalScroll = false
                    hasDeterminedGesture = false
                    targetPanel?.dispatchTouchEvent(event)
                    true
                }
                else -> {
                    targetPanel?.dispatchTouchEvent(event)
                    true
                }
            }
        }

        updateTabIndicator()
    }

    /**
     * Switches to the specified keyboard tab, inflating lazy panels if needed.
     */
    private fun switchToTab(tab: Int) {
        if (tab == currentKeyboardTab) return
        currentKeyboardTab = tab

        // Lazy-inflate new panels if needed
        when (tab) {
            2 -> inflateShellPanelIfNeeded()
            3 -> inflateClipboardPanelIfNeeded()
            4 -> inflateHistoryTabPanelIfNeeded()
        }

        updateKeyboardTabContent()

        // Update predictions when switching to QWERTY tab
        if (tab == 0) {
            updatePredictions()
        }
    }

    private fun inflateShellPanelIfNeeded() {
        if (!isShellPanelInflated) {
            val stub = keyboardTabbedPanel?.findViewById<ViewStub>(R.id.shellPanelStub)
            shellPanel = stub?.inflate()
            isShellPanelInflated = true
            setupShellPanel()
            DebugLog.i("DictationKeyboardService: Shell panel inflated")
        }
    }

    private fun inflateClipboardPanelIfNeeded() {
        if (!isClipboardPanelInflated) {
            val stub = keyboardTabbedPanel?.findViewById<ViewStub>(R.id.clipboardPanelStub)
            clipboardPanel = stub?.inflate()
            isClipboardPanelInflated = true
            setupClipboardPanel()
            DebugLog.i("DictationKeyboardService: Clipboard panel inflated")
        }
    }

    private fun inflateHistoryTabPanelIfNeeded() {
        if (!isHistoryTabPanelInflated) {
            try {
                val stub = keyboardTabbedPanel?.findViewById<ViewStub>(R.id.historyPanelStub)
                if (stub == null) {
                    DebugLog.e("DictationKeyboardService: historyPanelStub not found!")
                    return
                }
                historyTabPanel = stub.inflate()
                isHistoryTabPanelInflated = true
                setupHistoryTabPanel()
                DebugLog.i("DictationKeyboardService: History tab panel inflated")
            } catch (e: Exception) {
                DebugLog.e("DictationKeyboardService: Failed to inflate history panel: ${e.message}")
                Log.e(TAG, "Failed to inflate history panel", e)
            }
        }
    }

    /**
     * Updates the visible keyboard tab content for all 5 tabs.
     */
    private fun updateKeyboardTabContent() {
        // Hide all panels first
        qwertyPanel?.visibility = View.GONE
        cursorNumpadPanel?.visibility = View.GONE
        shellPanel?.visibility = View.GONE
        clipboardPanel?.visibility = View.GONE
        historyTabPanel?.visibility = View.GONE

        // Show the selected panel
        when (currentKeyboardTab) {
            0 -> qwertyPanel?.visibility = View.VISIBLE
            1 -> cursorNumpadPanel?.visibility = View.VISIBLE
            2 -> shellPanel?.visibility = View.VISIBLE
            3 -> clipboardPanel?.visibility = View.VISIBLE
            4 -> historyTabPanel?.visibility = View.VISIBLE
        }

        updateTabIndicator()
        DebugLog.d("DictationKeyboardService: Switched to keyboard tab $currentKeyboardTab")
    }

    /**
     * Updates the tab indicator position and colors for 5 tabs.
     */
    private fun updateTabIndicator() {
        val primaryColor = androidx.core.content.ContextCompat.getColor(this, R.color.primary)
        val inactiveColor = androidx.core.content.ContextCompat.getColor(this, R.color.on_surface_variant)

        // Update tab colors - active tab gets primary color
        val tabs = listOf(tabABC, tabNumpad, tabShell, tabClipboard, tabHistory)
        tabs.forEachIndexed { index, tab ->
            tab?.setTextColor(if (index == currentKeyboardTab) primaryColor else inactiveColor)
        }

        // Update indicator position (1/5 width, positioned under selected tab)
        tabIndicator?.post {
            val parentWidth = (tabIndicator?.parent as? View)?.width ?: 0
            val indicatorWidth = parentWidth / 5
            tabIndicator?.layoutParams = tabIndicator?.layoutParams?.apply {
                width = indicatorWidth
            }
            tabIndicator?.translationX = (currentKeyboardTab * indicatorWidth).toFloat()
        }
    }

    /**
     * Sets up the QWERTY keyboard key handlers.
     */
    private fun setupQwertyKeyboard() {
        qwertyPanel ?: return

        // Set up prediction strip
        prediction1 = qwertyPanel?.findViewById(R.id.prediction1)
        prediction2 = qwertyPanel?.findViewById(R.id.prediction2)
        prediction3 = qwertyPanel?.findViewById(R.id.prediction3)

        // Prediction tap handlers
        prediction1?.setOnClickListener { onPredictionTapped(0) }
        prediction2?.setOnClickListener { onPredictionTapped(1) }
        prediction3?.setOnClickListener { onPredictionTapped(2) }

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

        for ((id, _) in letterKeys) {
            val button = qwertyPanel?.findViewById<android.widget.Button>(id)
            button?.setOnClickListener {
                // Use the button's displayed text (works for both letters and numbers/symbols)
                val textToCommit = button.text.toString()
                currentInputConnection?.commitText(textToCommit, 1)

                // Turn off shift after one character (unless caps lock, and only in letters mode)
                if (!isNumbersMode && isShiftActive && !isCapsLock) {
                    isShiftActive = false
                    updateShiftKeyDisplay()
                }

                // Update predictions after typing
                updatePredictions()
            }
        }

        // Punctuation keys
        qwertyPanel?.findViewById<android.widget.Button>(R.id.key_comma)?.setOnClickListener {
            currentInputConnection?.commitText(",", 1)
            updatePredictions()
        }
        qwertyPanel?.findViewById<android.widget.Button>(R.id.key_period)?.setOnClickListener {
            currentInputConnection?.commitText(".", 1)
            updatePredictions()
        }

        // Space bar - triggers next-word prediction
        qwertyPanel?.findViewById<android.widget.Button>(R.id.key_space)?.setOnClickListener {
            currentInputConnection?.commitText(" ", 1)
            updatePredictions()
        }

        // Enter key
        qwertyPanel?.findViewById<android.widget.Button>(R.id.key_enter)?.setOnClickListener {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }

        // Backspace key - with hold-to-repeat (same behavior as dictation bar backspace)
        qwertyPanel?.findViewById<android.widget.Button>(R.id.key_backspace)?.setOnTouchListener { _, event ->
            handleBackspaceTouch(event)
        }

        // Shift key - tap for shift, double-tap for caps lock (or symbols toggle in numbers mode)
        val shiftButton = qwertyPanel?.findViewById<android.widget.Button>(R.id.key_shift)
        var lastShiftTapTime = 0L
        shiftButton?.setOnClickListener {
            if (isNumbersMode) {
                // In numbers mode, shift toggles secondary symbols
                isShiftActive = !isShiftActive
                updateSymbolsDisplay()
            } else {
                // In letters mode, normal shift/caps behavior
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
        }

        // 123/ABC key - toggle between letters and numbers/symbols
        qwertyPanel?.findViewById<android.widget.Button>(R.id.key_numbers)?.setOnClickListener {
            toggleNumbersMode()
        }
    }

    /**
     * Request updated predictions based on current input.
     */
    private fun updatePredictions() {
        val textBefore = currentInputConnection?.getTextBeforeCursor(100, 0)?.toString() ?: ""

        predictionManager?.requestPredictions(textBefore) { predictions ->
            currentPredictions = predictions
            displayPredictions(predictions)
        }
    }

    /**
     * Display predictions in the prediction strip.
     */
    private fun displayPredictions(predictions: List<com.tinydictation.android.prediction.Prediction>) {
        prediction1?.text = predictions.getOrNull(0)?.word ?: ""
        prediction2?.text = predictions.getOrNull(1)?.word ?: ""
        prediction3?.text = predictions.getOrNull(2)?.word ?: ""
    }

    /**
     * Handle tap on a prediction to insert it.
     */
    private fun onPredictionTapped(index: Int) {
        val prediction = currentPredictions.getOrNull(index) ?: return
        val ic = currentInputConnection ?: return

        if (prediction.isCompletion) {
            // Complete the partial word - delete it first, then insert the full word
            val textBefore = ic.getTextBeforeCursor(50, 0)?.toString() ?: ""
            val partialWord = textBefore.split(Regex("\\s+")).lastOrNull() ?: ""

            if (partialWord.isNotEmpty()) {
                ic.deleteSurroundingText(partialWord.length, 0)
            }
            ic.commitText(prediction.word + " ", 1)
        } else {
            // Insert next word
            ic.commitText(prediction.word + " ", 1)
        }

        // Let the engine learn from this selection
        val textBefore = ic.getTextBeforeCursor(50, 0)?.toString() ?: ""
        val words = textBefore.split(Regex("\\s+")).filter { it.isNotBlank() }.takeLast(3)
        predictionManager?.onWordsConfirmed(words)

        // Clear and refresh predictions
        updatePredictions()

        DebugLog.d("DictationKeyboardService: Inserted prediction '${prediction.word}' (completion=${prediction.isCompletion})")
    }

    /**
     * Sets up the cursor/numpad panel key handlers.
     */
    private fun setupCursorNumpadPanel() {
        cursorNumpadPanel ?: return

        // Number pad keys - commit text directly
        val numpadKeys = mapOf(
            R.id.numpad_0 to "0", R.id.numpad_1 to "1", R.id.numpad_2 to "2",
            R.id.numpad_3 to "3", R.id.numpad_4 to "4", R.id.numpad_5 to "5",
            R.id.numpad_6 to "6", R.id.numpad_7 to "7", R.id.numpad_8 to "8",
            R.id.numpad_9 to "9", R.id.numpad_dot to ".", R.id.numpad_equals to "="
        )

        for ((id, char) in numpadKeys) {
            cursorNumpadPanel?.findViewById<android.widget.Button>(id)?.setOnClickListener {
                currentInputConnection?.commitText(char, 1)
            }
        }

        // Cursor/navigation keys - with hold-to-repeat (same acceleration as backspace)
        cursorNumpadPanel?.findViewById<android.widget.Button>(R.id.cursor_up)?.setOnTouchListener { _, event ->
            handleCursorKeyTouch(event, KeyEvent.KEYCODE_DPAD_UP)
        }
        cursorNumpadPanel?.findViewById<android.widget.Button>(R.id.cursor_down)?.setOnTouchListener { _, event ->
            handleCursorKeyTouch(event, KeyEvent.KEYCODE_DPAD_DOWN)
        }
        cursorNumpadPanel?.findViewById<android.widget.Button>(R.id.cursor_left)?.setOnTouchListener { _, event ->
            handleCursorKeyTouch(event, KeyEvent.KEYCODE_DPAD_LEFT)
        }
        cursorNumpadPanel?.findViewById<android.widget.Button>(R.id.cursor_right)?.setOnTouchListener { _, event ->
            handleCursorKeyTouch(event, KeyEvent.KEYCODE_DPAD_RIGHT)
        }

        // Navigation keys
        cursorNumpadPanel?.findViewById<android.widget.Button>(R.id.cursor_home)?.setOnClickListener {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_MOVE_HOME)
        }
        cursorNumpadPanel?.findViewById<android.widget.Button>(R.id.cursor_end)?.setOnClickListener {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_MOVE_END)
        }
        cursorNumpadPanel?.findViewById<android.widget.Button>(R.id.cursor_pgup)?.setOnClickListener {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_PAGE_UP)
        }
        cursorNumpadPanel?.findViewById<android.widget.Button>(R.id.cursor_pgdn)?.setOnClickListener {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_PAGE_DOWN)
        }

        // Additional keys
        cursorNumpadPanel?.findViewById<android.widget.Button>(R.id.cursor_tab)?.setOnClickListener {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_TAB)
        }
        cursorNumpadPanel?.findViewById<android.widget.Button>(R.id.cursor_del)?.setOnClickListener {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_FORWARD_DEL)
        }
        // Backspace - with hold-to-repeat (same behavior as dictation bar backspace)
        cursorNumpadPanel?.findViewById<android.widget.Button>(R.id.cursor_bksp)?.setOnTouchListener { _, event ->
            handleBackspaceTouch(event)
        }
        cursorNumpadPanel?.findViewById<android.widget.Button>(R.id.cursor_esc)?.setOnClickListener {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ESCAPE)
        }
        cursorNumpadPanel?.findViewById<android.widget.Button>(R.id.cursor_space)?.setOnClickListener {
            currentInputConnection?.commitText(" ", 1)
        }
        cursorNumpadPanel?.findViewById<android.widget.Button>(R.id.cursor_enter)?.setOnClickListener {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    /**
     * Sets up the Shell commands panel with terminal control keys.
     */
    private fun setupShellPanel() {
        shellPanel ?: return

        // Map button tags to actions - Ctrl combos send special key events
        val ctrlCombos = mapOf(
            "ctrl_c" to 'c', "ctrl_u" to 'u', "ctrl_z" to 'z', "ctrl_d" to 'd',
            "ctrl_l" to 'l', "ctrl_a" to 'a', "ctrl_e" to 'e', "ctrl_k" to 'k',
            "ctrl_r" to 'r'
        )

        // Navigation keys
        val navKeys = mapOf(
            "up" to KeyEvent.KEYCODE_DPAD_UP,
            "down" to KeyEvent.KEYCODE_DPAD_DOWN,
            "left" to KeyEvent.KEYCODE_DPAD_LEFT,
            "right" to KeyEvent.KEYCODE_DPAD_RIGHT,
            "home" to KeyEvent.KEYCODE_MOVE_HOME,
            "end" to KeyEvent.KEYCODE_MOVE_END,
            "pgup" to KeyEvent.KEYCODE_PAGE_UP,
            "pgdn" to KeyEvent.KEYCODE_PAGE_DOWN,
            "tab" to KeyEvent.KEYCODE_TAB,
            "esc" to KeyEvent.KEYCODE_ESCAPE
        )

        // Set up all buttons by iterating through shell panel children
        fun setupButton(button: android.widget.Button) {
            val tag = button.tag as? String ?: return

            button.setOnClickListener {
                when {
                    tag.startsWith("ctrl_") -> {
                        // Send Ctrl+key combination
                        val char = ctrlCombos[tag] ?: return@setOnClickListener
                        sendCtrlKey(char)
                    }
                    tag == "shift_tab" -> {
                        // Send Shift+Tab
                        sendShiftTab()
                    }
                    navKeys.containsKey(tag) -> {
                        // Send navigation key
                        sendDownUpKeyEvents(navKeys[tag]!!)
                    }
                    else -> {
                        // Send as literal text (symbols)
                        currentInputConnection?.commitText(tag, 1)
                    }
                }
            }
        }

        // Find all buttons and set them up
        val shellPanelLayout = shellPanel as? android.view.ViewGroup ?: return
        for (i in 0 until shellPanelLayout.childCount) {
            val row = shellPanelLayout.getChildAt(i) as? android.view.ViewGroup ?: continue
            for (j in 0 until row.childCount) {
                val button = row.getChildAt(j) as? android.widget.Button ?: continue
                setupButton(button)
            }
        }

        DebugLog.i("DictationKeyboardService: Shell panel set up")
    }

    /**
     * Sends a Ctrl+key combination.
     */
    private fun sendCtrlKey(char: Char) {
        val keyCode = when (char) {
            'a' -> KeyEvent.KEYCODE_A
            'b' -> KeyEvent.KEYCODE_B
            'c' -> KeyEvent.KEYCODE_C
            'd' -> KeyEvent.KEYCODE_D
            'e' -> KeyEvent.KEYCODE_E
            'k' -> KeyEvent.KEYCODE_K
            'l' -> KeyEvent.KEYCODE_L
            'r' -> KeyEvent.KEYCODE_R
            'u' -> KeyEvent.KEYCODE_U
            'z' -> KeyEvent.KEYCODE_Z
            else -> return
        }

        val now = android.os.SystemClock.uptimeMillis()
        val downEvent = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, KeyEvent.META_CTRL_ON)
        val upEvent = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, KeyEvent.META_CTRL_ON)
        currentInputConnection?.sendKeyEvent(downEvent)
        currentInputConnection?.sendKeyEvent(upEvent)
    }

    /**
     * Sends Shift+Tab key combination.
     */
    private fun sendShiftTab() {
        val now = android.os.SystemClock.uptimeMillis()
        val downEvent = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 0, KeyEvent.META_SHIFT_ON)
        val upEvent = KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB, 0, KeyEvent.META_SHIFT_ON)
        currentInputConnection?.sendKeyEvent(downEvent)
        currentInputConnection?.sendKeyEvent(upEvent)
    }

    /**
     * Sets up the Clipboard panel with clipboard history.
     */
    private fun setupClipboardPanel() {
        try {
            clipboardPanel ?: return

            val recyclerView = clipboardPanel?.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.clipboardRecyclerView)
            val emptyState = clipboardPanel?.findViewById<View>(R.id.clipboardEmptyState)

            if (recyclerView == null) {
                DebugLog.e("DictationKeyboardService: clipboardRecyclerView not found!")
                return
            }

            // Set up GridLayoutManager with 2 columns
            recyclerView.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 2)

            // Load clipboard history and set up adapter
            loadClipboardContent(recyclerView, emptyState)

            DebugLog.i("DictationKeyboardService: Clipboard panel set up")
        } catch (e: Exception) {
            DebugLog.e("DictationKeyboardService: Failed to setup clipboard panel: ${e.message}")
            Log.e(TAG, "Failed to setup clipboard panel", e)
        }
    }

    /**
     * Loads clipboard history into the Clipboard tab RecyclerView.
     */
    private fun loadClipboardContent(recyclerView: androidx.recyclerview.widget.RecyclerView, emptyState: View?) {
        val clipboardManager = ClipboardHistoryManager.getInstance(this)
        // Returns pinned first, then by recency
        val entries = clipboardManager.getAll()

        if (entries.isEmpty()) {
            emptyState?.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyState?.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE

            recyclerView.adapter = ClipboardTabAdapter(
                entries = entries,
                onItemClick = { entry ->
                    // Tap to paste directly
                    commitTextInternal(entry.text)
                },
                onPinToggle = { entry ->
                    clipboardManager.togglePin(entry.id)
                    refreshClipboardPanel()
                    val message = if (entry.isPinned) R.string.item_unpinned else R.string.item_pinned
                    android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
                },
                onItemDelete = { entry ->
                    clipboardManager.delete(entry.id)
                    refreshClipboardPanel()
                }
            )
        }
    }

    /**
     * Refreshes the clipboard panel content.
     */
    private fun refreshClipboardPanel() {
        val recyclerView = clipboardPanel?.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.clipboardRecyclerView)
        val emptyState = clipboardPanel?.findViewById<View>(R.id.clipboardEmptyState)
        recyclerView?.let { loadClipboardContent(it, emptyState) }
    }

    /**
     * Adapter for Clipboard tab RecyclerView with pin toggle support.
     */
    private inner class ClipboardTabAdapter(
        private val entries: List<com.tinydictation.android.history.ClipboardEntry>,
        private val onItemClick: (com.tinydictation.android.history.ClipboardEntry) -> Unit,
        private val onPinToggle: (com.tinydictation.android.history.ClipboardEntry) -> Unit,
        private val onItemDelete: (com.tinydictation.android.history.ClipboardEntry) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<ClipboardTabAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            // Use themed context for Material components
            val themedContext = android.view.ContextThemeWrapper(parent.context, R.style.Theme_TinyDictation)
            val inflater = android.view.LayoutInflater.from(themedContext)
            val view = inflater.inflate(R.layout.item_clipboard, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(entries[position])
        }

        override fun getItemCount(): Int = entries.size

        inner class ViewHolder(itemView: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView) {
            private val tvTimestamp: android.widget.TextView? = itemView.findViewById(R.id.tvTimestamp)
            private val tvText: android.widget.TextView? = itemView.findViewById(R.id.tvText)
            private val ivPinned: android.widget.ImageView? = itemView.findViewById(R.id.ivPinned)

            fun bind(entry: com.tinydictation.android.history.ClipboardEntry) {
                tvTimestamp?.text = formatTimestamp(entry.timestamp)
                tvText?.text = entry.text

                // Pin icon state - filled when pinned, outline when not
                if (entry.isPinned) {
                    ivPinned?.setImageResource(R.drawable.ic_pin)
                    ivPinned?.setColorFilter(androidx.core.content.ContextCompat.getColor(itemView.context, R.color.primary))
                } else {
                    ivPinned?.setImageResource(R.drawable.ic_pin_outline)
                    ivPinned?.setColorFilter(androidx.core.content.ContextCompat.getColor(itemView.context, R.color.on_surface_variant))
                }

                // Tap item to paste
                itemView.setOnClickListener { onItemClick(entry) }

                // Tap pin icon to toggle pin
                ivPinned?.setOnClickListener { onPinToggle(entry) }

                // Long-press for options menu
                itemView.setOnLongClickListener { view ->
                    showClipboardItemMenu(view, entry)
                    true
                }
            }

            private fun showClipboardItemMenu(anchor: View, entry: com.tinydictation.android.history.ClipboardEntry) {
                val popup = android.widget.PopupMenu(anchor.context, anchor)
                popup.menu.add(if (entry.isPinned) R.string.unpin else R.string.pin)
                popup.menu.add(R.string.delete)

                popup.setOnMenuItemClickListener { item ->
                    when (item.title) {
                        anchor.context.getString(R.string.pin),
                        anchor.context.getString(R.string.unpin) -> {
                            onPinToggle(entry)
                            true
                        }
                        anchor.context.getString(R.string.delete) -> {
                            onItemDelete(entry)
                            true
                        }
                        else -> false
                    }
                }
                popup.show()
            }

            private fun formatTimestamp(timestamp: Long): String {
                val date = java.util.Date(timestamp)
                val now = java.util.Calendar.getInstance()
                val then = java.util.Calendar.getInstance().apply { time = date }

                return when {
                    // Today - just show time
                    now.get(java.util.Calendar.DATE) == then.get(java.util.Calendar.DATE) &&
                    now.get(java.util.Calendar.MONTH) == then.get(java.util.Calendar.MONTH) &&
                    now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) -> {
                        java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(date)
                    }
                    // Yesterday
                    now.get(java.util.Calendar.DATE) - then.get(java.util.Calendar.DATE) == 1 &&
                    now.get(java.util.Calendar.MONTH) == then.get(java.util.Calendar.MONTH) &&
                    now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) -> {
                        "Yest ${java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(date)}"
                    }
                    // Same year
                    now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) -> {
                        java.text.SimpleDateFormat("M/d h:mm a", java.util.Locale.getDefault()).format(date)
                    }
                    // Different year
                    else -> {
                        java.text.SimpleDateFormat("M/d/yy", java.util.Locale.getDefault()).format(date)
                    }
                }
            }
        }
    }

    /**
     * Sets up the History tab panel with transcription history.
     */
    private fun setupHistoryTabPanel() {
        try {
            historyTabPanel ?: return

            val recyclerView = historyTabPanel?.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.historyRecyclerView)
            val emptyState = historyTabPanel?.findViewById<View>(R.id.historyEmptyState)

            if (recyclerView == null) {
                DebugLog.e("DictationKeyboardService: historyRecyclerView not found!")
                return
            }

            // Set up GridLayoutManager with 2 columns
            recyclerView.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 2)

            // Load history and set up adapter
            loadHistoryTabContent(recyclerView, emptyState)

            DebugLog.i("DictationKeyboardService: History tab panel set up")
        } catch (e: Exception) {
            DebugLog.e("DictationKeyboardService: Failed to setup history panel: ${e.message}")
            Log.e(TAG, "Failed to setup history panel", e)
        }
    }

    /**
     * Loads transcription history into the History tab RecyclerView.
     * Newest entries are shown first (top left).
     */
    private fun loadHistoryTabContent(recyclerView: androidx.recyclerview.widget.RecyclerView, emptyState: View?) {
        val historyManager = com.tinydictation.android.history.TranscriptionHistoryManager.getInstance(this)
        // Sort by timestamp descending (newest first) - scroll down to see older
        val entries = historyManager.getAll().sortedByDescending { it.timestamp }

        if (entries.isEmpty()) {
            emptyState?.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyState?.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE

            recyclerView.adapter = HistoryTabAdapter(entries) { entry ->
                // Tap to paste directly
                commitTextInternal(entry.text)
            }
            // Start at position 0 which is now the newest item
        }
    }

    /**
     * Simple adapter for History tab RecyclerView.
     */
    private inner class HistoryTabAdapter(
        private val entries: List<com.tinydictation.android.history.TranscriptionEntry>,
        private val onItemClick: (com.tinydictation.android.history.TranscriptionEntry) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<HistoryTabAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            // Use themed context for Material components
            val themedContext = android.view.ContextThemeWrapper(parent.context, R.style.Theme_TinyDictation)
            val inflater = android.view.LayoutInflater.from(themedContext)
            val view = inflater.inflate(R.layout.item_history, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(entries[position])
        }

        override fun getItemCount(): Int = entries.size

        inner class ViewHolder(itemView: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView) {
            // itemView IS the root element - use safe access
            private val tvTimestamp: android.widget.TextView? = itemView.findViewById(R.id.tvTimestamp)
            private val tvLanguage: android.widget.TextView? = itemView.findViewById(R.id.tvLanguage)
            private val tvText: android.widget.TextView? = itemView.findViewById(R.id.tvText)

            fun bind(entry: com.tinydictation.android.history.TranscriptionEntry) {
                tvTimestamp?.text = formatTimestamp(entry.timestamp)
                tvLanguage?.text = entry.language.uppercase()
                tvText?.text = entry.text

                // Set click listener on the entire item view
                itemView.setOnClickListener { onItemClick(entry) }
            }

            private fun formatTimestamp(timestamp: Long): String {
                val date = java.util.Date(timestamp)
                val now = java.util.Calendar.getInstance()
                val then = java.util.Calendar.getInstance().apply { time = date }

                return when {
                    // Today - just show time
                    now.get(java.util.Calendar.DATE) == then.get(java.util.Calendar.DATE) &&
                    now.get(java.util.Calendar.MONTH) == then.get(java.util.Calendar.MONTH) &&
                    now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) -> {
                        java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(date)
                    }
                    // Yesterday
                    now.get(java.util.Calendar.DATE) - then.get(java.util.Calendar.DATE) == 1 &&
                    now.get(java.util.Calendar.MONTH) == then.get(java.util.Calendar.MONTH) &&
                    now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) -> {
                        "Yest ${java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(date)}"
                    }
                    // Same year
                    now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) -> {
                        java.text.SimpleDateFormat("M/d h:mm a", java.util.Locale.getDefault()).format(date)
                    }
                    // Different year
                    else -> {
                        java.text.SimpleDateFormat("M/d/yy", java.util.Locale.getDefault()).format(date)
                    }
                }
            }
        }
    }

    /**
     * Toggles between QWERTY letters and numbers/symbols mode.
     */
    private fun toggleNumbersMode() {
        isNumbersMode = !isNumbersMode
        updateKeyboardMode()
        DebugLog.d("DictationKeyboardService: Numbers mode: $isNumbersMode")
    }

    /**
     * Updates all keyboard keys based on current mode (letters vs numbers/symbols).
     */
    private fun updateKeyboardMode() {
        // Numbers/symbols mapping for each key position
        // Row 1: 1 2 3 4 5 6 7 8 9 0
        // Row 2: @ # $ % & - + ( )
        // Row 3: ! " ' : ; / ? Backspace
        val numbersRow1 = mapOf(
            R.id.key_q to "1", R.id.key_w to "2", R.id.key_e to "3", R.id.key_r to "4",
            R.id.key_t to "5", R.id.key_y to "6", R.id.key_u to "7", R.id.key_i to "8",
            R.id.key_o to "9", R.id.key_p to "0"
        )
        val numbersRow2 = mapOf(
            R.id.key_a to "@", R.id.key_s to "#", R.id.key_d to "$", R.id.key_f to "%",
            R.id.key_g to "&", R.id.key_h to "-", R.id.key_j to "+", R.id.key_k to "(",
            R.id.key_l to ")"
        )
        val numbersRow3 = mapOf(
            R.id.key_z to "!", R.id.key_x to "\"", R.id.key_c to "'", R.id.key_v to ":",
            R.id.key_b to ";", R.id.key_n to "/", R.id.key_m to "?"
        )

        val lettersRow1 = mapOf(
            R.id.key_q to "q", R.id.key_w to "w", R.id.key_e to "e", R.id.key_r to "r",
            R.id.key_t to "t", R.id.key_y to "y", R.id.key_u to "u", R.id.key_i to "i",
            R.id.key_o to "o", R.id.key_p to "p"
        )
        val lettersRow2 = mapOf(
            R.id.key_a to "a", R.id.key_s to "s", R.id.key_d to "d", R.id.key_f to "f",
            R.id.key_g to "g", R.id.key_h to "h", R.id.key_j to "j", R.id.key_k to "k",
            R.id.key_l to "l"
        )
        val lettersRow3 = mapOf(
            R.id.key_z to "z", R.id.key_x to "x", R.id.key_c to "c", R.id.key_v to "v",
            R.id.key_b to "b", R.id.key_n to "n", R.id.key_m to "m"
        )

        if (isNumbersMode) {
            // Update to numbers/symbols
            for ((id, char) in numbersRow1 + numbersRow2 + numbersRow3) {
                qwertyPanel?.findViewById<android.widget.Button>(id)?.text = char
            }
            // Update mode switch button to "ABC"
            qwertyPanel?.findViewById<android.widget.Button>(R.id.key_numbers)?.text = "ABC"
            // Hide shift key text (not applicable in numbers mode)
            qwertyPanel?.findViewById<android.widget.Button>(R.id.key_shift)?.text = "#+="
        } else {
            // Update to letters
            val useUppercase = isShiftActive || isCapsLock
            for ((id, char) in lettersRow1 + lettersRow2 + lettersRow3) {
                qwertyPanel?.findViewById<android.widget.Button>(id)?.text =
                    if (useUppercase) char.uppercase() else char
            }
            // Update mode switch button to "123"
            qwertyPanel?.findViewById<android.widget.Button>(R.id.key_numbers)?.text = "123"
            // Restore shift key
            updateShiftKeyDisplay()
        }
    }

    /**
     * Updates symbols display for secondary symbols (when shift is pressed in numbers mode).
     */
    private fun updateSymbolsDisplay() {
        // Primary symbols (no shift): 1-0, @ # $ % & - + ( ), ! " ' : ; / ?
        // Secondary symbols (shift): ~ ` | • √ π ÷ × ¶ ∆, € £ ¥ ^ ° = { } \ © ® ™ ✓
        val primaryRow1 = mapOf(
            R.id.key_q to "1", R.id.key_w to "2", R.id.key_e to "3", R.id.key_r to "4",
            R.id.key_t to "5", R.id.key_y to "6", R.id.key_u to "7", R.id.key_i to "8",
            R.id.key_o to "9", R.id.key_p to "0"
        )
        val primaryRow2 = mapOf(
            R.id.key_a to "@", R.id.key_s to "#", R.id.key_d to "$", R.id.key_f to "%",
            R.id.key_g to "&", R.id.key_h to "-", R.id.key_j to "+", R.id.key_k to "(",
            R.id.key_l to ")"
        )
        val primaryRow3 = mapOf(
            R.id.key_z to "!", R.id.key_x to "\"", R.id.key_c to "'", R.id.key_v to ":",
            R.id.key_b to ";", R.id.key_n to "/", R.id.key_m to "?"
        )

        val secondaryRow1 = mapOf(
            R.id.key_q to "~", R.id.key_w to "`", R.id.key_e to "|", R.id.key_r to "•",
            R.id.key_t to "√", R.id.key_y to "π", R.id.key_u to "÷", R.id.key_i to "×",
            R.id.key_o to "§", R.id.key_p to "∆"
        )
        val secondaryRow2 = mapOf(
            R.id.key_a to "€", R.id.key_s to "£", R.id.key_d to "¥", R.id.key_f to "^",
            R.id.key_g to "°", R.id.key_h to "=", R.id.key_j to "{", R.id.key_k to "}",
            R.id.key_l to "\\"
        )
        val secondaryRow3 = mapOf(
            R.id.key_z to "©", R.id.key_x to "®", R.id.key_c to "™", R.id.key_v to "✓",
            R.id.key_b to "[", R.id.key_n to "]", R.id.key_m to "_"
        )

        if (isShiftActive) {
            for ((id, char) in secondaryRow1 + secondaryRow2 + secondaryRow3) {
                qwertyPanel?.findViewById<android.widget.Button>(id)?.text = char
            }
            qwertyPanel?.findViewById<android.widget.Button>(R.id.key_shift)?.text = "123"
        } else {
            for ((id, char) in primaryRow1 + primaryRow2 + primaryRow3) {
                qwertyPanel?.findViewById<android.widget.Button>(id)?.text = char
            }
            qwertyPanel?.findViewById<android.widget.Button>(R.id.key_shift)?.text = "#+="
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
     * Handles touch events for cursor keys (up, down, left, right) with hold-to-repeat.
     *
     * Behavior:
     * - Tap: send one cursor key event
     * - Hold: send cursor key events with accelerating speed (same acceleration as backspace)
     *
     * Uses the same timing constants as backspace for consistent feel.
     */
    private fun handleCursorKeyTouch(event: MotionEvent, keyCode: Int): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                DebugLog.d("DictationKeyboardService: Cursor key $keyCode ACTION_DOWN")

                // Reset counter for this hold session
                cursorKeyCount = 0
                cursorKeyCode = keyCode

                // Send first key event immediately
                sendDownUpKeyEvents(keyCode)
                cursorKeyCount++

                // Record start time and set flag
                cursorStartTime = System.currentTimeMillis()
                isCursorKeyHeld = true

                // Remove any pending callbacks
                cursorHandler.removeCallbacks(cursorRepeatRunnable)

                // Start the repeat loop after initial delay
                cursorHandler.postDelayed(cursorRepeatRunnable, BACKSPACE_INITIAL_DELAY)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val holdDuration = System.currentTimeMillis() - cursorStartTime
                DebugLog.d("DictationKeyboardService: Cursor key $cursorKeyCode ACTION_UP/CANCEL after ${holdDuration}ms, $cursorKeyCount presses")

                // Clear the flag
                isCursorKeyHeld = false

                // Remove pending callbacks
                cursorHandler.removeCallbacks(cursorRepeatRunnable)
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

    private fun pasteFromClipboard() {
        Log.i(TAG, "DictationKeyboardService: Paste from clipboard")
        DebugLog.d("DictationKeyboardService: Pasting from clipboard")

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val clip = clipboard?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).coerceToText(this)
            if (text.isNotEmpty()) {
                currentInputConnection?.commitText(text, 1)
                DebugLog.d("DictationKeyboardService: Pasted ${text.length} characters")
            }
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

        // Check for pending paste from HistoryActivity
        pendingPasteText?.let { text ->
            Log.i(TAG, "Pasting pending text: ${text.take(50)}...")
            DebugLog.i("DictationKeyboardService: Pasting pending text (${text.length} chars)")
            pendingPasteText = null  // Clear before pasting to avoid loops
            commitTextInternal(text)
        }

        updateMicButton()
        updateLanguageButton()
        updateKeyboardButtonState()
        updateEmojiButtonState()

        // Auto-start dictation when keyboard opens (mic red/active by default)
        if (!isRecording && !isProcessing) {
            // Small delay to let UI settle before starting audio
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isRecording && !isProcessing) {
                    Log.i(TAG, "Auto-starting dictation on keyboard open")
                    DebugLog.i("DictationKeyboardService: Auto-starting dictation")

                    // Initialize TranscriptionManager if needed
                    if (transcriptionManager == null) {
                        initializeTranscriptionManager()
                    }

                    transcriptionManager?.let { manager ->
                        try {
                            manager.startListening()
                            updateMicButton()
                        } catch (e: SecurityException) {
                            Log.e(TAG, "Auto-start failed - mic permission denied", e)
                            DebugLog.e("DictationKeyboardService: Auto-start failed - ${e.message}")
                        } catch (e: Exception) {
                            Log.e(TAG, "Auto-start failed", e)
                            DebugLog.e("DictationKeyboardService: Auto-start failed - ${e.message}")
                        }
                    }
                }
            }, 100)
        }
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
