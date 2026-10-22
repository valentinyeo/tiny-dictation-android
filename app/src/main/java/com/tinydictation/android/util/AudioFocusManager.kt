package com.tinydictation.android.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

class AudioFocusManager(private val context: Context) {

    companion object {
        private const val TAG = "TinyDictation"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    interface AudioFocusListener {
        fun onAudioFocusGained()
        fun onAudioFocusLost()
        fun onAudioFocusLostTransient()
    }

    private var listener: AudioFocusListener? = null

    fun setAudioFocusListener(listener: AudioFocusListener?) {
        this.listener = listener
    }

    fun isMusicActive(): Boolean {
        return audioManager.isMusicActive
    }

    fun requestAudioFocus(duck: Boolean = false): Boolean {
        if (hasAudioFocus) {
            Log.d(TAG, "AudioFocusManager: Already have audio focus")
            return true
        }

        val focusGain = if (duck) {
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        } else {
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
        }

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()

        val focusRequest = AudioFocusRequest.Builder(focusGain)
            .setAudioAttributes(audioAttributes)
            .setAcceptsDelayedFocusGain(false)
            .setWillPauseWhenDucked(false)
            .setOnAudioFocusChangeListener(audioFocusChangeListener)
            .build()

        val result = audioManager.requestAudioFocus(focusRequest)

        return when (result) {
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> {
                Log.i(TAG, "AudioFocusManager: Audio focus granted (duck=$duck)")
                DebugLog.i("AudioFocusManager: Audio focus granted")
                audioFocusRequest = focusRequest
                hasAudioFocus = true
                listener?.onAudioFocusGained()
                true
            }
            AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> {
                Log.i(TAG, "AudioFocusManager: Audio focus request delayed")
                DebugLog.i("AudioFocusManager: Audio focus delayed")
                audioFocusRequest = focusRequest
                false
            }
            AudioManager.AUDIOFOCUS_REQUEST_FAILED -> {
                Log.w(TAG, "AudioFocusManager: Audio focus request failed")
                DebugLog.w("AudioFocusManager: Audio focus request failed")
                false
            }
            else -> {
                Log.w(TAG, "AudioFocusManager: Unknown audio focus result: $result")
                false
            }
        }
    }

    fun abandonAudioFocus() {
        if (!hasAudioFocus) {
            Log.d(TAG, "AudioFocusManager: No audio focus to abandon")
            return
        }

        audioFocusRequest?.let { request ->
            val result = audioManager.abandonAudioFocusRequest(request)
            if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Log.i(TAG, "AudioFocusManager: Audio focus abandoned")
                DebugLog.i("AudioFocusManager: Audio focus abandoned")
            } else {
                Log.w(TAG, "AudioFocusManager: Failed to abandon audio focus: $result")
            }
        }

        audioFocusRequest = null
        hasAudioFocus = false
    }

    fun hasAudioFocus(): Boolean = hasAudioFocus

    private val audioFocusChangeListener = AudioManager.OnAudioFocusChangeListener { focusChange ->
        when (focusChange) {
            AudioManager.AUDIOFOCUS_GAIN -> {
                Log.i(TAG, "AudioFocusManager: Audio focus regained")
                DebugLog.i("AudioFocusManager: Audio focus regained")
                hasAudioFocus = true
                listener?.onAudioFocusGained()
            }
            AudioManager.AUDIOFOCUS_LOSS -> {
                Log.i(TAG, "AudioFocusManager: Audio focus lost permanently")
                DebugLog.i("AudioFocusManager: Audio focus lost permanently")
                hasAudioFocus = false
                listener?.onAudioFocusLost()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                Log.i(TAG, "AudioFocusManager: Audio focus lost transiently")
                DebugLog.i("AudioFocusManager: Audio focus lost transiently")
                hasAudioFocus = false
                listener?.onAudioFocusLostTransient()
            }
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                Log.d(TAG, "AudioFocusManager: Asked to duck (ignoring for dictation)")
            }
        }
    }

    fun release() {
        abandonAudioFocus()
        listener = null
    }
}
