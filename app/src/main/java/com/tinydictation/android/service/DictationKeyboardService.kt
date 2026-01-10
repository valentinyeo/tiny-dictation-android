package com.tinydictation.android.service

import android.inputmethodservice.InputMethodService
import android.os.Build
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import com.tinydictation.android.R
import com.tinydictation.android.util.DebugLog

/**
 * Input Method Service (IME) for Tiny Dictation.
 *
 * This provides a zero-height keyboard that allows direct text input via InputConnection.
 * Benefits over accessibility-based text injection:
 * 1. Works in apps where accessibility paste doesn't work (e.g., terminal/CLI apps)
 * 2. Fewer button presses to transmit dictated text
 * 3. More reliable text insertion
 *
 * The keyboard view is intentionally minimal/invisible since dictation is controlled
 * via the floating bubble. A small button is shown to switch to other keyboards.
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
     * We use a minimal/zero-height view since dictation is done via the floating bubble.
     */
    override fun onCreateInputView(): View {
        Log.i(TAG, "DictationKeyboardService onCreateInputView")
        DebugLog.d("DictationKeyboardService: Creating input view")

        // Inflate our minimal keyboard layout
        keyboardView = layoutInflater.inflate(R.layout.view_keyboard, null)

        // Set up the keyboard switch button
        keyboardView?.findViewById<FrameLayout>(R.id.btnSwitchKeyboard)?.setOnClickListener {
            showKeyboardSwitcher()
        }

        // Set up status text to indicate dictation mode
        updateStatusText()

        return keyboardView!!
    }

    /**
     * Called when the keyboard is shown for a new input field.
     */
    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        Log.d(TAG, "DictationKeyboardService onStartInputView - field: ${info?.fieldName}, hint: ${info?.hintText}")
        DebugLog.d("DictationKeyboardService: Started input for field: ${info?.fieldName}")

        updateStatusText()
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
     * Updates the status text shown on the keyboard view.
     */
    private fun updateStatusText() {
        val statusText = keyboardView?.findViewById<TextView>(R.id.tvKeyboardStatus)
        val isListening = BubbleService.isRunning()
        statusText?.text = if (isListening) {
            "Dictation active - speak now"
        } else {
            "Tap bubble to start dictation"
        }
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
        updateStatusText()
    }
}
