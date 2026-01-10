package com.tinydictation.android.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.tinydictation.android.R
import com.tinydictation.android.SettingsActivity
import com.tinydictation.android.TinyDictationApp
import com.tinydictation.android.util.DebugLog
import com.tinydictation.android.util.PreferenceManager

/**
 * Service that displays a keyboard companion bar - a horizontal toolbar that appears
 * just above the user's existing keyboard (Gboard, SwiftKey, etc.).
 *
 * This provides dictation controls without replacing the user's preferred keyboard.
 *
 * Key features:
 * - Detects keyboard visibility via AccessibilityService (TYPE_INPUT_METHOD windows)
 * - Positions itself at the top edge of the keyboard (using estimated height)
 * - Contains Mic, Language, and Expand buttons
 * - Hides automatically when keyboard is dismissed
 *
 * Note: Keyboard detection uses DictationAccessibilityService to monitor for
 * TYPE_INPUT_METHOD windows, which is more reliable than overlay-based detection
 * since TYPE_APPLICATION_OVERLAY windows don't participate in window insets.
 */
class KeyboardCompanionService : LifecycleService() {

    private lateinit var windowManager: WindowManager
    private lateinit var preferenceManager: PreferenceManager
    private var companionView: View? = null
    private var companionParams: WindowManager.LayoutParams? = null

    // UI elements
    private var micButton: FrameLayout? = null
    private var micIcon: ImageView? = null
    private var languageButton: FrameLayout? = null
    private var languageText: TextView? = null
    private var expandButton: FrameLayout? = null

    // Keyboard visibility tracking
    private var isKeyboardVisible = false

    // Default keyboard height estimate (280dp is typical keyboard height)
    private val defaultKeyboardHeight: Int
        get() = (280 * resources.displayMetrics.density).toInt()

    // Handler for UI updates
    private val mainHandler = Handler(Looper.getMainLooper())

