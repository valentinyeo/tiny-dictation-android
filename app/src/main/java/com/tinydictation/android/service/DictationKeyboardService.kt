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
import android.widget.ScrollView
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
import com.tinydictation.android.ai.NextWordPredictor
import com.tinydictation.android.ai.TextImprover
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Input Method Service (IME) for Tiny Dictation.
 *
 * This provides a keyboard bar with action buttons for dictation and text manipulation.
 * Buttons: Chevron, Language, Mic, Enter, Backspace, History, Select All
 */
class DictationKeyboardService : InputMethodService(), ClipboardHistoryManager.OnClipboardHistoryChangedListener {

    companion object {
        private const val TAG = "TinyDictation"
        private const val LONG_PRESS_THRESHOLD = 500L
        private const val DOUBLE_TAP_TIMEOUT = 300L  // Max ms between taps for double-tap

        // MIC button color constants
        private const val COLOR_MIC_STREAMING = "#ED2939"    // Imperial red - actively transcribing/PTT streaming
        private const val COLOR_MIC_BUFFERING = "#FF8C00"    // Orange - PTT buffering
        private const val COLOR_MIC_PTT_IDLE = "#4169E1"     // Blue - PTT idle
        private const val COLOR_MIC_MUTED = "#808080"        // Grey - muted
        private const val COLOR_MIC_STANDBY = "#8B1C26"      // Darker imperial red - standby

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
    private var lastInterimLength = 0
    private var emojiPanelStub: ViewStub? = null
    private var emojiPanel: View? = null
    private var isEmojiPanelVisible = false
    private var isEmojiPanelInflated = false

    // Tabbed keyboard panel (7 tabs: ABC, 123, Claude, Shell, Snip, Clipboard, History)
    private var keyboardTabbedPanel: View? = null
    private var tabABC: TextView? = null
    private var tabNumpad: TextView? = null
    private var tabClaude: TextView? = null
    private var tabShell: TextView? = null
    private var tabSnippets: TextView? = null
    private var tabClipboard: TextView? = null
    private var tabHistory: TextView? = null
    private var tabIndicator: View? = null
    private var qwertyPanel: View? = null
    private var cursorNumpadPanel: View? = null
    private var claudePanel: View? = null
    private var shellPanel: View? = null
    private var snippetsPanel: View? = null
    private var clipboardPanel: View? = null
    private var historyTabPanel: View? = null  // Named differently to avoid conflict with historyPanel
    private var isKeyboardPanelVisible = false
    private var currentKeyboardTab = 0  // 0=ABC, 1=123, 2=Claude, 3=Shell, 4=Snip, 5=Clipboard, 6=History

    // Lazy loading flags for new panels
    private var isClaudePanelInflated = false
    private var isShellPanelInflated = false
    private var isSnippetsPanelInflated = false
    private var isClipboardPanelInflated = false
    private var isHistoryTabPanelInflated = false

    private var isShiftActive = false
    private var isCapsLock = false
    private var isNumbersMode = false  // Numbers/symbols keyboard mode
    private var isGermanMode = false  // German keyboard mode (umlauts on long-press)

    // Internal text buffer for apps that don't expose text (like terminal apps)
    // We track what we've typed ourselves when getTextBeforeCursor() returns empty
    private val internalTextBuffer = StringBuilder()
    private var useInternalBuffer = false  // True when app doesn't provide text context

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
    private var isMuteLocked = false  // True when mic is locked muted via long press

    // Push-to-talk mode
    private var isPushToTalkMode = false  // Persisted via PreferenceManager
    private var isPttRecording = false    // True while holding MIC in PTT mode
    private var pttStreamingStarted = false  // True once 500ms hold threshold passed
    private var pttStreamingHandler: Handler? = null
    private var pttStreamingRunnable: Runnable? = null
    private var micTouchStartedInPttMode: Boolean? = null  // locks handler for touch sequence

    // Clipboard history management
    private var clipboardHistoryManager: ClipboardHistoryManager? = null

    private var isTerminalApp = false  // True when keyboard is in a terminal app
    private var lastFinalText = ""  // Last final transcript committed (for terminal dedup)
    private var lastFinalTime = 0L  // Timestamp of last final commit (for terminal dedup)

    // Known terminal app package names (match by prefix for variant packages)
    private val terminalPackages = setOf(
        "com.server.auditor",           // Termius (legacy prefix)
        "com.termius",                  // Termius (alternate prefix)
        "org.connectbot",               // ConnectBot
        "com.sonelli.juicessh",         // JuiceSSH
        "com.termux",                   // Termux
        "yarolegovich.materialterminal", // Material Terminal
        "com.offsec.nethunter.kex"      // Kali NetHunter
    )

    /** Check if package is a terminal app (supports prefix matching for variant packages) */
    private fun isTerminalPackage(packageName: String): Boolean {
        return terminalPackages.any { packageName.startsWith(it) }
    }

    // Autocorrect
    private var autocorrectManager: AutocorrectManager? = null
    private var isUrlField = false  // Disable autocorrect in URL/URI input fields
    private var predictionStrip: LinearLayout? = null
    private var prediction1: TextView? = null
    private var prediction2: TextView? = null
    private var prediction3: TextView? = null

    // Auto AI fix (background sentence correction)
    private var isAutoFixRunning = false
    private var autoFixOriginalText: String? = null  // For undo
    private var autoFixReplacementText: String? = null
    private var autoFixUndoAvailable = false
    private val autoFixHandler = Handler(Looper.getMainLooper())
    private val AUTO_FIX_UNDO_TIMEOUT = 10000L  // Undo expires after 10 seconds
    private val AUTO_FIX_COLOR = Color.parseColor("#5B4FCF")  // Purple while fixing
    private val AUTO_FIX_DONE_COLOR = Color.parseColor("#2E7D32")  // Green when done

    // Next-word prediction (AI-powered via Haiku)
    private var nextWordPredictor: NextWordPredictor? = null
    private var activeNextWordJob: Job? = null
    private var isNextWordPredictionActive = false  // true when strip shows AI predictions

    // AI word completion (right slot of prediction strip, debounced)
    private var aiCompletionJob: Job? = null
    private val aiCompletionHandler = Handler(Looper.getMainLooper())
    private var aiCompletionWord: String? = null  // current AI completion shown in right slot
    private val AI_COMPLETION_DEBOUNCE = 200L  // ms after last keystroke before firing


    // Text improvement (AI-powered readability enhancement)
    private var textImprover: TextImprover? = null
    private var isImproving = false  // Prevent multiple concurrent improvements
    private var improveButton: FrameLayout? = null
    private var improveIcon: ImageView? = null

    // Period key long-press punctuation popup
    private var punctuationPopup: View? = null
    private var isPunctuationPopupShowing = false
    private var periodButton: android.widget.TextView? = null
    private var selectedPunctuation: String? = null
    private var punctuationTouchStartX = 0f
    private var punctuationTouchStartY = 0f
    private var punctuationExclamationView: TextView? = null
    private var punctuationQuestionView: TextView? = null
    private val PUNCTUATION_LONG_PRESS_THRESHOLD = 200L
    private val PUNCTUATION_MOVE_THRESHOLD_DP = 20f
    private val punctuationLongPressHandler = Handler(Looper.getMainLooper())

    // 'A' key long-press: German mode → popup with ä and @, English mode → @
    private var aKeyButton: android.widget.TextView? = null
    private var isAKeyLongPressTriggered = false
    private val aKeyLongPressHandler = Handler(Looper.getMainLooper())
    private var aKeyPopup: View? = null
    private var isAKeyPopupShowing = false
    private var aKeyUmlautView: android.widget.TextView? = null
    private var aKeyAtView: android.widget.TextView? = null
    private var selectedAKeyOption: String? = null
    private var aKeyTouchStartX = 0f
    private var aKeyTouchStartY = 0f
    private val aKeyLongPressRunnable = Runnable {
        isAKeyLongPressTriggered = true
        if (!isNumbersMode) {
            if (isGermanMode) {
                // German: show popup with ä and @
                showAKeyPopup()
            } else {
                // English: directly insert @
                currentInputConnection?.commitText("@", 1)
                trackTypedText("@")
                aKeyButton?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            }
        }
    }

    // 'O' key long-press for ö (German umlaut)
    private var oKeyButton: android.widget.TextView? = null
    private var isOKeyLongPressTriggered = false
    private val oKeyLongPressHandler = Handler(Looper.getMainLooper())
    private val oKeyLongPressRunnable = Runnable {
        // Insert ö on long press (respects shift/caps for Ö), skip in numbers mode
        isOKeyLongPressTriggered = true
        if (!isNumbersMode) {
            val char = if (isShiftActive || isCapsLock) "Ö" else "ö"
            currentInputConnection?.commitText(char, 1)
            trackTypedText(char)  // Track for terminal apps
            oKeyButton?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
    }

    // 'U' key long-press for ü (German umlaut)
    private var uKeyButton: android.widget.TextView? = null
    private var isUKeyLongPressTriggered = false
    private val uKeyLongPressHandler = Handler(Looper.getMainLooper())
    private val uKeyLongPressRunnable = Runnable {
        // Insert ü on long press (respects shift/caps for Ü), skip in numbers mode
        isUKeyLongPressTriggered = true
        if (!isNumbersMode) {
            val char = if (isShiftActive || isCapsLock) "Ü" else "ü"
            currentInputConnection?.commitText(char, 1)
            trackTypedText(char)  // Track for terminal apps
            uKeyButton?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
    }

    // Shift key long-press for German mode toggle
    private var shiftKeyButton: android.widget.TextView? = null
    private var isShiftLongPressTriggered = false
    private val shiftLongPressHandler = Handler(Looper.getMainLooper())
    private val shiftLongPressRunnable = Runnable {
        // Toggle German mode on long press
        isShiftLongPressTriggered = true
        isGermanMode = !isGermanMode
        shiftKeyButton?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        updateShiftKeyDisplay()
        updateLongPressHints()  // Update A key hint (ä vs @)
        DebugLog.d("DictationKeyboardService: German mode ${if (isGermanMode) "ON" else "OFF"}")
    }

    // Space bar cursor trackpad mode (long-press and drag to move cursor)
    private var spaceKeyButton: android.widget.TextView? = null
    private var isSpaceTrackpadMode = false
    private var spaceDownWord = ""  // Word captured on space touch-down for autocorrect on touch-up
    private var spaceTrackpadStartX = 0f
    private var spaceTrackpadLastX = 0f
    private var spaceTrackpadAccumulatedDelta = 0f
    private var spaceTrackpadLastMoveTime = 0L  // For velocity-based acceleration
    private val TRACKPAD_LONG_PRESS_THRESHOLD = 250L  // ms to enter trackpad mode
    private val TRACKPAD_MOVE_THRESHOLD_DP = 20f  // dp per cursor move
    private val spaceTrackpadHandler = Handler(Looper.getMainLooper())
    private val spaceTrackpadRunnable = Runnable {
        // Enter trackpad mode
        isSpaceTrackpadMode = true
        spaceKeyButton?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        DebugLog.d("DictationKeyboardService: Cursor trackpad mode ACTIVATED")
    }

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

    // Double-tap handling for mic button (enter/exit PTT)
    private var lastMicTapTime = 0L  // Timestamp of last quick tap (for double-tap detection)
    private var doubleTapHandler: Handler? = null
    private var doubleTapRunnable: Runnable? = null

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

    // Override detection: track when user is reverting an autocorrection
    // If user backspaces immediately after a correction and retypes the original, we reject it

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

        // Load push-to-talk mode from preferences
        val pttPrefs = PreferenceManager(this)
        isPushToTalkMode = pttPrefs.getPushToTalkEnabled()
        if (isPushToTalkMode) {
            DebugLog.i("DictationKeyboardService: PTT mode loaded from preferences")
        }

        // Initialize and start clipboard history manager
        clipboardHistoryManager = ClipboardHistoryManager.getInstance(this)
        clipboardHistoryManager?.startListening()

        // Initialize text improver (uses Anthropic API)
        textImprover = TextImprover(this)

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
                Handler(Looper.getMainLooper()).post {
                    if (!isFinal && text.isNotBlank()) {
                        // Skip interim results for terminal apps - they don't support
                        // deleteSurroundingText, so interim text can't be replaced
                        // and would show doubled text
                        if (isTerminalApp) return@post

                        // Interim result: show text immediately, replacing previous interim
                        val ic = currentInputConnection ?: return@post
                        // Delete previous interim text
                        if (lastInterimLength > 0) {
                            ic.deleteSurroundingText(lastInterimLength, 0)
                        }
                        // Commit new interim text
                        ic.commitText(text, 1)
                        lastInterimLength = text.length
                    } else if (isFinal && text.isNotBlank()) {
                        val ic = currentInputConnection ?: return@post

                        // Terminal dedup: skip duplicate final results within 2 seconds
                        if (isTerminalApp) {
                            val now = System.currentTimeMillis()
                            if (text == lastFinalText && now - lastFinalTime < 2000) {
                                DebugLog.d("DictationKeyboardService: Skipping duplicate final in terminal: '$text'")
                                return@post
                            }
                            lastFinalText = text
                            lastFinalTime = now
                        }

                        // Delete previous interim text before committing final
                        if (lastInterimLength > 0) {
                            ic.deleteSurroundingText(lastInterimLength, 0)
                            lastInterimLength = 0
                        }

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
                                commitTextInternal(text + " ")

                                // Save to transcription history
                                val language = prefs.getLanguage()
                                TranscriptionHistoryManager.getInstance(this@DictationKeyboardService).save(text, language)
                                DebugLog.i("DictationKeyboardService: Saved transcription to history")

                                // Skip auto-fix for dictated text - Deepgram already handles corrections
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
                            lastInterimLength = 0
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

        // Apply terminal mode if we're in a terminal app
        // (onStartInputView runs before initializeTranscriptionManager, so we must set it here)
        if (isTerminalApp) {
            transcriptionManager?.setTerminalMode(true)
            DebugLog.i("DictationKeyboardService: Applied terminal mode to new TranscriptionManager")
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "DictationKeyboardService onDestroy")
        DebugLog.i("DictationKeyboardService: Service destroyed")

        // Stop transcription if running
        transcriptionManager?.stopListening()
        transcriptionManager = null

        // Stop clipboard listening and unregister from updates
        clipboardHistoryManager?.removeOnHistoryChangedListener(this)
        clipboardHistoryManager?.stopListening()
        clipboardHistoryManager = null

        // Unregister broadcast receiver
        LocalBroadcastManager.getInstance(this).unregisterReceiver(pasteTextReceiver)

        instance = null
        doubleTapHandler?.removeCallbacksAndMessages(null)
        isBackspaceHeld = false
        backspaceHandler.removeCallbacksAndMessages(null)
        isCursorKeyHeld = false
        cursorHandler.removeCallbacksAndMessages(null)
        punctuationLongPressHandler.removeCallbacksAndMessages(null)
        aKeyLongPressHandler.removeCallbacksAndMessages(null)
        oKeyLongPressHandler.removeCallbacksAndMessages(null)
        uKeyLongPressHandler.removeCallbacksAndMessages(null)
        shiftLongPressHandler.removeCallbacksAndMessages(null)
        hidePunctuationPopup()
        hideAKeyPopup()
        pulseAnimator?.cancel()
        pulseAnimator = null

        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        Log.i(TAG, "DictationKeyboardService onCreateInputView")
        DebugLog.d("DictationKeyboardService: Creating input view")

        return try {
            // Reset lazy-loaded panel state before creating new view
            // This ensures panels are re-inflated and listeners re-registered
            clipboardHistoryManager?.removeOnHistoryChangedListener(this)
            claudePanel = null
            shellPanel = null
            snippetsPanel = null
            clipboardPanel = null
            historyTabPanel = null
            emojiPanel = null
            isClaudePanelInflated = false
            isShellPanelInflated = false
            isSnippetsPanelInflated = false
            isClipboardPanelInflated = false
            isHistoryTabPanelInflated = false
            isEmojiPanelInflated = false

            keyboardView = layoutInflater.inflate(R.layout.view_keyboard, null)

            micButton = keyboardView?.findViewById(R.id.btnKeyboardMic)
            micIcon = keyboardView?.findViewById(R.id.ivKeyboardMic)
            languageText = keyboardView?.findViewById(R.id.tvLanguage)
            emojiPanelStub = keyboardView?.findViewById(R.id.emojiPanelStub)

            // Tabbed keyboard panel (7 tabs)
            keyboardTabbedPanel = keyboardView?.findViewById(R.id.keyboardTabbedPanel)
            tabABC = keyboardTabbedPanel?.findViewById(R.id.tabABC)
            tabNumpad = keyboardTabbedPanel?.findViewById(R.id.tabNumpad)
            tabClaude = keyboardTabbedPanel?.findViewById(R.id.tabClaude)
            tabShell = keyboardTabbedPanel?.findViewById(R.id.tabShell)
            tabSnippets = keyboardTabbedPanel?.findViewById(R.id.tabSnippets)
            tabClipboard = keyboardTabbedPanel?.findViewById(R.id.tabClipboard)
            tabHistory = keyboardTabbedPanel?.findViewById(R.id.tabHistory)
            tabIndicator = keyboardTabbedPanel?.findViewById(R.id.tabIndicator)
            qwertyPanel = keyboardTabbedPanel?.findViewById(R.id.qwertyPanel)
            cursorNumpadPanel = keyboardTabbedPanel?.findViewById(R.id.cursorNumpadPanel)
            // Claude, Shell, Snippets, Clipboard, History panels are lazy-loaded via ViewStub

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
            applyCompactMode()
            applyButtonVisibility()
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
                // AMOLED black theme - Samsung-style dark gray keys on black
                backgroundColor = Color.parseColor("#000000")  // Pure black
                buttonColor = Color.parseColor("#2D2D2F")      // Samsung exact color
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

        // Apply AMOLED black background to dictation bar only
        // (Dictation bar buttons keep their XML styling from keyboard_button_background.xml)
        keyboardView?.findViewById<LinearLayout>(R.id.keyboardRoot)?.setBackgroundColor(Color.BLACK)
        keyboardView?.findViewById<LinearLayout>(R.id.keyboardContainer)?.setBackgroundColor(Color.BLACK)

        // Only the QWERTY panel gets theme-specific styling

        // Apply theme to QWERTY keyboard buttons only
        applyQwertyTheme(buttonColor, iconColor, backgroundColor)

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
            cornerRadius = 6 * resources.displayMetrics.density  // 4dp Samsung-style
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

    /**
     * Applies theme colors to QWERTY keyboard buttons.
     */
    private fun applyQwertyTheme(buttonColor: Int, textColor: Int, backgroundColor: Int) {
        // Apply to QWERTY panel background
        qwertyPanel?.setBackgroundColor(backgroundColor)

        // Apply to cursor/numpad panel background
        cursorNumpadPanel?.setBackgroundColor(backgroundColor)

        // Get all Button views in the keyboard panels and apply theme
        // Text keys (TextView)
        val textKeyIds = listOf(
            // Number row
            R.id.key_1, R.id.key_2, R.id.key_3, R.id.key_4, R.id.key_5,
            R.id.key_6, R.id.key_7, R.id.key_8, R.id.key_9, R.id.key_0,
            // QWERTY keys
            R.id.key_q, R.id.key_w, R.id.key_e, R.id.key_r, R.id.key_t,
            R.id.key_y, R.id.key_u, R.id.key_i, R.id.key_o, R.id.key_p,
            R.id.key_a, R.id.key_s, R.id.key_d, R.id.key_f, R.id.key_g,
            R.id.key_h, R.id.key_j, R.id.key_k, R.id.key_l,
            R.id.key_z, R.id.key_x, R.id.key_c, R.id.key_v, R.id.key_b,
            R.id.key_n, R.id.key_m,
            R.id.key_comma, R.id.key_space, R.id.key_period
        )

        // Accent keys (shift, numbers) - use darker accent color #1A1A1A
        val accentKeyIds = listOf(R.id.key_shift, R.id.key_numbers)

        for (id in textKeyIds) {
            qwertyPanel?.findViewById<android.widget.TextView>(id)?.let { button ->
                // Create themed background
                val backgroundDrawable = GradientDrawable().apply {
                    setColor(buttonColor)
                    cornerRadius = 6 * resources.displayMetrics.density  // 6dp Samsung-style
                }
                val rippleDrawable = RippleDrawable(
                    android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary)),
                    backgroundDrawable,
                    null
                ).mutate()
                button.background = rippleDrawable
                button.setTextColor(textColor)
            }
        }

        // Accent color for special keys (shift, numbers, backspace, enter)
        val accentColor = ContextCompat.getColor(this, R.color.dictation_bar_button)

        // Accent text keys (shift, numbers)
        for (id in accentKeyIds) {
            qwertyPanel?.findViewById<android.widget.TextView>(id)?.let { button ->
                val backgroundDrawable = GradientDrawable().apply {
                    setColor(accentColor)
                    cornerRadius = 6 * resources.displayMetrics.density
                }
                val rippleDrawable = RippleDrawable(
                    android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary)),
                    backgroundDrawable,
                    null
                ).mutate()
                button.background = rippleDrawable
                button.setTextColor(textColor)
            }
        }

        // Icon keys (ImageView) - backspace and enter - use accent color
        val iconKeyIds = listOf(R.id.key_backspace, R.id.key_enter)
        for (id in iconKeyIds) {
            qwertyPanel?.findViewById<android.widget.ImageView>(id)?.let { imageView ->
                val backgroundDrawable = GradientDrawable().apply {
                    setColor(accentColor)
                    cornerRadius = 6 * resources.displayMetrics.density  // 6dp Samsung-style
                }
                val rippleDrawable = RippleDrawable(
                    android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary)),
                    backgroundDrawable,
                    null
                ).mutate()
                imageView.background = rippleDrawable
                imageView.setColorFilter(textColor)
            }
        }

        // Apply to numpad/cursor keys
        val numpadIds = listOf(
            R.id.numpad_0, R.id.numpad_1, R.id.numpad_2, R.id.numpad_3,
            R.id.numpad_4, R.id.numpad_5, R.id.numpad_6, R.id.numpad_7,
            R.id.numpad_8, R.id.numpad_9, R.id.numpad_dot, R.id.numpad_equals,
            R.id.cursor_up, R.id.cursor_down, R.id.cursor_left, R.id.cursor_right,
            R.id.cursor_home, R.id.cursor_end, R.id.cursor_pgup, R.id.cursor_pgdn,
            R.id.cursor_tab, R.id.cursor_del, R.id.cursor_bksp, R.id.cursor_esc,
            R.id.cursor_space, R.id.cursor_enter
        )

        for (id in numpadIds) {
            cursorNumpadPanel?.findViewById<android.widget.TextView>(id)?.let { button ->
                val backgroundDrawable = GradientDrawable().apply {
                    setColor(buttonColor)
                    cornerRadius = 6 * resources.displayMetrics.density  // 4dp Samsung-style
                }
                val rippleDrawable = RippleDrawable(
                    android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary)),
                    backgroundDrawable,
                    null
                ).mutate()
                button.background = rippleDrawable
                button.setTextColor(textColor)
            }
        }
    }

    /**
     * Applies compact mode to the dictation bar if enabled.
     * Reduces button sizes by ~40% for a minimalist look.
     */
    private fun applyCompactMode() {
        val prefs = PreferenceManager(this)
        if (!prefs.getCompactDictationBar()) return

        val density = resources.displayMetrics.density

        // Compact sizes (approximately 40% reduction)
        val compactButtonSize = (32 * density).toInt()   // 48dp -> 32dp
        val compactIconSize = (16 * density).toInt()     // 24dp -> 16dp
        val compactSmallButtonWidth = (24 * density).toInt()  // 36dp -> 24dp for chevron
        val compactSmallIconSize = (12 * density).toInt()     // 18dp -> 12dp for chevron icon
        val compactPadding = (4 * density).toInt()       // 10dp -> 4dp vertical padding
        val compactMargin = (4 * density).toInt()        // 6dp -> 4dp button margins
        val compactMinHeight = (40 * density).toInt()    // 64dp -> 40dp

        // Apply to keyboard container
        keyboardView?.findViewById<LinearLayout>(R.id.keyboardContainer)?.apply {
            setPadding(paddingLeft, compactPadding, paddingRight, compactPadding)
            minimumHeight = compactMinHeight
        }

        // Dictation bar button IDs (standard size buttons)
        val standardButtons = listOf(
            R.id.btnSelectAll, R.id.btnEmoji, R.id.btnSpace, R.id.btnLanguage,
            R.id.btnBackspace, R.id.btnKeyboardMic, R.id.btnEnter,
            R.id.btnKeyboard, R.id.btnHistory, R.id.btnImprove
        )

        // Standard icons inside those buttons
        val standardIcons = listOf(
            R.id.ivSelectAll, R.id.ivEmoji, R.id.ivSpace, R.id.ivBackspace,
            R.id.ivKeyboardMic, R.id.ivEnter, R.id.ivKeyboard, R.id.ivHistory, R.id.ivImprove
        )

        // Apply compact size to standard buttons
        for (id in standardButtons) {
            keyboardView?.findViewById<FrameLayout>(id)?.let { button ->
                val layoutParams = button.layoutParams as LinearLayout.LayoutParams
                layoutParams.width = compactButtonSize
                layoutParams.height = compactButtonSize
                layoutParams.marginStart = compactMargin
                button.layoutParams = layoutParams
            }
        }

        // Apply compact size to icons
        for (id in standardIcons) {
            keyboardView?.findViewById<ImageView>(id)?.let { icon ->
                val layoutParams = icon.layoutParams
                layoutParams.width = compactIconSize
                layoutParams.height = compactIconSize
                icon.layoutParams = layoutParams
            }
        }

        // Handle chevron button (smaller button)
        keyboardView?.findViewById<FrameLayout>(R.id.btnSwitchBack)?.let { button ->
            val layoutParams = button.layoutParams as LinearLayout.LayoutParams
            layoutParams.width = compactSmallButtonWidth
            layoutParams.height = compactButtonSize
            button.layoutParams = layoutParams
        }
        keyboardView?.findViewById<ImageView>(R.id.ivSwitchBack)?.let { icon ->
            val layoutParams = icon.layoutParams
            layoutParams.width = compactSmallIconSize
            layoutParams.height = compactSmallIconSize
            icon.layoutParams = layoutParams
        }

        // Handle language text (TextView, not ImageView)
        keyboardView?.findViewById<TextView>(R.id.tvLanguage)?.let { text ->
            text.textSize = 11f  // 14sp -> 11sp
        }

        DebugLog.i("DictationKeyboardService: Compact mode applied")
    }

    /**
     * Applies button visibility settings to the dictation bar.
     * Hides buttons that the user has disabled in settings.
     */
    private fun applyButtonVisibility() {
        val prefs = PreferenceManager(this)

        // Apply visibility to each button (MIC is always visible)
        keyboardView?.findViewById<FrameLayout>(R.id.btnSwitchBack)?.visibility =
            if (prefs.getBtnSwitchBackEnabled()) View.VISIBLE else View.GONE
        keyboardView?.findViewById<FrameLayout>(R.id.btnSelectAll)?.visibility =
            if (prefs.getBtnSelectAllEnabled()) View.VISIBLE else View.GONE
        keyboardView?.findViewById<FrameLayout>(R.id.btnEmoji)?.visibility =
            if (prefs.getBtnEmojiEnabled()) View.VISIBLE else View.GONE
        keyboardView?.findViewById<FrameLayout>(R.id.btnSpace)?.visibility =
            if (prefs.getBtnSpaceEnabled()) View.VISIBLE else View.GONE
        keyboardView?.findViewById<FrameLayout>(R.id.btnLanguage)?.visibility =
            if (prefs.getBtnLanguageEnabled()) View.VISIBLE else View.GONE
        keyboardView?.findViewById<FrameLayout>(R.id.btnBackspace)?.visibility =
            if (prefs.getBtnBackspaceEnabled()) View.VISIBLE else View.GONE
        keyboardView?.findViewById<FrameLayout>(R.id.btnEnter)?.visibility =
            if (prefs.getBtnEnterEnabled()) View.VISIBLE else View.GONE
        keyboardView?.findViewById<FrameLayout>(R.id.btnKeyboard)?.visibility =
            if (prefs.getBtnKeyboardEnabled()) View.VISIBLE else View.GONE
        keyboardView?.findViewById<FrameLayout>(R.id.btnHistory)?.visibility =
            if (prefs.getBtnHistoryEnabled()) View.VISIBLE else View.GONE
        keyboardView?.findViewById<FrameLayout>(R.id.btnImprove)?.visibility =
            if (prefs.getBtnImproveEnabled()) View.VISIBLE else View.GONE

        DebugLog.i("DictationKeyboardService: Button visibility applied")
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
        // Long-press opens settings
        keyboardView?.findViewById<FrameLayout>(R.id.btnHistory)?.let { historyBtn ->
            historyBtn.setOnClickListener {
                // Show keyboard panel if hidden, then switch to History tab
                if (!isKeyboardPanelVisible) {
                    toggleKeyboardPanel()
                }
                switchToTab(4)  // History tab
            }
            historyBtn.setOnLongClickListener {
                openSettings()
                true
            }
        }

        // Enter button
        keyboardView?.findViewById<FrameLayout>(R.id.btnEnter)?.setOnClickListener {
            sendEnterKey()
        }

        // Space button - insert space
        keyboardView?.findViewById<FrameLayout>(R.id.btnSpace)?.setOnClickListener {
            autocorrectManager?.onWordBoundary()
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

        // Improve button - AI-powered text improvement
        improveButton = keyboardView?.findViewById(R.id.btnImprove)
        improveIcon = keyboardView?.findViewById(R.id.ivImprove)
        improveButton?.setOnClickListener {
            improveText()
        }
    }

    /**
     * Handles touch events for mic button.
     * Locks to the handler that received ACTION_DOWN for the entire touch sequence,
     * so a mode switch mid-touch (e.g. long-press entering PTT) doesn't confuse the UP event.
     */
    private fun handleMicTouch(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            micTouchStartedInPttMode = isPushToTalkMode
        }

        val usePtt = micTouchStartedInPttMode ?: isPushToTalkMode
        val result = if (usePtt) {
            handleMicTouchPTT(event)
        } else {
            handleMicTouchNormal(event)
        }

        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            micTouchStartedInPttMode = null
        }

        return result
    }

    /**
     * Normal mode: tap = toggle mute (with 300ms delay), double-tap = enter PTT mode.
     */
    private fun handleMicTouchNormal(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                micButton?.parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                micButton?.parent?.requestDisallowInterceptTouchEvent(false)
                if (event.action == MotionEvent.ACTION_CANCEL) return true

                val now = System.currentTimeMillis()
                if (now - lastMicTapTime < DOUBLE_TAP_TIMEOUT) {
                    // Double-tap detected - enter PTT
                    lastMicTapTime = 0L
                    doubleTapRunnable?.let { doubleTapHandler?.removeCallbacks(it) }
                    DebugLog.i("DictationKeyboardService: Double-tap detected - entering PTT")
                    enterPushToTalkMode()
                } else {
                    // First tap - schedule toggleMute after timeout (cancelled if second tap comes)
                    lastMicTapTime = now
                    doubleTapHandler = Handler(Looper.getMainLooper())
                    doubleTapRunnable = Runnable {
                        toggleMute()
                    }
                    doubleTapHandler?.postDelayed(doubleTapRunnable!!, DOUBLE_TAP_TIMEOUT)
                }
                return true
            }
        }
        return false
    }

    /**
     * PTT mode: hold = record, double-tap = exit PTT, single tap = no-op.
     */
    private fun handleMicTouchPTT(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Prevent scroll view from intercepting our hold gesture
                micButton?.parent?.requestDisallowInterceptTouchEvent(true)
                // Start buffering immediately on touch
                isPttRecording = true
                pttStreamingStarted = false
                startPttBuffering()

                // Schedule streaming start after 500ms hold
                pttStreamingHandler = Handler(Looper.getMainLooper())
                pttStreamingRunnable = Runnable {
                    pttStreamingStarted = true
                    startPttStreaming()
                }
                pttStreamingHandler?.postDelayed(pttStreamingRunnable!!, LONG_PRESS_THRESHOLD)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                micButton?.parent?.requestDisallowInterceptTouchEvent(false)
                pttStreamingRunnable?.let { pttStreamingHandler?.removeCallbacks(it) }

                if (!pttStreamingStarted && event.action == MotionEvent.ACTION_UP) {
                    // Quick tap (<500ms) - check for double-tap to exit PTT
                    stopPttBuffering(discard = true)
                    isPttRecording = false

                    val now = System.currentTimeMillis()
                    if (now - lastMicTapTime < DOUBLE_TAP_TIMEOUT) {
                        // Double-tap detected - exit PTT
                        lastMicTapTime = 0L
                        doubleTapRunnable?.let { doubleTapHandler?.removeCallbacks(it) }
                        DebugLog.i("DictationKeyboardService: Double-tap detected - exiting PTT")
                        exitPushToTalkMode()
                    } else {
                        // First tap - just record time, no action on single tap in PTT
                        lastMicTapTime = now
                    }
                } else {
                    // Release after streaming = stop recording and transcribe
                    stopPttStreaming()
                    isPttRecording = false
                    pttStreamingStarted = false
                }
                return true
            }
        }
        return false
    }

    /**
     * Unified mute toggle for normal mode. Replaces old toggleDictation + toggleMuteLock.
     */
    private fun toggleMute() {
        isMuteLocked = !isMuteLocked

        if (isMuteLocked) {
            // Stop dictation if running
            if (isRecording || isProcessing || isStandby) {
                transcriptionManager?.stopListening()
                isRecording = false
                isProcessing = false
                isStandby = false
            }
            Log.i(TAG, "Mic muted")
            DebugLog.i("DictationKeyboardService: Mic muted via tap")
        } else {
            Log.i(TAG, "Mic unmuted - starting dictation")
            DebugLog.i("DictationKeyboardService: Mic unmuted via tap")

            // Start dictation on unmute
            if (transcriptionManager == null) {
                initializeTranscriptionManager()
            }
            transcriptionManager?.let { manager ->
                try {
                    manager.startListening()
                } catch (e: Exception) {
                    Log.e(TAG, "Error starting listening after unmute", e)
                    DebugLog.e("DictationKeyboardService: Error starting after unmute - ${e.message}")
                    Toast.makeText(this, "Failed to start recording: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }

        updateMicButton()
    }

    private fun enterPushToTalkMode() {
        isPushToTalkMode = true
        PreferenceManager(this).setPushToTalkEnabled(true)

        // Stop any running dictation
        if (isRecording || isProcessing || isStandby) {
            transcriptionManager?.stopListening()
            isRecording = false
            isProcessing = false
            isStandby = false
        }
        isMuteLocked = false

        performHapticFeedback()
        Toast.makeText(this, "Push-to-talk mode", Toast.LENGTH_SHORT).show()
        Log.i(TAG, "Entered push-to-talk mode")
        DebugLog.i("DictationKeyboardService: Entered PTT mode")

        updateMicButton()
    }

    private fun exitPushToTalkMode() {
        isPushToTalkMode = false
        PreferenceManager(this).setPushToTalkEnabled(false)

        performHapticFeedback()
        Toast.makeText(this, "Normal dictation mode", Toast.LENGTH_SHORT).show()
        Log.i(TAG, "Exited push-to-talk mode")
        DebugLog.i("DictationKeyboardService: Exited PTT mode")

        // Auto-start dictation in normal mode
        if (transcriptionManager == null) {
            initializeTranscriptionManager()
        }
        transcriptionManager?.let { manager ->
            try {
                manager.startListening()
            } catch (e: Exception) {
                Log.e(TAG, "Error starting after PTT exit", e)
            }
        }

        updateMicButton()
    }

    private fun startPttBuffering() {
        if (transcriptionManager == null) {
            initializeTranscriptionManager()
        }
        transcriptionManager?.let { manager ->
            try {
                manager.startBufferingOnly()
                updateMicButton()
            } catch (e: Exception) {
                Log.e(TAG, "PTT buffering start failed", e)
                DebugLog.e("DictationKeyboardService: PTT buffering failed - ${e.message}")
                isPttRecording = false
            }
        }
    }

    private fun startPttStreaming() {
        transcriptionManager?.let { manager ->
            manager.startDirectStreaming()
            performHapticFeedback()
            updateMicButton()
        }
    }

    private fun stopPttBuffering(discard: Boolean) {
        transcriptionManager?.stopBuffering(discard)
        isRecording = false
        isProcessing = false
        isStandby = false
        updateMicButton()
    }

    private fun stopPttStreaming() {
        transcriptionManager?.stopBuffering(discard = false)
        isRecording = false
        isProcessing = false
        isStandby = false
        updateMicButton()
    }

    private fun performHapticFeedback() {
        micButton?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
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

    

    private fun toggleLanguage() {
        Log.i(TAG, "DictationKeyboardService: Toggle language")
        DebugLog.i("DictationKeyboardService: Toggling language")

        val prefs = PreferenceManager(this)
        val currentLang = prefs.getLanguage()

        // Toggle between en and de (or extend for more languages)
        val newLang = if (currentLang == "en") "de" else "en"
        prefs.setLanguage(newLang)

        // Switch autocorrect dictionary to match
        autocorrectManager?.setLanguage(newLang)

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
        val color = when {
            // PTT mode states
            isPushToTalkMode && isPttRecording && pttStreamingStarted ->
                Color.parseColor(COLOR_MIC_STREAMING)  // PTT streaming (speaking)
            isPushToTalkMode && isPttRecording && !pttStreamingStarted ->
                Color.parseColor(COLOR_MIC_BUFFERING)  // PTT buffering (keep holding...)
            isPushToTalkMode ->
                Color.parseColor(COLOR_MIC_PTT_IDLE)   // PTT idle (hold to talk)
            // Normal mode states
            isMuteLocked -> Color.parseColor(COLOR_MIC_MUTED)     // Muted
            isProcessing -> Color.parseColor(COLOR_MIC_STREAMING)  // Actively transcribing
            isStandby -> Color.parseColor(COLOR_MIC_STANDBY)      // Standby
            isRecording -> Color.parseColor(COLOR_MIC_STREAMING)   // Legacy
            else -> getThemedIconColor()  // White/themed - off
        }
        micIcon?.imageTintList = android.content.res.ColorStateList.valueOf(color)

        // Pulse animation only for PTT streaming
        if (isPushToTalkMode && isPttRecording && pttStreamingStarted) {
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
        val lang = prefs.getLanguage().uppercase()
        val langName = getLanguageDisplayName(prefs.getLanguage())

        // Update space bar language label (Samsung-style)
        spaceKeyButton?.text = langName

        // Language button always visible - user controls language via button
        languageButton?.visibility = View.VISIBLE
        languageText?.text = lang
    }

    private fun getLanguageDisplayName(langCode: String): String {
        return when (langCode) {
            "en" -> "English"
            "de" -> "Deutsch"
            else -> langCode.uppercase()
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

        // Apply number row visibility when panel becomes visible
        if (isKeyboardPanelVisible) {
            updateNumberRowVisibility()
            // Always reset to ABC tab when opening panel (no tab memory)
            if (currentKeyboardTab != 0) {
                currentKeyboardTab = 0
                updateKeyboardTabContent()
            }
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
        claudePanel?.visibility = View.GONE
        shellPanel?.visibility = View.GONE
        snippetsPanel?.visibility = View.GONE
        clipboardPanel?.visibility = View.GONE
        historyTabPanel?.visibility = View.GONE

        // Load panel visibility preferences
        val prefs = PreferenceManager(this)

        // Tab click listeners for all 7 tabs
        tabABC?.setOnClickListener { switchToTab(0) }
        tabNumpad?.setOnClickListener { switchToTab(1) }
        tabClaude?.setOnClickListener { switchToTab(2) }
        tabShell?.setOnClickListener { switchToTab(3) }
        tabSnippets?.setOnClickListener { switchToTab(4) }
        tabClipboard?.setOnClickListener { switchToTab(5) }
        tabHistory?.setOnClickListener { switchToTab(6) }

        // Hide tabs based on preferences
        tabClaude?.visibility = if (prefs.getPanelClaudeEnabled()) View.VISIBLE else View.GONE
        tabShell?.visibility = if (prefs.getPanelShellEnabled()) View.VISIBLE else View.GONE
        tabSnippets?.visibility = if (prefs.getPanelSnippetsEnabled()) View.VISIBLE else View.GONE
        tabClipboard?.visibility = if (prefs.getPanelClipboardEnabled()) View.VISIBLE else View.GONE
        tabHistory?.visibility = if (prefs.getPanelHistoryEnabled()) View.VISIBLE else View.GONE

        // Pass all touch events through to the target panel (no swipe gestures)
        val swipeOverlay = keyboardTabbedPanel?.findViewById<View>(R.id.swipeOverlay)
        swipeOverlay?.setOnTouchListener { _, event ->
            // Get the target panel for passing through events (7 tabs)
            val targetPanel = when (currentKeyboardTab) {
                0 -> qwertyPanel
                1 -> cursorNumpadPanel
                2 -> claudePanel
                3 -> shellPanel
                4 -> snippetsPanel
                5 -> clipboardPanel
                6 -> historyTabPanel
                else -> null
            }
            targetPanel?.dispatchTouchEvent(event)
            true
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
            2 -> inflateClaudePanelIfNeeded()
            3 -> inflateShellPanelIfNeeded()
            4 -> inflateSnippetsPanelIfNeeded()
            5 -> {
                inflateClipboardPanelIfNeeded()
                refreshClipboardPanel()  // Always refresh to show latest clipboard content
            }
            6 -> inflateHistoryTabPanelIfNeeded()
        }

        updateKeyboardTabContent()

    }

    private fun inflateClaudePanelIfNeeded() {
        if (!isClaudePanelInflated) {
            val stub = keyboardTabbedPanel?.findViewById<ViewStub>(R.id.claudePanelStub)
            claudePanel = stub?.inflate()
            isClaudePanelInflated = true
            setupClaudePanel()
            DebugLog.i("DictationKeyboardService: Claude panel inflated")
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

    private fun inflateSnippetsPanelIfNeeded() {
        if (!isSnippetsPanelInflated) {
            try {
                val stub = keyboardTabbedPanel?.findViewById<ViewStub>(R.id.snippetsPanelStub)
                if (stub == null) {
                    DebugLog.e("DictationKeyboardService: snippetsPanelStub not found!")
                    return
                }
                // Use themed LayoutInflater for Material components (FAB, MaterialButton)
                val themedContext = android.view.ContextThemeWrapper(this, R.style.Theme_TinyDictation)
                stub.setLayoutInflater(android.view.LayoutInflater.from(themedContext))
                snippetsPanel = stub.inflate()
                isSnippetsPanelInflated = true
                setupSnippetsPanel()
                DebugLog.i("DictationKeyboardService: Snippets panel inflated")
            } catch (e: Exception) {
                DebugLog.e("DictationKeyboardService: Failed to inflate snippets panel: ${e.message}")
                Log.e(TAG, "Failed to inflate snippets panel", e)
            }
        }
    }

    private fun inflateClipboardPanelIfNeeded() {
        if (!isClipboardPanelInflated) {
            try {
                val stub = keyboardTabbedPanel?.findViewById<ViewStub>(R.id.clipboardPanelStub)
                if (stub == null) {
                    DebugLog.e("DictationKeyboardService: clipboardPanelStub not found!")
                    return
                }
                clipboardPanel = stub.inflate()
                isClipboardPanelInflated = true
                setupClipboardPanel()
                DebugLog.i("DictationKeyboardService: Clipboard panel inflated")
            } catch (e: Exception) {
                DebugLog.e("DictationKeyboardService: Failed to inflate clipboard panel: ${e.message}")
                Log.e(TAG, "Failed to inflate clipboard panel", e)
            }
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
     * Updates the visible keyboard tab content for all 7 tabs.
     */
    private fun updateKeyboardTabContent() {
        // Hide all panels first
        qwertyPanel?.visibility = View.GONE
        cursorNumpadPanel?.visibility = View.GONE
        claudePanel?.visibility = View.GONE
        shellPanel?.visibility = View.GONE
        snippetsPanel?.visibility = View.GONE
        clipboardPanel?.visibility = View.GONE
        historyTabPanel?.visibility = View.GONE

        // Show the selected panel
        when (currentKeyboardTab) {
            0 -> qwertyPanel?.visibility = View.VISIBLE
            1 -> cursorNumpadPanel?.visibility = View.VISIBLE
            2 -> claudePanel?.visibility = View.VISIBLE
            3 -> shellPanel?.visibility = View.VISIBLE
            4 -> snippetsPanel?.visibility = View.VISIBLE
            5 -> clipboardPanel?.visibility = View.VISIBLE
            6 -> historyTabPanel?.visibility = View.VISIBLE
        }

        updateTabIndicator()
        DebugLog.d("DictationKeyboardService: Switched to keyboard tab $currentKeyboardTab")
    }

    /**
     * Updates the tab indicator position and colors for 7 tabs.
     */
    private fun updateTabIndicator() {
        val primaryColor = androidx.core.content.ContextCompat.getColor(this, R.color.primary)
        val inactiveColor = androidx.core.content.ContextCompat.getColor(this, R.color.on_surface_variant)

        // Update tab colors - active tab gets primary color
        val tabs = listOf(tabABC, tabNumpad, tabClaude, tabShell, tabSnippets, tabClipboard, tabHistory)
        tabs.forEachIndexed { index, tab ->
            tab?.setTextColor(if (index == currentKeyboardTab) primaryColor else inactiveColor)
        }

        // Update indicator position (1/7 width, positioned under selected tab)
        tabIndicator?.post {
            val parentWidth = (tabIndicator?.parent as? View)?.width ?: 0
            val indicatorWidth = parentWidth / 7
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

        // Number row and hints visibility (refreshed on each keyboard open)
        updateNumberRowVisibility()

        // Number keys - simple click handlers
        val numberKeys = listOf(
            R.id.key_1 to "1", R.id.key_2 to "2", R.id.key_3 to "3",
            R.id.key_4 to "4", R.id.key_5 to "5", R.id.key_6 to "6",
            R.id.key_7 to "7", R.id.key_8 to "8", R.id.key_9 to "9",
            R.id.key_0 to "0"
        )
        for ((id, char) in numberKeys) {
            qwertyPanel?.findViewById<android.widget.TextView>(id)?.setOnClickListener {
                currentInputConnection?.commitText(char, 1)
                trackTypedText(char)  // Track for terminal apps
            }
        }

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

        // Long-press number mappings for top row (Q=1, W=2, ... P=0)
        val longPressNumbers = mapOf(
            R.id.key_q to "1", R.id.key_w to "2", R.id.key_e to "3", R.id.key_r to "4",
            R.id.key_t to "5", R.id.key_y to "6", R.id.key_u to "7", R.id.key_i to "8",
            R.id.key_o to "9", R.id.key_p to "0"
        )

        for ((id, _) in letterKeys) {
            val button = qwertyPanel?.findViewById<android.widget.TextView>(id)
            button?.setOnClickListener {
                // Use the button's displayed text (works for both letters and numbers/symbols)
                val textToCommit = button.text.toString()
                currentInputConnection?.commitText(textToCommit, 1)
                trackTypedText(textToCommit)  // Track for terminal apps

                // Feed letter to autocorrect (only for actual letters, not numbers/symbols, not URL fields)
                if (!isNumbersMode && !isTerminalApp && !isUrlField && textToCommit.length == 1 && textToCommit[0].isLetter()) {
                    cancelNextWordPrediction()
                    autocorrectManager?.onLetterTyped(textToCommit[0])
                    requestAiCompletion()

                }

                // Turn off shift after one character (unless caps lock, and only in letters mode)
                if (!isNumbersMode && isShiftActive && !isCapsLock) {
                    isShiftActive = false
                    updateShiftKeyDisplay()
                }
            }

            // Add long-press for numbers on top row (only in letters mode)
            longPressNumbers[id]?.let { number ->
                button?.setOnLongClickListener {
                    if (!isNumbersMode) {
                        currentInputConnection?.commitText(number, 1)
                        trackTypedText(number)  // Track for terminal apps
                    }
                    true
                }
            }
        }

        // 'A' key - tap for 'a', long press for 'ä' (German umlaut)
        aKeyButton = qwertyPanel?.findViewById<android.widget.TextView>(R.id.key_a)
        aKeyButton?.setOnTouchListener { _, event ->
            handleAKeyTouch(event)
        }

        // 'O' key - tap for 'o', long press for 'ö' (German umlaut)
        oKeyButton = qwertyPanel?.findViewById<android.widget.TextView>(R.id.key_o)
        oKeyButton?.setOnTouchListener { _, event ->
            handleOKeyTouch(event)
        }

        // 'U' key - tap for 'u', long press for 'ü' (German umlaut)
        uKeyButton = qwertyPanel?.findViewById<android.widget.TextView>(R.id.key_u)
        uKeyButton?.setOnTouchListener { _, event ->
            handleUKeyTouch(event)
        }

        // Punctuation keys
        qwertyPanel?.findViewById<android.widget.TextView>(R.id.key_comma)?.setOnClickListener {
            autocorrectManager?.onWordBoundary()
            currentInputConnection?.commitText(",", 1)
            trackTypedText(",")  // Track for terminal apps
        }
        // Period key - tap for ".", long press for punctuation popup (! and ?)
        periodButton = qwertyPanel?.findViewById<android.widget.TextView>(R.id.key_period)
        periodButton?.setOnTouchListener { view, event ->
            handlePeriodTouch(view, event)
        }

        // Space bar - tap for space, long-press + drag for cursor trackpad mode
        spaceKeyButton = qwertyPanel?.findViewById<android.widget.TextView>(R.id.key_space)
        spaceKeyButton?.text = getLanguageDisplayName(PreferenceManager(this).getLanguage())
        spaceKeyButton?.setOnTouchListener { _, event ->
            handleSpaceKeyTouch(event)
        }

        // Enter key (ImageView)
        qwertyPanel?.findViewById<android.view.View>(R.id.key_enter)?.setOnClickListener {
            autocorrectManager?.onWordBoundary()
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            // Clear internal buffer after Enter (terminal command submitted)
            internalTextBuffer.clear()
            DebugLog.d("DictationKeyboardService: Enter pressed, cleared internal buffer")
        }

        // Backspace key (ImageView) - with hold-to-repeat (same behavior as dictation bar backspace)
        qwertyPanel?.findViewById<android.view.View>(R.id.key_backspace)?.setOnTouchListener { _, event ->
            handleBackspaceTouch(event)
        }

        // Shift key - tap for shift, double-tap for caps lock, long-press for German mode toggle
        shiftKeyButton = qwertyPanel?.findViewById<android.widget.TextView>(R.id.key_shift)
        shiftKeyButton?.setOnTouchListener { _, event ->
            handleShiftKeyTouch(event)
        }

        // 123/ABC key - toggle between letters and numbers/symbols
        qwertyPanel?.findViewById<android.widget.TextView>(R.id.key_numbers)?.setOnClickListener {
            toggleNumbersMode()
        }

        // Autocorrect and prediction strip
        setupAutocorrect()
    }

    /**
     * Sets up the autocorrect manager and wires up the prediction strip.
     */
    private fun setupAutocorrect() {
        // Initialize autocorrect manager (loads dictionary)
        if (autocorrectManager == null) {
            autocorrectManager = AutocorrectManager(this)
        }
        // Sync autocorrect language with current transcription language
        val currentLang = PreferenceManager(this).getLanguage()
        autocorrectManager?.setLanguage(currentLang)

        // Find prediction strip views
        predictionStrip = qwertyPanel?.findViewById(R.id.predictionStrip)
        prediction1 = qwertyPanel?.findViewById(R.id.prediction1)
        prediction2 = qwertyPanel?.findViewById(R.id.prediction2)
        prediction3 = qwertyPanel?.findViewById(R.id.prediction3)

        // Sentence prediction bar

        DebugLog.d("setupAutocorrect: strip=${predictionStrip != null}, p1=${prediction1 != null}, p2=${prediction2 != null}, p3=${prediction3 != null}")

        // Set up suggestion callback
        autocorrectManager?.onSuggestionsUpdated = { typed, suggestions ->
            updatePredictionStrip(typed, suggestions)
        }

        // Set up prediction strip tap handlers
        prediction1?.setOnClickListener { applyPrediction(0) }
        prediction2?.setOnClickListener { applyPrediction(1) }
        prediction3?.setOnClickListener { applyPrediction(2) }
    }

    /**
     * Updates the prediction strip with current word and suggestions.
     */
    private fun updatePredictionStrip(typed: String, suggestions: List<String>) {
        if (typed.isEmpty() && suggestions.isEmpty()) {
            // Clear text but keep strip visible to prevent key resizing
            prediction1?.text = ""
            prediction2?.text = ""
            // Only clear right slot if no AI completion is showing
            if (aiCompletionWord == null) {
                prediction3?.text = ""
            }
            return
        }

        // Layout: [left=alt] [MIDDLE=best] [right=AI completion]
        // Left + Middle = offline dictionary predictions
        // Right = AI (Haiku) word completion (managed separately)
        prediction1?.text = suggestions.getOrNull(1) ?: ""
        prediction2?.text = suggestions.getOrNull(0) ?: typed
        // Don't overwrite right slot - it's managed by AI completion
    }

    /**
     * Applies a prediction strip suggestion.
     * @param index 0 = left (offline alt), 1 = middle (offline best), 2 = right (AI completion)
     */
    private fun applyPrediction(index: Int) {
        val manager = autocorrectManager ?: return
        val typed = manager.getCurrentWord()
        if (typed.isEmpty()) return

        val replacement: String
        if (index == 2 && aiCompletionWord != null) {
            // Right slot = AI completion
            replacement = aiCompletionWord!!
        } else {
            // Left/middle = offline dictionary
            val suggestionIndex = when (index) {
                0 -> 1  // left = second suggestion
                1 -> 0  // middle = best suggestion
                else -> return
            }
            replacement = manager.getCachedSuggestions().getOrNull(suggestionIndex) ?: return
        }

        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        // Delete the typed word
        ic.deleteSurroundingText(typed.length, 0)
        // Apply case: uppercase first letter if original was uppercase, or "i" → "I"
        val cased = if (typed.first().isUpperCase()) {
            replacement.replaceFirstChar { it.uppercase() }
        } else if (replacement == "i") {
            "I"  // Standalone "i" is always capitalized in English
        } else {
            replacement
        }
        ic.commitText("$cased ", 1)
        ic.endBatchEdit()

        trackTypedText("$cased ")
        manager.onPredictionTapped(replacement)
        cancelAiCompletion()

        // Trigger AI next-word prediction (word + space already committed)
        requestNextWordPrediction()
    }

    /**
     * Track typed character in internal buffer (for terminal apps).
     */
    private fun trackTypedText(text: String) {
        internalTextBuffer.append(text)
        // Keep buffer reasonable size (last 100 chars)
        if (internalTextBuffer.length > 100) {
            internalTextBuffer.delete(0, internalTextBuffer.length - 100)
        }
    }

    /**
     * Handle backspace in internal buffer.
     */
    private fun trackBackspace(count: Int = 1) {
        val deleteCount = minOf(count, internalTextBuffer.length)
        if (deleteCount > 0) {
            internalTextBuffer.delete(internalTextBuffer.length - deleteCount, internalTextBuffer.length)
        }
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
            cursorNumpadPanel?.findViewById<android.widget.TextView>(id)?.setOnClickListener {
                currentInputConnection?.commitText(char, 1)
            }
        }

        // Cursor/navigation keys - with hold-to-repeat (same acceleration as backspace)
        cursorNumpadPanel?.findViewById<android.widget.TextView>(R.id.cursor_up)?.setOnTouchListener { _, event ->
            handleCursorKeyTouch(event, KeyEvent.KEYCODE_DPAD_UP)
        }
        cursorNumpadPanel?.findViewById<android.widget.TextView>(R.id.cursor_down)?.setOnTouchListener { _, event ->
            handleCursorKeyTouch(event, KeyEvent.KEYCODE_DPAD_DOWN)
        }
        cursorNumpadPanel?.findViewById<android.widget.TextView>(R.id.cursor_left)?.setOnTouchListener { _, event ->
            handleCursorKeyTouch(event, KeyEvent.KEYCODE_DPAD_LEFT)
        }
        cursorNumpadPanel?.findViewById<android.widget.TextView>(R.id.cursor_right)?.setOnTouchListener { _, event ->
            handleCursorKeyTouch(event, KeyEvent.KEYCODE_DPAD_RIGHT)
        }

        // Navigation keys
        cursorNumpadPanel?.findViewById<android.widget.TextView>(R.id.cursor_home)?.setOnClickListener {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_MOVE_HOME)
        }
        cursorNumpadPanel?.findViewById<android.widget.TextView>(R.id.cursor_end)?.setOnClickListener {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_MOVE_END)
        }
        cursorNumpadPanel?.findViewById<android.widget.TextView>(R.id.cursor_pgup)?.setOnClickListener {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_PAGE_UP)
        }
        cursorNumpadPanel?.findViewById<android.widget.TextView>(R.id.cursor_pgdn)?.setOnClickListener {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_PAGE_DOWN)
        }

        // Additional keys
        cursorNumpadPanel?.findViewById<android.widget.TextView>(R.id.cursor_tab)?.setOnClickListener {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_TAB)
        }
        cursorNumpadPanel?.findViewById<android.widget.TextView>(R.id.cursor_del)?.setOnClickListener {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_FORWARD_DEL)
        }
        // Backspace - with hold-to-repeat (same behavior as dictation bar backspace)
        cursorNumpadPanel?.findViewById<android.widget.TextView>(R.id.cursor_bksp)?.setOnTouchListener { _, event ->
            handleBackspaceTouch(event)
        }
        cursorNumpadPanel?.findViewById<android.widget.TextView>(R.id.cursor_esc)?.setOnClickListener {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ESCAPE)
        }
        cursorNumpadPanel?.findViewById<android.widget.TextView>(R.id.cursor_space)?.setOnClickListener {
            currentInputConnection?.commitText(" ", 1)
        }
        cursorNumpadPanel?.findViewById<android.widget.TextView>(R.id.cursor_enter)?.setOnClickListener {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    /**
     * Sets up the Claude commands panel - buttons insert their tag text as commands.
     */
    private fun setupClaudePanel() {
        claudePanel ?: return

        // Find all TextViews with tags and set them up to insert their tag as text
        fun setupButtonsInViewGroup(viewGroup: android.view.ViewGroup) {
            for (i in 0 until viewGroup.childCount) {
                val child = viewGroup.getChildAt(i)
                when (child) {
                    is android.widget.TextView -> {
                        val tag = child.tag as? String ?: continue
                        child.setOnClickListener {
                            // Insert the command text followed by Enter to execute
                            currentInputConnection?.commitText(tag, 1)
                            trackTypedText(tag)  // Track for terminal apps
                            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
                            // Clear internal buffer after Enter (command submitted)
                            internalTextBuffer.clear()
                            DebugLog.d("DictationKeyboardService: Claude command '$tag' inserted")
                        }
                    }
                    is android.view.ViewGroup -> {
                        setupButtonsInViewGroup(child)
                    }
                }
            }
        }

        // The panel has a ScrollView as root, get its child LinearLayout
        val scrollView = claudePanel as? ScrollView
        val contentLayout = scrollView?.getChildAt(0) as? android.view.ViewGroup ?: return
        setupButtonsInViewGroup(contentLayout)

        DebugLog.i("DictationKeyboardService: Claude panel set up")
    }

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
        fun setupButton(button: android.widget.TextView) {
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
                val button = row.getChildAt(j) as? android.widget.TextView ?: continue
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
     * Sets up the Snippets panel with saved text snippets.
     */
    private fun setupSnippetsPanel() {
        try {
            snippetsPanel ?: return

            val recyclerView = snippetsPanel?.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.snippetsRecyclerView)
            val emptyState = snippetsPanel?.findViewById<View>(R.id.snippetsEmptyState)

            if (recyclerView == null) {
                DebugLog.e("DictationKeyboardService: snippetsRecyclerView not found!")
                return
            }

            // Set up GridLayoutManager with 2 columns
            recyclerView.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 2)

            // Load snippets and set up adapter
            loadSnippetsContent(recyclerView, emptyState)

            // Set up FAB for adding new snippet
            val fabAddSnippet = snippetsPanel?.findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.fabAddSnippet)
            fabAddSnippet?.setOnClickListener {
                showAddSnippetDialog()
            }

            // Set up empty state button for creating first snippet
            val btnCreateSnippetEmpty = snippetsPanel?.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnCreateSnippetEmpty)
            btnCreateSnippetEmpty?.setOnClickListener {
                showAddSnippetDialog()
            }

            DebugLog.i("DictationKeyboardService: Snippets panel set up")
        } catch (e: Exception) {
            DebugLog.e("DictationKeyboardService: Failed to setup snippets panel: ${e.message}")
            Log.e(TAG, "Failed to setup snippets panel", e)
        }
    }

    /**
     * Loads snippets into the Snippets tab RecyclerView.
     * Snippets are sorted alphabetically (case-insensitive).
     */
    private fun loadSnippetsContent(recyclerView: androidx.recyclerview.widget.RecyclerView, emptyState: View?) {
        val snippetManager = com.tinydictation.android.snippet.SnippetManager.getInstance(this)
        // Returns alphabetically sorted snippets
        val snippets = snippetManager.getAll()

        if (snippets.isEmpty()) {
            emptyState?.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyState?.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE

            recyclerView.adapter = SnippetsTabAdapter(
                snippets = snippets,
                onItemClick = { snippet ->
                    // Tap to paste directly
                    commitTextInternal(snippet)
                },
                onItemDelete = { snippet ->
                    snippetManager.delete(snippet)
                    refreshSnippetsPanel()
                    android.widget.Toast.makeText(this, R.string.snippet_deleted, android.widget.Toast.LENGTH_SHORT).show()
                }
            )
        }
    }

    /**
     * Refreshes the snippets panel content.
     */
    private fun refreshSnippetsPanel() {
        val recyclerView = snippetsPanel?.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.snippetsRecyclerView)
        val emptyState = snippetsPanel?.findViewById<View>(R.id.snippetsEmptyState)
        recyclerView?.let { loadSnippetsContent(it, emptyState) }
    }

    /**
     * Shows a dialog for manually creating a new snippet.
     */
    private fun showAddSnippetDialog() {
        try {
            val themedContext = android.view.ContextThemeWrapper(this, R.style.Theme_TinyDictation)

            val editText = android.widget.EditText(themedContext).apply {
                hint = "Enter snippet text"
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 3
                maxLines = 6
                setPadding(48, 32, 48, 32)
            }

            val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(themedContext)
                .setTitle("New Snippet")
                .setView(editText)
                .setPositiveButton("Save") { _, _ ->
                    val text = editText.text.toString().trim()
                    if (text.isNotBlank()) {
                        val snippetManager = com.tinydictation.android.snippet.SnippetManager.getInstance(this)
                        if (snippetManager.add(text)) {
                            refreshSnippetsPanel()
                            android.widget.Toast.makeText(this, R.string.snippet_saved, android.widget.Toast.LENGTH_SHORT).show()
                            DebugLog.i("DictationKeyboardService: Snippet created manually: ${text.take(30)}...")
                        } else {
                            android.widget.Toast.makeText(this, R.string.snippet_already_exists, android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("Cancel", null)
                .create()
            dialog.window?.setType(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            dialog.show()
        } catch (e: Exception) {
            DebugLog.e("DictationKeyboardService: Failed to show add snippet dialog: ${e.message}")
            Log.e(TAG, "Failed to show add snippet dialog", e)
        }
    }

    /**
     * Adapter for Snippets tab RecyclerView.
     */
    private inner class SnippetsTabAdapter(
        private val snippets: List<String>,
        private val onItemClick: (String) -> Unit,
        private val onItemDelete: (String) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<SnippetsTabAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
            // Use themed context for Material components
            val themedContext = android.view.ContextThemeWrapper(parent.context, R.style.Theme_TinyDictation)
            val inflater = android.view.LayoutInflater.from(themedContext)
            val view = inflater.inflate(R.layout.item_snippet, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(snippets[position])
        }

        override fun getItemCount(): Int = snippets.size

        inner class ViewHolder(itemView: View) : androidx.recyclerview.widget.RecyclerView.ViewHolder(itemView) {
            private val tvSnippetText: android.widget.TextView? = itemView.findViewById(R.id.tvSnippetText)

            fun bind(snippet: String) {
                tvSnippetText?.text = snippet

                // Tap item to paste
                itemView.setOnClickListener { onItemClick(snippet) }

                // Long-press for delete option
                itemView.setOnLongClickListener { view ->
                    showSnippetItemMenu(view, snippet)
                    true
                }
            }

            private fun showSnippetItemMenu(anchor: View, snippet: String) {
                val popup = android.widget.PopupMenu(anchor.context, anchor)
                popup.menu.add(R.string.delete)

                popup.setOnMenuItemClickListener { item ->
                    when (item.title) {
                        anchor.context.getString(R.string.delete) -> {
                            onItemDelete(snippet)
                            true
                        }
                        else -> false
                    }
                }
                popup.show()
            }
        }
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

            // Register for real-time clipboard updates
            clipboardHistoryManager?.addOnHistoryChangedListener(this)

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
                },
                onSaveAsSnippet = { entry ->
                    val snippetManager = com.tinydictation.android.snippet.SnippetManager.getInstance(this)
                    if (snippetManager.add(entry.text)) {
                        android.widget.Toast.makeText(this, R.string.snippet_saved, android.widget.Toast.LENGTH_SHORT).show()
                        refreshSnippetsPanel()
                    } else {
                        android.widget.Toast.makeText(this, R.string.snippet_already_exists, android.widget.Toast.LENGTH_SHORT).show()
                    }
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
     * Called when clipboard history changes (new item copied).
     * Updates the clipboard panel in real-time if it's visible.
     */
    override fun onClipboardHistoryChanged() {
        // Run on main thread since this may be called from a background context
        Handler(Looper.getMainLooper()).post {
            if (isClipboardPanelInflated && clipboardPanel?.visibility == View.VISIBLE) {
                refreshClipboardPanel()
                DebugLog.d("DictationKeyboardService: Clipboard panel refreshed (real-time update)")
            }
        }
    }

    /**
     * Adapter for Clipboard tab RecyclerView with pin toggle support.
     */
    private inner class ClipboardTabAdapter(
        private val entries: List<com.tinydictation.android.history.ClipboardEntry>,
        private val onItemClick: (com.tinydictation.android.history.ClipboardEntry) -> Unit,
        private val onPinToggle: (com.tinydictation.android.history.ClipboardEntry) -> Unit,
        private val onItemDelete: (com.tinydictation.android.history.ClipboardEntry) -> Unit,
        private val onSaveAsSnippet: (com.tinydictation.android.history.ClipboardEntry) -> Unit
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
                popup.menu.add(R.string.save_as_snippet)
                popup.menu.add(R.string.delete)

                popup.setOnMenuItemClickListener { item ->
                    when (item.title) {
                        anchor.context.getString(R.string.pin),
                        anchor.context.getString(R.string.unpin) -> {
                            onPinToggle(entry)
                            true
                        }
                        anchor.context.getString(R.string.save_as_snippet) -> {
                            onSaveAsSnippet(entry)
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
                qwertyPanel?.findViewById<android.widget.TextView>(id)?.text = char
            }
            // Update mode switch button to "ABC"
            qwertyPanel?.findViewById<android.widget.TextView>(R.id.key_numbers)?.text = "ABC"
            // Hide shift key text (not applicable in numbers mode)
            qwertyPanel?.findViewById<android.widget.TextView>(R.id.key_shift)?.text = "#+="
        } else {
            // Update to letters
            val useUppercase = isShiftActive || isCapsLock
            for ((id, char) in lettersRow1 + lettersRow2 + lettersRow3) {
                qwertyPanel?.findViewById<android.widget.TextView>(id)?.text =
                    if (useUppercase) char.uppercase() else char
            }
            // Update mode switch button to "123"
            qwertyPanel?.findViewById<android.widget.TextView>(R.id.key_numbers)?.text = "123"
            // Restore shift key
            updateShiftKeyDisplay()
        }
        // Update long-press hint visibility (umlauts hidden in numbers mode)
        updateLongPressHints()
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
                qwertyPanel?.findViewById<android.widget.TextView>(id)?.text = char
            }
            qwertyPanel?.findViewById<android.widget.TextView>(R.id.key_shift)?.text = "123"
        } else {
            for ((id, char) in primaryRow1 + primaryRow2 + primaryRow3) {
                qwertyPanel?.findViewById<android.widget.TextView>(id)?.text = char
            }
            qwertyPanel?.findViewById<android.widget.TextView>(R.id.key_shift)?.text = "#+="
        }
    }

    /**
     * Updates the shift key display based on current state.
     * Shows German mode indicator (DE) when active.
     */
    private fun updateShiftKeyDisplay() {
        val shiftButton = qwertyPanel?.findViewById<android.widget.TextView>(R.id.key_shift)
        shiftButton?.text = when {
            isGermanMode && isCapsLock -> "DE⇪"  // German mode + caps lock
            isGermanMode && isShiftActive -> "DE⇧"  // German mode + shift active
            isGermanMode -> "DE"  // German mode indicator
            isCapsLock -> "⇪"  // Caps lock indicator
            isShiftActive -> "⇧"  // Shift active (filled)
            else -> "⇧"  // Normal shift
        }

        // Update letter key displays to show uppercase when shift/caps active
        updateLetterKeyDisplays()
    }

    /**
     * Updates number row visibility and number hints on top row keys.
     * Called on keyboard open to apply latest settings.
     */
    private fun updateNumberRowVisibility() {
        val prefs = PreferenceManager(this)
        val showNumberRow = prefs.getShowNumberRow()

        DebugLog.d("DictationKeyboardService: updateNumberRowVisibility - showNumberRow=$showNumberRow, qwertyPanel=${qwertyPanel != null}")

        // Update number row visibility
        val numberRow = qwertyPanel?.findViewById<LinearLayout>(R.id.numberRow)
        DebugLog.d("DictationKeyboardService: numberRow found=${numberRow != null}")
        numberRow?.visibility = if (showNumberRow) View.VISIBLE else View.GONE

        // Update number hints visibility (show hints when number row is hidden)
        val hintVisibility = if (showNumberRow) View.GONE else View.VISIBLE
        val hintIds = listOf(
            R.id.hint_q, R.id.hint_w, R.id.hint_e, R.id.hint_r, R.id.hint_t,
            R.id.hint_y, R.id.hint_u, R.id.hint_i, R.id.hint_o, R.id.hint_p
        )
        for (id in hintIds) {
            qwertyPanel?.findViewById<android.widget.TextView>(id)?.visibility = hintVisibility
        }
    }

    /**
     * Updates long-press hint visibility on keys with umlaut long-press actions.
     * Hints are shown in letters mode and hidden in numbers/symbols mode.
     */
    private fun updateLongPressHints() {
        val hintVisibility = if (isNumbersMode) View.GONE else View.VISIBLE
        val umlauthintIds = listOf(R.id.hint_a, R.id.hint_o_umlaut, R.id.hint_u_umlaut)
        for (id in umlauthintIds) {
            qwertyPanel?.findViewById<android.widget.TextView>(id)?.visibility = hintVisibility
        }
        // A key hint: show "@" in English, "ä" in German
        val aHint = qwertyPanel?.findViewById<android.widget.TextView>(R.id.hint_a)
        aHint?.text = if (isGermanMode) "ä" else "@"
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
            qwertyPanel?.findViewById<android.widget.TextView>(id)?.text =
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

    // Runnable for punctuation popup long-press
    private val punctuationLongPressRunnable = Runnable {
        showPunctuationPopup()
    }

    /**
     * Handles touch events for the 'a' key.
     * - Tap: insert 'a' (or 'A' if shifted)
     * - Long press (English): insert '@'
     * - Long press (German): show popup with 'ä' and '@', drag to select
     */
    private fun handleAKeyTouch(event: MotionEvent): Boolean {
        val moveThreshold = PUNCTUATION_MOVE_THRESHOLD_DP * resources.displayMetrics.density

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isAKeyLongPressTriggered = false
                selectedAKeyOption = null
                aKeyTouchStartX = event.rawX
                aKeyTouchStartY = event.rawY
                aKeyLongPressHandler.postDelayed(
                    aKeyLongPressRunnable,
                    PUNCTUATION_LONG_PRESS_THRESHOLD
                )
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isAKeyPopupShowing) {
                    updateAKeySelection(event.rawX, event.rawY)
                } else {
                    val dx = event.rawX - aKeyTouchStartX
                    val dy = event.rawY - aKeyTouchStartY
                    if (Math.sqrt((dx * dx + dy * dy).toDouble()) > moveThreshold) {
                        aKeyLongPressHandler.removeCallbacks(aKeyLongPressRunnable)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                aKeyLongPressHandler.removeCallbacks(aKeyLongPressRunnable)

                if (isAKeyPopupShowing) {
                    // Popup mode: commit selected option or default to ä
                    val char = selectedAKeyOption ?: (if (isShiftActive || isCapsLock) "Ä" else "ä")
                    currentInputConnection?.commitText(char, 1)
                    trackTypedText(char)
                    hideAKeyPopup()
                    DebugLog.d("DictationKeyboardService: A key popup selected '$char'")
                } else if (!isAKeyLongPressTriggered) {
                    // Short tap - use displayed text
                    val char = aKeyButton?.text?.toString() ?: "a"
                    currentInputConnection?.commitText(char, 1)
                    trackTypedText(char)

                    if (!isNumbersMode && !isTerminalApp && !isUrlField && char.length == 1 && char[0].isLetter()) {
                        cancelNextWordPrediction()
                        autocorrectManager?.onLetterTyped(char[0])
                        requestAiCompletion()

                    }

                    if (!isNumbersMode && isShiftActive && !isCapsLock) {
                        isShiftActive = false
                        updateShiftKeyDisplay()
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                aKeyLongPressHandler.removeCallbacks(aKeyLongPressRunnable)
                hideAKeyPopup()
                return true
            }
        }
        return false
    }

    /**
     * Shows the A key popup above the key with ä and @ options.
     */
    private fun showAKeyPopup() {
        isAKeyPopupShowing = true
        selectedAKeyOption = null

        aKeyButton ?: return
        val density = resources.displayMetrics.density

        aKeyPopup = createAKeyPopupView()

        aKeyPopup?.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popupWidth = aKeyPopup?.measuredWidth ?: 0
        val popupHeight = aKeyPopup?.measuredHeight ?: 0

        val buttonLocation = IntArray(2)
        val panelLocation = IntArray(2)
        aKeyButton?.getLocationInWindow(buttonLocation)
        keyboardTabbedPanel?.getLocationInWindow(panelLocation)

        val relativeX = buttonLocation[0] - panelLocation[0]
        val relativeY = buttonLocation[1] - panelLocation[1]
        val buttonWidth = aKeyButton?.width ?: 0

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = (relativeX + (buttonWidth / 2) - (popupWidth / 2)).coerceAtLeast(0)
            topMargin = relativeY - popupHeight - (8 * density).toInt()
        }

        val container = keyboardTabbedPanel?.findViewById<FrameLayout>(R.id.keyboardPagesContainer)
        container?.addView(aKeyPopup, params)

        aKeyButton?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        DebugLog.d("DictationKeyboardService: Showing A key popup (ä / @)")
    }

    /**
     * Creates the A key popup view with ä and @ options.
     */
    private fun createAKeyPopupView(): View {
        val density = resources.displayMetrics.density
        val popupWidth = (120 * density).toInt()
        val popupHeight = (56 * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(popupWidth, popupHeight)
            gravity = android.view.Gravity.CENTER
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(ContextCompat.getColor(this@DictationKeyboardService, R.color.surface))
                cornerRadius = 12 * density
                setStroke((1 * density).toInt(), ContextCompat.getColor(this@DictationKeyboardService, R.color.outline))
            }
            elevation = 8 * density
        }

        val umlautChar = if (isShiftActive || isCapsLock) "Ä" else "ä"
        val umlautText = android.widget.TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                (60 * density).toInt(),
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            text = umlautChar
            textSize = 24f
            gravity = android.view.Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@DictationKeyboardService, R.color.on_surface))
            tag = umlautChar
            id = View.generateViewId()
        }

        val atText = android.widget.TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                (60 * density).toInt(),
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            text = "@"
            textSize = 24f
            gravity = android.view.Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@DictationKeyboardService, R.color.on_surface))
            tag = "@"
            id = View.generateViewId()
        }

        container.addView(umlautText)
        container.addView(atText)

        aKeyUmlautView = umlautText
        aKeyAtView = atText

        return container
    }

    /**
     * Updates the selected A key option based on finger position.
     */
    private fun updateAKeySelection(rawX: Float, rawY: Float) {
        aKeyPopup ?: return

        val location = IntArray(2)
        aKeyPopup?.getLocationOnScreen(location)
        val popupX = location[0]
        val popupCenterX = popupX + (aKeyPopup?.width ?: 0) / 2

        val highlightColor = ContextCompat.getColor(this, R.color.primary)
        val normalColor = ContextCompat.getColor(this, R.color.on_surface)

        if (rawX < popupCenterX) {
            // Left side: umlaut
            val umlautChar = if (isShiftActive || isCapsLock) "Ä" else "ä"
            selectedAKeyOption = umlautChar
            aKeyUmlautView?.setTextColor(highlightColor)
            aKeyAtView?.setTextColor(normalColor)
        } else {
            // Right side: @
            selectedAKeyOption = "@"
            aKeyUmlautView?.setTextColor(normalColor)
            aKeyAtView?.setTextColor(highlightColor)
        }
    }

    /**
     * Hides and cleans up the A key popup.
     */
    private fun hideAKeyPopup() {
        isAKeyPopupShowing = false
        selectedAKeyOption = null

        aKeyPopup?.let { popup ->
            (popup.parent as? android.view.ViewGroup)?.removeView(popup)
        }
        aKeyPopup = null
        aKeyUmlautView = null
        aKeyAtView = null
    }

    /**
     * Handles touch events for the 'o' key with long-press for ö (German umlaut).
     * - Tap: insert 'o' (or 'O' if shifted)
     * - Long press (200ms): insert 'ö' (or 'Ö' if shifted)
     */
    private fun handleOKeyTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isOKeyLongPressTriggered = false
                // Start long-press timer
                oKeyLongPressHandler.postDelayed(
                    oKeyLongPressRunnable,
                    PUNCTUATION_LONG_PRESS_THRESHOLD
                )
                return true
            }
            MotionEvent.ACTION_UP -> {
                oKeyLongPressHandler.removeCallbacks(oKeyLongPressRunnable)

                if (!isOKeyLongPressTriggered) {
                    // Short tap - use displayed text (handles both letters and numbers/symbols mode)
                    val char = oKeyButton?.text?.toString() ?: "o"
                    currentInputConnection?.commitText(char, 1)
                    trackTypedText(char)  // Track for terminal apps

                    if (!isNumbersMode && !isTerminalApp && !isUrlField && char.length == 1 && char[0].isLetter()) {
                        cancelNextWordPrediction()
                        autocorrectManager?.onLetterTyped(char[0])
                        requestAiCompletion()

                    }

                    // Turn off shift after one character (unless caps lock)
                    if (!isNumbersMode && isShiftActive && !isCapsLock) {
                        isShiftActive = false
                        updateShiftKeyDisplay()
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                oKeyLongPressHandler.removeCallbacks(oKeyLongPressRunnable)
                return true
            }
        }
        return false
    }

    /**
     * Handles touch events for the 'u' key with long-press for ü (German umlaut).
     * - Tap: insert 'u' (or 'U' if shifted)
     * - Long press (200ms): insert 'ü' (or 'Ü' if shifted)
     */
    private fun handleUKeyTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isUKeyLongPressTriggered = false
                // Start long-press timer
                uKeyLongPressHandler.postDelayed(
                    uKeyLongPressRunnable,
                    PUNCTUATION_LONG_PRESS_THRESHOLD
                )
                return true
            }
            MotionEvent.ACTION_UP -> {
                uKeyLongPressHandler.removeCallbacks(uKeyLongPressRunnable)

                if (!isUKeyLongPressTriggered) {
                    // Short tap - use displayed text (handles both letters and numbers/symbols mode)
                    val char = uKeyButton?.text?.toString() ?: "u"
                    currentInputConnection?.commitText(char, 1)
                    trackTypedText(char)  // Track for terminal apps

                    if (!isNumbersMode && !isTerminalApp && !isUrlField && char.length == 1 && char[0].isLetter()) {
                        cancelNextWordPrediction()
                        autocorrectManager?.onLetterTyped(char[0])
                        requestAiCompletion()

                    }

                    // Turn off shift after one character (unless caps lock)
                    if (!isNumbersMode && isShiftActive && !isCapsLock) {
                        isShiftActive = false
                        updateShiftKeyDisplay()
                    }
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                uKeyLongPressHandler.removeCallbacks(uKeyLongPressRunnable)
                return true
            }
        }
        return false
    }

    // Track last shift tap time for double-tap detection
    private var lastShiftTapTime = 0L

    // Track last space tap time for double-tap correction trigger

    /**
     * Handles touch events for the shift key.
     * - Tap: toggle shift (or secondary symbols in numbers mode)
     * - Double-tap: toggle caps lock
     * - Long press (200ms): toggle German umlaut mode
     */
    private fun handleShiftKeyTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isShiftLongPressTriggered = false
                // Start long-press timer for German mode toggle
                shiftLongPressHandler.postDelayed(
                    shiftLongPressRunnable,
                    PUNCTUATION_LONG_PRESS_THRESHOLD
                )
                return true
            }
            MotionEvent.ACTION_UP -> {
                shiftLongPressHandler.removeCallbacks(shiftLongPressRunnable)

                if (!isShiftLongPressTriggered) {
                    // Short tap - normal shift behavior
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
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                shiftLongPressHandler.removeCallbacks(shiftLongPressRunnable)
                return true
            }
        }
        return false
    }

    /**
     * Handles touch events for the space key with cursor trackpad mode.
     * - Tap: insert space
     * - Long press (250ms): enter cursor trackpad mode
     * - Drag in trackpad mode: move cursor left/right (20dp = 1 cursor move)
     * - Release: exit trackpad mode or insert space if not activated
     */
    private fun handleSpaceKeyTouch(event: MotionEvent): Boolean {
        val moveThreshold = TRACKPAD_MOVE_THRESHOLD_DP * resources.displayMetrics.density

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Reset state
                isSpaceTrackpadMode = false
                spaceTrackpadStartX = event.rawX
                spaceTrackpadLastX = event.rawX
                spaceTrackpadAccumulatedDelta = 0f
                spaceTrackpadLastMoveTime = System.currentTimeMillis()

                // Snapshot the current word and clear the autocorrect buffer immediately.
                // This prevents fast typing from appending the next word's letters to the
                // previous word (e.g. "is" + fast "p" → "isp" instead of "is" + "p").
                // The saved word is used on ACTION_UP for autocorrect.
                spaceDownWord = autocorrectManager?.getCurrentWord() ?: ""
                autocorrectManager?.onWordBoundary()

                // Start long-press timer
                spaceTrackpadHandler.postDelayed(
                    spaceTrackpadRunnable,
                    TRACKPAD_LONG_PRESS_THRESHOLD
                )
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isSpaceTrackpadMode) {
                    // In trackpad mode - move cursor based on drag distance with velocity-based acceleration
                    val currentTime = System.currentTimeMillis()
                    val timeDelta = (currentTime - spaceTrackpadLastMoveTime).coerceAtLeast(1)
                    val deltaX = event.rawX - spaceTrackpadLastX

                    // Calculate velocity (pixels per millisecond)
                    val velocity = Math.abs(deltaX) / timeDelta

                    // Velocity-based acceleration: slow finger = 1x, fast finger = up to 4x
                    // velocity of ~0.5 px/ms (moderate speed) = 1.5x
                    // velocity of ~2.0 px/ms (fast swipe) = 3x
                    val acceleration = (1f + velocity * 1.5f).coerceIn(1f, 4f)

                    spaceTrackpadAccumulatedDelta += deltaX * acceleration
                    spaceTrackpadLastX = event.rawX
                    spaceTrackpadLastMoveTime = currentTime

                    // Check if accumulated movement exceeds threshold
                    while (Math.abs(spaceTrackpadAccumulatedDelta) >= moveThreshold) {
                        if (spaceTrackpadAccumulatedDelta >= moveThreshold) {
                            // Move cursor right
                            sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
                            spaceTrackpadAccumulatedDelta -= moveThreshold
                            spaceKeyButton?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        } else if (spaceTrackpadAccumulatedDelta <= -moveThreshold) {
                            // Move cursor left
                            sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT)
                            spaceTrackpadAccumulatedDelta += moveThreshold
                            spaceKeyButton?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                    }
                } else {
                    // Not in trackpad mode yet - check if moved too far before long press
                    val dx = event.rawX - spaceTrackpadStartX
                    if (Math.abs(dx) > moveThreshold * 2) {
                        // Moved too far - cancel long press timer
                        spaceTrackpadHandler.removeCallbacks(spaceTrackpadRunnable)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                spaceTrackpadHandler.removeCallbacks(spaceTrackpadRunnable)

                if (!isSpaceTrackpadMode) {
                    // Cancel any active AI predictions
                    cancelNextWordPrediction()
                    cancelAiCompletion()

                    // Use the word captured on touch-down (not getCurrentWord which was
                    // cleared on DOWN to prevent fast-typing race conditions)
                    val typed = spaceDownWord

                    if (isTerminalApp || isUrlField) {
                        // Terminal apps / URL fields: no autocorrect, no predictions, just space
                        currentInputConnection?.commitText(" ", 1)
                        trackTypedText(" ")
                    } else {
                        // Check autocorrect using the saved word
                        val correction = if (typed.length >= 2 && !autocorrectManager?.isKnownWord(typed)!!) {
                            autocorrectManager?.tryCorrectWord(typed)
                        } else null

                        if (correction != null) {
                            val ic = currentInputConnection
                            if (ic != null) {
                                ic.beginBatchEdit()
                                ic.deleteSurroundingText(typed.length, 0)
                                // Preserve case from original
                                val cased = if (typed.first().isUpperCase()) {
                                    correction.replaceFirstChar { it.uppercase() }
                                } else {
                                    correction
                                }
                                ic.commitText("$cased ", 1)
                                ic.endBatchEdit()
                                trackTypedText("$cased ")
                                autocorrectManager?.recordAutocorrect(typed, correction)
                            }
                        } else {
                            // Auto-capitalize standalone "i" → "I"
                            if (typed == "i") {
                                val ic = currentInputConnection
                                if (ic != null) {
                                    ic.beginBatchEdit()
                                    ic.deleteSurroundingText(1, 0)
                                    ic.commitText("I ", 1)
                                    ic.endBatchEdit()
                                    trackTypedText("I ")
                                }
                            } else {
                                currentInputConnection?.commitText(" ", 1)
                                trackTypedText(" ")
                            }
                        }

                        // Request AI predictions (non-terminal only)
                        requestNextWordPrediction()

                    }
                } else {
                    DebugLog.d("DictationKeyboardService: Cursor trackpad mode DEACTIVATED")
                }
                isSpaceTrackpadMode = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                spaceTrackpadHandler.removeCallbacks(spaceTrackpadRunnable)
                isSpaceTrackpadMode = false
                return true
            }
        }
        return false
    }

    /**
     * Handles touch events for the period key with long-press punctuation popup.
     * - Tap: insert "."
     * - Long press: show popup with ! (upper-left) and ? (upper-right)
     * - Drag: select punctuation based on finger position
     * - Release: commit selected punctuation or "." if no selection
     */
    private fun handlePeriodTouch(view: View, event: MotionEvent): Boolean {
        val moveThreshold = PUNCTUATION_MOVE_THRESHOLD_DP * resources.displayMetrics.density

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                // Reset state
                selectedPunctuation = null
                isPunctuationPopupShowing = false

                // Record initial touch position
                punctuationTouchStartX = event.rawX
                punctuationTouchStartY = event.rawY

                // Start long-press timer
                punctuationLongPressHandler.postDelayed(
                    punctuationLongPressRunnable,
                    PUNCTUATION_LONG_PRESS_THRESHOLD
                )
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isPunctuationPopupShowing) {
                    // Update selection based on drag position
                    updatePunctuationSelection(event.rawX, event.rawY)
                } else {
                    // Check if moved too far before long press triggered - cancel
                    val dx = event.rawX - punctuationTouchStartX
                    val dy = event.rawY - punctuationTouchStartY
                    if (Math.sqrt((dx * dx + dy * dy).toDouble()) > moveThreshold) {
                        punctuationLongPressHandler.removeCallbacks(punctuationLongPressRunnable)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                punctuationLongPressHandler.removeCallbacks(punctuationLongPressRunnable)
                autocorrectManager?.onWordBoundary()

                if (isPunctuationPopupShowing) {
                    // Commit selected punctuation
                    val char = selectedPunctuation ?: "."
                    currentInputConnection?.commitText(char, 1)
                    hidePunctuationPopup()
                    DebugLog.d("DictationKeyboardService: Punctuation popup selected '$char'")
                    triggerAutoFix()
                } else {
                    // Short tap - insert period
                    currentInputConnection?.commitText(".", 1)
                    // Trigger auto AI fix after sentence end
                    triggerAutoFix()
                }
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                punctuationLongPressHandler.removeCallbacks(punctuationLongPressRunnable)
                hidePunctuationPopup()
                return true
            }
        }
        return false
    }

    /**
     * Shows the punctuation popup above the period key.
     */
    private fun showPunctuationPopup() {
        isPunctuationPopupShowing = true
        selectedPunctuation = null

        periodButton ?: return
        val density = resources.displayMetrics.density

        // Create popup view programmatically
        punctuationPopup = createPunctuationPopupView()

        // Measure popup
        punctuationPopup?.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popupWidth = punctuationPopup?.measuredWidth ?: 0
        val popupHeight = punctuationPopup?.measuredHeight ?: 0

        // Get button position relative to keyboardTabbedPanel
        val buttonLocation = IntArray(2)
        val panelLocation = IntArray(2)
        periodButton?.getLocationInWindow(buttonLocation)
        keyboardTabbedPanel?.getLocationInWindow(panelLocation)

        val relativeX = buttonLocation[0] - panelLocation[0]
        val relativeY = buttonLocation[1] - panelLocation[1]
        val buttonWidth = periodButton?.width ?: 0

        // Position centered above button
        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = relativeX + (buttonWidth / 2) - (popupWidth / 2)
            topMargin = relativeY - popupHeight - (8 * density).toInt()
        }

        // Add to keyboardPagesContainer (FrameLayout)
        val container = keyboardTabbedPanel?.findViewById<FrameLayout>(R.id.keyboardPagesContainer)
        container?.addView(punctuationPopup, params)

        // Haptic feedback
        periodButton?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)

        DebugLog.d("DictationKeyboardService: Showing punctuation popup")
    }

    /**
     * Creates the punctuation popup view with ! and ? options.
     */
    private fun createPunctuationPopupView(): View {
        val density = resources.displayMetrics.density
        val popupWidth = (120 * density).toInt()  // 60dp per option
        val popupHeight = (56 * density).toInt()

        // Root container
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(popupWidth, popupHeight)
            gravity = android.view.Gravity.CENTER

            // Dark background with rounded corners
            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(this@DictationKeyboardService, R.color.surface))
                cornerRadius = 12 * density
                setStroke((1 * density).toInt(), ContextCompat.getColor(this@DictationKeyboardService, R.color.outline))
            }
            elevation = 8 * density
        }

        // Exclamation mark (left)
        val exclamationText = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                (60 * density).toInt(),
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            text = "!"
            textSize = 24f
            gravity = android.view.Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@DictationKeyboardService, R.color.on_surface))
            tag = "!"
            id = View.generateViewId()
        }

        // Question mark (right)
        val questionText = TextView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                (60 * density).toInt(),
                LinearLayout.LayoutParams.MATCH_PARENT
            )
            text = "?"
            textSize = 24f
            gravity = android.view.Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@DictationKeyboardService, R.color.on_surface))
            tag = "?"
            id = View.generateViewId()
        }

        container.addView(exclamationText)
        container.addView(questionText)

        // Store references for highlighting
        punctuationExclamationView = exclamationText
        punctuationQuestionView = questionText

        return container
    }

    /**
     * Updates the selected punctuation based on finger position.
     */
    private fun updatePunctuationSelection(rawX: Float, rawY: Float) {
        punctuationPopup ?: return

        // Get popup position on screen
        val location = IntArray(2)
        punctuationPopup?.getLocationOnScreen(location)
        val popupX = location[0]
        val popupCenterX = popupX + (punctuationPopup?.width ?: 0) / 2

        // Determine selection based on X position relative to popup center
        val newSelection = if (rawX < popupCenterX) "!" else "?"

        if (newSelection != selectedPunctuation) {
            selectedPunctuation = newSelection
            updatePunctuationHighlight()
            // Haptic feedback on selection change
            periodButton?.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

    /**
     * Updates visual highlighting of the selected punctuation option.
     */
    private fun updatePunctuationHighlight() {
        val density = resources.displayMetrics.density
        val highlightColor = ContextCompat.getColor(this, R.color.primary)
        val normalColor = ContextCompat.getColor(this, R.color.on_surface)
        val highlightBgColor = ContextCompat.getColor(this, R.color.primary_container)

        punctuationExclamationView?.let { view ->
            if (selectedPunctuation == "!") {
                view.setTextColor(highlightColor)
                view.background = GradientDrawable().apply {
                    setColor(highlightBgColor)
                    cornerRadius = 8 * density
                }
            } else {
                view.setTextColor(normalColor)
                view.background = null
            }
        }

        punctuationQuestionView?.let { view ->
            if (selectedPunctuation == "?") {
                view.setTextColor(highlightColor)
                view.background = GradientDrawable().apply {
                    setColor(highlightBgColor)
                    cornerRadius = 8 * density
                }
            } else {
                view.setTextColor(normalColor)
                view.background = null
            }
        }
    }

    /**
     * Hides and cleans up the punctuation popup.
     */
    private fun hidePunctuationPopup() {
        isPunctuationPopupShowing = false
        selectedPunctuation = null

        punctuationPopup?.let { popup ->
            // Remove from parent
            (popup.parent as? android.view.ViewGroup)?.removeView(popup)
        }
        punctuationPopup = null
        punctuationExclamationView = null
        punctuationQuestionView = null
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
        // Check if we should undo an autocorrect
        val undo = autocorrectManager?.lastAutocorrect
        if (undo != null && autocorrectManager?.onBackspace() == true) {
            // Undo: delete the corrected word + space, re-insert original
            val ic = currentInputConnection
            if (ic != null) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(undo.replacement.length + 1, 0) // +1 for space
                ic.commitText(undo.original, 1)
                ic.endBatchEdit()
                DebugLog.d("DictationKeyboardService: Undid autocorrect '${undo.replacement}' → '${undo.original}'")
                return
            }
        }

        // Normal backspace
        autocorrectManager?.onBackspace()
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        trackBackspace(1)  // Track for terminal apps
    }

    private fun selectAll() {
        Log.i(TAG, "DictationKeyboardService: Select all")
        DebugLog.d("DictationKeyboardService: Selecting all text")

        currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
    }

    /**
     * Improves the readability of text in the current input field using AI.
     *
     * This function:
     * 1. Gets all text from the current input field
     * 2. Sends it to Anthropic API for improvement
     * 3. Replaces the original text with the improved version
     *
     * Improvements include:
     * - Making text 20-30% shorter
     * - Adding bullet points where appropriate
     * - Using clear, professional, nonviolent language
     * - Preserving the user's tone and intent
     */
    /**
     * Triggers automatic AI sentence fixing in the background.
     * Called after a sentence ends (period/dictation) to fix grammar/spelling via Haiku.
     * Shows progress in prediction strip and offers undo after completion.
     */
    private fun triggerAutoFix() {
        if (isAutoFixRunning || autoFixUndoAvailable || isTerminalApp) return
        if (textImprover == null) {
            textImprover = TextImprover(this)
        }
        if (!PreferenceManager(this).hasAnthropicApiKey()) return

        val ic = currentInputConnection ?: return
        // Get the last sentence (text before cursor)
        val textBefore = ic.getTextBeforeCursor(500, 0)?.toString() ?: return
        if (textBefore.isBlank() || textBefore.length < 5) return

        // Find the last sentence (from previous sentence end or start of text)
        val lastSentenceStart = findLastSentenceStart(textBefore)
        val sentence = textBefore.substring(lastSentenceStart).trim()
        if (sentence.length < 5) return

        isAutoFixRunning = true
        autoFixOriginalText = sentence

        // Show "fixing" indicator in prediction strip
        showAutoFixProgress()

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val result = textImprover?.improve(sentence)
                val improved = result?.getOrNull()

                if (improved != null && improved != sentence && improved.isNotBlank()) {
                    // Ensure first letter is capitalized (start of sentence)
                    val capitalizedImproved = improved.replaceFirstChar { it.uppercase() }

                    // Apply the fix - recapture text to handle user typing during API call
                    val currentIc = currentInputConnection
                    if (currentIc != null) {
                        // Re-read text before cursor to find the original sentence
                        // (user may have typed more characters since we captured it)
                        val currentText = currentIc.getTextBeforeCursor(500, 0)?.toString() ?: ""
                        val sentencePos = currentText.lastIndexOf(sentence)

                        if (sentencePos >= 0) {
                            // Calculate how many chars to delete: from sentence start to cursor
                            val charsToDelete = currentText.length - sentencePos
                            // Text typed after the sentence that we need to preserve
                            val textAfterSentence = currentText.substring(sentencePos + sentence.length)

                            currentIc.beginBatchEdit()
                            currentIc.deleteSurroundingText(charsToDelete, 0)
                            // Add trailing space so user can type the next word immediately
                            val improvedWithSpace = "$capitalizedImproved "
                            currentIc.commitText(improvedWithSpace + textAfterSentence, 1)
                            currentIc.endBatchEdit()

                            autoFixReplacementText = improvedWithSpace
                            autoFixUndoAvailable = true
                            showAutoFixUndo()

                            // Auto-expire undo after timeout
                            autoFixHandler.postDelayed({
                                clearAutoFixUndo()
                            }, AUTO_FIX_UNDO_TIMEOUT)

                            DebugLog.i("DictationKeyboardService: Auto-fix applied: '$sentence' → '$capitalizedImproved'")
                        } else {
                            // Original sentence not found (user deleted it or moved cursor)
                            DebugLog.w("DictationKeyboardService: Auto-fix: original sentence not found, skipping")
                            clearAutoFixState()
                        }
                    } else {
                        clearAutoFixState()
                    }
                } else {
                    // No changes needed or error
                    clearAutoFixState()
                    DebugLog.d("DictationKeyboardService: Auto-fix: no changes needed")
                }
            } catch (e: Exception) {
                DebugLog.e("DictationKeyboardService: Auto-fix failed: ${e.message}")
                clearAutoFixState()
            }
            isAutoFixRunning = false
        }
    }

    /** Find the start index of the last sentence in text */
    private fun findLastSentenceStart(text: String): Int {
        // Look for the last sentence-ending punctuation before the final one
        val trimmed = text.trimEnd()
        // Find second-to-last sentence end
        for (i in trimmed.length - 2 downTo 0) {
            if (trimmed[i] == '.' || trimmed[i] == '!' || trimmed[i] == '?') {
                return i + 1
            }
        }
        return 0 // entire text is one sentence
    }

    /** Show purple "Fixing..." indicator in prediction strip */
    private fun showAutoFixProgress() {
        prediction1?.text = ""
        prediction2?.text = "AI fixing..."
        prediction3?.text = ""
        predictionStrip?.setBackgroundColor(AUTO_FIX_COLOR)
        prediction2?.setTextColor(Color.WHITE)
    }

    /** Show green undo option in prediction strip */
    private fun showAutoFixUndo() {
        prediction1?.text = ""
        prediction2?.text = "Undo fix"
        prediction3?.text = ""
        predictionStrip?.setBackgroundColor(AUTO_FIX_DONE_COLOR)
        prediction2?.setTextColor(Color.WHITE)

        // Make undo tappable
        prediction2?.setOnClickListener {
            undoAutoFix()
        }
    }

    /** Undo the last auto-fix */
    private fun undoAutoFix() {
        val original = autoFixOriginalText ?: return
        val replacement = autoFixReplacementText ?: return

        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        ic.deleteSurroundingText(replacement.length, 0)
        ic.commitText(original, 1)
        ic.endBatchEdit()

        DebugLog.i("DictationKeyboardService: Auto-fix undone")
        clearAutoFixUndo()
    }

    /** Clear auto-fix undo state and restore prediction strip */
    private fun clearAutoFixUndo() {
        autoFixUndoAvailable = false
        autoFixOriginalText = null
        autoFixReplacementText = null
        autoFixHandler.removeCallbacksAndMessages(null)

        // Restore prediction strip to normal
        predictionStrip?.setBackgroundColor(Color.TRANSPARENT)
        prediction1?.setTextColor(ContextCompat.getColor(this, R.color.keyboard_key_text))
        prediction2?.setTextColor(ContextCompat.getColor(this, R.color.keyboard_key_text))
        prediction3?.setTextColor(ContextCompat.getColor(this, R.color.keyboard_key_text))
        prediction1?.text = ""
        prediction2?.text = ""
        prediction3?.text = ""

        // Re-attach normal prediction tap handlers
        prediction1?.setOnClickListener { applyPrediction(0) }
        prediction2?.setOnClickListener { applyPrediction(1) }
        prediction3?.setOnClickListener { applyPrediction(2) }
    }

    /** Clear auto-fix state without restoring strip (for when fix didn't apply) */
    private fun clearAutoFixState() {
        isAutoFixRunning = false
        autoFixOriginalText = null
        autoFixReplacementText = null
        autoFixUndoAvailable = false
        // Restore prediction strip colors
        predictionStrip?.setBackgroundColor(Color.TRANSPARENT)
        prediction1?.setTextColor(ContextCompat.getColor(this, R.color.keyboard_key_text))
        prediction2?.setTextColor(ContextCompat.getColor(this, R.color.keyboard_key_text))
        prediction3?.setTextColor(ContextCompat.getColor(this, R.color.keyboard_key_text))
        prediction1?.text = ""
        prediction2?.text = ""
        prediction3?.text = ""
    }

    /**
     * Request AI next-word prediction after space press.
     * Fires a background Haiku call with recent context, updates prediction strip on response.
     * Cancelled if user starts typing before response arrives.
     */
    private fun requestNextWordPrediction() {
        if (isAutoFixRunning || isTerminalApp) return

        // Clear auto-fix undo if still showing - next-word prediction takes priority
        if (autoFixUndoAvailable) {
            clearAutoFixUndo()
        }

        if (nextWordPredictor == null) {
            nextWordPredictor = NextWordPredictor(this)
        }
        if (nextWordPredictor?.isAvailable() != true) {
            DebugLog.d("NextWordPredictor: Not available (no API key)")
            return
        }

        // Cancel any existing prediction request
        activeNextWordJob?.cancel()

        val ic = currentInputConnection ?: return
        val contextText = ic.getTextBeforeCursor(100, 0)?.toString() ?: return
        if (contextText.isBlank()) return

        // Show loading indicator immediately
        isNextWordPredictionActive = true
        prediction1?.text = ""
        prediction2?.text = "···"
        prediction3?.text = ""

        DebugLog.d("NextWordPredictor: Requesting prediction for: \"${contextText.takeLast(30)}\"")

        activeNextWordJob = CoroutineScope(Dispatchers.Main).launch {
            val predictions = nextWordPredictor?.predict(contextText) ?: emptyList()
            if (predictions.isNotEmpty() && isNextWordPredictionActive) {
                DebugLog.d("NextWordPredictor: Got predictions: $predictions")
                // Show predictions: middle = best, left = second, right = third
                prediction2?.text = predictions.getOrNull(0) ?: ""
                prediction1?.text = predictions.getOrNull(1) ?: ""
                prediction3?.text = predictions.getOrNull(2) ?: ""

                // Set up tap handlers for AI predictions
                prediction1?.setOnClickListener { applyNextWordPrediction(predictions.getOrNull(1)) }
                prediction2?.setOnClickListener { applyNextWordPrediction(predictions.getOrNull(0)) }
                prediction3?.setOnClickListener { applyNextWordPrediction(predictions.getOrNull(2)) }
            } else if (predictions.isEmpty() && isNextWordPredictionActive) {
                DebugLog.d("NextWordPredictor: No predictions returned")
                // Clear the loading indicator
                prediction1?.text = ""
                prediction2?.text = ""
                prediction3?.text = ""
                isNextWordPredictionActive = false
            }
        }
    }

    /**
     * Apply a next-word prediction: insert the word + space, then request another prediction.
     */
    private fun applyNextWordPrediction(word: String?) {
        if (word.isNullOrBlank()) return

        val ic = currentInputConnection ?: return
        ic.commitText("$word ", 1)
        trackTypedText("$word ")

        // Clear state and request another prediction
        isNextWordPredictionActive = false
        autocorrectManager?.onWordBoundary()
        requestNextWordPrediction()
    }

    /**
     * Cancel any active next-word prediction and clear the strip if showing AI predictions.
     */
    private fun cancelNextWordPrediction() {
        activeNextWordJob?.cancel()
        activeNextWordJob = null
        if (isNextWordPredictionActive) {
            isNextWordPredictionActive = false
            prediction1?.text = ""
            prediction2?.text = ""
            prediction3?.text = ""
            // Restore normal tap handlers
            prediction1?.setOnClickListener { applyPrediction(0) }
            prediction2?.setOnClickListener { applyPrediction(1) }
            prediction3?.setOnClickListener { applyPrediction(2) }
        }
    }

    /**
     * Request AI word completion for the right prediction slot.
     * Debounced: waits 400ms after last keystroke before firing API call.
     */
    private fun requestAiCompletion() {
        if (isAutoFixRunning || isTerminalApp || isNextWordPredictionActive) return

        if (nextWordPredictor == null) {
            nextWordPredictor = NextWordPredictor(this)
        }
        if (nextWordPredictor?.isAvailable() != true) return

        val partialWord = autocorrectManager?.getCurrentWord() ?: return
        if (partialWord.isEmpty()) return

        // Debounce: cancel previous timer, start new one
        aiCompletionHandler.removeCallbacksAndMessages(null)
        aiCompletionHandler.postDelayed({
            // Re-check the word hasn't changed
            val currentWord = autocorrectManager?.getCurrentWord() ?: return@postDelayed
            if (currentWord.isEmpty()) return@postDelayed

            val ic = currentInputConnection ?: return@postDelayed
            val contextText = ic.getTextBeforeCursor(100, 0)?.toString() ?: return@postDelayed

            aiCompletionJob?.cancel()
            aiCompletionJob = CoroutineScope(Dispatchers.Main).launch {
                val completed = nextWordPredictor?.completeWord(contextText, currentWord)
                // Only show if user is still typing the same word
                val stillTyping = autocorrectManager?.getCurrentWord() ?: ""
                if (completed != null && stillTyping.isNotEmpty() && completed.startsWith(stillTyping.lowercase())) {
                    aiCompletionWord = completed
                    prediction3?.text = completed
                    prediction3?.setOnClickListener { applyPrediction(2) }
                }
            }
        }, AI_COMPLETION_DEBOUNCE)
    }

    /**
     * Cancel any active AI word completion request and clear the right slot.
     */
    private fun cancelAiCompletion() {
        aiCompletionHandler.removeCallbacksAndMessages(null)
        aiCompletionJob?.cancel()
        aiCompletionJob = null
        aiCompletionWord = null
        prediction3?.text = ""
    }

    private fun improveText() {
        DebugLog.i("DictationKeyboardService: Improve button pressed")

        if (isImproving) {
            DebugLog.w("DictationKeyboardService: Text improvement already in progress")
            Toast.makeText(this, "Already improving...", Toast.LENGTH_SHORT).show()
            return
        }

        val ic = currentInputConnection
        if (ic == null) {
            DebugLog.e("DictationKeyboardService: No input connection for text improvement")
            Toast.makeText(this, "No text field selected", Toast.LENGTH_SHORT).show()
            return
        }

        // Get all text from the input field
        val before = ic.getTextBeforeCursor(Int.MAX_VALUE, 0) ?: ""
        val after = ic.getTextAfterCursor(Int.MAX_VALUE, 0) ?: ""
        var fullText = "$before$after"
        var useBuffer = false

        DebugLog.d("DictationKeyboardService: Improve - before='${before.take(50)}' (${before.length}), after='${after.take(50)}' (${after.length})")

        // Fallback to internal buffer for apps that don't expose text (like terminal apps, Beeper)
        if (fullText.isBlank() && internalTextBuffer.isNotEmpty()) {
            fullText = internalTextBuffer.toString()
            useBuffer = true
            DebugLog.i("DictationKeyboardService: Using internal buffer for improve (${fullText.length} chars)")
        }

        if (fullText.isBlank()) {
            DebugLog.w("DictationKeyboardService: No text to improve (buffer also empty)")
            Toast.makeText(this, "No text to improve", Toast.LENGTH_SHORT).show()
            return
        }

        Log.i(TAG, "DictationKeyboardService: Improving text (${fullText.length} chars, buffer=$useBuffer)")
        DebugLog.i("DictationKeyboardService: Starting text improvement for ${fullText.length} chars (useBuffer=$useBuffer)")

        isImproving = true
        val originalLength = fullText.length
        val textToImprove = fullText  // Capture for coroutine

        // Visual feedback - change icon tint to indicate processing
        improveIcon?.let { icon ->
            icon.setColorFilter(ContextCompat.getColor(this, R.color.bubble_recording))
        }
        Toast.makeText(this, "Improving text...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val result = textImprover?.improve(textToImprove)

                if (result?.isSuccess == true) {
                    val improvedText = result.getOrNull() ?: textToImprove

                    if (useBuffer) {
                        // For apps that don't expose text, delete old text by sending backspaces
                        // then type the improved text
                        DebugLog.d("DictationKeyboardService: Using backspace method for replacement")

                        // Delete all characters
                        for (i in 0 until originalLength) {
                            sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_DEL)
                        }

                        // Type improved text
                        ic.commitText(improvedText, 1)

                        // Update internal buffer
                        internalTextBuffer.clear()
                        internalTextBuffer.append(improvedText)
                    } else {
                        // Standard approach: Select all and replace
                        ic.beginBatchEdit()
                        try {
                            // Select all text
                            val totalLength = before.length + after.length
                            ic.setSelection(0, totalLength)

                            // Replace with improved text
                            ic.commitText(improvedText, 1)
                        } finally {
                            ic.endBatchEdit()
                        }
                    }

                    val savedChars = textToImprove.length - improvedText.length
                    val savedPercent = if (textToImprove.isNotEmpty()) {
                        (savedChars * 100 / textToImprove.length)
                    } else 0

                    DebugLog.i("DictationKeyboardService: Text improved! ${textToImprove.length} -> ${improvedText.length} chars (saved $savedPercent%)")

                    if (savedChars > 0) {
                        Toast.makeText(this@DictationKeyboardService, "Improved! Saved $savedChars chars ($savedPercent%)", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this@DictationKeyboardService, "Text improved!", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    val error = result?.exceptionOrNull()?.message ?: "Unknown error"
                    DebugLog.e("DictationKeyboardService: Text improvement failed - $error")
                    Toast.makeText(this@DictationKeyboardService, "Failed: $error", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                DebugLog.e("DictationKeyboardService: Text improvement exception - ${e.message}")
                Toast.makeText(this@DictationKeyboardService, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                isImproving = false

                // Reset icon color
                improveIcon?.let { icon ->
                    icon.setColorFilter(ContextCompat.getColor(this@DictationKeyboardService, R.color.keyboard_key_text))
                }
            }
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
        DebugLog.d("DictationKeyboardService: onStartInputView called, restarting=$restarting")

        // Detect URL/URI/email input fields - disable autocorrect for these
        val inputType = info?.inputType ?: 0
        val variation = inputType and android.text.InputType.TYPE_MASK_VARIATION
        val wasUrlField = isUrlField
        isUrlField = variation == android.text.InputType.TYPE_TEXT_VARIATION_URI ||
                     variation == android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                     variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
        if (isUrlField != wasUrlField) {
            DebugLog.d("DictationKeyboardService: URL field detection: $isUrlField (variation=$variation)")
        }

        // Clear autocorrect state on any input view start to prevent word accumulation
        // (e.g. search bars that trigger restarts, switching fields, etc.)
        autocorrectManager?.onWordBoundary()

        // Clear internal text buffer on new input session (for terminal app tracking)
        if (!restarting) {
            internalTextBuffer.clear()
            useInternalBuffer = false
            DebugLog.d("DictationKeyboardService: Cleared internal text buffer (new session)")
        }

        // Detect terminal apps
        val currentPackage = info?.packageName ?: ""
        val wasTerminalApp = isTerminalApp
        isTerminalApp = isTerminalPackage(currentPackage)
        // Set terminal mode on transcription engine (disables formatting, interims)
        if (isTerminalApp != wasTerminalApp) {
            transcriptionManager?.setTerminalMode(isTerminalApp)
            if (isTerminalApp) {
                lastFinalText = ""
                lastFinalTime = 0L
            }
        }

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
        updateNumberRowVisibility()

        // Reset to ABC tab when starting new input (no tab memory between sessions)
        if (!restarting && currentKeyboardTab != 0) {
            currentKeyboardTab = 0
            updateKeyboardTabContent()
        }

        // Auto-start dictation when keyboard opens (mic red/active by default)
        // Skip auto-start if mute is locked or in PTT mode
        if (!isRecording && !isProcessing && !isMuteLocked && !isPushToTalkMode) {
            // Small delay to let UI settle before starting audio
            Handler(Looper.getMainLooper()).postDelayed({
                if (!isRecording && !isProcessing && !isMuteLocked && !isPushToTalkMode) {
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

    /**
     * Called when the cursor position or text selection changes.
     * We use this to update predictions regardless of how text was input
     * (typing, dictation, paste, terminal apps like Termius, etc.)
     */
    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)

        // Clear autocorrect state on unexpected cursor movement
        // (e.g., user tapped elsewhere, search bar replaced text, app autocomplete)
        val expectedDelta = 1 // normal single char movement
        if (Math.abs(newSelStart - oldSelStart) > expectedDelta + 1) {
            autocorrectManager?.clearUndoState()
            autocorrectManager?.onWordBoundary()
        }

        // Also detect backwards cursor movement (e.g. search bar cleared/replaced text)
        // or cursor jumping to start (newSelStart == 0 while we had a word building)
        if (newSelStart < oldSelStart && autocorrectManager?.getCurrentWord()?.isNotEmpty() == true) {
            autocorrectManager?.onWordBoundary()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        Log.d(TAG, "DictationKeyboardService onFinishInputView")
        DebugLog.d("DictationKeyboardService: Finished input view")

        // Cleanup PTT recording if keyboard closes mid-hold
        if (isPttRecording) {
            pttStreamingRunnable?.let { pttStreamingHandler?.removeCallbacks(it) }
            transcriptionManager?.stopBuffering(discard = true)
            isPttRecording = false
            pttStreamingStarted = false
            DebugLog.i("DictationKeyboardService: PTT recording cancelled (keyboard hidden)")
        }

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
