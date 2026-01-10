package com.tinydictation.android.service

import android.animation.ObjectAnimator
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
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
    }

    private var keyboardView: View? = null
    private var micButton: FrameLayout? = null
    private var micIcon: ImageView? = null
    private var languageText: TextView? = null

    // Pulse animation for transcribing state
    private var pulseAnimator: ObjectAnimator? = null

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

    // Single runnable instance that checks the flag to decide whether to continue
    private val backspaceRepeatRunnable = object : Runnable {
        override fun run() {
            // Check flag - if button was released, stop immediately
            if (!isBackspaceHeld) {
                DebugLog.d("DictationKeyboardService: Backspace released, stopping repeat")
                return
            }

            val holdDuration = System.currentTimeMillis() - backspaceStartTime

            if (holdDuration >= BACKSPACE_WORD_DELETE_TIME) {
                // After 2 seconds, delete words instead of characters
                deleteWord()
                // Continue word deletion as long as button is held
                backspaceHandler.postDelayed(this, BACKSPACE_WORD_DELETE_DELAY)
            } else {
                // Delete single character
                sendBackspace()

                // Calculate accelerated delay based on hold duration
                // Linear interpolation from REPEAT_DELAY_START to REPEAT_DELAY_MIN
                val accelerationProgress = (holdDuration.toFloat() / BACKSPACE_ACCELERATION_TIME).coerceIn(0f, 1f)
                val currentDelay = (BACKSPACE_REPEAT_DELAY_START -
                    (BACKSPACE_REPEAT_DELAY_START - BACKSPACE_REPEAT_DELAY_MIN) * accelerationProgress).toLong()

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
    }

    override fun onDestroy() {
        Log.i(TAG, "DictationKeyboardService onDestroy")
        DebugLog.i("DictationKeyboardService: Service destroyed")
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

        // Backspace button - with hold-to-repeat
        keyboardView?.findViewById<FrameLayout>(R.id.btnBackspace)?.setOnTouchListener { _, event ->
            handleBackspaceTouch(event)
        }

        // Select All button
        keyboardView?.findViewById<FrameLayout>(R.id.btnSelectAll)?.setOnClickListener {
            selectAll()
        }

        // Paste button - short press paste, long press opens history
        keyboardView?.findViewById<FrameLayout>(R.id.btnPaste)?.let { btnPaste ->
            btnPaste.setOnClickListener {
                pasteFromClipboard()
            }
            btnPaste.setOnLongClickListener {
                openTranscriptionHistory()
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
        val lang = prefs.getLanguage().uppercase()
        languageText?.text = lang
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

                // Send first backspace immediately
                sendBackspace()

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
                DebugLog.d("DictationKeyboardService: Backspace ACTION_UP/CANCEL")

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

    private fun sendBackspace() {
        Log.i(TAG, "DictationKeyboardService: Backspace")
        DebugLog.d("DictationKeyboardService: Sending backspace")

        currentInputConnection?.let { ic ->
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
        }
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

        currentInputConnection?.let { ic ->
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    /**
     * Switches back to the previously used keyboard.
     * Uses switchToLastInputMethod() which is available from API 11+.
     */
    private fun switchToPreviousKeyboard() {
        Log.i(TAG, "DictationKeyboardService: Switching to previous keyboard")
        DebugLog.i("DictationKeyboardService: Switching to previous keyboard")

        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val token = window?.window?.attributes?.token

            if (token != null) {
                // API 11+ - Direct switch to previous keyboard
                val switched = imm.switchToLastInputMethod(token)
                if (switched) {
                    Log.i(TAG, "Successfully switched to previous keyboard")
                    DebugLog.i("DictationKeyboardService: Switched to previous keyboard")
                } else {
                    Log.w(TAG, "switchToLastInputMethod returned false, showing picker")
                    showInputMethodPicker()
                }
            } else {
                Log.w(TAG, "No window token, showing picker")
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
     */
    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)

        // Get the keyboard view height
        val view = keyboardView
        if (view != null && view.height > 0) {
            // The visible region is the keyboard area
            outInsets.visibleTopInsets = outInsets.contentTopInsets
            outInsets.contentTopInsets = outInsets.contentTopInsets

            // Touch region should cover the keyboard
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_CONTENT

            Log.d(TAG, "onComputeInsets: height=${view.height}")
        }
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