    // Screen lock state tracking
    private var screenLockReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "KeyboardCompanionService onCreate")
        DebugLog.i("KeyboardCompanionService: Service created")
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        preferenceManager = PreferenceManager(this)

        // Register screen lock broadcast receiver
        registerScreenLockReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> {
                Log.i(TAG, "KeyboardCompanionService received STOP action")
                DebugLog.i("KeyboardCompanionService: Received STOP action")
                stopSelf()
                return START_NOT_STICKY
            }
        }

        Log.i(TAG, "KeyboardCompanionService starting foreground")
        DebugLog.i("KeyboardCompanionService: Starting foreground service")
        startForeground(NOTIFICATION_ID, createNotification())

        // Register for keyboard visibility updates from AccessibilityService
        setupKeyboardVisibilityListener()

        return START_STICKY
    }

    /**
     * Sets up keyboard visibility listener using AccessibilityService.
     * This is more reliable than overlay-based detection since overlays
     * don't participate in window insets.
     */
    private fun setupKeyboardVisibilityListener() {
        Log.i(TAG, "Setting up keyboard visibility listener via AccessibilityService")
        DebugLog.i("KeyboardCompanionService: Setting up keyboard listener")

        DictationAccessibilityService.onKeyboardVisibilityChanged = { isVisible ->
            Log.i(TAG, "Keyboard visibility callback: $isVisible")
            DebugLog.i("KeyboardCompanionService: Keyboard visible=$isVisible")

            mainHandler.post {
                if (isVisible != isKeyboardVisible) {
                    isKeyboardVisible = isVisible
                    if (isVisible) {
                        showCompanionBar(defaultKeyboardHeight)
                    } else {
                        hideCompanionBar()
                    }
                }
            }
        }

        // Also check current state if accessibility service is already running
        if (DictationAccessibilityService.isServiceRunning()) {
            Log.i(TAG, "AccessibilityService already running - checking initial state")
            DebugLog.i("KeyboardCompanionService: AccessibilityService active")
        } else {
            Log.w(TAG, "AccessibilityService not running - keyboard detection may not work")
            DebugLog.w("KeyboardCompanionService: AccessibilityService NOT running!")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i(TAG, "KeyboardCompanionService onDestroy")
        DebugLog.i("KeyboardCompanionService: Service destroyed")

        // Unregister keyboard visibility listener
        DictationAccessibilityService.onKeyboardVisibilityChanged = null

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

        removeCompanionBar()
        instance = null
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    /**
     * Registers a broadcast receiver to handle screen lock/unlock events.
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerScreenLockReceiver() {
        screenLockReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        Log.i(TAG, "Screen turned OFF - hiding companion bar")
                        DebugLog.i("KeyboardCompanionService: Screen OFF")
                        hideCompanionBar()
                    }
                    Intent.ACTION_USER_PRESENT -> {
                        Log.i(TAG, "User returned from lock screen")
                        DebugLog.i("KeyboardCompanionService: User present")
                        // Keyboard detector will handle showing the bar if keyboard is visible
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(screenLockReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(screenLockReceiver, filter)
        }
        Log.i(TAG, "Screen lock receiver registered")
    }

    /**
     * Shows the companion bar positioned just above the keyboard.
     * Only shows if companion mode is selected.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun showCompanionBar(keyboardHeight: Int) {
        Log.i(TAG, "showCompanionBar called with keyboardHeight=$keyboardHeight")
        DebugLog.i("KeyboardCompanionService: showCompanionBar called, kbdHeight=$keyboardHeight")

        // Check if companion mode is the selected dictation mode
        val dictationMode = preferenceManager.getDictationMode()
        Log.i(TAG, "Current dictation mode: $dictationMode")
        if (dictationMode != PreferenceManager.MODE_COMPANION) {
            Log.i(TAG, "Not showing companion bar - current mode is $dictationMode, not companion mode")
            DebugLog.i("KeyboardCompanionService: Not showing bar - mode is $dictationMode")
            return
        }

        if (companionView != null) {
            // Already showing, just update position
            Log.d(TAG, "Companion view already exists, updating position")
            updateCompanionBarPosition(keyboardHeight)
            return
        }

        Log.i(TAG, "Creating companion bar above keyboard (height=$keyboardHeight)")
        DebugLog.i("KeyboardCompanionService: Creating companion bar")

        val inflater = LayoutInflater.from(this)
        companionView = inflater.inflate(R.layout.view_keyboard_companion, null)

        // Get display metrics
        val displayMetrics = resources.displayMetrics
        val screenHeight = displayMetrics.heightPixels
        val barHeight = (40 * displayMetrics.density).toInt()

        // Position the bar just above the keyboard
        companionParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            barHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.START
            y = keyboardHeight  // Position above the keyboard
        }

        // Get UI elements
        micButton = companionView?.findViewById(R.id.btnCompanionMic)
        micIcon = companionView?.findViewById(R.id.ivCompanionMic)
        languageButton = companionView?.findViewById(R.id.btnCompanionLanguage)
        languageText = companionView?.findViewById(R.id.tvCompanionLanguage)
        expandButton = companionView?.findViewById(R.id.btnCompanionExpand)

        // Setup button handlers
        setupButtonHandlers()

        // Apply theme
        applyTheme()

        // Update initial states
        updateMicButton()
        updateLanguageButton()

        try {
            windowManager.addView(companionView, companionParams)
            Log.i(TAG, "Companion bar shown successfully")
            DebugLog.i("KeyboardCompanionService: Companion bar visible")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show companion bar", e)
            DebugLog.e("KeyboardCompanionService: Failed to show bar - ${e.message}")
        }
    }

    /**
     * Updates the companion bar position when keyboard height changes.
     */
    private fun updateCompanionBarPosition(keyboardHeight: Int) {
        companionParams?.let { params ->
            params.y = keyboardHeight
            companionView?.let { view ->
                try {
                    windowManager.updateViewLayout(view, params)
                    Log.d(TAG, "Companion bar position updated: y=$keyboardHeight")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update companion bar position", e)
                }
            }
        }
    }

    /**
     * Hides the companion bar.
     */
    private fun hideCompanionBar() {
        companionView?.let { view ->
            try {
                windowManager.removeView(view)
                Log.i(TAG, "Companion bar hidden")
                DebugLog.d("KeyboardCompanionService: Companion bar hidden")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to hide companion bar", e)
            }
        }
        companionView = null
        micButton = null
        micIcon = null
        languageButton = null
        languageText = null
        expandButton = null
    }

    /**
     * Removes the companion bar completely.
     */
    private fun removeCompanionBar() {
        hideCompanionBar()
    }

    /**
     * Sets up button click handlers.
     */
    private fun setupButtonHandlers() {
        // Mic button - toggle dictation
        micButton?.setOnClickListener {
            toggleDictation()
        }

        // Language button - toggle language
        languageButton?.setOnClickListener {
            toggleLanguage()
        }

        // Expand button - switch to full Tiny Dictation keyboard
        expandButton?.setOnClickListener {
            switchToTinyDictationKeyboard()
        }
    }

    /**
     * Applies the selected theme colors to the companion bar.
     */
    private fun applyTheme() {
        val themeSetting = preferenceManager.getKeyboardTheme()

        // Determine if we should use dark theme
        val useDarkTheme = when (themeSetting) {
            PreferenceManager.THEME_LIGHT -> false
            PreferenceManager.THEME_DARK -> true
            else -> { // THEME_SYSTEM
                val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                nightModeFlags == Configuration.UI_MODE_NIGHT_YES
            }
        }

        // Define colors based on theme
        val backgroundColor: Int
        val buttonColor: Int
        val iconColor: Int

        if (useDarkTheme) {
            backgroundColor = ContextCompat.getColor(this, R.color.surface_variant)
            buttonColor = ContextCompat.getColor(this, R.color.surface)
            iconColor = ContextCompat.getColor(this, R.color.on_surface)
        } else {
            backgroundColor = Color.parseColor("#F5F5F5")
            buttonColor = Color.parseColor("#E0E0E0")
            iconColor = Color.parseColor("#1C1B1F")
        }

        // Apply background color to container
        companionView?.findViewById<LinearLayout>(R.id.companionBarContainer)?.setBackgroundColor(backgroundColor)

        // Apply button and icon colors
        applyButtonTheme(micButton, buttonColor, iconColor)
        applyButtonTheme(languageButton, buttonColor, iconColor)
        applyButtonTheme(expandButton, buttonColor, iconColor)

        // Apply text color to language text
        languageText?.setTextColor(iconColor)

        DebugLog.d("KeyboardCompanionService: Applied theme - dark=$useDarkTheme")
    }

    /**
     * Applies theme colors to a button.
     */
    private fun applyButtonTheme(button: FrameLayout?, buttonColor: Int, iconColor: Int) {
        button ?: return

        // Create new background drawable with themed color
        val backgroundDrawable = GradientDrawable().apply {
            setColor(buttonColor)
            cornerRadius = 8 * resources.displayMetrics.density
        }

        // Wrap in ripple for touch feedback
        val rippleDrawable = RippleDrawable(
            android.content.res.ColorStateList.valueOf(ContextCompat.getColor(this, R.color.primary)),
            backgroundDrawable,
            null
        )
        button.background = rippleDrawable

        // Apply icon tint to ImageView children
        for (i in 0 until button.childCount) {
            val child = button.getChildAt(i)
            if (child is ImageView) {
                child.setColorFilter(iconColor)
            }
        }
    }

    /**
     * Toggles dictation on/off.
     */
    private fun toggleDictation() {
        Log.i(TAG, "KeyboardCompanionService: Toggle dictation")
        DebugLog.i("KeyboardCompanionService: Toggling dictation")

        BubbleService.getInstance()?.let { service ->
            service.toggleRecording()
            updateMicButton()
        } ?: run {
            Log.w(TAG, "BubbleService not running - starting it")
            DebugLog.w("KeyboardCompanionService: Starting BubbleService")
            BubbleService.start(this)
            // Update after a short delay
            mainHandler.postDelayed({ updateMicButton() }, 500)
        }

        // Provide haptic feedback
        vibrateForFeedback()
    }

    /**
     * Toggles between English and German.
     */
    private fun toggleLanguage() {
        Log.i(TAG, "KeyboardCompanionService: Toggle language")
        DebugLog.i("KeyboardCompanionService: Toggling language")

        val currentLang = preferenceManager.getLanguage()
        val newLang = if (currentLang == "en") "de" else "en"
        preferenceManager.setLanguage(newLang)

        // Notify BubbleService of language change
        BubbleService.onLanguageChanged?.invoke(newLang)

        updateLanguageButton()
        Log.i(TAG, "Language changed to: $newLang")

        // Provide haptic feedback
        vibrateForFeedback()
    }

    /**
     * Switches to Tiny Dictation keyboard.
     */
    private fun switchToTinyDictationKeyboard() {
        Log.i(TAG, "KeyboardCompanionService: Switching to Tiny Dictation keyboard")
        DebugLog.i("KeyboardCompanionService: Switching to keyboard")

        vibrateForFeedback()

        // Try direct switch if we have permission
        val imeId = "$packageName/.service.DictationKeyboardService"

        val switched = try {
            android.provider.Settings.Secure.putString(
                contentResolver,
                android.provider.Settings.Secure.DEFAULT_INPUT_METHOD,
                imeId
            )
        } catch (e: SecurityException) {
            Log.d(TAG, "WRITE_SECURE_SETTINGS not granted, showing picker")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error switching keyboard", e)
            false
        }

        if (!switched) {
            // Fall back to keyboard picker
            try {
                val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            } catch (e: Exception) {
                Log.e(TAG, "Error showing keyboard picker", e)
                Toast.makeText(this, "Could not open keyboard picker", Toast.LENGTH_SHORT).show()
            }
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
            getThemedIconColor()
        }
        micIcon?.setColorFilter(color)
    }

    /**
     * Updates the language button text.
     */
    private fun updateLanguageButton() {
        val lang = preferenceManager.getLanguage().uppercase()
        languageText?.text = lang
    }

    /**
     * Gets the appropriate icon color based on theme.
     */
    private fun getThemedIconColor(): Int {
        val themeSetting = preferenceManager.getKeyboardTheme()

        val useDarkTheme = when (themeSetting) {
            PreferenceManager.THEME_LIGHT -> false
            PreferenceManager.THEME_DARK -> true
            else -> {
                val nightModeFlags = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
                nightModeFlags == Configuration.UI_MODE_NIGHT_YES
            }
        }

        return if (useDarkTheme) {
            ContextCompat.getColor(this, R.color.on_surface)
        } else {
            Color.parseColor("#1C1B1F")
        }
    }

    /**
     * Provides haptic feedback.
     */
    private fun vibrateForFeedback() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(android.os.VibrationEffect.createOneShot(30, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(30)
            }
        } catch (e: Exception) {
            // Ignore vibration errors
        }
    }

    /**
     * Creates the foreground service notification.
     */
    private fun createNotification(): Notification {
        val intent = Intent(this, SettingsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = Intent(this, KeyboardCompanionService::class.java).apply {
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
            .setContentText("Keyboard companion mode active")
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(pendingIntent)
            .addAction(R.drawable.ic_mic, getString(R.string.stop_dictation), stopPendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /**
     * Refreshes the mic button state. Called from external components.
     */
    fun refreshStatus() {
        mainHandler.post {
            updateMicButton()
            updateLanguageButton()
        }
    }

    companion object {
        private const val TAG = "TinyDictation"
        private const val NOTIFICATION_ID = 1002
        private const val ACTION_STOP = "com.tinydictation.android.action.STOP_COMPANION"

        @Volatile
        private var instance: KeyboardCompanionService? = null

        /**
         * Checks if the service is currently running.
         */
        fun isRunning(): Boolean = instance != null

        /**
         * Gets the current service instance, if running.
         */
        fun getInstance(): KeyboardCompanionService? = instance

        /**
         * Starts the keyboard companion service.
         */
        fun start(context: Context) {
            Log.i(TAG, "Starting KeyboardCompanionService")
            val intent = Intent(context, KeyboardCompanionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * Stops the keyboard companion service.
         */
        fun stop(context: Context) {
            Log.i(TAG, "Stopping KeyboardCompanionService")
            val intent = Intent(context, KeyboardCompanionService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
