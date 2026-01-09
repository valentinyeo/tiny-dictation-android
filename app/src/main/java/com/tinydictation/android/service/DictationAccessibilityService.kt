package com.tinydictation.android.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.tinydictation.android.util.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * AccessibilityService for injecting transcribed text into any focused text field.
 * This service allows Tiny Dictation to work with any app on the device.
 */
class DictationAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TinyDictation"

        @Volatile
        private var instance: DictationAccessibilityService? = null

        /**
         * Gets the current service instance, if available.
         */
        fun getInstance(): DictationAccessibilityService? = instance

        /**
         * Checks if the accessibility service is enabled in system settings.
         *
         * @param context Application context
         * @return true if the service is enabled, false otherwise
         */
        fun isServiceEnabled(context: Context): Boolean {
            val serviceName = "${context.packageName}/${DictationAccessibilityService::class.java.canonicalName}"

            val enabledServices = try {
                Settings.Secure.getString(
                    context.contentResolver,
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
                )
            } catch (e: Exception) {
                null
            }

            return enabledServices?.contains(serviceName) == true
        }

        /**
         * Checks if the service is currently running and connected.
         *
         * @return true if the service is active, false otherwise
         */
        fun isServiceRunning(): Boolean = instance != null

        /**
         * Convenience method to inject text if service is available.
         *
         * @param text The text to inject
         * @return true if successful, false if service unavailable or injection failed
         */
        fun inject(text: String): Boolean {
            Log.d(TAG, "Inject requested: $text")
            return instance?.injectText(text) ?: run {
                Log.e(TAG, "Cannot inject text - accessibility service not running")
                false
            }
        }

        /**
         * Performs paste action on the currently focused field.
         * Uses clipboard content that was previously set.
         *
         * @return true if paste was successful, false otherwise
         */
        fun paste(): Boolean {
            Log.d(TAG, "Paste requested")
            return instance?.performPasteAction() ?: run {
                Log.e(TAG, "Cannot paste - accessibility service not running")
                false
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var preferenceManager: PreferenceManager

    override fun onServiceConnected() {
        preferenceManager = PreferenceManager(this)
        super.onServiceConnected()
        Log.i(TAG, "DictationAccessibilityService connected")
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We primarily use this service for text injection,
        // not for processing accessibility events.
        // Events are listened to maintain service connection.
    }

    override fun onInterrupt() {
        Log.w(TAG, "DictationAccessibilityService interrupted")
        // Handle service interruption if needed
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "DictationAccessibilityService destroyed")
        serviceScope.cancel()
        instance = null
    }

    /**
     * Injects text into the currently focused editable text field.
     * Uses ACTION_SET_TEXT if available, falls back to clipboard paste.
     * If "simulate typing" is enabled, types character-by-character at cursor position.
     *
     * @param text The text to inject
     * @return true if text was successfully injected, false otherwise
     */
    fun injectText(text: String): Boolean {
        Log.d(TAG, "Injecting text: $text")
        val focusedNode = findFocusedEditableNode()
        if (focusedNode == null) {
            Log.e(TAG, "Cannot inject text - no focused editable field found")
            return false
        }

        return try {
            // Check if simulate typing is enabled
            val simulateTyping = preferenceManager.getSimulateTyping()
            Log.d(TAG, "Simulate typing mode: $simulateTyping")

            if (simulateTyping) {
                // Use character-by-character typing mode
                injectTextCharByChar(focusedNode, text)
            } else {
                // Use standard paste mode
                val success = injectTextWithSetText(focusedNode, text)
                if (!success) {
                    Log.d(TAG, "ACTION_SET_TEXT failed, trying clipboard method")
                    injectTextWithClipboard(focusedNode, text)
                } else {
                    Log.i(TAG, "Text injected successfully using ACTION_SET_TEXT")
                    true
                }
            }
        } finally {
            focusedNode.recycle()
        }
    }

    /**
     * Injects text asynchronously.
     * Useful when called from coroutines or background operations.
     */
    fun injectTextAsync(text: String, onResult: (Boolean) -> Unit) {
        serviceScope.launch {
            val result = injectText(text)
            onResult(result)
        }
    }

    /**
     * Performs a paste action on the currently focused editable field.
     * Assumes clipboard already contains the desired content.
     *
     * @return true if paste was successful, false otherwise
     */
    fun performPasteAction(): Boolean {
        Log.d(TAG, "Performing paste action")
        val focusedNode = findFocusedEditableNode()
        if (focusedNode == null) {
            Log.e(TAG, "Cannot paste - no focused editable field found")
            return false
        }

        return try {
            val pasteSuccess = focusedNode.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            Log.i(TAG, "Paste action ${if (pasteSuccess) "succeeded" else "failed"}")
            pasteSuccess
        } finally {
            focusedNode.recycle()
        }
    }

    /**
     * Finds the currently focused editable AccessibilityNodeInfo.
     *
     * @return The focused editable node, or null if none found
     */
    private fun findFocusedEditableNode(): AccessibilityNodeInfo? {
        // First try to get the input-focused node
        val focusedNode = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        if (focusedNode != null && focusedNode.isEditable) {
            return focusedNode
        }

        // If direct focus didn't work, search through the node tree
        focusedNode?.recycle()
        return findEditableNodeInTree(rootInActiveWindow)
    }

    /**
     * Recursively searches for an editable, focused node in the accessibility tree.
     */
    private fun findEditableNodeInTree(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null

        // Check if this node is editable and focused
        if (node.isEditable && node.isFocused) {
            return AccessibilityNodeInfo.obtain(node)
        }

        // Search children
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val result = findEditableNodeInTree(child)
            child.recycle()
            if (result != null) {
                return result
            }
        }

        return null
    }

    /**
     * Attempts to inject text using ACTION_SET_TEXT.
     * This is the preferred method as it's more reliable.
     */
    private fun injectTextWithSetText(node: AccessibilityNodeInfo, text: String): Boolean {
        // Get existing text and append new text
        val existingText = node.text?.toString() ?: ""
        val newText = if (existingText.isNotEmpty() && !existingText.endsWith(" ")) {
            "$existingText $text"
        } else {
            "$existingText$text"
        }

        Log.d(TAG, "Setting text via ACTION_SET_TEXT: $newText")
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                newText
            )
        }

        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
    }

    /**
     * Fallback method using clipboard paste.
     * Used when ACTION_SET_TEXT is not supported.
     */
    private fun injectTextWithClipboard(node: AccessibilityNodeInfo, text: String): Boolean {
        Log.d(TAG, "Using clipboard method for text injection")
        val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        // Save current clipboard content to restore later
        val previousClip = clipboardManager.primaryClip

        return try {
            // Prepare text with space prefix if needed
            val existingText = node.text?.toString() ?: ""
            val textToInsert = if (existingText.isNotEmpty() && !existingText.endsWith(" ")) {
                " $text"
            } else {
                text
            }

            // Set our text to clipboard
            val clip = ClipData.newPlainText("dictation", textToInsert)
            clipboardManager.setPrimaryClip(clip)

            // Move cursor to end
            val arguments = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, existingText.length)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, existingText.length)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, arguments)

            // Perform paste
            val pasteSuccess = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            Log.i(TAG, "Clipboard paste ${if (pasteSuccess) "succeeded" else "failed"}")

            // Restore previous clipboard content
            if (previousClip != null) {
                clipboardManager.setPrimaryClip(previousClip)
            }

            pasteSuccess
        } catch (e: Exception) {
            Log.e(TAG, "Error during clipboard text injection", e)
            e.printStackTrace()
            false
        }
    }

    /**
     * Injects text character-by-character at the current cursor position.
     * This method appends each character to the existing text, preserving cursor position.
     * Useful for apps where cursor position matters.
     *
     * @param node The focused editable node
     * @param text The text to inject
     * @return true if successful, false otherwise
     */
    private fun injectTextCharByChar(node: AccessibilityNodeInfo, text: String): Boolean {
        Log.d(TAG, "Injecting text character-by-character: $text")

        // Get the current text and cursor position
        val existingText = node.text?.toString() ?: ""

        // Try to get current selection/cursor position
        val selectionStart = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            node.textSelectionStart.takeIf { it >= 0 } ?: existingText.length
        } else {
            existingText.length
        }
        val selectionEnd = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            node.textSelectionEnd.takeIf { it >= 0 } ?: existingText.length
        } else {
            existingText.length
        }

        Log.d(TAG, "Current text length: ${existingText.length}, cursor: $selectionStart-$selectionEnd")

        // Prepare the text to insert - add space if needed
        val textToInsert = if (existingText.isNotEmpty() &&
            selectionStart == existingText.length &&
            !existingText.endsWith(" ") &&
            !text.startsWith(" ")) {
            " $text"
        } else {
            text
        }

        // Build the new text with insertion at cursor position
        val newText = StringBuilder(existingText).apply {
            // If there's a selection, delete it first
            if (selectionStart != selectionEnd) {
                delete(selectionStart, selectionEnd)
            }
            // Insert the new text at cursor position
            insert(selectionStart, textToInsert)
        }.toString()

        Log.d(TAG, "Setting new text via character simulation: $newText")

        // Use ACTION_SET_TEXT to set the complete new text
        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                newText
            )
        }

        val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)

        if (success) {
            // Move cursor to end of inserted text
            val newCursorPos = selectionStart + textToInsert.length
            val cursorArgs = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursorPos)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursorPos)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, cursorArgs)
            Log.i(TAG, "Text injected successfully using character-by-character mode, cursor at $newCursorPos")
        } else {
            Log.e(TAG, "Character-by-character injection failed")
        }

        return success
    }

}
