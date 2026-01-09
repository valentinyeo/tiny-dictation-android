package com.tinydictation.android.service

import android.annotation.SuppressLint
import android.app.KeyguardManager
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.tinydictation.android.HistoryActivity
import com.tinydictation.android.MainActivity
import com.tinydictation.android.SettingsActivity
import com.tinydictation.android.R
import com.tinydictation.android.TinyDictationApp
import com.tinydictation.android.history.TranscriptionHistoryManager
import com.tinydictation.android.transcription.TranscriptionManager
import com.tinydictation.android.util.DebugLog
import com.tinydictation.android.util.PreferenceManager
import kotlinx.coroutines.launch

/**
 * Foreground service that displays a draggable floating bubble overlay.
 * The bubble allows users to start/stop dictation from anywhere on the device.
 */
class BubbleService : LifecycleService() {

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

    // Close target overlay
    private var closeTargetView: View? = null
    private var closeTargetParams: WindowManager.LayoutParams? = null
    private var isCloseTargetVisible = false
    private var isBubbleOverTarget = false

    private var currentState: BubbleState = BubbleState.IDLE
    private var currentLanguage: String = "en"  // "en" or "de"

    // Text buffer for paste functionality
    private val textBuffer = StringBuilder()

    // Clipboard state: false = copy mode, true = paste mode (ready to paste)
    private var isPasteMode = false

    // Compact/translucent mode
    private var isCompactMode = false

    // Touch handling variables
    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0f
    private var initialTouchY: Float = 0f
    private var isDragging: Boolean = false
    private var lastClickTime: Long = 0

    // Drag threshold to distinguish between tap and drag
    private val dragThreshold = 10

    // Double-tap detection
    private var lastTapTime: Long = 0
    private val doubleTapThreshold = 300L // milliseconds

    // Long-press detection
    private var longPressHandler: android.os.Handler? = null
    private var longPressRunnable: Runnable? = null
    private val longPressThreshold = 500L // milliseconds
    private var isLongPressTriggered = false

    // Preference listener for automatic visibility updates
    private var prefsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null

    // TranscriptionManager - owned by this service to survive activity lifecycle
    private var transcriptionManager: TranscriptionManager? = null
    private lateinit var preferenceManager: PreferenceManager

