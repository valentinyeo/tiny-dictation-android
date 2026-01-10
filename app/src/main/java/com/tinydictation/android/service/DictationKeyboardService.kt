package com.tinydictation.android.service

import android.animation.ObjectAnimator
import android.content.BroadcastReceiver
import android.content.ClipboardManager
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.tinydictation.android.HistoryActivity
import com.tinydictation.android.R
import com.tinydictation.android.SettingsActivity
import com.tinydictation.android.util.DebugLog
import com.tinydictation.android.util.PreferenceManager

/**
 * Input Method Service (IME) for Tiny Dictation.
 *
 * This provides a keyboard bar with action buttons for dictation and text manipulation.
 * Buttons: Mic, Language, Backspace, Select All, Paste, Enter
 */
class DictationKeyboardService : InputMethodService() {

    companion object {
        private const val TAG = "TinyDictation"
        private const val LONG_PRESS_THRESHOLD = 500L

        /** Broadcast action for pasting text from HistoryActivity */
        const val ACTION_PASTE_TEXT = "com.tinydictation.ACTION_PASTE_TEXT"
        const val EXTRA_TEXT = "text"

        // Emoji categories with common emojis
        val EMOJI_SMILEYS = listOf(
            "\uD83D\uDE00", "\uD83D\uDE03", "\uD83D\uDE04", "\uD83D\uDE01", "\uD83D\uDE06",
            "\uD83D\uDE05", "\uD83D\uDE02", "\uD83E\uDD23", "\uD83D\uDE0A", "\uD83D\uDE07",
            "\uD83D\uDE42", "\uD83D\uDE43", "\uD83D\uDE09", "\uD83D\uDE0C", "\uD83D\uDE0D",
            "\uD83E\uDD70", "\uD83D\uDE18", "\uD83D\uDE17", "\uD83D\uDE1A", "\uD83D\uDE19",
            "\uD83D\uDE0B", "\uD83D\uDE1B", "\uD83D\uDE1C", "\uD83E\uDD2A", "\uD83D\uDE1D",
            "\uD83E\uDD11", "\uD83E\uDD17", "\uD83E\uDD2D", "\uD83E\uDD2B", "\uD83E\uDD14",
            "\uD83E\uDD10", "\uD83E\uDD28", "\uD83D\uDE10", "\uD83D\uDE11", "\uD83D\uDE36",
            "\uD83D\uDE0F", "\uD83D\uDE12", "\uD83D\uDE44", "\uD83D\uDE2C", "\uD83E\uDD25",
            "\uD83D\uDE14", "\uD83D\uDE1E", "\uD83D\uDE1F", "\uD83D\uDE15", "\uD83D\uDE41",
            "\uD83D\uDE23", "\uD83D\uDE16", "\uD83D\uDE2B", "\uD83D\uDE29", "\uD83E\uDD7A",
            "\uD83D\uDE22", "\uD83D\uDE2D", "\uD83D\uDE24", "\uD83D\uDE20", "\uD83D\uDE21",
            "\uD83E\uDD2C", "\uD83E\uDD2F", "\uD83D\uDE33", "\uD83E\uDD75", "\uD83E\uDD76"
        )

        val EMOJI_GESTURES = listOf(
            "\uD83D\uDC4B", "\uD83E\uDD1A", "\uD83D\uDD90", "\u270B", "\uD83D\uDD96",
            "\uD83D\uDC4C", "\uD83E\uDD0C", "\uD83E\uDD0F", "\u270C", "\uD83E\uDD1E",
            "\uD83E\uDD1F", "\uD83E\uDD18", "\uD83E\uDD19", "\uD83D\uDC48", "\uD83D\uDC49",
            "\uD83D\uDC46", "\uD83D\uDD95", "\uD83D\uDC47", "\u261D", "\uD83D\uDC4D",
            "\uD83D\uDC4E", "\u270A", "\uD83D\uDC4A", "\uD83E\uDD1B", "\uD83E\uDD1C",
            "\uD83D\uDC4F", "\uD83D\uDE4C", "\uD83D\uDC50", "\uD83E\uDD32", "\uD83E\uDD1D",
            "\uD83D\uDE4F", "\u270D", "\uD83D\uDC85", "\uD83E\uDD33", "\uD83D\uDCAA",
            "\uD83E\uDDB5", "\uD83E\uDDB6", "\uD83D\uDC42", "\uD83D\uDC43", "\uD83E\uDDE0"
        )

        val EMOJI_HEARTS = listOf(
            "\u2764", "\uD83E\uDDE1", "\uD83D\uDC9B", "\uD83D\uDC9A", "\uD83D\uDC99",
            "\uD83D\uDC9C", "\uD83E\uDD0E", "\uD83D\uDDA4", "\uD83E\uDD0D", "\uD83D\uDC94",
            "\u2763", "\uD83D\uDC95", "\uD83D\uDC9E", "\uD83D\uDC93", "\uD83D\uDC97",
            "\uD83D\uDC96", "\uD83D\uDC98", "\uD83D\uDC9D", "\uD83D\uDC9F", "\u2665",
            "\uD83D\uDC8B", "\uD83D\uDC4B", "\u2728", "\uD83C\uDF1F", "\u2B50",
            "\uD83D\uDD25", "\uD83D\uDCA5", "\uD83C\uDF08", "\u2600", "\uD83C\uDF19"
        )

        val EMOJI_ANIMALS = listOf(
            "\uD83D\uDC36", "\uD83D\uDC31", "\uD83D\uDC2D", "\uD83D\uDC39", "\uD83D\uDC30",
            "\uD83E\uDD8A", "\uD83D\uDC3B", "\uD83D\uDC3C", "\uD83D\uDC28", "\uD83D\uDC2F",
            "\uD83E\uDD81", "\uD83D\uDC2E", "\uD83D\uDC37", "\uD83D\uDC38", "\uD83D\uDC35",
            "\uD83D\uDC14", "\uD83D\uDC27", "\uD83D\uDC26", "\uD83E\uDD86", "\uD83E\uDD85",
            "\uD83E\uDD89", "\uD83E\uDD87", "\uD83D\uDC3A", "\uD83D\uDC17", "\uD83D\uDC34",
            "\uD83E\uDD84", "\uD83D\uDC1D", "\uD83D\uDC1B", "\uD83E\uDD8B", "\uD83D\uDC0C",
            "\uD83D\uDC1A", "\uD83D\uDC22", "\uD83D\uDC0D", "\uD83E\uDD8E", "\uD83E\uDD96"
        )

        val EMOJI_FOOD = listOf(
            "\uD83C\uDF4E", "\uD83C\uDF4A", "\uD83C\uDF4B", "\uD83C\uDF4C", "\uD83C\uDF49",
            "\uD83C\uDF47", "\uD83C\uDF53", "\uD83C\uDF48", "\uD83C\uDF52", "\uD83C\uDF51",
            "\uD83E\uDD5D", "\uD83C\uDF45", "\uD83E\uDD51", "\uD83E\uDD66", "\uD83E\uDD52",
            "\uD83C\uDF36", "\uD83C\uDF3D", "\uD83E\uDD55", "\uD83E\uDDC4", "\uD83E\uDDC5",
            "\uD83C\uDF5E", "\uD83E\uDD50", "\uD83E\uDD56", "\uD83E\uDDC0", "\uD83C\uDF56",
            "\uD83C\uDF55", "\uD83C\uDF54", "\uD83C\uDF5F", "\uD83C\uDF2D", "\uD83E\uDD6A",
            "\uD83C\uDF2E", "\uD83C\uDF2F", "\uD83E\uDD59", "\uD83E\uDD5A", "\uD83E\uDD5E",
            "\uD83C\uDF70", "\uD83C\uDF82", "\uD83E\uDDC1", "\uD83C\uDF69", "\uD83C\uDF6A"
        )

        val EMOJI_ACTIVITIES = listOf(
            "\u26BD", "\uD83C\uDFC0", "\uD83C\uDFC8", "\u26BE", "\uD83E\uDD4E",
            "\uD83C\uDFBE", "\uD83C\uDFD0", "\uD83C\uDFC9", "\uD83E\uDD4F", "\uD83C\uDFB1",
            "\uD83C\uDFD3", "\uD83C\uDFF8", "\uD83C\uDFD2", "\uD83C\uDFD1", "\uD83E\uDD4D",
            "\u26F3", "\uD83C\uDFF9", "\uD83C\uDFA3", "\uD83E\uDD3C", "\uD83E\uDD4A",
            "\uD83E\uDD3D", "\u26F7", "\uD83C\uDFC2", "\uD83E\uDD3A", "\uD83C\uDFC7",
            "\uD83C\uDFCB", "\uD83E\uDD38", "\uD83D\uDEB4", "\uD83C\uDFC3", "\uD83E\uDDD8",
            "\uD83C\uDFC6", "\uD83E\uDD47", "\uD83E\uDD48", "\uD83E\uDD49", "\uD83C\uDF96"
        )

        val EMOJI_OBJECTS = listOf(
            "\uD83D\uDCF1", "\uD83D\uDCBB", "\uD83D\uDCBD", "\uD83D\uDCBE", "\uD83D\uDCBF",
            "\uD83D\uDCF7", "\uD83D\uDCF9", "\uD83D\uDCFA", "\uD83D\uDCFB", "\uD83D\uDCFC",
            "\u23F0", "\u231A", "\uD83D\uDCA1", "\uD83D\uDD26", "\uD83D\uDD0B",
            "\uD83D\uDD0C", "\uD83D\uDECB", "\uD83D\uDECF", "\uD83D\uDEBF", "\uD83D\uDEC1",
            "\uD83D\uDCDA", "\uD83D\uDCD6", "\uD83D\uDCDD", "\u270F", "\uD83D\uDD8A",
            "\uD83D\uDD8B", "\uD83D\uDCBC", "\uD83D\uDCC1", "\uD83D\uDCC2", "\uD83D\uDCC5",
            "\uD83D\uDCCB", "\uD83D\uDCC8", "\uD83D\uDCC9", "\uD83D\uDCCA", "\uD83D\uDD12"
        )

        val EMOJI_SYMBOLS = listOf(
            "\u2714", "\u2716", "\u274C", "\u2B55", "\u2757",
            "\u2753", "\u2705", "\u2795", "\u2796", "\u2797",
            "\u27A1", "\u2B05", "\u2B06", "\u2B07", "\u21A9",
            "\u21AA", "\uD83D\uDD04", "\uD83D\uDD01", "\uD83D\uDD00", "\uD83D\uDD02",
            "\u25B6", "\u23F8", "\u23F9", "\u23FA", "\u23ED",
            "\u23EE", "\u23E9", "\u23EA", "\uD83D\uDD0A", "\uD83D\uDD07",
            "\uD83D\uDD14", "\uD83D\uDD15", "\uD83C\uDFB5", "\uD83C\uDFB6", "\u2122"
        )

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

    // Emoji picker
    private var emojiPickerView: View? = null
    private var isEmojiPickerVisible = false
    private var currentEmojiCategory = EMOJI_SMILEYS
    private var emojiAdapter: EmojiAdapter? = null

    // Pulse animation for transcribing state
    private var pulseAnimator: ObjectAnimator? = null

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
    }

