package com.tinydictation.android.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import com.tinydictation.android.MainActivity
import com.tinydictation.android.R
import com.tinydictation.android.TinyDictationApp
import com.tinydictation.android.util.DebugLog
import kotlinx.coroutines.launch

/**
 * Foreground service that displays a draggable floating bubble overlay.
 * The bubble allows users to start/stop dictation from anywhere on the device.
 */
class BubbleService : LifecycleService() {

    private lateinit var windowManager: WindowManager
    private var bubbleView: View? = null
    private var bubbleParams: WindowManager.LayoutParams? = null

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

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "BubbleService onCreate")
        DebugLog.i("BubbleService: Service created")
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
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

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "BubbleService onDestroy")
        DebugLog.i("BubbleService: Service destroyed")
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
        bubbleView?.findViewById<TextView>(R.id.tvPasteButton)?.setOnClickListener {
            handlePasteButtonClick()
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
     * Copies the text buffer to clipboard and switches to paste mode.
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
        Toast.makeText(this, "Copied! Tap again to paste", Toast.LENGTH_SHORT).show()

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
        bubbleView?.findViewById<TextView>(R.id.tvPasteButton)?.text =
            if (isPasteMode) "📥" else "📋"  // 📥 = paste, 📋 = clipboard/copy
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
        if (isCompactMode) return
        val prefs = getSharedPreferences("tiny_dictation_prefs", Context.MODE_PRIVATE)
        val showLanguage = prefs.getBoolean("show_language_bubble", true)
        val showPaste = prefs.getBoolean("show_paste_bubble", true)

        bubbleView?.findViewById<TextView>(R.id.tvLanguageToggle)?.visibility =
            if (showLanguage) View.VISIBLE else View.GONE
        bubbleView?.findViewById<TextView>(R.id.tvPasteButton)?.visibility =
            if (showPaste) View.VISIBLE else View.GONE
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
                        toggleCompactMode()
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
                }

                if (isDragging) {
                    bubbleParams?.let { params ->
                        params.x = initialX + deltaX
                        params.y = initialY + deltaY
                        try {
                            windowManager.updateViewLayout(bubbleView, params)
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
