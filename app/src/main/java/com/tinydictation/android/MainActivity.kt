package com.tinydictation.android

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.tinydictation.android.service.BubbleService
import com.tinydictation.android.service.DictationAccessibilityService
import com.tinydictation.android.transcription.TranscriptionManager
import com.tinydictation.android.util.PreferenceManager

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TinyDictation"
    }

    private lateinit var preferenceManager: PreferenceManager
    private var transcriptionManager: TranscriptionManager? = null

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
    private lateinit var settingsButton: MaterialButton

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
        settingsButton = findViewById(R.id.btnSettings)
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

    private fun startDictation() {
        Log.i(TAG, "Starting dictation service")
        BubbleService.start(this)

        // Set up transcription manager with callbacks
        val apiKey = preferenceManager.getApiKey() ?: return

        transcriptionManager = TranscriptionManager(
            apiKey = apiKey,
            onTranscript = { text, isFinal ->
                Log.d(TAG, "Transcript received - isFinal: $isFinal, text: $text")
                if (isFinal && text.isNotBlank()) {
                    DictationAccessibilityService.inject(text + " ")
                }
            },
            onError = { error ->
                Log.e(TAG, "Transcription error: $error")
                runOnUiThread {
                    Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
                }
            },
            onStateChange = { state ->
                Log.d(TAG, "Transcription state changed: $state")
                val bubbleState = when (state) {
                    TranscriptionManager.TranscriptionState.IDLE -> BubbleService.BubbleState.IDLE
                    TranscriptionManager.TranscriptionState.LISTENING -> BubbleService.BubbleState.LISTENING
                    TranscriptionManager.TranscriptionState.TRANSCRIBING -> BubbleService.BubbleState.RECORDING
                    TranscriptionManager.TranscriptionState.ERROR -> BubbleService.BubbleState.IDLE
                }
                BubbleService.updateBubbleState(this, bubbleState)
            }
        )

        // Connect bubble callbacks to transcription manager
        BubbleService.onListeningStarted = {
            Log.d(TAG, "Bubble listening started callback")
            transcriptionManager?.startListening()
        }

        BubbleService.onListeningStopped = {
            Log.d(TAG, "Bubble listening stopped callback")
            transcriptionManager?.stopListening()
        }
    }

    private fun stopDictation() {
        Log.i(TAG, "Stopping dictation service")
        transcriptionManager?.stopListening()
        transcriptionManager = null
        BubbleService.stop(this)
        BubbleService.onListeningStarted = null
        BubbleService.onListeningStopped = null
    }
}