    override fun onDestroy() {
        Log.i(TAG, "DictationKeyboardService onDestroy")
        DebugLog.i("DictationKeyboardService: Service destroyed")

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

        // Stop companion service entirely when our keyboard is active
        // Just hiding the bar doesn't work because the service's keyboard visibility
        // listener will show it again. We need to stop the service completely.
        if (KeyboardCompanionService.isRunning()) {
            Log.i(TAG, "DictationKeyboardService: Stopping KeyboardCompanionService from onCreateInputView")
            DebugLog.i("DictationKeyboardService: Stopping companion service on create")
            KeyboardCompanionService.stop(this)
        }

        return try {
            keyboardView = layoutInflater.inflate(R.layout.view_keyboard, null)

            micButton = keyboardView?.findViewById(R.id.btnKeyboardMic)
            micIcon = keyboardView?.findViewById(R.id.ivKeyboardMic)
            languageText = keyboardView?.findViewById(R.id.tvLanguage)

            setupButtonHandlers()
            applyTheme()
            updateMicButton()
            updateLanguageButton()
            setupVersionDisplay()

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

        // Determine if we should use dark theme
        val useDarkTheme = when (themeSetting) {
            PreferenceManager.THEME_LIGHT -> false
            PreferenceManager.THEME_DARK -> true
            else -> { // THEME_SYSTEM
                val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                nightModeFlags == Configuration.UI_MODE_NIGHT_YES
            }
        }

        // Define colors based on theme
        val backgroundColor: Int
        val buttonColor: Int
        val iconColor: Int

        if (useDarkTheme) {
            // Dark theme - use existing dark colors from colors.xml
            backgroundColor = ContextCompat.getColor(this, R.color.surface_variant)
            buttonColor = ContextCompat.getColor(this, R.color.surface)
            iconColor = ContextCompat.getColor(this, R.color.on_surface)
        } else {
            // Light theme - white background, gray buttons, black icons
            backgroundColor = Color.parseColor("#F5F5F5")
            buttonColor = Color.parseColor("#E0E0E0")
            iconColor = Color.parseColor("#1C1B1F")
        }

        // Apply background color to root layout
        (keyboardView as? LinearLayout)?.setBackgroundColor(backgroundColor)

        // Apply button and icon colors to all buttons
        applyButtonTheme(keyboardView?.findViewById(R.id.btnKeyboardMic), buttonColor, iconColor)
        applyButtonTheme(keyboardView?.findViewById(R.id.btnLanguage), buttonColor, iconColor)
        applyButtonTheme(keyboardView?.findViewById(R.id.btnEmoji), buttonColor, iconColor)
        applyButtonTheme(keyboardView?.findViewById(R.id.btnBackspace), buttonColor, iconColor)
        applyButtonTheme(keyboardView?.findViewById(R.id.btnEnter), buttonColor, iconColor)
        applyButtonTheme(keyboardView?.findViewById(R.id.btnPaste), buttonColor, iconColor)
        applyButtonTheme(keyboardView?.findViewById(R.id.btnSelectAll), buttonColor, iconColor)
        applyButtonTheme(keyboardView?.findViewById(R.id.btnSwitchBack), buttonColor, iconColor)

        // Apply text color to language text
        languageText?.setTextColor(iconColor)

        // Apply text color to "All" button text
        keyboardView?.findViewById<FrameLayout>(R.id.btnSelectAll)?.let { button ->
            val textView = button.getChildAt(0) as? TextView
            textView?.setTextColor(iconColor)
        }

        DebugLog.d("DictationKeyboardService: Applied theme - dark=$useDarkTheme")
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

        // Emoji button
        keyboardView?.findViewById<FrameLayout>(R.id.btnEmoji)?.setOnClickListener {
            toggleEmojiPicker()
        }

        // Backspace button - with hold-to-repeat
        keyboardView?.findViewById<FrameLayout>(R.id.btnBackspace)?.setOnTouchListener { _, event ->
            handleBackspaceTouch(event)
        }

        // Select All button
        keyboardView?.findViewById<FrameLayout>(R.id.btnSelectAll)?.setOnClickListener {
            selectAll()
        }

        // Paste button - short press opens history, long press pastes from clipboard
        keyboardView?.findViewById<FrameLayout>(R.id.btnPaste)?.let { btnPaste ->
            btnPaste.setOnClickListener {
                openTranscriptionHistory()
            }
            btnPaste.setOnLongClickListener {
                pasteFromClipboard()
                true
            }
        }

        // Enter button
        keyboardView?.findViewById<FrameLayout>(R.id.btnEnter)?.setOnClickListener {
            sendEnterKey()
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
        DebugLog.i("DictationKeyboardService: Long press paste - opening history")

        val intent = Intent(this, HistoryActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    private fun toggleDictation() {
        Log.i(TAG, "DictationKeyboardService: Toggle dictation")
        DebugLog.i("DictationKeyboardService: Toggling dictation")

        BubbleService.getInstance()?.let { service ->
            service.toggleRecording()
            updateMicButton()
        } ?: run {
            Log.w(TAG, "BubbleService not running - starting it")
            DebugLog.w("DictationKeyboardService: Starting BubbleService")
            BubbleService.start(this)
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

        // Notify BubbleService of language change
        BubbleService.onLanguageChanged?.invoke(newLang)

        updateLanguageButton()
        Log.i(TAG, "Language changed to: $newLang")
    }

    private fun updateMicButton() {
        val bubbleService = BubbleService.getInstance()
        val isRecording = bubbleService?.isCurrentlyRecording() ?: false
        val isProcessing = bubbleService?.isProcessing() ?: false

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

        val useDarkTheme = when (themeSetting) {
            PreferenceManager.THEME_LIGHT -> false
            PreferenceManager.THEME_DARK -> true
            else -> { // THEME_SYSTEM
                val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                nightModeFlags == Configuration.UI_MODE_NIGHT_YES
            }
        }

        return if (useDarkTheme) {
            ContextCompat.getColor(this, R.color.on_surface)
        } else {
            Color.parseColor("#1C1B1F")
        }
    }

    private fun updateLanguageButton() {
        val prefs = PreferenceManager(this)
        val languageButton = keyboardView?.findViewById<FrameLayout>(R.id.btnLanguage)

        // Hide language button if auto-detect is enabled and the provider supports it
        val autoDetectEnabled = prefs.getAutoDetectLanguage() && prefs.currentProviderSupportsAutoDetect()
        if (autoDetectEnabled) {
            languageButton?.visibility = View.GONE
            DebugLog.d("DictationKeyboardService: Language button hidden (auto-detect enabled)")
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

    private fun pasteFromClipboard() {
        Log.i(TAG, "DictationKeyboardService: Paste")
        DebugLog.d("DictationKeyboardService: Pasting from clipboard")

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val text = clip.getItemAt(0).coerceToText(this).toString()
            if (text.isNotEmpty()) {
                currentInputConnection?.commitText(text, 1)
                Log.d(TAG, "Pasted ${text.length} characters")
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

        updateMicButton()
        updateLanguageButton()

        // Auto-hide bubbles when keyboard is visible
        BubbleService.getInstance()?.setKeyboardVisible(true)

        // Stop companion service entirely when our keyboard is active
        // Just hiding the bar doesn't work because the service's keyboard visibility
        // listener will show it again. We need to stop the service completely.
        if (KeyboardCompanionService.isRunning()) {
            Log.i(TAG, "DictationKeyboardService: Stopping KeyboardCompanionService")
            DebugLog.i("DictationKeyboardService: Stopping companion service - keyboard is active")
            KeyboardCompanionService.stop(this)
        }

        // Update dictation mode preference to keyboard mode
        // This ensures the preference reflects the actual active mode
        val prefs = PreferenceManager(this)
        if (prefs.getDictationMode() != PreferenceManager.MODE_KEYBOARD) {
            Log.i(TAG, "DictationKeyboardService: Updating dictation mode to MODE_KEYBOARD")
            DebugLog.i("DictationKeyboardService: Setting mode to keyboard")
            prefs.setDictationMode(PreferenceManager.MODE_KEYBOARD)
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

        // Show bubbles again when keyboard is hidden
        BubbleService.getInstance()?.setKeyboardVisible(false)
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

    // ==================== Emoji Picker ====================

    /**
     * Toggles the emoji picker visibility.
     */
    private fun toggleEmojiPicker() {
        if (isEmojiPickerVisible) {
            hideEmojiPicker()
        } else {
            showEmojiPicker()
        }
    }

    /**
     * Shows the emoji picker, replacing the keyboard bar temporarily.
     */
    private fun showEmojiPicker() {
        Log.i(TAG, "DictationKeyboardService: Showing emoji picker")
        DebugLog.i("DictationKeyboardService: Opening emoji picker")

        // Inflate emoji picker if not already done
        if (emojiPickerView == null) {
            emojiPickerView = layoutInflater.inflate(R.layout.view_emoji_picker, null)
            setupEmojiPicker()
        }

        // Hide the keyboard bar and show emoji picker
        keyboardView?.visibility = View.GONE

        // Set the emoji picker as the input view
        setInputView(emojiPickerView!!)
        isEmojiPickerVisible = true

        // Show smileys category by default
        showEmojiCategory(EMOJI_SMILEYS)
        updateCategoryTabHighlight(R.id.tabSmileys)
    }

    /**
     * Hides the emoji picker and shows the keyboard bar again.
     */
    private fun hideEmojiPicker() {
        Log.i(TAG, "DictationKeyboardService: Hiding emoji picker")
        DebugLog.i("DictationKeyboardService: Closing emoji picker")

        keyboardView?.visibility = View.VISIBLE
        setInputView(keyboardView!!)
        isEmojiPickerVisible = false
    }

    /**
     * Sets up the emoji picker with category tabs and grid.
     */
    private fun setupEmojiPicker() {
        val picker = emojiPickerView ?: return

        // Setup RecyclerView with grid layout
        val emojiGrid = picker.findViewById<RecyclerView>(R.id.emojiGrid)
        emojiGrid.layoutManager = GridLayoutManager(this, 8) // 8 columns

        emojiAdapter = EmojiAdapter(EMOJI_SMILEYS) { emoji ->
            insertEmoji(emoji)
        }
        emojiGrid.adapter = emojiAdapter

        // Setup category tab clicks
        picker.findViewById<TextView>(R.id.tabSmileys)?.setOnClickListener {
            showEmojiCategory(EMOJI_SMILEYS)
            updateCategoryTabHighlight(R.id.tabSmileys)
        }
        picker.findViewById<TextView>(R.id.tabGestures)?.setOnClickListener {
            showEmojiCategory(EMOJI_GESTURES)
            updateCategoryTabHighlight(R.id.tabGestures)
        }
        picker.findViewById<TextView>(R.id.tabHearts)?.setOnClickListener {
            showEmojiCategory(EMOJI_HEARTS)
            updateCategoryTabHighlight(R.id.tabHearts)
        }
        picker.findViewById<TextView>(R.id.tabAnimals)?.setOnClickListener {
            showEmojiCategory(EMOJI_ANIMALS)
            updateCategoryTabHighlight(R.id.tabAnimals)
        }
        picker.findViewById<TextView>(R.id.tabFood)?.setOnClickListener {
            showEmojiCategory(EMOJI_FOOD)
            updateCategoryTabHighlight(R.id.tabFood)
        }
        picker.findViewById<TextView>(R.id.tabActivities)?.setOnClickListener {
            showEmojiCategory(EMOJI_ACTIVITIES)
            updateCategoryTabHighlight(R.id.tabActivities)
        }
        picker.findViewById<TextView>(R.id.tabObjects)?.setOnClickListener {
            showEmojiCategory(EMOJI_OBJECTS)
            updateCategoryTabHighlight(R.id.tabObjects)
        }
        picker.findViewById<TextView>(R.id.tabSymbols)?.setOnClickListener {
            showEmojiCategory(EMOJI_SYMBOLS)
            updateCategoryTabHighlight(R.id.tabSymbols)
        }

        // Setup close button
        picker.findViewById<FrameLayout>(R.id.btnCloseEmoji)?.setOnClickListener {
            hideEmojiPicker()
        }

        // Apply theme to emoji picker
        applyEmojiPickerTheme()
    }

    /**
     * Updates the emoji grid to show a specific category.
     */
    private fun showEmojiCategory(emojis: List<String>) {
        currentEmojiCategory = emojis
        emojiAdapter?.updateEmojis(emojis)
    }

    /**
     * Highlights the selected category tab.
     */
    private fun updateCategoryTabHighlight(selectedTabId: Int) {
        val picker = emojiPickerView ?: return
        val tabIds = listOf(
            R.id.tabSmileys, R.id.tabGestures, R.id.tabHearts, R.id.tabAnimals,
            R.id.tabFood, R.id.tabActivities, R.id.tabObjects, R.id.tabSymbols
        )

        for (tabId in tabIds) {
            val tab = picker.findViewById<TextView>(tabId)
            tab?.alpha = if (tabId == selectedTabId) 1.0f else 0.5f
        }
    }

    /**
     * Applies the current theme to the emoji picker.
     */
    private fun applyEmojiPickerTheme() {
        val picker = emojiPickerView ?: return
        val prefs = PreferenceManager(this)
        val themeSetting = prefs.getKeyboardTheme()

        val useDarkTheme = when (themeSetting) {
            PreferenceManager.THEME_LIGHT -> false
            PreferenceManager.THEME_DARK -> true
            else -> {
                val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                nightModeFlags == Configuration.UI_MODE_NIGHT_YES
            }
        }

        val backgroundColor = if (useDarkTheme) {
            ContextCompat.getColor(this, R.color.surface_variant)
        } else {
            Color.parseColor("#F5F5F5")
        }

        val buttonColor = if (useDarkTheme) {
            ContextCompat.getColor(this, R.color.surface)
        } else {
            Color.parseColor("#E0E0E0")
        }

        val iconColor = if (useDarkTheme) {
            ContextCompat.getColor(this, R.color.on_surface)
        } else {
            Color.parseColor("#1C1B1F")
        }

        // Apply background
        picker.findViewById<LinearLayout>(R.id.emojiPickerContainer)?.setBackgroundColor(backgroundColor)

        // Apply theme to close button
        applyButtonTheme(picker.findViewById(R.id.btnCloseEmoji), buttonColor, iconColor)
    }

    /**
     * Inserts an emoji into the current text field.
     */
    private fun insertEmoji(emoji: String) {
        Log.d(TAG, "DictationKeyboardService: Inserting emoji: $emoji")
        DebugLog.d("DictationKeyboardService: Emoji inserted")

        currentInputConnection?.let { ic ->
            ic.commitText(emoji, 1)
        }
    }

    /**
     * RecyclerView adapter for emoji grid.
     */
    inner class EmojiAdapter(
        private var emojis: List<String>,
        private val onEmojiClick: (String) -> Unit
    ) : RecyclerView.Adapter<EmojiAdapter.EmojiViewHolder>() {

        inner class EmojiViewHolder(val textView: TextView) : RecyclerView.ViewHolder(textView)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): EmojiViewHolder {
            val textView = TextView(parent.context).apply {
                layoutParams = android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    (44 * resources.displayMetrics.density).toInt()
                )
                gravity = android.view.Gravity.CENTER
                textSize = 24f
                isClickable = true
                isFocusable = true
                setBackgroundResource(android.R.drawable.list_selector_background)
            }
            return EmojiViewHolder(textView)
        }

        override fun onBindViewHolder(holder: EmojiViewHolder, position: Int) {
            val emoji = emojis[position]
            holder.textView.text = emoji
            holder.textView.setOnClickListener {
                onEmojiClick(emoji)
            }
        }

        override fun getItemCount(): Int = emojis.size

        fun updateEmojis(newEmojis: List<String>) {
            emojis = newEmojis
            notifyDataSetChanged()
        }
    }
}
