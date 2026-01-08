package com.tinydictation.android

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
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

    private lateinit var preferenceManager: PreferenceManager
    private lateinit var transcriptionManager: TranscriptionManager

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
        updatePermissionStatus()
        if (!isGranted) {
            Toast.makeText(this, R.string.error_no_permission, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        preferenceManager = PreferenceManager(this)
        transcriptionManager = TranscriptionManager(this)

        initViews()
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
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
            requestMicrophonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        overlayButton.setOnClickListener {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }

        accessibilityButton.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(
                this,
                "Find 'Tiny Dictation' and enable it",
                Toast.LENGTH_LONG
            ).show()
        }

        startButton.setOnClickListener {
            if (allPermissionsGranted()) {
                if (!preferenceManager.hasApiKey()) {
                    Toast.makeText(this, R.string.error_no_api_key, Toast.LENGTH_SHORT).show()
                    startActivity(Intent(this, SettingsActivity::class.java))
                    return@setOnClickListener
                }

                if (BubbleService.isRunning()) {
                    stopDictation()
                } else {
                    startDictation()
                }
                updateStartButton()
            } else {
                Toast.makeText(this, R.string.error_no_permission, Toast.LENGTH_SHORT).show()
            }
        }

        settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
    }

    private fun updatePermissionStatus() {
        // Microphone permission
        val hasMicrophone = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        updatePermissionCard(microphoneIcon, microphoneButton, hasMicrophone)

        // Overlay permission
        val hasOverlay = Settings.canDrawOverlays(this)
        updatePermissionCard(overlayIcon, overlayButton, hasOverlay)

        // Accessibility service
        val hasAccessibility = isAccessibilityServiceEnabled()
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
        BubbleService.start(this)

        // Set up transcription callbacks
        val apiKey = preferenceManager.getApiKey() ?: return
        transcriptionManager.apiKey = apiKey

        transcriptionManager.onTranscript = { text, isFinal ->
            if (isFinal && text.isNotBlank()) {
                DictationAccessibilityService.inject(text + " ")
            }
        }

        transcriptionManager.onStateChanged = { state ->
            val bubbleState = when (state) {
                TranscriptionManager.TranscriptionState.IDLE -> BubbleService.BubbleState.IDLE
                TranscriptionManager.TranscriptionState.LISTENING -> BubbleService.BubbleState.LISTENING
                TranscriptionManager.TranscriptionState.TRANSCRIBING -> BubbleService.BubbleState.RECORDING
                TranscriptionManager.TranscriptionState.ERROR -> BubbleService.BubbleState.IDLE
            }
            BubbleService.updateBubbleState(this, bubbleState)
        }

        // Connect bubble callbacks to transcription manager
        BubbleService.onListeningStarted = {
            transcriptionManager.startListening()
        }

        BubbleService.onListeningStopped = {
            transcriptionManager.stopListening()
        }
    }

    private fun stopDictation() {
        transcriptionManager.stopListening()
        BubbleService.stop(this)
        BubbleService.onListeningStarted = null
        BubbleService.onListeningStopped = null
    }
}
