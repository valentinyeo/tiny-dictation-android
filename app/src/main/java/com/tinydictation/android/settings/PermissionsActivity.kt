package com.tinydictation.android.settings

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.tinydictation.android.R
import com.tinydictation.android.service.DictationAccessibilityService

/**
 * Activity for managing app permissions including microphone, accessibility, and keyboard setup.
 */
class PermissionsActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "TinyDictation"
    }

    private lateinit var microphoneIcon: ImageView
    private lateinit var microphoneButton: MaterialButton
    private lateinit var accessibilityIcon: ImageView
    private lateinit var accessibilityButton: MaterialButton
    private lateinit var setupKeyboardButton: MaterialButton

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
        setContentView(R.layout.activity_permissions_settings)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        initViews()
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun initViews() {
        microphoneIcon = findViewById(R.id.iconMicrophone)
        microphoneButton = findViewById(R.id.btnEnableMicrophone)
        accessibilityIcon = findViewById(R.id.iconAccessibility)
        accessibilityButton = findViewById(R.id.btnEnableAccessibility)
        setupKeyboardButton = findViewById(R.id.btnSetupKeyboard)
    }

    private fun setupClickListeners() {
        microphoneButton.setOnClickListener {
            Log.d(TAG, "Microphone button clicked - requesting permission")
            requestMicrophonePermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        accessibilityButton.setOnClickListener {
            Log.d(TAG, "Accessibility button clicked - opening settings")
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Find 'Tiny Dictation' and enable it", Toast.LENGTH_LONG).show()
        }

        setupKeyboardButton.setOnClickListener {
            openKeyboardSettings()
        }
    }

    private fun updatePermissionStatus() {
        // Microphone permission
        val hasMicrophone = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        Log.d(TAG, "Permission status - Microphone: $hasMicrophone")
        updatePermissionCard(microphoneIcon, microphoneButton, hasMicrophone)

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

    private fun openKeyboardSettings() {
        try {
            val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Enable 'Tiny Dictation' in the keyboard list", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e(TAG, "Error opening keyboard settings", e)
            Toast.makeText(this, "Could not open keyboard settings", Toast.LENGTH_SHORT).show()
        }
    }
}
