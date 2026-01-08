package com.tinydictation.android.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
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

        bubbleView?.setOnTouchListener { view, event ->
            handleTouch(view, event)
        }

        try {
            windowManager.addView(bubbleView, bubbleParams)
            updateBubbleUI()
            Log.i(TAG, "Bubble created successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create bubble", e)
            e.printStackTrace()
        }
    }

    /**
     * Handles touch events for dragging and tapping the bubble.
     */
    private fun handleTouch(view: View, event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = bubbleParams?.x ?: 0
                initialY = bubbleParams?.y ?: 0
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                isDragging = false
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                val deltaX = (event.rawX - initialTouchX).toInt()
                val deltaY = (event.rawY - initialTouchY).toInt()

                if (!isDragging && (Math.abs(deltaX) > dragThreshold || Math.abs(deltaY) > dragThreshold)) {
                    isDragging = true
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
            val statusText = view.findViewById<TextView>(R.id.tvStatus)

            when (currentState) {
                BubbleState.IDLE -> {
                    iconView?.setImageResource(R.drawable.ic_mic)
                    statusText?.visibility = View.GONE
                    bubbleContainer?.setBackgroundResource(R.drawable.bubble_background)
                }
                BubbleState.LISTENING -> {
                    iconView?.setImageResource(R.drawable.ic_mic)
                    iconView?.setColorFilter(ContextCompat.getColor(this, R.color.bubble_listening))
                    statusText?.setText(R.string.bubble_listening)
                    statusText?.visibility = View.VISIBLE
                    bubbleContainer?.setBackgroundResource(R.drawable.bubble_background_listening)
                }
                BubbleState.RECORDING -> {
                    iconView?.setImageResource(R.drawable.ic_mic_active)
                    iconView?.clearColorFilter()
                    statusText?.setText(R.string.bubble_recording)
                    statusText?.visibility = View.VISIBLE
                    bubbleContainer?.setBackgroundResource(R.drawable.bubble_background_active)
                }
                BubbleState.PROCESSING -> {
                    iconView?.setImageResource(R.drawable.ic_mic)
                    iconView?.setColorFilter(ContextCompat.getColor(this, R.color.bubble_processing))
                    statusText?.setText(R.string.bubble_processing)
                    statusText?.visibility = View.VISIBLE
                    bubbleContainer?.setBackgroundResource(R.drawable.bubble_background_processing)
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

        @Volatile
        private var instance: BubbleService? = null

        // Callbacks for listening state changes
        var onListeningStarted: (() -> Unit)? = null
        var onListeningStopped: (() -> Unit)? = null

        /**
         * Checks if the service is currently running.
         */
        fun isRunning(): Boolean = instance != null

        /**
         * Gets the current service instance, if running.
         */
        fun getInstance(): BubbleService? = instance

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
