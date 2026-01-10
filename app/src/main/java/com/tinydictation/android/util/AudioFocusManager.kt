package com.tinydictation.android.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log

/**
 * Manages audio focus for dictation sessions.
 *
 * When enabled, this class requests audio focus when dictation starts,
 * which causes other audio apps (music players, YouTube, etc.) to pause or duck.
 * When dictation stops, audio focus is abandoned, allowing other apps to resume.
 *
 * Key behaviors:
 * - AUDIOFOCUS_GAIN_TRANSIENT: Pauses other audio apps temporarily
 * - AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK: Asks other apps to lower volume (ducking)
 * - isMusicActive(): Can detect if media is currently playing
 *
 * Note: Not all apps respect audio focus requests. Well-behaved apps will pause
 * or duck, but some may continue playing.
 */
class AudioFocusManager(private val context: Context) {

    companion object {
        private const val TAG = "TinyDictation"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var audioFocusRequest: AudioFocusRequest? = null
    private var hasAudioFocus = false

    /**
     * Listener for audio focus changes.
     * Called when audio focus is gained, lost, or when other apps request focus.
     */
    interface AudioFocusListener {
        fun onAudioFocusGained()
        fun onAudioFocusLost()
        fun onAudioFocusLostTransient()
    }

    private var listener: AudioFocusListener? = null

    /**
     * Sets the listener for audio focus changes.
     */
    fun setAudioFocusListener(listener: AudioFocusListener?) {
        this.listener = listener
    }

    /**
     * Checks if any music or media is currently playing on the device.
     * This can be used to detect if the user is playing YouTube, Spotify, etc.
     *
     * @return true if music is active, false otherwise
     */
    fun isMusicActive(): Boolean {
        return audioManager.isMusicActive
    }

    /**
     * Requests audio focus for dictation.
     * This will cause well-behaved audio apps to pause their playback.
     *
     * Uses AUDIOFOCUS_GAIN_TRANSIENT which indicates we want to pause other audio
     * temporarily (as opposed to AUDIOFOCUS_GAIN which would be for permanent takeover).
     *
     * @param duck If true, requests AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK which asks
     *             other apps to lower their volume instead of pausing. If false,
     *             requests AUDIOFOCUS_GAIN_TRANSIENT which asks them to pause.
     * @return true if audio focus was granted, false otherwise
     */
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

    /**
     * Abandons audio focus, allowing other audio apps to resume playback.
     * Call this when dictation stops.
     */
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

    /**
     * Returns whether we currently hold audio focus.
     */
    fun hasAudioFocus(): Boolean = hasAudioFocus

    /**
     * Listener for audio focus changes from the system.
     * This is called when:
     * - Another app requests audio focus (we might lose it)
     * - Our delayed request is granted
     * - The system takes focus away
     */
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
                // Another app wants us to duck - we can continue but should lower volume
                // For dictation, we don't have output volume, so we can ignore this
                Log.d(TAG, "AudioFocusManager: Asked to duck (ignoring for dictation)")
            }
        }
    }

    /**
     * Releases resources. Call when the manager is no longer needed.
     */
    fun release() {
        abandonAudioFocus()
        listener = null
    }
}
