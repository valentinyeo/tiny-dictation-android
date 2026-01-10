package com.tinydictation.android.service

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import android.widget.TextView
import androidx.core.content.ContextCompat
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

    // Long press handling for mic button
    private var longPressHandler: Handler? = null
    private var longPressRunnable: Runnable? = null
    private var isLongPressTriggered = false

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

            setupButtonHandlers()
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

    private fun setupButtonHandlers() {
        // Mic button - short press toggle, long press settings
        micButton?.setOnTouchListener { _, event ->
            handleMicTouch(event)
        }

        // Language button
        keyboardView?.findViewById<FrameLayout>(R.id.btnLanguage)?.setOnClickListener {
            toggleLanguage()
        }

        // Backspace button
        keyboardView?.findViewById<FrameLayout>(R.id.btnBackspace)?.setOnClickListener {
            sendBackspace()
        }

        // Select All button
        keyboardView?.findViewById<FrameLayout>(R.id.btnSelectAll)?.setOnClickListener {
            selectAll()
        }

        // Paste button
        keyboardView?.findViewById<FrameLayout>(R.id.btnPaste)?.setOnClickListener {
            pasteFromClipboard()
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
        val isRecording = BubbleService.getInstance()?.isCurrentlyRecording() ?: false
        val color = if (isRecording) {
            ContextCompat.getColor(this, R.color.bubble_recording)
        } else {
            ContextCompat.getColor(this, R.color.on_surface)
        }
        micIcon?.setColorFilter(color)
    }

    private fun updateLanguageButton() {
        val prefs = PreferenceManager(this)
        val lang = prefs.getLanguage().uppercase()
        languageText?.text = lang
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
}
