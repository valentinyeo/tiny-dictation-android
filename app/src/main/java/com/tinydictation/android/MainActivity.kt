package com.tinydictation.android

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.tinydictation.android.service.BubbleService
import com.tinydictation.android.service.DictationAccessibilityService
import com.tinydictation.android.util.PreferenceManager

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TinyDictation"
    }

    private lateinit var preferenceManager: PreferenceManager

    // Permission cards
    private lateinit var microphoneCard: MaterialCardView
    private lateinit var overlayCard: MaterialCardView
    private lateinit var accessibilityCard: MaterialCardView

    // Status icons
    private lateinit var microphoneIcon: ImageView
    private lateinit var overlayIcon: ImageView
    private lateinit var accessibilityIcon: ImageView

    // Buttons
    private lateinit var microphoneButton: MaterialButton
    private lateinit var overlayButton: MaterialButton
    private lateinit var accessibilityButton: MaterialButton
    private lateinit var startButton: MaterialButton
    private lateinit var historyButton: MaterialButton
    private lateinit var settingsButton: MaterialButton
    private lateinit var versionInfo: TextView

    private val requestMicrophonePermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Log.i(TAG, "Microphone permission result: $isGranted")
        updatePermissionStatus()
        if (!isGranted) {
            Log.e(TAG, "Microphone permission denied")
            Toast.makeText(this, R.string.error_no_permission, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "MainActivity onCreate")
        setContentView(R.layout.activity_main)

        preferenceManager = PreferenceManager(this)

        initViews()
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "MainActivity onResume")
        updatePermissionStatus()
        updateStartButton()

        // Auto-start dictation if all permissions granted and API key configured
        autoStartDictationIfReady()
    }

    private fun autoStartDictationIfReady() {
        // Only auto-start if not already running
        if (BubbleService.isRunning()) {
            return
        }

        // Check all conditions
        if (!allPermissionsGranted()) {
            Log.d(TAG, "Auto-start skipped: missing permissions")
            return
        }

        if (!preferenceManager.hasApiKey()) {
            Log.d(TAG, "Auto-start skipped: no API key")
            return
        }

        Log.i(TAG, "Auto-starting dictation")
        startDictation()
        updateStartButton()
    }

    private fun initViews() {
        microphoneCard = findViewById(R.id.cardMicrophone)
        overlayCard = findViewById(R.id.cardOverlay)
        accessibilityCard = findViewById(R.id.cardAccessibility)

        microphoneIcon = findViewById(R.id.iconMicrophone)
        overlayIcon = findViewById(R.id.iconOverlay)
        accessibilityIcon = findViewById(R.id.iconAccessibility)

        microphoneButton = findViewById(R.id.btnEnableMicrophone)
        overlayButton = findViewById(R.id.btnEnableOverlay)
        accessibilityButton = findViewById(R.id.btnEnableAccessibility)

        startButton = findViewById(R.id.btnStartDictation)
        historyButton = findViewById(R.id.btnHistory)
        settingsButton = findViewById(R.id.btnSettings)
        versionInfo = findViewById(R.id.tvVersionInfo)

        // Display version info
        displayVersionInfo()
    }

    private fun displayVersionInfo() {
        try {
            val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(packageName, 0)
            }
            versionInfo.text = "v${packageInfo.versionName}"
        } catch (e: PackageManager.NameNotFoundException) {
            versionInfo.text = ""
        }
    }

    private fun setupClickListeners() {
        microphoneButton.setOnClickListener {
            Log.d(TAG, "Microphone button clicked - requesting permission")
            requestMicrophonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        overlayButton.setOnClickListener {
            Log.d(TAG, "Overlay button clicked - opening settings")
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        accessibilityButton.setOnClickListener {
            Log.d(TAG, "Accessibility button clicked - opening settings")
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(
                this,
                "Find 'Tiny Dictation' and enable it",
                Toast.LENGTH_LONG
            ).show()
        }

        startButton.setOnClickListener {
            Log.d(TAG, "Start/Stop button clicked")
            if (allPermissionsGranted()) {
                if (!preferenceManager.hasApiKey()) {
                    Log.e(TAG, "No API key configured")
                    Toast.makeText(this, R.string.error_no_api_key, Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this, SettingsActivity::class.java))
                    return@setOnClickListener
                }

                if (BubbleService.isRunning()) {
                    Log.i(TAG, "Stopping dictation")
                    stopDictation()
                } else {
                    Log.i(TAG, "Starting dictation")
                    startDictation()
                }
                updateStartButton()
            } else {
                Log.e(TAG, "Cannot start - missing permissions")
                Toast.makeText(this, R.string.error_no_permission, Toast.LENGTH_SHORT).show()
            }
        }

        historyButton.setOnClickListener {
            Log.d(TAG, "History button clicked")
            startActivity(Intent(this, HistoryActivity::class.java))
        }

        settingsButton.setOnClickListener {
            Log.d(TAG, "Settings button clicked")
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun updatePermissionStatus() {
        // Microphone permission
        val hasMicrophone = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "Permission status - Microphone: $hasMicrophone")

        updatePermissionCard(microphoneIcon, microphoneButton, hasMicrophone)

        // Overlay permission
        val hasOverlay = Settings.canDrawOverlays(this)
        Log.d(TAG, "Permission status - Overlay: $hasOverlay")
        updatePermissionCard(overlayIcon, overlayButton, hasOverlay)

        // Accessibility service
        val hasAccessibility = isAccessibilityServiceEnabled()
        Log.d(TAG, "Permission status - Accessibility: $hasAccessibility")
        updatePermissionCard(accessibilityIcon, accessibilityButton, hasAccessibility)
    }

    private fun updatePermissionCard(icon: ImageView, button: MaterialButton, isEnabled: Boolean) {
        if (isEnabled) {
            icon.setImageResource(R.drawable.ic_check)
            icon.setColorFilter(ContextCompat.getColor(this, R.color.bubble_listening))
            button.visibility = View.GONE
        } else {
            icon.setImageResource(R.drawable.ic_warning)
            icon.setColorFilter(ContextCompat.getColor(this, R.color.bubble_recording))
            button.visibility = View.VISIBLE
        }
    }

    private fun updateStartButton() {
        if (BubbleService.isRunning()) {
            startButton.text = getString(R.string.stop_dictation)
            startButton.setBackgroundColor(ContextCompat.getColor(this, R.color.bubble_recording))
        } else {
            startButton.text = getString(R.string.start_dictation)
            startButton.setBackgroundColor(ContextCompat.getColor(this, R.color.primary))
        }

        startButton.isEnabled = allPermissionsGranted()
    }

    private fun allPermissionsGranted(): Boolean {
        val hasMicrophone = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        val hasOverlay = Settings.canDrawOverlays(this)
        val hasAccessibility = isAccessibilityServiceEnabled()

        return hasMicrophone && hasOverlay && hasAccessibility
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledServices = am.getEnabledAccessibilityServiceList(
            AccessibilityServiceInfo.FEEDBACK_GENERIC
        )
        return enabledServices.any {
            it.resolveInfo.serviceInfo.packageName == packageName &&
            it.resolveInfo.serviceInfo.name == DictationAccessibilityService::class.java.name
        }
    }

    /**
     * Starts dictation by launching the BubbleService.
     * TranscriptionManager is now owned by BubbleService to survive activity lifecycle
     * and properly handle lock screen events.
     */
    private fun startDictation() {
        Log.i(TAG, "Starting dictation service")

        // Verify microphone permission before starting
        if (ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "Microphone permission not granted")
            Toast.makeText(this, R.string.error_no_permission, Toast.LENGTH_SHORT).show()
            return
        }

        // Verify API key before starting
        if (!preferenceManager.hasApiKey()) {
            Log.e(TAG, "API key is null")
            Toast.makeText(this, R.string.error_no_api_key, Toast.LENGTH_SHORT).show()
            return
        }

        // Start the bubble service - it now owns the TranscriptionManager
        BubbleService.start(this)
        Log.i(TAG, "BubbleService started - TranscriptionManager is now managed by service")
    }

    /**
     * Stops dictation by stopping the BubbleService.
     */
    private fun stopDictation() {
        Log.i(TAG, "Stopping dictation service")
        BubbleService.stop(this)
    }
}
