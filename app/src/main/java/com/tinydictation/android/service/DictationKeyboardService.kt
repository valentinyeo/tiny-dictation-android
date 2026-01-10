package com.tinydictation.android.service

import android.content.ClipboardManager
import android.content.Context
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.tinydictation.android.R
import com.tinydictation.android.util.DebugLog

/**
 * Input Method Service (IME) for Tiny Dictation.
 *
 * This provides a keyboard bar with action buttons for dictation and text manipulation.
 * Benefits over accessibility-based text injection:
 * 1. Works in apps where accessibility paste doesn't work (e.g., terminal/CLI apps)
 * 2. Fewer button presses to transmit dictated text
 * 3. More reliable text insertion
 *
 * Buttons:
 * - Mic: Toggle dictation on/off
 * - Select All: Select all text in the field
 * - Paste: Paste from clipboard
 * - Enter: Send enter/return key
 * - Switch: Change to another keyboard
 */
class DictationKeyboardService : InputMethodService() {

    companion object {
        private const val TAG = "TinyDictation"

        @Volatile
        private var instance: DictationKeyboardService? = null

        /**
         * Gets the current service instance, if available.
         */
        fun getInstance(): DictationKeyboardService? = instance

        /**
         * Checks if the keyboard service is currently running and connected.
         * @return true if the service is active
         */
        fun isServiceRunning(): Boolean = instance != null

        /**
         * Commits text to the currently focused input field via InputConnection.
         * @param text The text to commit
         * @return true if successful, false if service unavailable or commit failed
         */
        fun commitText(text: String): Boolean {
            Log.d(TAG, "commitText requested: $text")
            return instance?.commitTextInternal(text) ?: run {
                Log.e(TAG, "Cannot commit text - keyboard service not running")
                false
            }
        }

        /**
         * Checks if text can be committed (i.e., there's an active input connection).
         * @return true if text can be committed
         */
        fun canCommitText(): Boolean {
            return instance?.currentInputConnection != null
        }
    }

    private var keyboardView: View? = null
    private var micButton: FrameLayout? = null
    private var micIcon: ImageView? = null

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
        super.onDestroy()
    }

    /**
     * Creates the input view (keyboard view).
     */
    override fun onCreateInputView(): View {
        Log.i(TAG, "DictationKeyboardService onCreateInputView")
        DebugLog.d("DictationKeyboardService: Creating input view")

        return try {
            // Inflate our keyboard layout
            keyboardView = layoutInflater.inflate(R.layout.view_keyboard, null)

            // Get references to views
            micButton = keyboardView?.findViewById(R.id.btnKeyboardMic)
            micIcon = keyboardView?.findViewById(R.id.ivKeyboardMic)

            // Set up button click handlers
            setupButtonHandlers()

            // Update mic button state
            updateMicButton()

            keyboardView!!
        } catch (e: Exception) {
            Log.e(TAG, "Error creating keyboard view", e)
            DebugLog.e("DictationKeyboardService: Error creating view - ${e.message}")
            // Return a simple fallback view
            TextView(this).apply {
                text = "Tiny Dictation Keyboard"
                setPadding(16, 16, 16, 16)
                textSize = 16f
            }
        }
    }

    /**
     * Sets up click handlers for all keyboard buttons.
     */
    private fun setupButtonHandlers() {
        // Mic button - toggle dictation
        keyboardView?.findViewById<FrameLayout>(R.id.btnKeyboardMic)?.setOnClickListener {
            toggleDictation()
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

        // Keyboard switch button
        keyboardView?.findViewById<FrameLayout>(R.id.btnSwitchKeyboard)?.setOnClickListener {
            showKeyboardSwitcher()
        }
    }

    /**
     * Toggles dictation on/off via the BubbleService.
     */
    private fun toggleDictation() {
        Log.i(TAG, "DictationKeyboardService: Toggle dictation")
        DebugLog.i("DictationKeyboardService: Toggling dictation")

        // Toggle via BubbleService
        BubbleService.getInstance()?.let { service ->
            // This will trigger the bubble tap behavior
            service.toggleRecording()
            updateMicButton()
        } ?: run {
            // If BubbleService isn't running, try to start it
            Log.w(TAG, "BubbleService not running - starting it")
            DebugLog.w("DictationKeyboardService: Starting BubbleService")
            BubbleService.start(this)
        }
    }

    /**
     * Updates the mic button appearance based on recording state.
     */
    private fun updateMicButton() {
        val isRecording = BubbleService.getInstance()?.isCurrentlyRecording() ?: false
        val color = if (isRecording) {
            ContextCompat.getColor(this, R.color.bubble_recording)
        } else {
            ContextCompat.getColor(this, R.color.primary)
        }
        micIcon?.setColorFilter(color)
    }

    /**
     * Selects all text in the current input field.
     */
    private fun selectAll() {
        Log.i(TAG, "DictationKeyboardService: Select all")
        DebugLog.d("DictationKeyboardService: Selecting all text")

        currentInputConnection?.let { ic ->
            // Get the full text
            val before = ic.getTextBeforeCursor(Int.MAX_VALUE, 0) ?: ""
            val after = ic.getTextAfterCursor(Int.MAX_VALUE, 0) ?: ""
            val totalLength = before.length + after.length

            // Select all by setting selection from 0 to end
            ic.setSelection(0, totalLength)
        }
    }

    /**
     * Pastes text from the system clipboard.
     */
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

    /**
     * Sends an Enter/Return key event.
     */
    private fun sendEnterKey() {
        Log.i(TAG, "DictationKeyboardService: Enter key")
        DebugLog.d("DictationKeyboardService: Sending enter key")

        currentInputConnection?.let { ic ->
            // Send key down and up events for Enter
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
    }

    /**
     * Called when the keyboard is shown for a new input field.
     */
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Log.d(TAG, "DictationKeyboardService onStartInputView - field: ${info?.fieldName}, hint: ${info?.hintText}")
        DebugLog.d("DictationKeyboardService: Started input for field: ${info?.fieldName}")

        updateMicButton()
    }

    /**
     * Called when the input connection changes.
     */
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        Log.d(TAG, "DictationKeyboardService onStartInput - inputType: ${attribute?.inputType}")
    }

    /**
     * Called when the keyboard is hidden.
     */
    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        Log.d(TAG, "DictationKeyboardService onFinishInputView")
        DebugLog.d("DictationKeyboardService: Finished input view")
    }

    /**
     * Shows the system keyboard picker dialog.
     */
    private fun showKeyboardSwitcher() {
        Log.i(TAG, "DictationKeyboardService: Showing keyboard switcher")
        DebugLog.i("DictationKeyboardService: Opening keyboard picker")

        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showInputMethodPicker()
    }

    /**
     * Commits text to the currently focused input field.
     * @param text The text to commit
     * @return true if successful
     */
    private fun commitTextInternal(text: String): Boolean {
        val ic = currentInputConnection
        if (ic == null) {
            Log.e(TAG, "Cannot commit text - no input connection")
            DebugLog.e("DictationKeyboardService: No input connection for commit")
            return false
        }

        return try {
            // Commit the text at the current cursor position
            // The second parameter (1) means move cursor to after the inserted text
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

    /**
     * Called by BubbleService to refresh the keyboard status display.
     */
    fun refreshStatus() {
        updateMicButton()
    }
}
