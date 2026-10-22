package com.tinydictation.android.service

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.tinydictation.android.util.DebugLog
import com.tinydictation.android.util.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class DictationAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "TinyDictation"

        @Volatile
        private var instance: DictationAccessibilityService? = null

        var onKeyboardVisibilityChanged: ((Boolean) -> Unit)? = null

        fun getInstance(): DictationAccessibilityService? = instance

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

        fun isServiceRunning(): Boolean = instance != null

        fun inject(text: String): Boolean {
            Log.d(TAG, "Inject requested: $text")
            return instance?.injectText(text) ?: run {
                Log.e(TAG, "Cannot inject text - accessibility service not running")
                false
            }
        }

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
    private val mainHandler = Handler(Looper.getMainLooper())

    private var lastKnownKeyboardVisible = false

    override fun onServiceConnected() {
        preferenceManager = PreferenceManager(this)
        super.onServiceConnected()
        Log.i(TAG, "DictationAccessibilityService connected")
        instance = this

        setupKeyboardVisibilityMonitoring()
    }

    private fun setupKeyboardVisibilityMonitoring() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val controller = softKeyboardController
                controller.addOnShowModeChangedListener { _, showMode ->
                    Log.d(TAG, "Soft keyboard show mode changed: $showMode")
                    DebugLog.d("AccessibilityService: Keyboard show mode: $showMode")
                }
                Log.i(TAG, "SoftKeyboardController listener registered")
                DebugLog.i("AccessibilityService: SoftKeyboardController monitoring enabled")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to setup SoftKeyboardController", e)
                DebugLog.e("AccessibilityService: SoftKeyboardController setup failed - ${e.message}")
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                val className = event.className?.toString() ?: ""
                val packageName = event.packageName?.toString() ?: ""

                val isInputMethod = className.contains("InputMethod", ignoreCase = true) ||
                        packageName.contains(".inputmethod", ignoreCase = true) ||
                        packageName.contains("keyboard", ignoreCase = true) ||
                        packageName == "com.google.android.inputmethod.latin" ||
                        packageName == "com.samsung.android.honeyboard" ||
                        packageName == "com.swiftkey.swiftkey" ||
                        packageName == "com.touchtype.swiftkey"

                if (isInputMethod) {
                    Log.d(TAG, "InputMethod window state changed: pkg=$packageName, class=$className")
                    DebugLog.d("AccessibilityService: IME window detected: $packageName")

                    notifyKeyboardVisibility(true)
                }
            }

            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                val source = event.source
                if (source != null && source.isEditable) {
                    Log.d(TAG, "Editable field focused - keyboard may appear")
                    mainHandler.postDelayed({
                        checkKeyboardVisibilityFromFocus()
                    }, 300)
                    source.recycle()
                }
            }

            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                checkKeyboardVisibilityFromWindows()
            }
        }
    }

    private fun checkKeyboardVisibilityFromWindows() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            try {
                val windowsList = windows
                var keyboardFound = false

                for (window in windowsList) {
                    if (window.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                        keyboardFound = true
                        break
                    }
                }

                if (keyboardFound != lastKnownKeyboardVisible) {
                    Log.d(TAG, "Keyboard visibility from windows: $keyboardFound")
                    DebugLog.d("AccessibilityService: Keyboard visible (from windows): $keyboardFound")
                    notifyKeyboardVisibility(keyboardFound)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error checking keyboard visibility from windows", e)
            }
        }
    }

    private fun checkKeyboardVisibilityFromFocus() {
        checkKeyboardVisibilityFromWindows()
    }

    private fun notifyKeyboardVisibility(isVisible: Boolean) {
        if (isVisible != lastKnownKeyboardVisible) {
            lastKnownKeyboardVisible = isVisible
            Log.i(TAG, "Keyboard visibility changed: $isVisible")
            DebugLog.i("AccessibilityService: Keyboard visibility -> $isVisible")

            mainHandler.post {
                onKeyboardVisibilityChanged?.invoke(isVisible)
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "DictationAccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "DictationAccessibilityService destroyed")
        serviceScope.cancel()
        onKeyboardVisibilityChanged = null
        instance = null
    }

    fun injectText(text: String): Boolean {
        Log.d(TAG, "Injecting text: $text")
        val focusedNode = findFocusedEditableNode()
        if (focusedNode == null) {
            Log.e(TAG, "Cannot inject text - no focused editable field found")
            return false
        }

        return try {
            val simulateTyping = preferenceManager.getSimulateTyping()
            Log.d(TAG, "Simulate typing mode: $simulateTyping")

            if (simulateTyping) {
                injectTextCharByChar(focusedNode, text)
            } else {
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

    fun injectTextAsync(text: String, onResult: (Boolean) -> Unit) {
        serviceScope.launch {
            val result = injectText(text)
            onResult(result)
        }
    }

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

    private fun findFocusedEditableNode(): AccessibilityNodeInfo? {
        val focusedNode = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)

        if (focusedNode != null && focusedNode.isEditable) {
            return focusedNode
        }

        focusedNode?.recycle()
        return findEditableNodeInTree(rootInActiveWindow)
    }

    private fun findEditableNodeInTree(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null

        if (node.isEditable && node.isFocused) {
            return AccessibilityNodeInfo.obtain(node)
        }

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

    private fun injectTextWithSetText(node: AccessibilityNodeInfo, text: String): Boolean {
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

    private fun injectTextWithClipboard(node: AccessibilityNodeInfo, text: String): Boolean {
        Log.d(TAG, "Using clipboard method for text injection")
        val clipboardManager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager

        val previousClip = clipboardManager.primaryClip

        return try {
            val existingText = node.text?.toString() ?: ""
            val textToInsert = if (existingText.isNotEmpty() && !existingText.endsWith(" ")) {
                " $text"
            } else {
                text
            }

            val clip = ClipData.newPlainText("dictation", textToInsert)
            clipboardManager.setPrimaryClip(clip)

            val arguments = Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, existingText.length)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, existingText.length)
            }
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, arguments)

            val pasteSuccess = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            Log.i(TAG, "Clipboard paste ${if (pasteSuccess) "succeeded" else "failed"}")

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

    private fun injectTextCharByChar(node: AccessibilityNodeInfo, text: String): Boolean {
        Log.d(TAG, "Injecting text character-by-character: $text")

        val existingText = node.text?.toString() ?: ""

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

        val textToInsert = if (existingText.isNotEmpty() &&
            selectionStart == existingText.length &&
            !existingText.endsWith(" ") &&
            !text.startsWith(" ")) {
            " $text"
        } else {
            text
        }

        val newText = StringBuilder(existingText).apply {
            if (selectionStart != selectionEnd) {
                delete(selectionStart, selectionEnd)
            }
            insert(selectionStart, textToInsert)
        }.toString()

        Log.d(TAG, "Setting new text via character simulation: $newText")

        val arguments = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                newText
            )
        }

        val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)

        if (success) {
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
