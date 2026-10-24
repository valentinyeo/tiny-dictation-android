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
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
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

class DictationKeyboardService : InputMethodService(), ClipboardHistoryManager.OnClipboardHistoryChangedListener {

    companion object {
        private const val TAG = "TinyDictation"
        private const val LONG_PRESS_THRESHOLD = 500L
        private const val DOUBLE_TAP_TIMEOUT = 300L

        private const val COLOR_MIC_STREAMING = "#ED2939"
        private const val COLOR_MIC_BUFFERING = "#FF8C00"
        private const val COLOR_MIC_PTT_IDLE = "#4169E1"
        private const val COLOR_MIC_MUTED = "#808080"
        private const val COLOR_MIC_STANDBY = "#8B1C26"

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

        fun pasteText(text: String): Boolean {
            return instance?.commitTextInternal(text) ?: false
        }

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

    private var keyboardTabbedPanel: View? = null
    private var tabABC: TextView? = null
    private var tabNumpad: TextView? = null
    private var tabClaude: TextView? = null
    private var tabShell: TextView? = null
    private var tabSnippets: TextView? = null
    private var tabClipboard: TextView? = null
    private var tabHistory: TextView? = null
    private var qwertyPanel: View? = null
    private var cursorNumpadPanel: View? = null
    private var claudePanel: View? = null
    private var shellPanel: View? = null
    private var snippetsPanel: View? = null
    private var clipboardPanel: View? = null
    private var historyTabPanel: View? = null
    private var isKeyboardPanelVisible = false
    private var currentKeyboardTab = 0

    private var isClaudePanelInflated = false
    private var isShellPanelInflated = false
    private var isSnippetsPanelInflated = false
    private var isClipboardPanelInflated = false
    private var isHistoryTabPanelInflated = false

    private var isShiftActive = false
    private var isCapsLock = false
    private var isNumbersMode = false
    private var isGermanMode = false

    private val internalTextBuffer = StringBuilder()
    private var useInternalBuffer = false

    private var keyboardButton: FrameLayout? = null
    private var keyboardIcon: ImageView? = null
    private var emojiButton: FrameLayout? = null
    private var emojiIcon: ImageView? = null

    private var qwertyDisplayHelper: QwertyDisplayHelper? = null

    private var pulseAnimator: ObjectAnimator? = null

    private var transcriptionManager: TranscriptionManager? = null
    private var isRecording = false
    private var isProcessing = false
    private var isStandby = false
    private var isMuteLocked = false
    private var wasAutoMutedByPlayback = false
    private var audioPlaybackCallback: AudioManager.AudioPlaybackCallback? = null

    private var isScreenAwake = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var screenAwakeButton: FrameLayout? = null
    private var screenAwakeIcon: ImageView? = null

    private var isPushToTalkMode = false
    private var isPttRecording = false
    private var pttStreamingStarted = false
    private var pttStreamingHandler: Handler? = null
    private var pttStreamingRunnable: Runnable? = null
    private var micTouchStartedInPttMode: Boolean? = null

    private var clipboardHistoryManager: ClipboardHistoryManager? = null

    private var isTerminalApp = false
    private var lastFinalText = ""
    private var lastFinalTime = 0L

    private val terminalPackages = setOf(
        "com.server.auditor",
        "com.termius",
        "org.connectbot",
        "com.sonelli.juicessh",
        "com.termux",
        "yarolegovich.materialterminal",
        "com.offsec.nethunter.kex"
    )

    private fun isTerminalPackage(packageName: String): Boolean {
        return terminalPackages.any { packageName.startsWith(it) }
    }

    private var autocorrectManager: AutocorrectManager? = null
    private var isUrlField = false
    private var isPredictionStripEnabled = true
    private var isAutocorrectEnabled = true
    private var isAiNextWordEnabled = true
    private var isAiWordCompletionEnabled = true
    private var isAutoFixEnabled = true
    private var predictionStrip: LinearLayout? = null
    private var prediction1: TextView? = null
    private var prediction2: TextView? = null
    private var prediction3: TextView? = null

    private var isAutoFixRunning = false
    private var autoFixOriginalText: String? = null
    private var autoFixReplacementText: String? = null
    private var autoFixUndoAvailable = false
    private val autoFixHandler = Handler(Looper.getMainLooper())
    private val AUTO_FIX_UNDO_TIMEOUT = 10000L
    private val AUTO_FIX_COLOR = Color.parseColor("#5B4FCF")
    private val AUTO_FIX_DONE_COLOR = Color.parseColor("#2E7D32")

    private var nextWordPredictor: NextWordPredictor? = null
    private var activeNextWordJob: Job? = null
    private var isNextWordPredictionActive = false

    private var aiCompletionJob: Job? = null
    private val aiCompletionHandler = Handler(Looper.getMainLooper())
    private var aiCompletionWord: String? = null
    private val AI_COMPLETION_DEBOUNCE = 200L


    private var textImprover: TextImprover? = null
    private var isImproving = false
    private var improveButton: FrameLayout? = null
    private var improveIcon: ImageView? = null

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
                showAKeyPopup()
            } else {
                currentInputConnection?.commitText("@", 1)
                trackTypedText("@")
                aKeyButton?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            }
        }
    }

    private var oKeyButton: android.widget.TextView? = null
    private var isOKeyLongPressTriggered = false
    private val oKeyLongPressHandler = Handler(Looper.getMainLooper())
    private val oKeyLongPressRunnable = Runnable {
        isOKeyLongPressTriggered = true
        if (!isNumbersMode) {
            val char = if (isShiftActive || isCapsLock) "Ö" else "ö"
            currentInputConnection?.commitText(char, 1)
            trackTypedText(char)
            oKeyButton?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private var uKeyButton: android.widget.TextView? = null
    private var isUKeyLongPressTriggered = false
    private val uKeyLongPressHandler = Handler(Looper.getMainLooper())
    private val uKeyLongPressRunnable = Runnable {
        isUKeyLongPressTriggered = true
        if (!isNumbersMode) {
            val char = if (isShiftActive || isCapsLock) "Ü" else "ü"
            currentInputConnection?.commitText(char, 1)
            trackTypedText(char)
            uKeyButton?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        }
    }

    private var shiftKeyButton: android.widget.TextView? = null
    private var isShiftLongPressTriggered = false
    private val shiftLongPressHandler = Handler(Looper.getMainLooper())
    private val shiftLongPressRunnable = Runnable {
        isShiftLongPressTriggered = true
        isGermanMode = !isGermanMode
        shiftKeyButton?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        updateShiftKeyDisplay()
        updateLongPressHints()
        DebugLog.d("DictationKeyboardService: German mode ${if (isGermanMode) "ON" else "OFF"}")
    }

    private var spaceKeyButton: android.widget.TextView? = null
    private var isSpaceTrackpadMode = false
    private var spaceDownWord = ""
    private var spaceTrackpadStartX = 0f
    private var spaceTrackpadLastX = 0f
    private var spaceTrackpadAccumulatedDelta = 0f
    private var spaceTrackpadLastMoveTime = 0L
    private val TRACKPAD_LONG_PRESS_THRESHOLD = 250L
    private val TRACKPAD_MOVE_THRESHOLD_DP = 20f
    private val spaceTrackpadHandler = Handler(Looper.getMainLooper())
    private val spaceTrackpadRunnable = Runnable {
        isSpaceTrackpadMode = true
        spaceKeyButton?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        DebugLog.d("DictationKeyboardService: Cursor trackpad mode ACTIVATED")
    }

    private var isScreenOffReceiverRegistered = false
    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                stopDictation()
            }
        }
    }
    private val dictationStartHandler = Handler(Looper.getMainLooper())

    private val pasteTextReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_PASTE_TEXT) {
                val text = intent.getStringExtra(EXTRA_TEXT)
                if (!text.isNullOrEmpty()) {
                    Log.i(TAG, "Received paste request: ${text.take(50)}...")
                    DebugLog.i("DictationKeyboardService: Pasting ${text.length} chars from history")
                    if (currentInputConnection != null) {
                        commitTextInternal(text)
                    } else {
                        Log.i(TAG, "No input connection, storing for pending paste")
                        DebugLog.i("DictationKeyboardService: Storing pending paste (keyboard hidden)")
                        pendingPasteText = text
                    }
                }
            }
        }
    }

    private var lastMicTapTime = 0L
    private var doubleTapHandler: Handler? = null
    private var doubleTapRunnable: Runnable? = null

    private val backspaceHandler = Handler(Looper.getMainLooper())
    @Volatile private var isBackspaceHeld = false
    private val BACKSPACE_INITIAL_DELAY = 400L
    private val BACKSPACE_REPEAT_DELAY_START = 100L
    private val BACKSPACE_REPEAT_DELAY_MIN = 25L
    private val BACKSPACE_ACCELERATION_TIME = 1500L
    private val BACKSPACE_WORD_DELETE_TIME = 2000L
    private val BACKSPACE_WORD_DELETE_DELAY = 80L
    private var backspaceStartTime = 0L

    private var backspaceCount = 0


    private val cursorHandler = Handler(Looper.getMainLooper())
    @Volatile private var isCursorKeyHeld = false
    private var cursorStartTime = 0L
    private var cursorKeyCode = 0
    private var cursorKeyCount = 0

    private val cursorRepeatRunnable = object : Runnable {
        override fun run() {
            if (!isCursorKeyHeld) {
                DebugLog.d("DictationKeyboardService: Cursor key released after $cursorKeyCount presses, stopping repeat")
                return
            }

            val holdDuration = System.currentTimeMillis() - cursorStartTime

            sendDownUpKeyEvents(cursorKeyCode)
            cursorKeyCount++

            val accelerationProgress = (holdDuration.toFloat() / BACKSPACE_ACCELERATION_TIME).coerceIn(0f, 1f)
            val currentDelay = (BACKSPACE_REPEAT_DELAY_START -
                (BACKSPACE_REPEAT_DELAY_START - BACKSPACE_REPEAT_DELAY_MIN) * accelerationProgress).toLong()

            if (cursorKeyCount % 10 == 0) {
                DebugLog.d("DictationKeyboardService: Cursor key count=$cursorKeyCount, delay=${currentDelay}ms, held=${holdDuration}ms")
            }

            cursorHandler.postDelayed(this, currentDelay)
        }
    }

    private val backspaceRepeatRunnable = object : Runnable {
        override fun run() {
            if (!isBackspaceHeld) {
                DebugLog.d("DictationKeyboardService: Backspace released after $backspaceCount deletions, stopping repeat")
                return
            }

            val holdDuration = System.currentTimeMillis() - backspaceStartTime

            if (holdDuration >= BACKSPACE_WORD_DELETE_TIME) {
                deleteWord()
                backspaceCount++
                backspaceHandler.postDelayed(this, BACKSPACE_WORD_DELETE_DELAY)
            } else {
                sendBackspace()
                backspaceCount++

                val accelerationProgress = (holdDuration.toFloat() / BACKSPACE_ACCELERATION_TIME).coerceIn(0f, 1f)
                val currentDelay = (BACKSPACE_REPEAT_DELAY_START -
                    (BACKSPACE_REPEAT_DELAY_START - BACKSPACE_REPEAT_DELAY_MIN) * accelerationProgress).toLong()

                if (backspaceCount % 10 == 0) {
                    DebugLog.d("DictationKeyboardService: Backspace count=$backspaceCount, delay=${currentDelay}ms, held=${holdDuration}ms")
                }

                backspaceHandler.postDelayed(this, currentDelay)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "DictationKeyboardService onCreate")
        DebugLog.i("DictationKeyboardService: Service created")
        instance = this

        ContextCompat.registerReceiver(
            this,
            screenOffReceiver,
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        isScreenOffReceiverRegistered = true

        val filter = IntentFilter(ACTION_PASTE_TEXT)
        LocalBroadcastManager.getInstance(this).registerReceiver(pasteTextReceiver, filter)

        initializeTranscriptionManager()

        val pttPrefs = PreferenceManager(this)
        isPushToTalkMode = pttPrefs.getPushToTalkEnabled()
        if (isPushToTalkMode) {
            DebugLog.i("DictationKeyboardService: PTT mode loaded from preferences")
        }

        clipboardHistoryManager = ClipboardHistoryManager.getInstance(this)
        clipboardHistoryManager?.startListening()

        textImprover = TextImprover(this)

        registerAudioPlaybackCallback()
    }

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
                    if (text.isNotBlank()) {
                        keyboardView?.findViewById<TextView>(R.id.tvTranscriptionError)
                            ?.visibility = View.GONE
                    }
                    if (!isFinal && text.isNotBlank()) {
                        if (isTerminalApp) return@post

                        val ic = currentInputConnection ?: return@post
                        if (lastInterimLength > 0) {
                            ic.deleteSurroundingText(lastInterimLength, 0)
                        }
                        ic.commitText(text, 1)
                        lastInterimLength = text.length
                    } else if (isFinal && text.isNotBlank()) {
                        val ic = currentInputConnection ?: return@post

                        if (isTerminalApp) {
                            val now = System.currentTimeMillis()
                            if (text == lastFinalText && now - lastFinalTime < 2000) {
                                DebugLog.d("DictationKeyboardService: Skipping duplicate final in terminal:", "'$text'")
                                return@post
                            }
                            lastFinalText = text
                            lastFinalTime = now
                        }

                        if (lastInterimLength > 0) {
                            ic.deleteSurroundingText(lastInterimLength, 0)
                            lastInterimLength = 0
                        }

                        val commandResult = VoiceCommandProcessor.process(
                            text,
                            currentInputConnection
                        ) { keyCode -> sendDownUpKeyEvents(keyCode) }

                        when (commandResult) {
                            is VoiceCommandProcessor.CommandResult.Executed -> {
                                DebugLog.i("DictationKeyboardService: Voice command executed for", "'$text'")
                            }
                            is VoiceCommandProcessor.CommandResult.Failed -> {
                                DebugLog.w("DictationKeyboardService: Voice command failed -", commandResult.reason)
                            }
                            is VoiceCommandProcessor.CommandResult.NotACommand -> {
                                commitTextInternal(text + " ")

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
                DebugLog.e("DictationKeyboardService: Transcription error -", error)
                Handler(Looper.getMainLooper()).post {
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                    keyboardView?.findViewById<TextView>(R.id.tvTranscriptionError)?.apply {
                        text = error
                        visibility = View.VISIBLE
                    }
                    val state = transcriptionManager?.getState()
                    isRecording = transcriptionManager?.isListening() == true
                    isProcessing = state == TranscriptionManager.TranscriptionState.TRANSCRIBING
                    isStandby = state == TranscriptionManager.TranscriptionState.STANDBY
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

        if (isTerminalApp) {
            transcriptionManager?.setTerminalMode(true)
            DebugLog.i("DictationKeyboardService: Applied terminal mode to new TranscriptionManager")
        }
    }

    override fun onDestroy() {
        Log.i(TAG, "DictationKeyboardService onDestroy")
        DebugLog.i("DictationKeyboardService: Service destroyed")

        stopDictation()
        transcriptionManager = null

        if (isScreenOffReceiverRegistered) {
            unregisterReceiver(screenOffReceiver)
            isScreenOffReceiverRegistered = false
        }

        clipboardHistoryManager?.removeOnHistoryChangedListener(this)
        clipboardHistoryManager?.stopListening()
        clipboardHistoryManager = null

        unregisterAudioPlaybackCallback()

        LocalBroadcastManager.getInstance(this).unregisterReceiver(pasteTextReceiver)

        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        isScreenAwake = false

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

        window?.window?.let { w ->
            w.navigationBarColor = Color.BLACK
            w.decorView?.setBackgroundColor(Color.BLACK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                w.isNavigationBarContrastEnforced = false
            }
        }

        return try {
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

            keyboardTabbedPanel = keyboardView?.findViewById(R.id.keyboardTabbedPanel)
            tabABC = keyboardTabbedPanel?.findViewById(R.id.tabABC)
            tabNumpad = keyboardTabbedPanel?.findViewById(R.id.tabNumpad)
            tabClaude = keyboardTabbedPanel?.findViewById(R.id.tabClaude)
            tabShell = keyboardTabbedPanel?.findViewById(R.id.tabShell)
            tabSnippets = keyboardTabbedPanel?.findViewById(R.id.tabSnippets)
            tabClipboard = keyboardTabbedPanel?.findViewById(R.id.tabClipboard)
            tabHistory = keyboardTabbedPanel?.findViewById(R.id.tabHistory)
            qwertyPanel = keyboardTabbedPanel?.findViewById(R.id.qwertyPanel)
            qwertyDisplayHelper = qwertyPanel?.let { QwertyDisplayHelper(it) }
            cursorNumpadPanel = keyboardTabbedPanel?.findViewById(R.id.cursorNumpadPanel)

            keyboardButton = keyboardView?.findViewById(R.id.btnKeyboard)
            keyboardIcon = keyboardView?.findViewById(R.id.ivKeyboard)
            emojiButton = keyboardView?.findViewById(R.id.btnEmoji)
            emojiIcon = keyboardView?.findViewById(R.id.ivEmoji)

            setupButtonHandlers()
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

            keyboardView!!.post {
                var parent = keyboardView?.parent
                while (parent is View) {
                    (parent as View).setBackgroundColor(Color.BLACK)
                    parent = parent.getParent()
                }
            }

            keyboardView!!
        } catch (e: Exception) {
            Log.e(TAG, "Error creating keyboard view", e)
            val errorDetails = "Error: ${e.message}\n\n${e.stackTraceToString()}"
            DebugLog.e("DictationKeyboardService: Error creating view - ${e.message}\n${e.stackTraceToString()}")

            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 16, 16, 16)
                setBackgroundColor(android.graphics.Color.BLACK)

                addView(TextView(this@DictationKeyboardService).apply {
                    text = "Error: ${e.message}"
                    textSize = 12f
                    setTextColor(android.graphics.Color.RED)
                    setPadding(0, 0, 0, 16)
                })

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

    private fun applyTheme() {
        val prefs = PreferenceManager(this)
        val themeSetting = prefs.getKeyboardTheme()

        val backgroundColor: Int
        val buttonColor: Int
        val iconColor: Int

        when (themeSetting) {
            PreferenceManager.THEME_AMOLED -> {
                backgroundColor = Color.parseColor("#000000")
                buttonColor = Color.parseColor("#2D2D2F")
                iconColor = Color.parseColor("#FFFFFF")
            }
            PreferenceManager.THEME_DARK -> {
                backgroundColor = ContextCompat.getColor(this, R.color.surface_variant)
                buttonColor = ContextCompat.getColor(this, R.color.surface)
                iconColor = ContextCompat.getColor(this, R.color.on_surface)
            }
            PreferenceManager.THEME_LIGHT -> {
                backgroundColor = Color.parseColor("#F5F5F5")
                buttonColor = Color.parseColor("#E0E0E0")
                iconColor = Color.parseColor("#1C1B1F")
            }
            else -> {
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

        keyboardView?.findViewById<LinearLayout>(R.id.keyboardRoot)?.setBackgroundColor(Color.BLACK)
        keyboardView?.findViewById<LinearLayout>(R.id.keyboardContainer)?.setBackgroundColor(Color.BLACK)


        applyQwertyTheme(buttonColor, iconColor, backgroundColor)

        DebugLog.d("DictationKeyboardService: Applied theme - $themeSetting")
    }

    private fun applyButtonTheme(button: FrameLayout?, buttonColor: Int, iconColor: Int) {
        button ?: return

        val backgroundDrawable = GradientDrawable().apply {
            setColor(buttonColor)
            cornerRadius = 6 * resources.displayMetrics.density
        }

        val rippleDrawable = RippleDrawable(
            android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary)),
            backgroundDrawable,
            null
        ).mutate() as RippleDrawable
        button.background = rippleDrawable

        val iconColorStateList = android.content.res.ColorStateList.valueOf(iconColor)
        for (i in 0 until button.childCount) {
            val child = button.getChildAt(i)
            if (child is ImageView) {
                child.imageTintList = iconColorStateList
            }
        }
    }

    private fun applyQwertyTheme(buttonColor: Int, textColor: Int, backgroundColor: Int) {
        qwertyPanel?.setBackgroundColor(backgroundColor)

        cursorNumpadPanel?.setBackgroundColor(backgroundColor)

        val textKeyIds = listOf(
            R.id.key_1, R.id.key_2, R.id.key_3, R.id.key_4, R.id.key_5,
            R.id.key_6, R.id.key_7, R.id.key_8, R.id.key_9, R.id.key_0,
            R.id.key_q, R.id.key_w, R.id.key_e, R.id.key_r, R.id.key_t,
            R.id.key_y, R.id.key_u, R.id.key_i, R.id.key_o, R.id.key_p,
            R.id.key_a, R.id.key_s, R.id.key_d, R.id.key_f, R.id.key_g,
            R.id.key_h, R.id.key_j, R.id.key_k, R.id.key_l,
            R.id.key_z, R.id.key_x, R.id.key_c, R.id.key_v, R.id.key_b,
            R.id.key_n, R.id.key_m,
            R.id.key_comma, R.id.key_space, R.id.key_period
        )

        val accentKeyIds = listOf(R.id.key_shift, R.id.key_numbers)

        for (id in textKeyIds) {
            qwertyPanel?.findViewById<android.widget.TextView>(id)?.let { button ->
                val backgroundDrawable = GradientDrawable().apply {
                    setColor(buttonColor)
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

        val accentColor = ContextCompat.getColor(this, R.color.dictation_bar_button)

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

        val iconKeyIds = listOf(R.id.key_backspace, R.id.key_enter)
        for (id in iconKeyIds) {
            qwertyPanel?.findViewById<android.widget.ImageView>(id)?.let { imageView ->
                val backgroundDrawable = GradientDrawable().apply {
                    setColor(accentColor)
                    cornerRadius = 6 * resources.displayMetrics.density
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
    }

    private fun applyCompactMode() {
        val prefs = PreferenceManager(this)
        if (!prefs.getCompactDictationBar()) return

        val density = resources.displayMetrics.density

        val compactButtonSize = (32 * density).toInt()
        val compactIconSize = (16 * density).toInt()
        val compactSmallButtonWidth = (24 * density).toInt()
        val compactSmallIconSize = (12 * density).toInt()
        val compactPadding = (4 * density).toInt()
        val compactMargin = (4 * density).toInt()
        val compactMinHeight = (40 * density).toInt()

        keyboardView?.findViewById<LinearLayout>(R.id.keyboardContainer)?.apply {
            setPadding(paddingLeft, compactPadding, paddingRight, compactPadding)
            minimumHeight = compactMinHeight
        }

        val standardButtons = listOf(
            R.id.btnSelectAll, R.id.btnEmoji, R.id.btnSpace, R.id.btnLanguage,
            R.id.btnBackspace, R.id.btnKeyboardMic, R.id.btnEnter,
            R.id.btnKeyboard, R.id.btnHistory, R.id.btnImprove, R.id.btnScreenAwake
        )

        val standardIcons = listOf(
            R.id.ivSelectAll, R.id.ivEmoji, R.id.ivSpace, R.id.ivBackspace,
            R.id.ivKeyboardMic, R.id.ivEnter, R.id.ivKeyboard, R.id.ivHistory, R.id.ivImprove,
            R.id.ivScreenAwake
        )

        for (id in standardButtons) {
            keyboardView?.findViewById<FrameLayout>(id)?.let { button ->
                val layoutParams = button.layoutParams as LinearLayout.LayoutParams
                layoutParams.width = compactButtonSize
                layoutParams.height = compactButtonSize
                layoutParams.marginStart = compactMargin
                button.layoutParams = layoutParams
            }
        }

        for (id in standardIcons) {
            keyboardView?.findViewById<ImageView>(id)?.let { icon ->
                val layoutParams = icon.layoutParams
                layoutParams.width = compactIconSize
                layoutParams.height = compactIconSize
                icon.layoutParams = layoutParams
            }
        }

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

        keyboardView?.findViewById<TextView>(R.id.tvLanguage)?.let { text ->
            text.textSize = 11f
        }

        DebugLog.i("DictationKeyboardService: Compact mode applied")
    }

    private fun applyButtonVisibility() {
        val prefs = PreferenceManager(this)

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
        micButton?.setOnTouchListener { _, event ->
            handleMicTouch(event)
        }

        keyboardView?.findViewById<FrameLayout>(R.id.btnLanguage)?.setOnClickListener {
            toggleLanguage()
        }

        keyboardView?.findViewById<FrameLayout>(R.id.btnBackspace)?.setOnTouchListener { _, event ->
            handleBackspaceTouch(event)
        }

        keyboardView?.findViewById<FrameLayout>(R.id.btnSelectAll)?.setOnClickListener {
            selectAll()
        }

        keyboardView?.findViewById<FrameLayout>(R.id.btnHistory)?.let { historyBtn ->
            historyBtn.setOnClickListener {
                if (!isKeyboardPanelVisible) {
                    toggleKeyboardPanel()
                }
                switchToTab(4)
            }
            historyBtn.setOnLongClickListener {
                openSettings()
                true
            }
        }

        keyboardView?.findViewById<FrameLayout>(R.id.btnEnter)?.setOnClickListener {
            sendEnterKey()
        }

        keyboardView?.findViewById<FrameLayout>(R.id.btnSpace)?.setOnClickListener {
            autocorrectManager?.onWordBoundary()
            currentInputConnection?.commitText(" ", 1)
        }

        keyboardView?.findViewById<FrameLayout>(R.id.btnEmoji)?.setOnClickListener {
            toggleEmojiPanel()
        }

        keyboardView?.findViewById<FrameLayout>(R.id.btnKeyboard)?.setOnClickListener {
            toggleKeyboardPanel()
        }

        keyboardView?.findViewById<FrameLayout>(R.id.btnSwitchBack)?.setOnClickListener {
            switchToPreviousKeyboard()
        }

        improveButton = keyboardView?.findViewById(R.id.btnImprove)
        improveIcon = keyboardView?.findViewById(R.id.ivImprove)
        improveButton?.setOnClickListener {
            improveText()
        }

        screenAwakeButton = keyboardView?.findViewById(R.id.btnScreenAwake)
        screenAwakeIcon = keyboardView?.findViewById(R.id.ivScreenAwake)
        screenAwakeButton?.setOnClickListener {
            toggleScreenAwake()
        }
        updateScreenAwakeButton()
    }

    private fun toggleScreenAwake() {
        isScreenAwake = !isScreenAwake
        if (isScreenAwake) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "TinyDictation::ScreenAwake"
            )
            wakeLock?.acquire()
            DebugLog.i("DictationKeyboardService: Screen awake ON")
        } else {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
            wakeLock = null
            DebugLog.i("DictationKeyboardService: Screen awake OFF")
        }
        updateScreenAwakeButton()
    }

    private fun updateScreenAwakeButton() {
        val color = if (isScreenAwake) Color.parseColor("#FFD700") else Color.WHITE
        screenAwakeIcon?.imageTintList = android.content.res.ColorStateList.valueOf(color)
    }

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
                    lastMicTapTime = 0L
                    doubleTapRunnable?.let { doubleTapHandler?.removeCallbacks(it) }
                    DebugLog.i("DictationKeyboardService: Double-tap detected - entering PTT")
                    enterPushToTalkMode()
                } else {
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

    private fun handleMicTouchPTT(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                micButton?.parent?.requestDisallowInterceptTouchEvent(true)
                isPttRecording = true
                pttStreamingStarted = false
                startPttBuffering()

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
                    stopPttBuffering(discard = true)
                    isPttRecording = false

                    val now = System.currentTimeMillis()
                    if (now - lastMicTapTime < DOUBLE_TAP_TIMEOUT) {
                        lastMicTapTime = 0L
                        doubleTapRunnable?.let { doubleTapHandler?.removeCallbacks(it) }
                        DebugLog.i("DictationKeyboardService: Double-tap detected - exiting PTT")
                        exitPushToTalkMode()
                    } else {
                        lastMicTapTime = now
                    }
                } else {
                    stopPttStreaming()
                    isPttRecording = false
                    pttStreamingStarted = false
                }
                return true
            }
        }
        return false
    }

    private fun toggleMute() {
        wasAutoMutedByPlayback = false
        isMuteLocked = !isMuteLocked

        if (isMuteLocked) {
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

    private fun registerAudioPlaybackCallback() {
        val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audioPlaybackCallback = object : AudioManager.AudioPlaybackCallback() {
            override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>) {
                val hasActivePlayback = configs.isNotEmpty()
                Handler(Looper.getMainLooper()).post {
                    if (hasActivePlayback && !isMuteLocked && !isPushToTalkMode &&
                        (isRecording || isProcessing || isStandby)) {
                        wasAutoMutedByPlayback = true
                        isMuteLocked = true
                        transcriptionManager?.stopListening()
                        isRecording = false
                        isProcessing = false
                        isStandby = false
                        updateMicButton()
                        DebugLog.i("DictationKeyboardService: Auto-muted — phone playing audio")
                    } else if (!hasActivePlayback && wasAutoMutedByPlayback && isMuteLocked) {
                        wasAutoMutedByPlayback = false
                        isMuteLocked = false
                        if (transcriptionManager == null) {
                            initializeTranscriptionManager()
                        }
                        transcriptionManager?.let { manager ->
                            try {
                                manager.startListening()
                            } catch (e: Exception) {
                                Log.e(TAG, "Error restarting after audio playback ended", e)
                            }
                        }
                        updateMicButton()
                        DebugLog.i("DictationKeyboardService: Auto-unmuted — phone audio stopped")
                    }
                }
            }
        }
        audioManager.registerAudioPlaybackCallback(audioPlaybackCallback!!, Handler(Looper.getMainLooper()))
        DebugLog.d("DictationKeyboardService: Audio playback callback registered")
    }

    private fun unregisterAudioPlaybackCallback() {
        audioPlaybackCallback?.let { callback ->
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            audioManager?.unregisterAudioPlaybackCallback(callback)
            DebugLog.d("DictationKeyboardService: Audio playback callback unregistered")
        }
        audioPlaybackCallback = null
    }

    private fun enterPushToTalkMode() {
        wasAutoMutedByPlayback = false
        isPushToTalkMode = true
        PreferenceManager(this).setPushToTalkEnabled(true)

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

        val newLang = if (currentLang == "en") "de" else "en"
        prefs.setLanguage(newLang)

        autocorrectManager?.setLanguage(newLang)

        if (isRecording || isProcessing) {
            Log.i(TAG, "Restarting mic to apply language change")
            DebugLog.i("DictationKeyboardService: Restarting mic for language change")
            transcriptionManager?.stopListening()
            isRecording = false
            isProcessing = false
            isStandby = false
            updateMicButton()

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
            transcriptionManager?.setLanguage(newLang)
        }

        updateLanguageButton()
        Log.i(TAG, "Language changed to: $newLang")
    }

    private fun updateMicButton() {
        val color = when {
            isPushToTalkMode && isPttRecording && pttStreamingStarted ->
                Color.parseColor(COLOR_MIC_STREAMING)
            isPushToTalkMode && isPttRecording && !pttStreamingStarted ->
                Color.parseColor(COLOR_MIC_BUFFERING)
            isPushToTalkMode ->
                Color.parseColor(COLOR_MIC_PTT_IDLE)
            isMuteLocked -> Color.parseColor(COLOR_MIC_MUTED)
            isProcessing -> Color.parseColor(COLOR_MIC_STREAMING)
            isStandby -> Color.parseColor(COLOR_MIC_STANDBY)
            isRecording -> Color.parseColor(COLOR_MIC_STREAMING)
            else -> getThemedIconColor()
        }
        micIcon?.imageTintList = android.content.res.ColorStateList.valueOf(color)

        if (isPushToTalkMode && isPttRecording && pttStreamingStarted) {
            startPulseAnimation(micIcon)
        } else {
            stopPulseAnimation(micIcon)
        }
    }

    private fun startPulseAnimation(view: ImageView?) {
        view ?: return
        if (pulseAnimator?.isRunning == true) return
        pulseAnimator?.cancel()
        pulseAnimator = ObjectAnimator.ofFloat(view, "alpha", 1f, 0.4f).apply {
            duration = 500
            repeatCount = ObjectAnimator.INFINITE
            repeatMode = ObjectAnimator.REVERSE
            start()
        }
    }

    private fun stopPulseAnimation(view: ImageView?) {
        pulseAnimator?.cancel()
        pulseAnimator = null
        view?.alpha = 1f
    }

    private fun getThemedIconColor(): Int {
        val prefs = PreferenceManager(this)
        val themeSetting = prefs.getKeyboardTheme()

        return when (themeSetting) {
            PreferenceManager.THEME_AMOLED -> Color.parseColor("#FFFFFF")
            PreferenceManager.THEME_DARK -> ContextCompat.getColor(this, R.color.on_surface)
            PreferenceManager.THEME_LIGHT -> Color.parseColor("#1C1B1F")
            else -> {
                val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                if (nightModeFlags == Configuration.UI_MODE_NIGHT_YES) {
                    ContextCompat.getColor(this, R.color.on_surface)
                } else {
                    Color.parseColor("#1C1B1F")
                }
            }
        }
    }

    private fun updateKeyboardButtonState() {
        val iconRes = if (isKeyboardPanelVisible) R.drawable.ic_keyboard_expanded else R.drawable.ic_keyboard
        keyboardIcon?.setImageResource(iconRes)

        val iconColor = if (isKeyboardPanelVisible) {
            ContextCompat.getColor(this, R.color.primary)
        } else {
            getThemedIconColor()
        }
        keyboardIcon?.imageTintList = android.content.res.ColorStateList.valueOf(iconColor)
    }

    private fun updateEmojiButtonState() {
        val iconRes = if (isEmojiPanelVisible) R.drawable.ic_emoji_expanded else R.drawable.ic_emoji
        emojiIcon?.setImageResource(iconRes)

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

        spaceKeyButton?.text = langName

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

    private fun scrollDictationBarToDefault() {
        val scrollView = keyboardView?.findViewById<HorizontalScrollView>(R.id.dictationBarScrollView)
        val micButton = keyboardView?.findViewById<FrameLayout>(R.id.btnKeyboardMic)

        scrollView?.post {
            micButton?.let { mic ->
                val scrollViewWidth = scrollView.width
                val micLeft = mic.left
                val micWidth = mic.width
                val micCenter = micLeft + micWidth / 2
                val scrollTo = micCenter - scrollViewWidth / 2
                scrollView.smoothScrollTo(scrollTo.coerceAtLeast(0), 0)
            }
        }
    }

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

    private fun toggleEmojiPanel() {
        if (isKeyboardPanelVisible) {
            isKeyboardPanelVisible = false
            keyboardTabbedPanel?.visibility = View.GONE
            updateKeyboardButtonState()
        }

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

    private fun toggleKeyboardPanel() {
        if (isEmojiPanelVisible) {
            isEmojiPanelVisible = false
            emojiPanel?.visibility = View.GONE
            updateEmojiButtonState()
        }

        isKeyboardPanelVisible = !isKeyboardPanelVisible
        if (isKeyboardPanelVisible) wasAutoMutedByPlayback = false
        keyboardTabbedPanel?.visibility = if (isKeyboardPanelVisible) View.VISIBLE else View.GONE
        updateKeyboardButtonState()

        if (isKeyboardPanelVisible && !isMuteLocked && !isPushToTalkMode) {
            isMuteLocked = true
            if (isRecording || isProcessing || isStandby) {
                transcriptionManager?.stopListening()
                isRecording = false
                isProcessing = false
                isStandby = false
            }
            updateMicButton()
            DebugLog.i("DictationKeyboardService: Auto-muted mic for typing")
        }

        if (!isKeyboardPanelVisible && isNumbersMode) {
            isNumbersMode = false
            isShiftActive = false
            updateKeyboardMode()
        }

        if (isKeyboardPanelVisible) {
            updateNumberRowVisibility()
            if (currentKeyboardTab != 0) {
                currentKeyboardTab = 0
                updateKeyboardTabContent()
            }
        }

        Log.d(TAG, "Keyboard panel visibility: $isKeyboardPanelVisible")
        DebugLog.i("DictationKeyboardService: Keyboard panel ${if (isKeyboardPanelVisible) "shown" else "hidden"}")
    }

    private fun setupKeyboardTabs() {
        qwertyPanel?.visibility = View.VISIBLE
        cursorNumpadPanel?.visibility = View.GONE
        claudePanel?.visibility = View.GONE
        shellPanel?.visibility = View.GONE
        snippetsPanel?.visibility = View.GONE
        clipboardPanel?.visibility = View.GONE
        historyTabPanel?.visibility = View.GONE

        val prefs = PreferenceManager(this)

        tabABC?.setOnClickListener { switchToTab(0) }
        tabNumpad?.setOnClickListener { switchToTab(1) }
        tabClaude?.setOnClickListener { switchToTab(2) }
        tabShell?.setOnClickListener { switchToTab(3) }
        tabSnippets?.setOnClickListener { switchToTab(4) }
        tabClipboard?.setOnClickListener { switchToTab(5) }
        tabHistory?.setOnClickListener { switchToTab(6) }

        tabClaude?.visibility = if (prefs.getPanelClaudeEnabled()) View.VISIBLE else View.GONE
        tabShell?.visibility = if (prefs.getPanelShellEnabled()) View.VISIBLE else View.GONE
        tabSnippets?.visibility = if (prefs.getPanelSnippetsEnabled()) View.VISIBLE else View.GONE
        tabClipboard?.visibility = if (prefs.getPanelClipboardEnabled()) View.VISIBLE else View.GONE
        tabHistory?.visibility = if (prefs.getPanelHistoryEnabled()) View.VISIBLE else View.GONE

        val swipeOverlay = keyboardTabbedPanel?.findViewById<View>(R.id.swipeOverlay)
        var overlayDownY = 0f
        var overlayDownX = 0f
        var overlaySwipeHandled = false
        swipeOverlay?.setOnTouchListener { _, event ->
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
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    overlayDownY = event.rawY
                    overlayDownX = event.rawX
                    overlaySwipeHandled = false
                    targetPanel?.dispatchTouchEvent(event)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = event.rawY - overlayDownY
                    val dx = Math.abs(event.rawX - overlayDownX)
                    if (!overlaySwipeHandled && dy > 80 && dy > dx) {
                        overlaySwipeHandled = true
                        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
                        targetPanel?.dispatchTouchEvent(cancel)
                        cancel.recycle()
                        if (isKeyboardPanelVisible) toggleKeyboardPanel()
                    } else if (!overlaySwipeHandled) {
                        targetPanel?.dispatchTouchEvent(event)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!overlaySwipeHandled) {
                        targetPanel?.dispatchTouchEvent(event)
                    }
                }
                else -> targetPanel?.dispatchTouchEvent(event)
            }
            true
        }

        val dictationBar = keyboardView?.findViewById<HorizontalScrollView>(R.id.dictationBarScrollView)
        var barDownY = 0f
        var barDownX = 0f
        var barSwipeHandled = false
        dictationBar?.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    barDownY = event.rawY
                    barDownX = event.rawX
                    barSwipeHandled = false
                    v.onTouchEvent(event)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dy = barDownY - event.rawY
                    val dx = Math.abs(event.rawX - barDownX)
                    if (!barSwipeHandled && dy > 80 && dy > dx) {
                        barSwipeHandled = true
                        if (!isKeyboardPanelVisible) toggleKeyboardPanel()
                    } else {
                        v.onTouchEvent(event)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!barSwipeHandled) {
                        v.onTouchEvent(event)
                    }
                }
                else -> v.onTouchEvent(event)
            }
            true
        }

        updateTabIndicator()
    }

    private fun switchToTab(tab: Int) {
        if (tab == currentKeyboardTab) return
        currentKeyboardTab = tab

        when (tab) {
            2 -> inflateClaudePanelIfNeeded()
            3 -> inflateShellPanelIfNeeded()
            4 -> inflateSnippetsPanelIfNeeded()
            5 -> {
                inflateClipboardPanelIfNeeded()
                refreshClipboardPanel()
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

    private fun updateKeyboardTabContent() {
        qwertyPanel?.visibility = View.GONE
        cursorNumpadPanel?.visibility = View.GONE
        claudePanel?.visibility = View.GONE
        shellPanel?.visibility = View.GONE
        snippetsPanel?.visibility = View.GONE
        clipboardPanel?.visibility = View.GONE
        historyTabPanel?.visibility = View.GONE

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

    private fun updateTabIndicator() {
        val activeColor = androidx.core.content.ContextCompat.getColor(this, R.color.tab_active)
        val inactiveColor = androidx.core.content.ContextCompat.getColor(this, R.color.tab_inactive)

        val tabs = listOf(tabABC, tabNumpad, tabClaude, tabShell, tabSnippets, tabClipboard, tabHistory)
        tabs.forEachIndexed { index, tab ->
            tab?.setTextColor(if (index == currentKeyboardTab) activeColor else inactiveColor)
        }
    }

    private fun setupQwertyKeyboard() {
        qwertyPanel ?: return

        updateNumberRowVisibility()

        val numberKeys = listOf(
            R.id.key_1 to "1", R.id.key_2 to "2", R.id.key_3 to "3",
            R.id.key_4 to "4", R.id.key_5 to "5", R.id.key_6 to "6",
            R.id.key_7 to "7", R.id.key_8 to "8", R.id.key_9 to "9",
            R.id.key_0 to "0"
        )
        for ((id, char) in numberKeys) {
            qwertyPanel?.findViewById<android.widget.TextView>(id)?.setOnClickListener {
                currentInputConnection?.commitText(char, 1)
                trackTypedText(char)
            }
        }

        val letterKeys = mapOf(
            R.id.key_q to "q", R.id.key_w to "w", R.id.key_e to "e", R.id.key_r to "r",
            R.id.key_t to "t", R.id.key_y to "y", R.id.key_u to "u", R.id.key_i to "i",
            R.id.key_o to "o", R.id.key_p to "p", R.id.key_a to "a", R.id.key_s to "s",
            R.id.key_d to "d", R.id.key_f to "f", R.id.key_g to "g", R.id.key_h to "h",
            R.id.key_j to "j", R.id.key_k to "k", R.id.key_l to "l", R.id.key_z to "z",
            R.id.key_x to "x", R.id.key_c to "c", R.id.key_v to "v", R.id.key_b to "b",
            R.id.key_n to "n", R.id.key_m to "m"
        )

        val longPressNumbers = mapOf(
            R.id.key_q to "1", R.id.key_w to "2", R.id.key_e to "3", R.id.key_r to "4",
            R.id.key_t to "5", R.id.key_y to "6", R.id.key_u to "7", R.id.key_i to "8",
            R.id.key_o to "9", R.id.key_p to "0"
        )

        for ((id, _) in letterKeys) {
            val button = qwertyPanel?.findViewById<android.widget.TextView>(id)
            button?.setOnClickListener {
                val textToCommit = button.text.toString()
                currentInputConnection?.commitText(textToCommit, 1)
                trackTypedText(textToCommit)

                if (!isNumbersMode && !isTerminalApp && !isUrlField && allowsCloudText() && textToCommit.length == 1 && textToCommit[0].isLetter()) {
                    cancelNextWordPrediction()
                    if (isAutocorrectEnabled || isPredictionStripEnabled) {
                        autocorrectManager?.onLetterTyped(textToCommit[0])
                    }
                    if (isPredictionStripEnabled) {
                        requestAiCompletion()
                    }
                }

                if (!isNumbersMode && isShiftActive && !isCapsLock) {
                    isShiftActive = false
                    updateShiftKeyDisplay()
                }
            }

            longPressNumbers[id]?.let { number ->
                button?.setOnLongClickListener {
                    if (!isNumbersMode) {
                        currentInputConnection?.commitText(number, 1)
                        trackTypedText(number)
                    }
                    true
                }
            }
        }

        aKeyButton = qwertyPanel?.findViewById<android.widget.TextView>(R.id.key_a)
        aKeyButton?.setOnTouchListener { _, event ->
            handleAKeyTouch(event)
        }

        oKeyButton = qwertyPanel?.findViewById<android.widget.TextView>(R.id.key_o)
        oKeyButton?.setOnTouchListener { _, event ->
            handleOKeyTouch(event)
        }

        uKeyButton = qwertyPanel?.findViewById<android.widget.TextView>(R.id.key_u)
        uKeyButton?.setOnTouchListener { _, event ->
            handleUKeyTouch(event)
        }

        qwertyPanel?.findViewById<android.widget.TextView>(R.id.key_comma)?.setOnClickListener {
            autocorrectManager?.onWordBoundary()
            currentInputConnection?.commitText(",", 1)
            trackTypedText(",")
        }
        periodButton = qwertyPanel?.findViewById<android.widget.TextView>(R.id.key_period)
        periodButton?.setOnTouchListener { view, event ->
            handlePeriodTouch(view, event)
        }

        spaceKeyButton = qwertyPanel?.findViewById<android.widget.TextView>(R.id.key_space)
        spaceKeyButton?.text = getLanguageDisplayName(PreferenceManager(this).getLanguage())
        spaceKeyButton?.setOnTouchListener { _, event ->
            handleSpaceKeyTouch(event)
        }

        qwertyPanel?.findViewById<android.view.View>(R.id.key_enter)?.setOnClickListener {
            autocorrectManager?.onWordBoundary()
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
            internalTextBuffer.clear()
            DebugLog.d("DictationKeyboardService: Enter pressed, cleared internal buffer")
        }

        qwertyPanel?.findViewById<android.view.View>(R.id.key_backspace)?.setOnTouchListener { _, event ->
            handleBackspaceTouch(event)
        }

        shiftKeyButton = qwertyPanel?.findViewById<android.widget.TextView>(R.id.key_shift)
        shiftKeyButton?.setOnTouchListener { _, event ->
            handleShiftKeyTouch(event)
        }

        qwertyPanel?.findViewById<android.widget.TextView>(R.id.key_numbers)?.setOnClickListener {
            toggleNumbersMode()
        }

        setupProximityTouchOnRows()

        setupAutocorrect()
    }

    private fun setupProximityTouchOnRows() {
        val panel = qwertyPanel as? LinearLayout ?: return
        val letterRowIds = listOf(
            listOf(R.id.key_q, R.id.key_w, R.id.key_e, R.id.key_r, R.id.key_t,
                   R.id.key_y, R.id.key_u, R.id.key_i, R.id.key_o, R.id.key_p),
            listOf(R.id.key_a, R.id.key_s, R.id.key_d, R.id.key_f, R.id.key_g,
                   R.id.key_h, R.id.key_j, R.id.key_k, R.id.key_l),
            listOf(R.id.key_z, R.id.key_x, R.id.key_c, R.id.key_v,
                   R.id.key_b, R.id.key_n, R.id.key_m)
        )

        for (i in 0 until panel.childCount) {
            val row = panel.getChildAt(i) as? LinearLayout ?: continue
            val rowKeyIds = letterRowIds.firstOrNull { ids ->
                ids.any { row.findViewById<View>(it) != null }
            } ?: continue

            val keyViews = mutableListOf<View>()
            for (id in rowKeyIds) {
                val view = row.findViewById<View>(id)
                val target = if (view?.parent is FrameLayout && view.parent != row) {
                    view.parent as View
                } else {
                    view
                }
                if (target != null) keyViews.add(target)
            }

            row.setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    var nearestKey: View? = null
                    var minDist = Float.MAX_VALUE
                    val touchX = event.x
                    val touchY = event.y

                    for (key in keyViews) {
                        val centerX = key.left + key.width / 2f
                        val centerY = key.top + key.height / 2f
                        val dx = touchX - centerX
                        val dy = touchY - centerY
                        val dist = dx * dx + dy * dy
                        if (dist < minDist) {
                            minDist = dist
                            nearestKey = key
                        }
                    }

                    nearestKey?.let {
                        it.performClick()
                        it.isPressed = true
                        it.postDelayed({ it.isPressed = false }, 100)
                    }
                    true
                } else {
                    false
                }
            }
        }
    }

    private fun setupAutocorrect() {
        val prefs = PreferenceManager(this)
        isPredictionStripEnabled = prefs.getPredictionStripEnabled()
        isAutocorrectEnabled = prefs.getAutocorrectEnabled()
        isAiNextWordEnabled = prefs.getAiNextWordEnabled()
        isAiWordCompletionEnabled = prefs.getAiWordCompletionEnabled()
        isAutoFixEnabled = prefs.getAutoFixEnabled()

        if (autocorrectManager == null) {
            autocorrectManager = AutocorrectManager(this)
        }
        val currentLang = prefs.getLanguage()
        autocorrectManager?.setLanguage(currentLang)

        predictionStrip = qwertyPanel?.findViewById(R.id.predictionStrip)
        prediction1 = qwertyPanel?.findViewById(R.id.prediction1)
        prediction2 = qwertyPanel?.findViewById(R.id.prediction2)
        prediction3 = qwertyPanel?.findViewById(R.id.prediction3)

        if (!isPredictionStripEnabled) {
            predictionStrip?.visibility = View.GONE
            val container = keyboardTabbedPanel?.findViewById<FrameLayout>(R.id.keyboardPagesContainer)
            container?.let {
                val reducePx = (50 * resources.displayMetrics.density).toInt()
                it.layoutParams = it.layoutParams.apply { height -= reducePx }
            }
        }

        DebugLog.d("setupAutocorrect: strip=${predictionStrip != null}, predictions=$isPredictionStripEnabled, autocorrect=$isAutocorrectEnabled")

        autocorrectManager?.onSuggestionsUpdated = { typed, suggestions ->
            if (isPredictionStripEnabled) {
                updatePredictionStrip(typed, suggestions)
            }
        }

        prediction1?.setOnClickListener { applyPrediction(0) }
        prediction2?.setOnClickListener { applyPrediction(1) }
        prediction3?.setOnClickListener { applyPrediction(2) }
    }

    private fun updatePredictionStrip(typed: String, suggestions: List<String>) {
        if (typed.isEmpty() && suggestions.isEmpty()) {
            prediction1?.text = ""
            prediction2?.text = ""
            if (aiCompletionWord == null) {
                prediction3?.text = ""
            }
            return
        }

        prediction1?.text = suggestions.getOrNull(1) ?: ""
        prediction2?.text = suggestions.getOrNull(0) ?: typed
    }

    private fun applyPrediction(index: Int) {
        val manager = autocorrectManager ?: return
        val typed = manager.getCurrentWord()
        if (typed.isEmpty()) return

        val replacement: String
        if (index == 2 && aiCompletionWord != null) {
            replacement = aiCompletionWord!!
        } else {
            val suggestionIndex = when (index) {
                0 -> 1
                1 -> 0
                else -> return
            }
            replacement = manager.getCachedSuggestions().getOrNull(suggestionIndex) ?: return
        }

        val ic = currentInputConnection ?: return
        ic.beginBatchEdit()
        ic.deleteSurroundingText(typed.length, 0)
        val cased = if (typed.first().isUpperCase()) {
            replacement.replaceFirstChar { it.uppercase() }
        } else if (replacement == "i") {
            "I"
        } else {
            replacement
        }
        ic.commitText("$cased ", 1)
        ic.endBatchEdit()

        trackTypedText("$cased ")
        manager.onPredictionTapped(replacement)
        cancelAiCompletion()

        requestNextWordPrediction()
    }

    private fun trackTypedText(text: String) {
        if (!allowsCloudText()) return
        internalTextBuffer.append(text)
        if (internalTextBuffer.length > 100) {
            internalTextBuffer.delete(0, internalTextBuffer.length - 100)
        }
    }

    private fun trackBackspace(count: Int = 1) {
        val deleteCount = minOf(count, internalTextBuffer.length)
        if (deleteCount > 0) {
            internalTextBuffer.delete(internalTextBuffer.length - deleteCount, internalTextBuffer.length)
        }
    }

    private fun setupCursorNumpadPanel() {
        cursorNumpadPanel ?: return

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

        cursorNumpadPanel?.findViewById<android.widget.TextView>(R.id.cursor_tab)?.setOnClickListener {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_TAB)
        }
        cursorNumpadPanel?.findViewById<android.widget.TextView>(R.id.cursor_del)?.setOnClickListener {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_FORWARD_DEL)
        }
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

    private fun setupClaudePanel() {
        claudePanel ?: return

        fun setupButtonsInViewGroup(viewGroup: android.view.ViewGroup) {
            for (i in 0 until viewGroup.childCount) {
                val child = viewGroup.getChildAt(i)
                when (child) {
                    is android.widget.TextView -> {
                        val tag = child.tag as? String ?: continue
                        child.setOnClickListener {
                            currentInputConnection?.commitText(tag, 1)
                            trackTypedText(tag)
                            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
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

        val scrollView = claudePanel as? ScrollView
        val contentLayout = scrollView?.getChildAt(0) as? android.view.ViewGroup ?: return
        setupButtonsInViewGroup(contentLayout)

        DebugLog.i("DictationKeyboardService: Claude panel set up")
    }

    private fun setupShellPanel() {
        shellPanel ?: return

        val ctrlCombos = mapOf(
            "ctrl_c" to 'c', "ctrl_u" to 'u', "ctrl_z" to 'z', "ctrl_d" to 'd',
            "ctrl_l" to 'l', "ctrl_a" to 'a', "ctrl_e" to 'e', "ctrl_k" to 'k',
            "ctrl_r" to 'r'
        )

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

        fun setupButton(button: android.widget.TextView) {
            val tag = button.tag as? String ?: return

            button.setOnClickListener {
                when {
                    tag.startsWith("ctrl_") -> {
                        val char = ctrlCombos[tag] ?: return@setOnClickListener
                        sendCtrlKey(char)
                    }
                    tag == "shift_tab" -> {
                        sendShiftTab()
                    }
                    navKeys.containsKey(tag) -> {
                        sendDownUpKeyEvents(navKeys[tag]!!)
                    }
                    else -> {
                        currentInputConnection?.commitText(tag, 1)
                    }
                }
            }
        }

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

    private fun sendShiftTab() {
        val now = android.os.SystemClock.uptimeMillis()
        val downEvent = KeyEvent(now, now, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB, 0, KeyEvent.META_SHIFT_ON)
        val upEvent = KeyEvent(now, now, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_TAB, 0, KeyEvent.META_SHIFT_ON)
        currentInputConnection?.sendKeyEvent(downEvent)
        currentInputConnection?.sendKeyEvent(upEvent)
    }

    private fun setupSnippetsPanel() {
        try {
            snippetsPanel ?: return

            val recyclerView = snippetsPanel?.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.snippetsRecyclerView)
            val emptyState = snippetsPanel?.findViewById<View>(R.id.snippetsEmptyState)

            if (recyclerView == null) {
                DebugLog.e("DictationKeyboardService: snippetsRecyclerView not found!")
                return
            }

            recyclerView.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 2)

            loadSnippetsContent(recyclerView, emptyState)

            val fabAddSnippet = snippetsPanel?.findViewById<com.google.android.material.floatingactionbutton.FloatingActionButton>(R.id.fabAddSnippet)
            fabAddSnippet?.setOnClickListener {
                showAddSnippetDialog()
            }

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

    private fun loadSnippetsContent(recyclerView: androidx.recyclerview.widget.RecyclerView, emptyState: View?) {
        val snippetManager = com.tinydictation.android.snippet.SnippetManager.getInstance(this)
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

    private fun refreshSnippetsPanel() {
        val recyclerView = snippetsPanel?.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.snippetsRecyclerView)
        val emptyState = snippetsPanel?.findViewById<View>(R.id.snippetsEmptyState)
        recyclerView?.let { loadSnippetsContent(it, emptyState) }
    }

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
                            DebugLog.i("DictationKeyboardService: Snippet created manually:", "${text.take(30)}...")
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

    private inner class SnippetsTabAdapter(
        private val snippets: List<String>,
        private val onItemClick: (String) -> Unit,
        private val onItemDelete: (String) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<SnippetsTabAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
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

                itemView.setOnClickListener { onItemClick(snippet) }

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

    private fun setupClipboardPanel() {
        try {
            clipboardPanel ?: return

            val recyclerView = clipboardPanel?.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.clipboardRecyclerView)
            val emptyState = clipboardPanel?.findViewById<View>(R.id.clipboardEmptyState)

            if (recyclerView == null) {
                DebugLog.e("DictationKeyboardService: clipboardRecyclerView not found!")
                return
            }

            clipboardHistoryManager?.addOnHistoryChangedListener(this)

            recyclerView.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 2)

            loadClipboardContent(recyclerView, emptyState)

            DebugLog.i("DictationKeyboardService: Clipboard panel set up")
        } catch (e: Exception) {
            DebugLog.e("DictationKeyboardService: Failed to setup clipboard panel: ${e.message}")
            Log.e(TAG, "Failed to setup clipboard panel", e)
        }
    }

    private fun loadClipboardContent(recyclerView: androidx.recyclerview.widget.RecyclerView, emptyState: View?) {
        val clipboardManager = ClipboardHistoryManager.getInstance(this)
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

    private fun refreshClipboardPanel() {
        val recyclerView = clipboardPanel?.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.clipboardRecyclerView)
        val emptyState = clipboardPanel?.findViewById<View>(R.id.clipboardEmptyState)
        recyclerView?.let { loadClipboardContent(it, emptyState) }
    }

    override fun onClipboardHistoryChanged() {
        Handler(Looper.getMainLooper()).post {
            if (isClipboardPanelInflated && clipboardPanel?.visibility == View.VISIBLE) {
                refreshClipboardPanel()
                DebugLog.d("DictationKeyboardService: Clipboard panel refreshed (real-time update)")
            }
        }
    }

    private inner class ClipboardTabAdapter(
        private val entries: List<com.tinydictation.android.history.ClipboardEntry>,
        private val onItemClick: (com.tinydictation.android.history.ClipboardEntry) -> Unit,
        private val onPinToggle: (com.tinydictation.android.history.ClipboardEntry) -> Unit,
        private val onItemDelete: (com.tinydictation.android.history.ClipboardEntry) -> Unit,
        private val onSaveAsSnippet: (com.tinydictation.android.history.ClipboardEntry) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<ClipboardTabAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
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

                if (entry.isPinned) {
                    ivPinned?.setImageResource(R.drawable.ic_pin)
                    ivPinned?.setColorFilter(androidx.core.content.ContextCompat.getColor(itemView.context, R.color.primary))
                } else {
                    ivPinned?.setImageResource(R.drawable.ic_pin_outline)
                    ivPinned?.setColorFilter(androidx.core.content.ContextCompat.getColor(itemView.context, R.color.on_surface_variant))
                }

                itemView.setOnClickListener { onItemClick(entry) }

                ivPinned?.setOnClickListener { onPinToggle(entry) }

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
                    now.get(java.util.Calendar.DATE) == then.get(java.util.Calendar.DATE) &&
                    now.get(java.util.Calendar.MONTH) == then.get(java.util.Calendar.MONTH) &&
                    now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) -> {
                        java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(date)
                    }
                    now.get(java.util.Calendar.DATE) - then.get(java.util.Calendar.DATE) == 1 &&
                    now.get(java.util.Calendar.MONTH) == then.get(java.util.Calendar.MONTH) &&
                    now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) -> {
                        "Yest ${java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(date)}"
                    }
                    now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) -> {
                        java.text.SimpleDateFormat("M/d h:mm a", java.util.Locale.getDefault()).format(date)
                    }
                    else -> {
                        java.text.SimpleDateFormat("M/d/yy", java.util.Locale.getDefault()).format(date)
                    }
                }
            }
        }
    }

    private fun setupHistoryTabPanel() {
        try {
            historyTabPanel ?: return

            val recyclerView = historyTabPanel?.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.historyRecyclerView)
            val emptyState = historyTabPanel?.findViewById<View>(R.id.historyEmptyState)

            if (recyclerView == null) {
                DebugLog.e("DictationKeyboardService: historyRecyclerView not found!")
                return
            }

            recyclerView.layoutManager = androidx.recyclerview.widget.GridLayoutManager(this, 2)

            loadHistoryTabContent(recyclerView, emptyState)

            DebugLog.i("DictationKeyboardService: History tab panel set up")
        } catch (e: Exception) {
            DebugLog.e("DictationKeyboardService: Failed to setup history panel: ${e.message}")
            Log.e(TAG, "Failed to setup history panel", e)
        }
    }

    private fun loadHistoryTabContent(recyclerView: androidx.recyclerview.widget.RecyclerView, emptyState: View?) {
        val historyManager = com.tinydictation.android.history.TranscriptionHistoryManager.getInstance(this)
        val entries = historyManager.getAll().sortedByDescending { it.timestamp }

        if (entries.isEmpty()) {
            emptyState?.visibility = View.VISIBLE
            recyclerView.visibility = View.GONE
        } else {
            emptyState?.visibility = View.GONE
            recyclerView.visibility = View.VISIBLE

            recyclerView.adapter = HistoryTabAdapter(entries) { entry ->
                commitTextInternal(entry.text)
            }
        }
    }

    private inner class HistoryTabAdapter(
        private val entries: List<com.tinydictation.android.history.TranscriptionEntry>,
        private val onItemClick: (com.tinydictation.android.history.TranscriptionEntry) -> Unit
    ) : androidx.recyclerview.widget.RecyclerView.Adapter<HistoryTabAdapter.ViewHolder>() {

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): ViewHolder {
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
            private val tvTimestamp: android.widget.TextView? = itemView.findViewById(R.id.tvTimestamp)
            private val tvLanguage: android.widget.TextView? = itemView.findViewById(R.id.tvLanguage)
            private val tvText: android.widget.TextView? = itemView.findViewById(R.id.tvText)

            fun bind(entry: com.tinydictation.android.history.TranscriptionEntry) {
                tvTimestamp?.text = formatTimestamp(entry.timestamp)
                tvLanguage?.text = entry.language.uppercase()
                tvText?.text = entry.text

                itemView.setOnClickListener { onItemClick(entry) }
            }

            private fun formatTimestamp(timestamp: Long): String {
                val date = java.util.Date(timestamp)
                val now = java.util.Calendar.getInstance()
                val then = java.util.Calendar.getInstance().apply { time = date }

                return when {
                    now.get(java.util.Calendar.DATE) == then.get(java.util.Calendar.DATE) &&
                    now.get(java.util.Calendar.MONTH) == then.get(java.util.Calendar.MONTH) &&
                    now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) -> {
                        java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(date)
                    }
                    now.get(java.util.Calendar.DATE) - then.get(java.util.Calendar.DATE) == 1 &&
                    now.get(java.util.Calendar.MONTH) == then.get(java.util.Calendar.MONTH) &&
                    now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) -> {
                        "Yest ${java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(date)}"
                    }
                    now.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) -> {
                        java.text.SimpleDateFormat("M/d h:mm a", java.util.Locale.getDefault()).format(date)
                    }
                    else -> {
                        java.text.SimpleDateFormat("M/d/yy", java.util.Locale.getDefault()).format(date)
                    }
                }
            }
        }
    }

    private fun toggleNumbersMode() {
        isNumbersMode = !isNumbersMode
        updateKeyboardMode()
        DebugLog.d("DictationKeyboardService: Numbers mode: $isNumbersMode")
    }

    private fun updateKeyboardMode() {
        qwertyDisplayHelper?.updateKeyboardMode(isNumbersMode, isShiftActive, isCapsLock, isGermanMode)
        updateLongPressHints()
    }

    private fun updateSymbolsDisplay() {
        qwertyDisplayHelper?.updateSymbolsDisplay(isShiftActive)
    }

    private fun updateShiftKeyDisplay() {
        qwertyDisplayHelper?.updateShiftKeyDisplay(isGermanMode, isCapsLock, isShiftActive)
    }

    private fun updateNumberRowVisibility() {
        val prefs = PreferenceManager(this)
        val showNumberRow = prefs.getShowNumberRow()
        DebugLog.d("DictationKeyboardService: updateNumberRowVisibility - showNumberRow=$showNumberRow")
        qwertyDisplayHelper?.updateNumberRowVisibility(showNumberRow)
    }

    private fun updateLongPressHints() {
        qwertyDisplayHelper?.updateLongPressHints(isNumbersMode, isGermanMode)
    }

    private fun updateLetterKeyDisplays() {
        qwertyDisplayHelper?.updateLetterKeyDisplays(isShiftActive, isCapsLock)
    }

    private fun handleBackspaceTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                DebugLog.d("DictationKeyboardService: Backspace ACTION_DOWN")

                backspaceCount = 0

                sendBackspace()
                backspaceCount++

                backspaceStartTime = System.currentTimeMillis()
                isBackspaceHeld = true

                backspaceHandler.removeCallbacks(backspaceRepeatRunnable)

                backspaceHandler.postDelayed(backspaceRepeatRunnable, BACKSPACE_INITIAL_DELAY)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val holdDuration = System.currentTimeMillis() - backspaceStartTime
                DebugLog.d("DictationKeyboardService: Backspace ACTION_UP/CANCEL after ${holdDuration}ms, $backspaceCount deletions")

                isBackspaceHeld = false

                backspaceHandler.removeCallbacks(backspaceRepeatRunnable)
                return true
            }
        }
        return false
    }

    private fun handleCursorKeyTouch(event: MotionEvent, keyCode: Int): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                DebugLog.d("DictationKeyboardService: Cursor key $keyCode ACTION_DOWN")

                cursorKeyCount = 0
                cursorKeyCode = keyCode

                sendDownUpKeyEvents(keyCode)
                cursorKeyCount++

                cursorStartTime = System.currentTimeMillis()
                isCursorKeyHeld = true

                cursorHandler.removeCallbacks(cursorRepeatRunnable)

                cursorHandler.postDelayed(cursorRepeatRunnable, BACKSPACE_INITIAL_DELAY)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val holdDuration = System.currentTimeMillis() - cursorStartTime
                DebugLog.d("DictationKeyboardService: Cursor key $cursorKeyCode ACTION_UP/CANCEL after ${holdDuration}ms, $cursorKeyCount presses")

                isCursorKeyHeld = false

                cursorHandler.removeCallbacks(cursorRepeatRunnable)
                return true
            }
        }
        return false
    }

    private val punctuationLongPressRunnable = Runnable {
        showPunctuationPopup()
    }

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
                    val char = selectedAKeyOption ?: (if (isShiftActive || isCapsLock) "Ä" else "ä")
                    currentInputConnection?.commitText(char, 1)
                    trackTypedText(char)
                    hideAKeyPopup()
                    DebugLog.d("DictationKeyboardService: A key popup selected '$char'")
                } else if (!isAKeyLongPressTriggered) {
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

    private fun updateAKeySelection(rawX: Float, rawY: Float) {
        aKeyPopup ?: return

        val location = IntArray(2)
        aKeyPopup?.getLocationOnScreen(location)
        val popupX = location[0]
        val popupCenterX = popupX + (aKeyPopup?.width ?: 0) / 2

        val highlightColor = ContextCompat.getColor(this, R.color.primary)
        val normalColor = ContextCompat.getColor(this, R.color.on_surface)

        if (rawX < popupCenterX) {
            val umlautChar = if (isShiftActive || isCapsLock) "Ä" else "ä"
            selectedAKeyOption = umlautChar
            aKeyUmlautView?.setTextColor(highlightColor)
            aKeyAtView?.setTextColor(normalColor)
        } else {
            selectedAKeyOption = "@"
            aKeyUmlautView?.setTextColor(normalColor)
            aKeyAtView?.setTextColor(highlightColor)
        }
    }

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

    private fun handleOKeyTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isOKeyLongPressTriggered = false
                oKeyLongPressHandler.postDelayed(
                    oKeyLongPressRunnable,
                    PUNCTUATION_LONG_PRESS_THRESHOLD
                )
                return true
            }
            MotionEvent.ACTION_UP -> {
                oKeyLongPressHandler.removeCallbacks(oKeyLongPressRunnable)

                if (!isOKeyLongPressTriggered) {
                    val char = oKeyButton?.text?.toString() ?: "o"
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
                oKeyLongPressHandler.removeCallbacks(oKeyLongPressRunnable)
                return true
            }
        }
        return false
    }

    private fun handleUKeyTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isUKeyLongPressTriggered = false
                uKeyLongPressHandler.postDelayed(
                    uKeyLongPressRunnable,
                    PUNCTUATION_LONG_PRESS_THRESHOLD
                )
                return true
            }
            MotionEvent.ACTION_UP -> {
                uKeyLongPressHandler.removeCallbacks(uKeyLongPressRunnable)

                if (!isUKeyLongPressTriggered) {
                    val char = uKeyButton?.text?.toString() ?: "u"
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
                uKeyLongPressHandler.removeCallbacks(uKeyLongPressRunnable)
                return true
            }
        }
        return false
    }

    private var lastShiftTapTime = 0L


    private fun handleShiftKeyTouch(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isShiftLongPressTriggered = false
                shiftLongPressHandler.postDelayed(
                    shiftLongPressRunnable,
                    PUNCTUATION_LONG_PRESS_THRESHOLD
                )
                return true
            }
            MotionEvent.ACTION_UP -> {
                shiftLongPressHandler.removeCallbacks(shiftLongPressRunnable)

                if (!isShiftLongPressTriggered) {
                    if (isNumbersMode) {
                        isShiftActive = !isShiftActive
                        updateSymbolsDisplay()
                    } else {
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastShiftTapTime < 300) {
                            isCapsLock = !isCapsLock
                            isShiftActive = isCapsLock
                            DebugLog.d("DictationKeyboardService: Caps lock ${if (isCapsLock) "ON" else "OFF"}")
                        } else {
                            if (isCapsLock) {
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

    private fun handleSpaceKeyTouch(event: MotionEvent): Boolean {
        val moveThreshold = TRACKPAD_MOVE_THRESHOLD_DP * resources.displayMetrics.density

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                isSpaceTrackpadMode = false
                spaceTrackpadStartX = event.rawX
                spaceTrackpadLastX = event.rawX
                spaceTrackpadAccumulatedDelta = 0f
                spaceTrackpadLastMoveTime = System.currentTimeMillis()

                spaceDownWord = autocorrectManager?.getCurrentWord() ?: ""
                autocorrectManager?.onWordBoundary()

                spaceTrackpadHandler.postDelayed(
                    spaceTrackpadRunnable,
                    TRACKPAD_LONG_PRESS_THRESHOLD
                )
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isSpaceTrackpadMode) {
                    val currentTime = System.currentTimeMillis()
                    val timeDelta = (currentTime - spaceTrackpadLastMoveTime).coerceAtLeast(1)
                    val deltaX = event.rawX - spaceTrackpadLastX

                    val velocity = Math.abs(deltaX) / timeDelta

                    val acceleration = (1f + velocity * 1.5f).coerceIn(1f, 4f)

                    spaceTrackpadAccumulatedDelta += deltaX * acceleration
                    spaceTrackpadLastX = event.rawX
                    spaceTrackpadLastMoveTime = currentTime

                    while (Math.abs(spaceTrackpadAccumulatedDelta) >= moveThreshold) {
                        if (spaceTrackpadAccumulatedDelta >= moveThreshold) {
                            sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_RIGHT)
                            spaceTrackpadAccumulatedDelta -= moveThreshold
                            spaceKeyButton?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        } else if (spaceTrackpadAccumulatedDelta <= -moveThreshold) {
                            sendDownUpKeyEvents(KeyEvent.KEYCODE_DPAD_LEFT)
                            spaceTrackpadAccumulatedDelta += moveThreshold
                            spaceKeyButton?.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP)
                        }
                    }
                } else {
                    val dx = event.rawX - spaceTrackpadStartX
                    if (Math.abs(dx) > moveThreshold * 2) {
                        spaceTrackpadHandler.removeCallbacks(spaceTrackpadRunnable)
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                spaceTrackpadHandler.removeCallbacks(spaceTrackpadRunnable)

                if (!isSpaceTrackpadMode) {
                    cancelNextWordPrediction()
                    cancelAiCompletion()

                    val typed = spaceDownWord

                    if (isTerminalApp || isUrlField || !isAutocorrectEnabled) {
                        currentInputConnection?.commitText(" ", 1)
                        trackTypedText(" ")
                    } else {
                        val correction = if (typed.length >= 2 && !autocorrectManager?.isKnownWord(typed)!!) {
                            autocorrectManager?.tryCorrectWord(typed)
                        } else null

                        if (correction != null) {
                            val ic = currentInputConnection
                            if (ic != null) {
                                ic.beginBatchEdit()
                                ic.deleteSurroundingText(typed.length, 0)
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

    private fun handlePeriodTouch(view: View, event: MotionEvent): Boolean {
        val moveThreshold = PUNCTUATION_MOVE_THRESHOLD_DP * resources.displayMetrics.density

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                selectedPunctuation = null
                isPunctuationPopupShowing = false

                punctuationTouchStartX = event.rawX
                punctuationTouchStartY = event.rawY

                punctuationLongPressHandler.postDelayed(
                    punctuationLongPressRunnable,
                    PUNCTUATION_LONG_PRESS_THRESHOLD
                )
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isPunctuationPopupShowing) {
                    updatePunctuationSelection(event.rawX, event.rawY)
                } else {
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
                    val char = selectedPunctuation ?: "."
                    currentInputConnection?.commitText(char, 1)
                    hidePunctuationPopup()
                    DebugLog.d("DictationKeyboardService: Punctuation popup selected '$char'")
                    triggerAutoFix()
                } else {
                    currentInputConnection?.commitText(".", 1)
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

    private fun showPunctuationPopup() {
        isPunctuationPopupShowing = true
        selectedPunctuation = null

        periodButton ?: return
        val density = resources.displayMetrics.density

        punctuationPopup = createPunctuationPopupView()

        punctuationPopup?.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val popupWidth = punctuationPopup?.measuredWidth ?: 0
        val popupHeight = punctuationPopup?.measuredHeight ?: 0

        val buttonLocation = IntArray(2)
        val panelLocation = IntArray(2)
        periodButton?.getLocationInWindow(buttonLocation)
        keyboardTabbedPanel?.getLocationInWindow(panelLocation)

        val relativeX = buttonLocation[0] - panelLocation[0]
        val relativeY = buttonLocation[1] - panelLocation[1]
        val buttonWidth = periodButton?.width ?: 0

        val params = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = relativeX + (buttonWidth / 2) - (popupWidth / 2)
            topMargin = relativeY - popupHeight - (8 * density).toInt()
        }

        val container = keyboardTabbedPanel?.findViewById<FrameLayout>(R.id.keyboardPagesContainer)
        container?.addView(punctuationPopup, params)

        periodButton?.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)

        DebugLog.d("DictationKeyboardService: Showing punctuation popup")
    }

    private fun createPunctuationPopupView(): View {
        val density = resources.displayMetrics.density
        val popupWidth = (120 * density).toInt()
        val popupHeight = (56 * density).toInt()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(popupWidth, popupHeight)
            gravity = android.view.Gravity.CENTER

            background = GradientDrawable().apply {
                setColor(ContextCompat.getColor(this@DictationKeyboardService, R.color.surface))
                cornerRadius = 12 * density
                setStroke((1 * density).toInt(), ContextCompat.getColor(this@DictationKeyboardService, R.color.outline))
            }
            elevation = 8 * density
        }

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

        punctuationExclamationView = exclamationText
        punctuationQuestionView = questionText

        return container
    }

    private fun updatePunctuationSelection(rawX: Float, rawY: Float) {
        punctuationPopup ?: return

        val location = IntArray(2)
        punctuationPopup?.getLocationOnScreen(location)
        val popupX = location[0]
        val popupCenterX = popupX + (punctuationPopup?.width ?: 0) / 2

        val newSelection = if (rawX < popupCenterX) "!" else "?"

        if (newSelection != selectedPunctuation) {
            selectedPunctuation = newSelection
            updatePunctuationHighlight()
            periodButton?.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        }
    }

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

    private fun hidePunctuationPopup() {
        isPunctuationPopupShowing = false
        selectedPunctuation = null

        punctuationPopup?.let { popup ->
            (popup.parent as? android.view.ViewGroup)?.removeView(popup)
        }
        punctuationPopup = null
        punctuationExclamationView = null
        punctuationQuestionView = null
    }

    private fun deleteWord() {
        Log.i(TAG, "DictationKeyboardService: Delete word")
        DebugLog.d("DictationKeyboardService: Deleting word")

        currentInputConnection?.let { ic ->
            val textBefore = ic.getTextBeforeCursor(50, 0)
            if (textBefore.isNullOrEmpty()) {
                return
            }

            var endIndex = textBefore.length
            while (endIndex > 0 && textBefore[endIndex - 1] == ' ') {
                endIndex--
            }

            var startIndex = endIndex
            while (startIndex > 0 && textBefore[startIndex - 1] != ' ') {
                startIndex--
            }

            val charsToDelete = textBefore.length - startIndex
            if (charsToDelete > 0) {
                ic.deleteSurroundingText(charsToDelete, 0)
                DebugLog.d("DictationKeyboardService: Deleted $charsToDelete chars (word)")
            }
        }
    }

    private fun sendBackspace() {
        val undo = autocorrectManager?.lastAutocorrect
        if (undo != null && autocorrectManager?.onBackspace() == true) {
            val ic = currentInputConnection
            if (ic != null) {
                ic.beginBatchEdit()
                ic.deleteSurroundingText(undo.replacement.length + 1, 0)
                ic.commitText(undo.original, 1)
                ic.endBatchEdit()
                DebugLog.d("DictationKeyboardService: Undid autocorrect", "'${undo.replacement}' → '${undo.original}'")
                return
            }
        }

        autocorrectManager?.onBackspace()
        sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
        trackBackspace(1)
    }

    private fun selectAll() {
        Log.i(TAG, "DictationKeyboardService: Select all")
        DebugLog.d("DictationKeyboardService: Selecting all text")

        currentInputConnection?.performContextMenuAction(android.R.id.selectAll)
    }

    private fun allowsCloudText(): Boolean {
        val info = currentInputEditorInfo ?: return false
        return EditorPrivacy.allowsCloudText(info.inputType, info.imeOptions)
    }

    private fun triggerAutoFix() {
        if (!allowsCloudText()) return
        if (!isAutoFixEnabled || isAutoFixRunning || autoFixUndoAvailable || isTerminalApp) return
        if (textImprover == null) {
            textImprover = TextImprover(this)
        }
        if (!PreferenceManager(this).hasAnthropicApiKey()) return

        val ic = currentInputConnection ?: return
        val textBefore = ic.getTextBeforeCursor(500, 0)?.toString() ?: return
        if (textBefore.isBlank() || textBefore.length < 5) return

        val lastSentenceStart = findLastSentenceStart(textBefore)
        val sentence = textBefore.substring(lastSentenceStart).trim()
        if (sentence.length < 5) return

        isAutoFixRunning = true
        autoFixOriginalText = sentence

        showAutoFixProgress()

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val result = textImprover?.improve(sentence)
                val improved = result?.getOrNull()

                if (improved != null && improved != sentence && improved.isNotBlank()) {
                    val capitalizedImproved = improved.replaceFirstChar { it.uppercase() }

                    val currentIc = currentInputConnection
                    if (currentIc != null) {
                        val currentText = currentIc.getTextBeforeCursor(500, 0)?.toString() ?: ""
                        val sentencePos = currentText.lastIndexOf(sentence)

                        if (sentencePos >= 0) {
                            val charsToDelete = currentText.length - sentencePos
                            val textAfterSentence = currentText.substring(sentencePos + sentence.length)

                            currentIc.beginBatchEdit()
                            currentIc.deleteSurroundingText(charsToDelete, 0)
                            val improvedWithSpace = "$capitalizedImproved "
                            currentIc.commitText(improvedWithSpace + textAfterSentence, 1)
                            currentIc.endBatchEdit()

                            autoFixReplacementText = improvedWithSpace
                            autoFixUndoAvailable = true
                            showAutoFixUndo()

                            autoFixHandler.postDelayed({
                                clearAutoFixUndo()
                            }, AUTO_FIX_UNDO_TIMEOUT)

                            DebugLog.i("DictationKeyboardService: Auto-fix applied:", "'$sentence' → '$capitalizedImproved'")
                        } else {
                            DebugLog.w("DictationKeyboardService: Auto-fix: original sentence not found, skipping")
                            clearAutoFixState()
                        }
                    } else {
                        clearAutoFixState()
                    }
                } else {
                    clearAutoFixState()
                    DebugLog.d("DictationKeyboardService: Auto-fix: no changes needed")
                }
            } catch (e: Exception) {
                DebugLog.e("DictationKeyboardService: Auto-fix failed:", "${e.message}")
                clearAutoFixState()
            }
            isAutoFixRunning = false
        }
    }

    private fun findLastSentenceStart(text: String): Int {
        val trimmed = text.trimEnd()
        for (i in trimmed.length - 2 downTo 0) {
            if (trimmed[i] == '.' || trimmed[i] == '!' || trimmed[i] == '?') {
                return i + 1
            }
        }
        return 0
    }

    private fun showAutoFixProgress() {
        prediction1?.text = ""
        prediction2?.text = "AI fixing..."
        prediction3?.text = ""
        predictionStrip?.setBackgroundColor(AUTO_FIX_COLOR)
        prediction2?.setTextColor(Color.WHITE)
    }

    private fun showAutoFixUndo() {
        prediction1?.text = ""
        prediction2?.text = "Undo fix"
        prediction3?.text = ""
        predictionStrip?.setBackgroundColor(AUTO_FIX_DONE_COLOR)
        prediction2?.setTextColor(Color.WHITE)

        prediction2?.setOnClickListener {
            undoAutoFix()
        }
    }

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

    private fun clearAutoFixUndo() {
        autoFixUndoAvailable = false
        autoFixOriginalText = null
        autoFixReplacementText = null
        autoFixHandler.removeCallbacksAndMessages(null)

        predictionStrip?.setBackgroundColor(Color.TRANSPARENT)
        prediction1?.setTextColor(ContextCompat.getColor(this, R.color.keyboard_key_text))
        prediction2?.setTextColor(ContextCompat.getColor(this, R.color.keyboard_key_text))
        prediction3?.setTextColor(ContextCompat.getColor(this, R.color.keyboard_key_text))
        prediction1?.text = ""
        prediction2?.text = ""
        prediction3?.text = ""

        prediction1?.setOnClickListener { applyPrediction(0) }
        prediction2?.setOnClickListener { applyPrediction(1) }
        prediction3?.setOnClickListener { applyPrediction(2) }
    }

    private fun clearAutoFixState() {
        isAutoFixRunning = false
        autoFixOriginalText = null
        autoFixReplacementText = null
        autoFixUndoAvailable = false
        predictionStrip?.setBackgroundColor(Color.TRANSPARENT)
        prediction1?.setTextColor(ContextCompat.getColor(this, R.color.keyboard_key_text))
        prediction2?.setTextColor(ContextCompat.getColor(this, R.color.keyboard_key_text))
        prediction3?.setTextColor(ContextCompat.getColor(this, R.color.keyboard_key_text))
        prediction1?.text = ""
        prediction2?.text = ""
        prediction3?.text = ""
    }

    private fun requestNextWordPrediction() {
        if (!allowsCloudText()) return
        if (!isAiNextWordEnabled || isAutoFixRunning || isTerminalApp) return

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

        activeNextWordJob?.cancel()

        val ic = currentInputConnection ?: return
        val contextText = ic.getTextBeforeCursor(100, 0)?.toString() ?: return
        if (contextText.isBlank()) return

        isNextWordPredictionActive = true
        prediction1?.text = ""
        prediction2?.text = "···"
        prediction3?.text = ""

        DebugLog.d("NextWordPredictor: Requesting prediction for:", "\"${contextText.takeLast(30)}\"")

        activeNextWordJob = CoroutineScope(Dispatchers.Main).launch {
            val predictions = nextWordPredictor?.predict(contextText) ?: emptyList()
            if (predictions.isNotEmpty() && isNextWordPredictionActive) {
                DebugLog.d("NextWordPredictor: Got predictions:", predictions.toString())
                prediction2?.text = predictions.getOrNull(0) ?: ""
                prediction1?.text = predictions.getOrNull(1) ?: ""
                prediction3?.text = predictions.getOrNull(2) ?: ""

                prediction1?.setOnClickListener { applyNextWordPrediction(predictions.getOrNull(1)) }
                prediction2?.setOnClickListener { applyNextWordPrediction(predictions.getOrNull(0)) }
                prediction3?.setOnClickListener { applyNextWordPrediction(predictions.getOrNull(2)) }
            } else if (predictions.isEmpty() && isNextWordPredictionActive) {
                DebugLog.d("NextWordPredictor: No predictions returned")
                prediction1?.text = ""
                prediction2?.text = ""
                prediction3?.text = ""
                isNextWordPredictionActive = false
            }
        }
    }

    private fun applyNextWordPrediction(word: String?) {
        if (word.isNullOrBlank()) return

        val ic = currentInputConnection ?: return
        ic.commitText("$word ", 1)
        trackTypedText("$word ")

        isNextWordPredictionActive = false
        autocorrectManager?.onWordBoundary()
        requestNextWordPrediction()
    }

    private fun cancelNextWordPrediction() {
        activeNextWordJob?.cancel()
        activeNextWordJob = null
        if (isNextWordPredictionActive) {
            isNextWordPredictionActive = false
            prediction1?.text = ""
            prediction2?.text = ""
            prediction3?.text = ""
            prediction1?.setOnClickListener { applyPrediction(0) }
            prediction2?.setOnClickListener { applyPrediction(1) }
            prediction3?.setOnClickListener { applyPrediction(2) }
        }
    }

    private fun requestAiCompletion() {
        if (!allowsCloudText()) return
        if (!isAiWordCompletionEnabled || isAutoFixRunning || isTerminalApp || isNextWordPredictionActive) return

        if (nextWordPredictor == null) {
            nextWordPredictor = NextWordPredictor(this)
        }
        if (nextWordPredictor?.isAvailable() != true) return

        val partialWord = autocorrectManager?.getCurrentWord() ?: return
        if (partialWord.isEmpty()) return

        aiCompletionHandler.removeCallbacksAndMessages(null)
        aiCompletionHandler.postDelayed({
            if (!allowsCloudText()) return@postDelayed
            val currentWord = autocorrectManager?.getCurrentWord() ?: return@postDelayed
            if (currentWord.isEmpty()) return@postDelayed

            val ic = currentInputConnection ?: return@postDelayed
            val contextText = ic.getTextBeforeCursor(100, 0)?.toString() ?: return@postDelayed

            aiCompletionJob?.cancel()
            aiCompletionJob = CoroutineScope(Dispatchers.Main).launch {
                val completed = nextWordPredictor?.completeWord(contextText, currentWord)
                val stillTyping = autocorrectManager?.getCurrentWord() ?: ""
                if (completed != null && stillTyping.isNotEmpty() && completed.startsWith(stillTyping.lowercase())) {
                    aiCompletionWord = completed
                    prediction3?.text = completed
                    prediction3?.setOnClickListener { applyPrediction(2) }
                }
            }
        }, AI_COMPLETION_DEBOUNCE)
    }

    private fun cancelAiCompletion() {
        aiCompletionHandler.removeCallbacksAndMessages(null)
        aiCompletionJob?.cancel()
        aiCompletionJob = null
        aiCompletionWord = null
        prediction3?.text = ""
    }

    private fun improveText() {
        if (!allowsCloudText()) return
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

        val before = ic.getTextBeforeCursor(Int.MAX_VALUE, 0) ?: ""
        val after = ic.getTextAfterCursor(Int.MAX_VALUE, 0) ?: ""
        var fullText = "$before$after"
        var useBuffer = false

        DebugLog.d("DictationKeyboardService: Improve -", "before='${before.take(50)}' (${before.length}), after='${after.take(50)}' (${after.length})")

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
        val textToImprove = fullText

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
                        DebugLog.d("DictationKeyboardService: Using backspace method for replacement")

                        for (i in 0 until originalLength) {
                            sendDownUpKeyEvents(android.view.KeyEvent.KEYCODE_DEL)
                        }

                        ic.commitText(improvedText, 1)

                        internalTextBuffer.clear()
                        internalTextBuffer.append(improvedText)
                    } else {
                        ic.beginBatchEdit()
                        try {
                            val totalLength = before.length + after.length
                            ic.setSelection(0, totalLength)

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
                    DebugLog.e("DictationKeyboardService: Text improvement failed -", error)
                    Toast.makeText(this@DictationKeyboardService, "Failed: $error", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                DebugLog.e("DictationKeyboardService: Text improvement exception -", "${e.message}")
                Toast.makeText(this@DictationKeyboardService, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                isImproving = false

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

        sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
    }

    private fun switchToPreviousKeyboard() {
        Log.i(TAG, "DictationKeyboardService: Switching to previous keyboard")
        DebugLog.i("DictationKeyboardService: Switching to previous keyboard")

        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val token = window?.window?.attributes?.token

            DebugLog.d("DictationKeyboardService: Window token present: ${token != null}")

            if (token != null) {
                var switched = imm.switchToLastInputMethod(token)
                DebugLog.d("DictationKeyboardService: switchToLastInputMethod result: $switched")

                if (!switched) {
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

        val inputType = info?.inputType ?: 0
        val variation = inputType and android.text.InputType.TYPE_MASK_VARIATION
        val wasUrlField = isUrlField
        isUrlField = variation == android.text.InputType.TYPE_TEXT_VARIATION_URI ||
                     variation == android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                     variation == android.text.InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
        if (isUrlField != wasUrlField) {
            DebugLog.d("DictationKeyboardService: URL field detection: $isUrlField (variation=$variation)")
        }

        autocorrectManager?.onWordBoundary()

        if (!restarting) {
            internalTextBuffer.clear()
            useInternalBuffer = false
            DebugLog.d("DictationKeyboardService: Cleared internal text buffer (new session)")
        }

        val currentPackage = info?.packageName ?: ""
        val wasTerminalApp = isTerminalApp
        isTerminalApp = isTerminalPackage(currentPackage)
        if (isTerminalApp != wasTerminalApp) {
            transcriptionManager?.setTerminalMode(isTerminalApp)
            if (isTerminalApp) {
                lastFinalText = ""
                lastFinalTime = 0L
            }
        }

        pendingPasteText?.let { text ->
            Log.i(TAG, "Pasting pending text: ${text.take(50)}...")
            DebugLog.i("DictationKeyboardService: Pasting pending text (${text.length} chars)")
            pendingPasteText = null
            commitTextInternal(text)
        }

        updateMicButton()
        updateLanguageButton()
        updateKeyboardButtonState()
        updateEmojiButtonState()
        updateNumberRowVisibility()

        if (!restarting && currentKeyboardTab != 0) {
            currentKeyboardTab = 0
            updateKeyboardTabContent()
        }

        if (!isRecording && !isProcessing && !isMuteLocked && !isPushToTalkMode) {
            dictationStartHandler.postDelayed({
                if (!isRecording && !isProcessing && !isMuteLocked && !isPushToTalkMode) {
                    Log.i(TAG, "Auto-starting dictation on keyboard open")
                    DebugLog.i("DictationKeyboardService: Auto-starting dictation")

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
        if (!restarting) {
            cancelNextWordPrediction()
            cancelAiCompletion()
            autocorrectManager?.onWordBoundary()
            autocorrectManager?.clearUndoState()
            clearAutoFixState()
            internalTextBuffer.clear()
        }
        Log.d(TAG, "DictationKeyboardService onStartInput - inputType: ${attribute?.inputType}")
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)

        val expectedDelta = 1
        if (Math.abs(newSelStart - oldSelStart) > expectedDelta + 1) {
            autocorrectManager?.clearUndoState()
            autocorrectManager?.onWordBoundary()
        }

        if (newSelStart < oldSelStart && autocorrectManager?.getCurrentWord()?.isNotEmpty() == true) {
            autocorrectManager?.onWordBoundary()
        }
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        cancelNextWordPrediction()
        cancelAiCompletion()
        Log.d(TAG, "DictationKeyboardService onFinishInputView")
        DebugLog.d("DictationKeyboardService: Finished input view")

        stopDictation()
    }

    private fun stopDictation() {
        if (wasAutoMutedByPlayback) {
            wasAutoMutedByPlayback = false
            isMuteLocked = false
        }
        dictationStartHandler.removeCallbacksAndMessages(null)
        doubleTapHandler?.removeCallbacksAndMessages(null)
        pttStreamingRunnable?.let { pttStreamingHandler?.removeCallbacks(it) }
        if (isPttRecording) {
            transcriptionManager?.stopBuffering(discard = true)
        }
        transcriptionManager?.stopListening()
        isRecording = false
        isProcessing = false
        isStandby = false
        isPttRecording = false
        pttStreamingStarted = false
        micTouchStartedInPttMode = null
        lastMicTapTime = 0L
        updateMicButton()
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
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

    fun refreshTheme() {
        DebugLog.i("DictationKeyboardService: Refreshing theme from settings change")
        applyTheme()
    }

}
