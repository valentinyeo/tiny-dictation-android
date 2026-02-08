package com.tinydictation.android.settings

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputLayout
import com.tinydictation.android.R
import com.tinydictation.android.util.DebugLog
import com.tinydictation.android.util.PreferenceManager

/**
 * Activity for configuring audio settings including microphone selection,
 * media playback, silence detection, and auto-punctuation.
 */
class AudioSettingsActivity : AppCompatActivity() {

    private lateinit var preferenceManager: PreferenceManager
    private lateinit var audioManager: AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var headphoneMicSwitch: MaterialSwitch
    private lateinit var tvMicSourceStatus: TextView
    private lateinit var pauseMediaSwitch: MaterialSwitch
    private lateinit var silenceAutoStopSwitch: MaterialSwitch
    private lateinit var tilSilenceDuration: TextInputLayout
    private lateinit var silenceDurationDropdown: AutoCompleteTextView
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
            mainHandler.post { updateMicSourceStatus() }
        }
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
            mainHandler.post { updateMicSourceStatus() }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_audio_settings)

        preferenceManager = PreferenceManager(this)
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setNavigationOnClickListener { finish() }

        initViews()
        loadSettings()
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, mainHandler)
        updateMicSourceStatus()
    }

    override fun onPause() {
        super.onPause()
        audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
    }

    private fun initViews() {
        headphoneMicSwitch = findViewById(R.id.switchHeadphoneMic)
        tvMicSourceStatus = findViewById(R.id.tvMicSourceStatus)
        pauseMediaSwitch = findViewById(R.id.switchPauseMedia)
        silenceAutoStopSwitch = findViewById(R.id.switchSilenceAutoStop)
        tilSilenceDuration = findViewById(R.id.tilSilenceDuration)
        silenceDurationDropdown = findViewById(R.id.actvSilenceDuration)
        setupSilenceDurationDropdown()
    }

    private fun loadSettings() {
        headphoneMicSwitch.isChecked = preferenceManager.getUseHeadphoneMic()
        pauseMediaSwitch.isChecked = preferenceManager.getPauseMediaDuringDictation()
        val currentMs = preferenceManager.getSilenceAutoStopMs()
        silenceAutoStopSwitch.isChecked = currentMs > 0
        tilSilenceDuration.visibility = if (currentMs > 0) android.view.View.VISIBLE else android.view.View.GONE
    }

    private fun setupClickListeners() {
        headphoneMicSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setUseHeadphoneMic(isChecked)
            updateMicSourceStatus()
            DebugLog.i("Headphone mic setting changed to: $isChecked")
        }

        pauseMediaSwitch.setOnCheckedChangeListener { _, isChecked ->
            preferenceManager.setPauseMediaDuringDictation(isChecked)
            DebugLog.i("Pause media during dictation setting changed to: $isChecked")
        }

        silenceAutoStopSwitch.setOnCheckedChangeListener { _, isChecked ->
            tilSilenceDuration.visibility = if (isChecked) android.view.View.VISIBLE else android.view.View.GONE
            if (isChecked) {
                val selectedText = silenceDurationDropdown.text.toString()
                val durations = listOf("2 seconds" to 2000, "3 seconds" to 3000, "5 seconds" to 5000)
                val selectedMs = durations.find { it.first == selectedText }?.second
                    ?: PreferenceManager.DEFAULT_SILENCE_AUTO_STOP_MS
                preferenceManager.setSilenceAutoStopMs(selectedMs)
                DebugLog.i("Silence auto-stop enabled: ${selectedMs}ms")
            } else {
                preferenceManager.setSilenceAutoStopMs(0)
                DebugLog.i("Silence auto-stop disabled")
            }
        }
    }

    private fun setupSilenceDurationDropdown() {
        val durations = listOf("2 seconds" to 2000, "3 seconds" to 3000, "5 seconds" to 5000)
        val displayNames = durations.map { it.first }
        val adapter = ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, displayNames)
        silenceDurationDropdown.setAdapter(adapter)

        val currentMs = preferenceManager.getSilenceAutoStopMs()
        if (currentMs > 0) {
            val currentIndex = durations.indexOfFirst { it.second == currentMs }
            if (currentIndex >= 0) {
                silenceDurationDropdown.setText(displayNames[currentIndex], false)
            } else {
                silenceDurationDropdown.setText(displayNames[1], false) // Default to 3 seconds
            }
        }

        silenceDurationDropdown.setOnItemClickListener { _, _, position, _ ->
            val selectedMs = durations[position].second
            preferenceManager.setSilenceAutoStopMs(selectedMs)
            DebugLog.i("Silence auto-stop duration changed to: ${selectedMs}ms")
        }
    }

    private fun updateMicSourceStatus() {
        val useHeadphoneMic = preferenceManager.getUseHeadphoneMic()
        val connectedHeadset = getConnectedHeadsetMic()

        val statusText = when {
            useHeadphoneMic && connectedHeadset != null -> "Will use: $connectedHeadset"
            useHeadphoneMic && connectedHeadset == null -> "No headset connected - will use built-in mic"
            else -> "Will use: Built-in microphone"
        }

        tvMicSourceStatus.text = statusText

        val color = if (useHeadphoneMic && connectedHeadset == null) {
            ContextCompat.getColor(this, R.color.bubble_recording)
        } else {
            ContextCompat.getColor(this, R.color.bubble_listening)
        }
        tvMicSourceStatus.setTextColor(color)

        DebugLog.d("Mic source status: $statusText")
    }

    private fun getConnectedHeadsetMic(): String? {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        for (device in devices) {
            val productName = device.productName?.toString()?.takeIf { it.isNotBlank() }
            when (device.type) {
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> return productName ?: "Wired headset mic"
                AudioDeviceInfo.TYPE_USB_HEADSET -> return productName ?: "USB headset mic"
                AudioDeviceInfo.TYPE_BLE_HEADSET -> return productName ?: "Bluetooth LE headset mic"
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> return productName ?: "Bluetooth headset mic"
            }
        }
        return null
    }
}