    // Screen lock state tracking
    private var wasListeningBeforeLock = false
    private var screenLockReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "BubbleService onCreate")
        DebugLog.i("BubbleService: Service created")
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        preferenceManager = PreferenceManager(this)

        // Register preference listener for bubble visibility changes
        val prefs = getSharedPreferences("tiny_dictation_prefs", Context.MODE_PRIVATE)
        prefsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "show_language_bubble" || key == "show_paste_bubble" || key == "show_note_bubble") {
                Log.i(TAG, "Preference changed: $key")
                DebugLog.i("BubbleService: Preference changed: $key")
                updateBubbleVisibility()
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(prefsListener)

        // Initialize TranscriptionManager
        initializeTranscriptionManager()

        // Register screen lock broadcast receiver
        registerScreenLockReceiver()
    }

    /**
     * Initializes the TranscriptionManager with callbacks.
     */
    private fun initializeTranscriptionManager() {
        val apiKey = preferenceManager.getApiKey()
        if (apiKey.isNullOrBlank()) {
            Log.w(TAG, "No API key configured, TranscriptionManager not initialized")
            DebugLog.w("BubbleService: No API key, skipping TranscriptionManager init")
            return
        }

        Log.i(TAG, "Initializing TranscriptionManager")
        DebugLog.i("BubbleService: Initializing TranscriptionManager")

        transcriptionManager = TranscriptionManager(
            apiKey = apiKey,
            onTranscript = { text, isFinal ->
                Log.d(TAG, "Transcript received - isFinal: $isFinal, text: $text")
                if (isFinal && text.isNotBlank()) {
                    // Inject via accessibility service
                    DictationAccessibilityService.inject(text + " ")
                    // Also add to paste buffer for manual paste option
                    appendToBuffer(text)
                    // Save ALL transcriptions to history
                    TranscriptionHistoryManager.getInstance(this).save(text, currentLanguage)
                }
            },
            onError = { error ->
                Log.e(TAG, "Transcription error: $error")
                DebugLog.e("BubbleService: Transcription error - $error")
                android.os.Handler(mainLooper).post {
                    Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
                }
            },
            onStateChange = { state ->
                Log.d(TAG, "Transcription state changed: $state")
                val bubbleState = when (state) {
                    TranscriptionManager.TranscriptionState.IDLE -> BubbleState.IDLE
                    TranscriptionManager.TranscriptionState.LISTENING -> BubbleState.LISTENING
                    TranscriptionManager.TranscriptionState.TRANSCRIBING -> BubbleState.RECORDING
                    TranscriptionManager.TranscriptionState.ERROR -> BubbleState.IDLE
                }
                updateState(bubbleState)
            },
            context = this
        )

        // Set up bubble tap callbacks
        onListeningStarted = {
            Log.d(TAG, "Bubble listening started callback")
            DebugLog.d("BubbleService: Starting transcription from bubble tap")
            try {
                transcriptionManager?.startListening()
            } catch (e: SecurityException) {
                Log.e(TAG, "Security exception starting listening", e)
                DebugLog.e("BubbleService: Security exception - ${e.message}")
                android.os.Handler(mainLooper).post {
                    Toast.makeText(this, "Microphone permission denied", Toast.LENGTH_SHORT).show()
                }
                updateState(BubbleState.IDLE)
            } catch (e: Exception) {
                Log.e(TAG, "Error starting listening", e)
                DebugLog.e("BubbleService: Error starting listening - ${e.message}")
                android.os.Handler(mainLooper).post {
                    Toast.makeText(this, "Failed to start recording: ${e.message}", Toast.LENGTH_SHORT).show()
                }
                updateState(BubbleState.IDLE)
            }
        }

        onListeningStopped = {
            Log.d(TAG, "Bubble listening stopped callback")
            DebugLog.d("BubbleService: Stopping transcription from bubble tap")
            try {
                transcriptionManager?.stopListening()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping listening", e)
                DebugLog.e("BubbleService: Error stopping listening - ${e.message}")
            }
        }

        onLanguageChanged = { language ->
            Log.d(TAG, "Language changed to: $language")
            DebugLog.d("BubbleService: Language changed to $language")
            transcriptionManager?.setLanguage(language)
        }
    }

    /**
     * Registers a broadcast receiver to handle screen lock/unlock events.
     * This ensures dictation can resume after returning from lock screen.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerScreenLockReceiver() {
        screenLockReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        Log.i(TAG, "Screen turned OFF")
                        DebugLog.i("BubbleService: Screen OFF detected")
                        handleScreenOff()
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        Log.i(TAG, "User returned from lock screen")
                        DebugLog.i("BubbleService: User present (unlocked)")
                        handleUserPresent()
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        Log.d(TAG, "Screen turned ON (may still be locked)")
                        DebugLog.d("BubbleService: Screen ON detected")
                        // Note: Screen on doesn't mean unlocked, wait for USER_PRESENT
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenLockReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenLockReceiver, filter)
        }
        Log.i(TAG, "Screen lock receiver registered")
        DebugLog.i("BubbleService: Screen lock receiver registered")
    }

    /**
     * Handles screen off event - saves listening state and stops recording.
     * Audio recording often fails when screen is locked due to system restrictions.
     */
    private fun handleScreenOff() {
        wasListeningBeforeLock = (currentState == BubbleState.LISTENING || currentState == BubbleState.RECORDING)
        Log.i(TAG, "Screen off - wasListeningBeforeLock: $wasListeningBeforeLock, currentState: $currentState")
        DebugLog.i("BubbleService: Screen off, wasListening=$wasListeningBeforeLock")

        if (wasListeningBeforeLock) {
            // Stop listening gracefully - audio recording will likely fail anyway
            Log.i(TAG, "Pausing dictation due to screen lock")
            DebugLog.i("BubbleService: Pausing dictation for screen lock")
            try {
                transcriptionManager?.stopListening()
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping listening on screen off", e)
                DebugLog.e("BubbleService: Error stopping on screen off - ${e.message}")
            }
            // Keep the UI in listening state so user knows it will resume
            // Don't call updateState(BubbleState.IDLE) here
        }
    }

    /**
     * Handles user returning from lock screen - resumes dictation if it was active.
     */
    private fun handleUserPresent() {
        Log.i(TAG, "User present - wasListeningBeforeLock: $wasListeningBeforeLock")
        DebugLog.i("BubbleService: User present, wasListening=$wasListeningBeforeLock")

        if (wasListeningBeforeLock) {
            Log.i(TAG, "Resuming dictation after lock screen")
            DebugLog.i("BubbleService: Resuming dictation after unlock")

            // Small delay to ensure system is ready
            android.os.Handler(mainLooper).postDelayed({
                if (instance != null && wasListeningBeforeLock) {
                    try {
                        // Re-initialize TranscriptionManager if needed
                        if (transcriptionManager == null) {
                            Log.i(TAG, "Re-initializing TranscriptionManager after unlock")
                            DebugLog.i("BubbleService: Re-initializing TranscriptionManager")
                            initializeTranscriptionManager()
                        }

                        // Restart listening
                        Log.i(TAG, "Restarting listening after unlock")
                        DebugLog.i("BubbleService: Restarting listening")
                        updateState(BubbleState.LISTENING)
                        transcriptionManager?.startListening()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error resuming dictation after unlock", e)
                        DebugLog.e("BubbleService: Error resuming after unlock - ${e.message}")
                        updateState(BubbleState.IDLE)
                        android.os.Handler(mainLooper).post {
                            Toast.makeText(this, "Failed to resume dictation: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                    wasListeningBeforeLock = false
                }
            }, 500) // 500ms delay for system to stabilize
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "BubbleService received STOP action")
                DebugLog.i("BubbleService: Received STOP action")
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UPDATE_STATE -> {
                val stateOrdinal = intent.getIntExtra(EXTRA_STATE, BubbleState.IDLE.ordinal)
                val newState = BubbleState.entries.getOrElse(stateOrdinal) { BubbleState.IDLE }
                Log.d(TAG, "BubbleService updating state to: $newState")
                DebugLog.d("BubbleService: Updating state to $newState")
                updateState(newState)
                return START_STICKY
            }
        }

        Log.i(TAG, "BubbleService starting foreground")
        DebugLog.i("BubbleService: Starting foreground service")
        startForeground(NOTIFICATION_ID, createNotification())
        createBubble()

        // Auto-start listening if TranscriptionManager is initialized
        if (transcriptionManager != null && currentState == BubbleState.IDLE) {
            Log.i(TAG, "Auto-starting listening on service start")
            DebugLog.i("BubbleService: Auto-starting listening")
            android.os.Handler(mainLooper).postDelayed({
                if (instance != null && currentState == BubbleState.IDLE) {
                    clearBuffer()
                    updateState(BubbleState.LISTENING)
                    onListeningStarted?.invoke()
                }
            }, 300)
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "BubbleService onDestroy")
        DebugLog.i("BubbleService: Service destroyed")

        // Unregister screen lock receiver
        screenLockReceiver?.let { receiver ->
            try {
                unregisterReceiver(receiver)
                Log.i(TAG, "Screen lock receiver unregistered")
            } catch (e: Exception) {
                Log.e(TAG, "Error unregistering screen lock receiver", e)
            }
        }
        screenLockReceiver = null

        // Stop and release TranscriptionManager
        try {
            transcriptionManager?.release()
            Log.i(TAG, "TranscriptionManager released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing TranscriptionManager", e)
        }
        transcriptionManager = null

        // Clear callbacks
        onListeningStarted = null
        onListeningStopped = null
        onLanguageChanged = null

        // Unregister preference listener
        prefsListener?.let { listener ->
            getSharedPreferences("tiny_dictation_prefs", Context.MODE_PRIVATE)
                .unregisterOnSharedPreferenceChangeListener(listener)
        }
        prefsListener = null
        removeBubble()
        instance = null
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    /**
     * Creates and displays the floating bubble overlay.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun createBubble() {
        if (bubbleView != null) {
            Log.d(TAG, "Bubble already exists, skipping creation")
            return
        }

        Log.i(TAG, "Creating bubble overlay")
        val inflater = LayoutInflater.from(this)
        bubbleView = inflater.inflate(R.layout.view_bubble, null)

        // Get screen width to position bubble on the right side
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels

        bubbleParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - 150  // Position on right side
            y = 300  // A bit below the status bar
        }

        // Main bubble touch handler (for mic)
        bubbleView?.findViewById<FrameLayout>(R.id.bubbleContainer)?.setOnTouchListener { view, event ->
            handleTouch(view, event)
        }

        // Language toggle click handler
        bubbleView?.findViewById<TextView>(R.id.tvLanguageToggle)?.setOnClickListener {
            toggleLanguage()
        }

        // Paste button click handler - toggles between copy and paste
        bubbleView?.findViewById<TextView>(R.id.tvPasteButton)?.apply {
            setOnClickListener {
                handlePasteButtonClick()
            }
            setOnLongClickListener {
                openHistoryActivity()
                true
            }
        }

        // Note button click handler - sends transcription to configured note app
        bubbleView?.findViewById<FrameLayout>(R.id.noteButtonContainer)?.setOnClickListener {
            handleNoteButtonClick()
        }

        try {
            windowManager.addView(bubbleView, bubbleParams)
            // Set default opacity to ~65% for less intrusive appearance
            bubbleView?.alpha = DEFAULT_OPACITY
            updateBubbleUI()
            updateLanguageUI()
            updateBubbleVisibility()
            Log.i(TAG, "Bubble created successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create bubble", e)
            e.printStackTrace()
        }
    }

    /**
     * Toggles the language between English and German.
     */
    private fun toggleLanguage() {
        currentLanguage = if (currentLanguage == "en") "de" else "en"
        Log.i(TAG, "Language toggled to: $currentLanguage")
        DebugLog.i("BubbleService: Language changed to $currentLanguage")
        updateLanguageUI()
        onLanguageChanged?.invoke(currentLanguage)
    }

    /**
     * Updates the language toggle UI.
     */
    private fun updateLanguageUI() {
        bubbleView?.findViewById<TextView>(R.id.tvLanguageToggle)?.text =
            currentLanguage.uppercase()
    }

    /**
     * Handles paste button click - toggles between copy and paste modes.
     * First tap: copies buffer to clipboard, icon changes to paste mode
     * Second tap: triggers paste via accessibility service
     */
    private fun handlePasteButtonClick() {
        if (isPasteMode) {
            // Second tap: perform paste action
            performPaste()
        } else {
            // First tap: copy buffer to clipboard
            copyBufferToClipboard()
        }
    }

    /**
     * Handles note button click - sends transcription to configured note app.
     */
    private fun handleNoteButtonClick() {
        val text = textBuffer.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, "Nothing to send", Toast.LENGTH_SHORT).show()
            return
        }

        val noteAppPackage = preferenceManager.getNoteAppPackage()
        if (noteAppPackage == null) {
            Toast.makeText(this, "Please configure a note app in Settings", Toast.LENGTH_SHORT).show()
            return
        }

        Log.i(TAG, "Sending ${text.length} chars to note app: $noteAppPackage")
        DebugLog.i("BubbleService: Sending to note app: $noteAppPackage")

        try {
            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, text)
                setPackage(noteAppPackage)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            // Check if the configured app can handle this intent
            val resolveInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.resolveActivity(sendIntent, android.content.pm.PackageManager.ResolveInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.resolveActivity(sendIntent, 0)
            }

            if (resolveInfo != null) {
                startActivity(sendIntent)
                Toast.makeText(this, "Sent to note app!", Toast.LENGTH_SHORT).show()
                vibrateForFeedback()
            } else {
                // App might be uninstalled, try showing a chooser instead
                Log.w(TAG, "Configured note app not found, showing chooser")
                DebugLog.w("BubbleService: Note app $noteAppPackage not found")
                val chooserIntent = Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                }, "Send transcription to...").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(chooserIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error sending to note app", e)
            DebugLog.e("BubbleService: Error sending to note app - ${e.message}")
            Toast.makeText(this, "Failed to open note app", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Copies the text buffer to clipboard and switches to paste mode.
     * Also saves the transcription to history.
     */
    private fun copyBufferToClipboard() {
        val text = textBuffer.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, "Nothing to copy", Toast.LENGTH_SHORT).show()
            return
        }

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Dictation", text)
        clipboard.setPrimaryClip(clip)

        Log.i(TAG, "Copied ${text.length} chars to clipboard")
        DebugLog.i("BubbleService: Copied to clipboard: ${text.take(50)}...")
        Toast.makeText(this, "✓ Copied! Ready to paste", Toast.LENGTH_SHORT).show()

        // Vibrate to confirm copy
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator?.vibrate(android.os.VibrationEffect.createOneShot(100, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(100)
            }
        } catch (e: Exception) {
            // Ignore vibration errors
        }

        // Switch to paste mode (don't clear buffer yet)
        isPasteMode = true
        updatePasteButtonIcon()
    }

    /**
     * Performs paste action via accessibility service.
     */
    private fun performPaste() {
        val success = DictationAccessibilityService.paste()
        if (success) {
            Log.i(TAG, "Paste action successful")
            DebugLog.i("BubbleService: Paste successful")
            Toast.makeText(this, "Pasted!", Toast.LENGTH_SHORT).show()
            // Clear buffer and reset to copy mode after successful paste
            textBuffer.clear()
            isPasteMode = false
            updatePasteButtonIcon()
        } else {
            Log.w(TAG, "Paste action failed")
            DebugLog.w("BubbleService: Paste failed - accessibility service may not be running")
            Toast.makeText(this, "Paste failed - focus a text field first", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Updates the paste button icon based on current mode.
     */
    private fun updatePasteButtonIcon() {
        val pasteButton = bubbleView?.findViewById<TextView>(R.id.tvPasteButton)
        if (isPasteMode) {
            pasteButton?.text = "✓"  // Checkmark = ready to paste
            pasteButton?.setTextColor(android.graphics.Color.parseColor("#4CAF50"))  // Green
        } else {
            pasteButton?.text = "📋"  // Clipboard = tap to copy
            pasteButton?.setTextColor(android.graphics.Color.WHITE)
        }
    }

    /**
     * Appends transcribed text to the buffer.
     * Resets paste mode since we have new content.
     */
    fun appendToBuffer(text: String) {
        if (text.isNotBlank()) {
            if (textBuffer.isNotEmpty()) {
                textBuffer.append(" ")
            }
            textBuffer.append(text)
            Log.d(TAG, "Buffer now: ${textBuffer.length} chars")
            // Reset to copy mode when new text arrives
            if (isPasteMode) {
                isPasteMode = false
                updatePasteButtonIcon()
            }
        }
    }

    /**
     * Clears the text buffer and resets paste mode.
     */
    fun clearBuffer() {
        textBuffer.clear()
        isPasteMode = false
        updatePasteButtonIcon()
    }

    /**
     * Updates visibility of optional bubbles based on settings.
     * Called both at creation and when settings change.
     */
    fun updateBubbleVisibility() {
        // Don't update visibility if in compact mode (buttons are intentionally hidden)
        if (isCompactMode) {
            Log.d(TAG, "updateBubbleVisibility: skipping - compact mode")
            return
        }
        val prefs = getSharedPreferences("tiny_dictation_prefs", Context.MODE_PRIVATE)
        val showLanguage = prefs.getBoolean("show_language_bubble", true)
        val showPaste = prefs.getBoolean("show_paste_bubble", true)
        val showNote = prefs.getBoolean("show_note_bubble", false)

        Log.i(TAG, "updateBubbleVisibility: showLanguage=$showLanguage, showPaste=$showPaste, showNote=$showNote")
        DebugLog.i("BubbleService: Updating visibility - lang=$showLanguage, paste=$showPaste, note=$showNote")

        val langView = bubbleView?.findViewById<TextView>(R.id.tvLanguageToggle)
        val pasteView = bubbleView?.findViewById<TextView>(R.id.tvPasteButton)
        val noteView = bubbleView?.findViewById<FrameLayout>(R.id.noteButtonContainer)

        langView?.visibility = if (showLanguage) View.VISIBLE else View.GONE
        pasteView?.visibility = if (showPaste) View.VISIBLE else View.GONE
        noteView?.visibility = if (showNote) View.VISIBLE else View.GONE

        Log.i(TAG, "updateBubbleVisibility: langView=${langView != null}, pasteView=${pasteView != null}, noteView=${noteView != null}")
    }

    /**
     * Handles touch events for dragging, tapping, and long-pressing the bubble.
     */
    private fun handleTouch(view: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = bubbleParams?.x ?: 0
                initialY = bubbleParams?.y ?: 0
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging = false
                isLongPressTriggered = false

                // Start long-press detection
                longPressHandler = android.os.Handler(mainLooper)
                longPressRunnable = Runnable {
                    if (!isDragging) {
                        isLongPressTriggered = true
                        openSettingsActivity()
                    }
                }
                longPressHandler?.postDelayed(longPressRunnable!!, longPressThreshold)

                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = (event.rawX - initialTouchX).toInt()
                val deltaY = (event.rawY - initialTouchY).toInt()

                if (!isDragging && (Math.abs(deltaX) > dragThreshold || Math.abs(deltaY) > dragThreshold)) {
                    isDragging = true
                    // Cancel long-press if dragging
                    longPressRunnable?.let { longPressHandler?.removeCallbacks(it) }
                    // Show close target when dragging starts
                    showCloseTarget()
                }

                if (isDragging) {
                    bubbleParams?.let { params ->
                        params.x = initialX + deltaX
                        params.y = initialY + deltaY
                        try {
                            windowManager.updateViewLayout(bubbleView, params)
                            // Check if bubble is over close target
                            val isOverTarget = checkBubbleOverTarget(params.x, params.y)
                            // Log position periodically during drag for debugging
                            if (System.currentTimeMillis() % 500 < 50) {
                                Log.d(TAG, "Dragging bubble at (${params.x}, ${params.y}), overTarget=$isOverTarget")
                            }
                            updateCloseTargetState(isOverTarget)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                return true
            }

            MotionEvent.ACTION_UP -> {
                // Cancel any pending long-press
                longPressRunnable?.let { longPressHandler?.removeCallbacks(it) }

                Log.d(TAG, "ACTION_UP: isDragging=$isDragging, isBubbleOverTarget=$isBubbleOverTarget")
                DebugLog.d("BubbleService: ACTION_UP - dragging=$isDragging, overTarget=$isBubbleOverTarget")

                // Check if released over close target
                if (isDragging && isBubbleOverTarget) {
                    Log.i(TAG, "Bubble released on close target - closing service")
                    DebugLog.i("BubbleService: Drag-to-close triggered")
                    hideCloseTarget()
                    stopSelf()
                    return true
                }

                // Hide close target after drag ends
                hideCloseTarget()

                // Don't process tap if long-press was triggered
                if (isLongPressTriggered) {
                    return true
                }

                if (!isDragging) {
                    // This was a tap, not a drag
                    val currentTime = System.currentTimeMillis()

                    // Check for double-tap
                    if (currentTime - lastTapTime < doubleTapThreshold) {
                        Log.i(TAG, "Double-tap detected - closing bubble service")
                        DebugLog.i("BubbleService: Double-tap detected, closing service")
                        stopSelf()
                        return true
                    }

                    // Single tap with debounce
                    if (currentTime - lastClickTime > CLICK_DEBOUNCE_MS) {
                        lastClickTime = currentTime
                        lastTapTime = currentTime
                        onBubbleTapped()
                    }
                }
                return true
            }
        }
        return false
    }

    /**
     * Opens the settings activity from the bubble service.
     */
    private fun openSettingsActivity() {
        Log.i(TAG, "Long-press detected - opening SettingsActivity")
        DebugLog.i("BubbleService: Long-press detected, opening SettingsActivity")

        val intent = Intent(this, SettingsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)

        // Vibrate briefly for feedback
        vibrateForFeedback()
    }

    /**
     * Opens the history activity from the bubble service.
     */
    private fun openHistoryActivity() {
        Log.i(TAG, "Long-press on clipboard - opening HistoryActivity")
        DebugLog.i("BubbleService: Opening HistoryActivity")

        val intent = Intent(this, HistoryActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        startActivity(intent)

        // Vibrate briefly for feedback
        vibrateForFeedback()
    }

    /**
     * Provides haptic feedback for user interactions.
     */
    private fun vibrateForFeedback() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                vibrator?.vibrate(android.os.VibrationEffect.createOneShot(50, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(50)
            }
        } catch (e: Exception) {
            // Ignore vibration errors
        }
    }

    /**
     * Toggles between normal and compact/translucent mode.
     */
    private fun toggleCompactMode() {
        isCompactMode = !isCompactMode
        Log.i(TAG, "Compact mode: $isCompactMode")
        DebugLog.i("BubbleService: Compact mode = $isCompactMode")

        bubbleView?.let { view ->
            val bubbleContainer = view.findViewById<FrameLayout>(R.id.bubbleContainer)
            val languageToggle = view.findViewById<TextView>(R.id.tvLanguageToggle)
            val pasteButton = view.findViewById<TextView>(R.id.tvPasteButton)
            val noteButton = view.findViewById<FrameLayout>(R.id.noteButtonContainer)

            if (isCompactMode) {
                // Compact mode: smaller, more translucent, hide extra buttons
                view.alpha = COMPACT_OPACITY
                bubbleContainer?.layoutParams?.let { params ->
                    params.width = (24 * resources.displayMetrics.density).toInt()
                    params.height = (24 * resources.displayMetrics.density).toInt()
                    bubbleContainer.layoutParams = params
                }
                bubbleContainer?.findViewById<ImageView>(R.id.ivMicrophone)?.let { icon ->
                    icon.layoutParams.width = (12 * resources.displayMetrics.density).toInt()
                    icon.layoutParams.height = (12 * resources.displayMetrics.density).toInt()
                    icon.requestLayout()
                }
                languageToggle?.visibility = View.GONE
                pasteButton?.visibility = View.GONE
                noteButton?.visibility = View.GONE
            } else {
                // Normal mode: restore size and default opacity
                view.alpha = DEFAULT_OPACITY
                bubbleContainer?.layoutParams?.let { params ->
                    params.width = (40 * resources.displayMetrics.density).toInt()
                    params.height = (40 * resources.displayMetrics.density).toInt()
                    bubbleContainer.layoutParams = params
                }
                bubbleContainer?.findViewById<ImageView>(R.id.ivMicrophone)?.let { icon ->
                    icon.layoutParams.width = (20 * resources.displayMetrics.density).toInt()
                    icon.layoutParams.height = (20 * resources.displayMetrics.density).toInt()
                    icon.requestLayout()
                }
                // Restore bubble visibility based on settings
                updateBubbleVisibility()
            }
        }

        // Vibrate briefly for feedback
        vibrateForFeedback()
    }

    /**
     * Called when the bubble is tapped (not dragged).
     * Toggles the listening state.
     */
    private fun onBubbleTapped() {
        Log.d(TAG, "Bubble tapped - current state: $currentState")
        DebugLog.d("BubbleService: Bubble tapped in state $currentState")
        lifecycleScope.launch {
            when (currentState) {
                BubbleState.IDLE -> {
                    Log.i(TAG, "Starting listening from bubble tap")
                    DebugLog.i("BubbleService: Starting listening")
                    // Clear buffer for fresh dictation session
                    clearBuffer()
                    // Start listening - communicate with TranscriptionManager
                    updateState(BubbleState.LISTENING)
                    onListeningStarted?.invoke()
                }
                BubbleState.LISTENING, BubbleState.RECORDING -> {
                    Log.i(TAG, "Stopping listening from bubble tap")
                    DebugLog.i("BubbleService: Stopping listening")
                    // Stop listening
                    updateState(BubbleState.IDLE)
                    onListeningStopped?.invoke()
                }
                BubbleState.PROCESSING -> {
                    Log.d(TAG, "Bubble tap ignored - currently processing")
                    DebugLog.d("BubbleService: Tap ignored (processing)")
                    // Don't allow interaction while processing
                }
            }
        }
    }

    /**
     * Updates the bubble state and refreshes the UI.
     */
    fun updateState(newState: BubbleState) {
        Log.d(TAG, "Bubble state changed: $currentState -> $newState")
        currentState = newState
        updateBubbleUI()
    }

    /**
     * Updates the bubble UI based on the current state.
     */
    private fun updateBubbleUI() {
        bubbleView?.let { view ->
            val bubbleContainer = view.findViewById<FrameLayout>(R.id.bubbleContainer)
            val iconView = view.findViewById<ImageView>(R.id.ivMicrophone)

            when (currentState) {
                BubbleState.IDLE -> {
                    iconView?.setImageResource(R.drawable.ic_mic)
                    iconView?.clearColorFilter()
                    bubbleContainer?.setBackgroundResource(R.drawable.bubble_background)
                }
                BubbleState.LISTENING -> {
                    iconView?.setImageResource(R.drawable.ic_mic)
                    iconView?.clearColorFilter()
                    bubbleContainer?.setBackgroundResource(R.drawable.bubble_background)
                }
                BubbleState.RECORDING -> {
                    iconView?.setImageResource(R.drawable.ic_mic)
                    iconView?.setColorFilter(ContextCompat.getColor(this, R.color.bubble_recording))
                    bubbleContainer?.setBackgroundResource(R.drawable.bubble_background)
                }
                BubbleState.PROCESSING -> {
                    iconView?.setImageResource(R.drawable.ic_mic)
                    iconView?.setColorFilter(ContextCompat.getColor(this, R.color.bubble_processing))
                    bubbleContainer?.setBackgroundResource(R.drawable.bubble_background)
                }
            }
        }
    }

    /**
     * Removes the bubble from the window.
     */
    private fun removeBubble() {
        hideCloseTarget()
        bubbleView?.let { view ->
            try {
                Log.i(TAG, "Removing bubble overlay")
                windowManager.removeView(view)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove bubble", e)
                e.printStackTrace()
            }
        }
        bubbleView = null
    }

    /**
     * Shows the close target overlay at the bottom center of the screen.
     */
    private fun showCloseTarget() {
        if (isCloseTargetVisible) return

        Log.d(TAG, "Showing close target")
        val inflater = LayoutInflater.from(this)
        closeTargetView = inflater.inflate(R.layout.view_close_target, null)

        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels
        val targetSize = (80 * displayMetrics.density).toInt()  // Larger target (80dp instead of 64dp)

        // Position close target in center-bottom area, accounting for navigation bar
        val navBarHeight = (48 * displayMetrics.density).toInt()
        val bottomMargin = (120 * displayMetrics.density).toInt()  // Closer to bottom for easier reach

        closeTargetParams = WindowManager.LayoutParams(
            targetSize,
            targetSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (screenWidth - targetSize) / 2  // Center horizontally
            y = screenHeight - targetSize - bottomMargin - navBarHeight  // Bottom with safe margin
        }

        Log.d(TAG, "Close target position: x=${closeTargetParams?.x}, y=${closeTargetParams?.y}, screenHeight=$screenHeight, targetSize=$targetSize")
        DebugLog.d("BubbleService: Close target at x=${closeTargetParams?.x}, y=${closeTargetParams?.y}")

        try {
            windowManager.addView(closeTargetView, closeTargetParams)
            closeTargetView?.alpha = 0.8f
            isCloseTargetVisible = true
            Log.i(TAG, "Close target shown successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show close target", e)
            DebugLog.e("BubbleService: Failed to show close target: ${e.message}")
        }
    }

    /**
     * Hides the close target overlay.
     */
    private fun hideCloseTarget() {
        if (!isCloseTargetVisible) return

        Log.d(TAG, "Hiding close target")
        closeTargetView?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to hide close target", e)
            }
        }
        closeTargetView = null
        isCloseTargetVisible = false
        isBubbleOverTarget = false
    }

    /**
     * Checks if the bubble is currently over the close target.
     * Returns true if the bubble center is within the target area.
     * Uses circular hit detection with a generous radius for easy targeting.
     */
    private fun checkBubbleOverTarget(bubbleX: Int, bubbleY: Int): Boolean {
        // Use actual close target position from its params (not recalculated)
        val targetParams = closeTargetParams ?: return false

        val displayMetrics = resources.displayMetrics
        val targetSize = (80 * displayMetrics.density).toInt()  // Must match showCloseTarget
        val bubbleSize = (40 * displayMetrics.density).toInt()

        // Get target center from actual params
        val targetCenterX = targetParams.x + targetSize / 2
        val targetCenterY = targetParams.y + targetSize / 2

        // Calculate bubble center position
        val bubbleCenterX = bubbleX + bubbleSize / 2
        val bubbleCenterY = bubbleY + bubbleSize / 2

        // Use generous circular hit area (100dp radius for easy targeting)
        val hitRadius = (100 * displayMetrics.density).toInt()
        val distanceX = bubbleCenterX - targetCenterX
        val distanceY = bubbleCenterY - targetCenterY
        val distanceSquared = distanceX * distanceX + distanceY * distanceY
        val hitRadiusSquared = hitRadius * hitRadius

        val isOver = distanceSquared < hitRadiusSquared

        // Log for debugging
        val distance = Math.sqrt(distanceSquared.toDouble()).toInt()
        Log.d(TAG, "checkBubbleOverTarget: bubble($bubbleCenterX,$bubbleCenterY) target($targetCenterX,$targetCenterY) distance=$distance hitRadius=$hitRadius isOver=$isOver")

        if (isOver) {
            DebugLog.d("BubbleService: Bubble over close target (distance=$distance)")
        }

        return isOver
    }

    /**
     * Updates the close target visual state based on whether the bubble is over it.
     */
    private fun updateCloseTargetState(isOver: Boolean) {
        if (isBubbleOverTarget == isOver) return

        isBubbleOverTarget = isOver
        Log.d(TAG, "updateCloseTargetState: isOver=$isOver")

        closeTargetView?.let { view ->
            val container = view.findViewById<FrameLayout>(R.id.closeTargetContainer)
            if (isOver) {
                // Highlight: scale up and change background
                container?.setBackgroundResource(R.drawable.close_target_background_active)
                view.scaleX = 1.3f
                view.scaleY = 1.3f
                view.alpha = 1.0f
                // Vibrate to indicate hover over close target
                vibrateForFeedback()
                Log.i(TAG, "Bubble entered close target area - vibrating")
            } else {
                // Normal state
                container?.setBackgroundResource(R.drawable.close_target_background)
                view.scaleX = 1.0f
                view.scaleY = 1.0f
                view.alpha = 0.8f
            }
        }
    }

    /**
     * Creates the foreground service notification.
     */
    private fun createNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, BubbleService::class.java).apply {
            action = ACTION_STOP
        }

        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, TinyDictationApp.NOTIFICATION_CHANNEL_ID)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_mic, getString(R.string.stop_dictation), stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /**
     * Represents the different states of the dictation bubble.
     */
    enum class BubbleState {
        IDLE,       // Not listening, waiting for tap
        LISTENING,  // Actively listening for speech (VAD monitoring)
        RECORDING,  // Speech detected, recording audio
        PROCESSING  // Processing/transcribing audio
    }

    companion object {
        private const val TAG = "TinyDictation"
        private const val NOTIFICATION_ID = 1001
        private const val ACTION_STOP = "com.tinydictation.android.action.STOP"
        private const val ACTION_UPDATE_STATE = "com.tinydictation.android.action.UPDATE_STATE"
        private const val EXTRA_STATE = "extra_state"
        private const val CLICK_DEBOUNCE_MS = 300L
        private const val DEFAULT_OPACITY = 0.65f  // Less intrusive default
        private const val COMPACT_OPACITY = 0.4f   // Even more translucent in compact mode

        @Volatile
        private var instance: BubbleService? = null

        // Callbacks for listening state changes
        var onListeningStarted: (() -> Unit)? = null
        var onListeningStopped: (() -> Unit)? = null
        var onLanguageChanged: ((String) -> Unit)? = null

        /**
         * Checks if the service is currently running.
         */
        fun isRunning(): Boolean = instance != null

        /**
         * Gets the current language setting.
         */
        fun getCurrentLanguage(): String = instance?.currentLanguage ?: "en"

        /**
         * Appends text to the paste buffer.
         */
        fun addToBuffer(text: String) {
            instance?.appendToBuffer(text)
        }

        /**
         * Clears the paste buffer.
         */
        fun clearBuffer() {
            instance?.clearBuffer()
        }

        /**
         * Gets the current service instance, if running.
         */
        fun getInstance(): BubbleService? = instance

        /**
         * Refreshes bubble visibility based on current settings.
         * Call this when settings are changed in SettingsActivity.
         */
        fun refreshVisibility() {
            instance?.updateBubbleVisibility()
        }

        /**
         * Starts the bubble service.
         */
        fun start(context: Context) {
            Log.i(TAG, "Starting BubbleService")
            val intent = Intent(context, BubbleService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Stops the bubble service.
         */
        fun stop(context: Context) {
            Log.i(TAG, "Stopping BubbleService")
            val intent = Intent(context, BubbleService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        /**
         * Updates the bubble state from external components.
         */
        fun updateBubbleState(context: Context, state: BubbleState) {
            instance?.updateState(state) ?: run {
                // Service not running, send intent to start with state
                val intent = Intent(context, BubbleService::class.java).apply {
                    action = ACTION_UPDATE_STATE
                    putExtra(EXTRA_STATE, state.ordinal)
                }
                context.startService(intent)
            }
        }
    }
}
