package com.tinydictation.android.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * AccessibilityService for injecting transcribed text into any focused text field.
 * This service allows Tiny Dictation to work with any app on the device.
 */
class DictationAccessibilityService : AccessibilityService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We primarily use this service for text injection,
        // not for processing accessibility events.
        // Events are listened to maintain service connection.
    }

    override fun onInterrupt() {
        // Handle service interruption if needed
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        instance = null
    }

    /**
     * Injects text into the currently focused editable text field.
     * Uses ACTION_SET_TEXT if available, falls back to clipboard paste.
     *
     * @param text The text to inject
     * @return true if text was successfully injected, false otherwise
     */
    fun injectText(text: String): Boolean {
        val focusedNode = findFocusedEditableNode() ?: return false

        return try {
            val success = injectTextWithSetText(focusedNode, text)
            if (!success) {
                injectTextWithClipboard(focusedNode, text)
            } else {
                true
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

            // Restore previous clipboard content
            if (previousClip != null) {
                clipboardManager.setPrimaryClip(previousClip)
            }

            pasteSuccess
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    companion object {
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
            return instance?.injectText(text) ?: false
        }
    }
}
