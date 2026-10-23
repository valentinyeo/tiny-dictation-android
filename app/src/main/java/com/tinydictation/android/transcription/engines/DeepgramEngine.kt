package com.tinydictation.android.transcription.engines

import android.content.Context
import android.util.Log
import com.tinydictation.android.audio.AudioRecorder
import com.tinydictation.android.transcription.DeepgramClient
import com.tinydictation.android.transcription.TranscriptionEngine
import com.tinydictation.android.transcription.TranscriptionError
import com.tinydictation.android.util.DebugLog
import com.tinydictation.android.util.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

class DeepgramEngine(private val apiKey: String) : TranscriptionEngine {

    companion object {
        private const val TAG = "TinyDictation"
        private const val MAX_BUFFER_SAMPLES = 16000 * 5
    }

    override val engineId = "deepgram"
    override val displayName = "Deepgram (Cloud)"
    override val requiresInternet = true

    private var preferenceManager: PreferenceManager? = null
    private val deepgramClient = DeepgramClient { bytes ->
        val usage = preferenceManager?.recordDeepgramUsage(bytes)
        if (usage != null) {
            DebugLog.i("Deepgram streamed today: ${usage.seconds}s (${usage.requests} requests)")
        }
    }
    private val audioRecorder = AudioRecorder()
    private var scope: CoroutineScope? = null
    private var currentLanguage = "en"
    private var currentModel = PreferenceManager.DEEPGRAM_NOVA_2
    var terminalMode = false
    var smartFormat: Boolean = true
    var punctuate: Boolean = true
    var keywords: List<String> = emptyList()
    var replacements: List<Pair<String, String>> = emptyList()
    var numerals: Boolean = false
    var dictation: Boolean = false
    private var onTranscriptCallback: ((String, Boolean) -> Unit)? = null
    private var onErrorCallback: ((TranscriptionError) -> Unit)? = null
    private var isSessionActive = false

    private val preConnectionBuffer = mutableListOf<Short>()
    private var bufferFlushed = false

    private var closeJob: Job? = null

    fun setModel(model: String) {
        currentModel = model
        Log.i(TAG, "DeepgramEngine: Model set to $model")
    }

    override suspend fun isReady(context: Context, language: String): Boolean {
        return apiKey.isNotBlank()
    }

    override suspend fun initialize(context: Context, language: String): Result<Unit> {
        preferenceManager = PreferenceManager(context.applicationContext)
        currentLanguage = language
        return if (apiKey.isNotBlank()) {
            Result.success(Unit)
        } else {
            Result.failure(IllegalStateException("API key is required for Deepgram"))
        }
    }

    @Synchronized
    override fun startSession(
        onTranscript: (text: String, isFinal: Boolean) -> Unit,
        onError: (TranscriptionError) -> Unit
    ) {
        closeJob?.cancel()
        closeJob = null

        if (isSessionActive) {
            Log.d(TAG, "DeepgramEngine session already active")
            return
        }

        Log.i(TAG, "DeepgramEngine starting session (language=$currentLanguage, model=$currentModel)")
        DebugLog.i("DeepgramEngine: Starting session (model=$currentModel)")

        onTranscriptCallback = onTranscript
        onErrorCallback = onError
        isSessionActive = true
        scope?.cancel()
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        scope?.launch {
            synchronized(this@DeepgramEngine) {
                ensureActive()
                if (!isSessionActive) return@synchronized
                deepgramClient.connect(
                    apiKey = apiKey,
                    onInterim = { text ->
                        onTranscript(text, false)
                    },
                    onFinal = { text ->
                        onTranscript(text, true)
                    },
                    onError = { error ->
                        DebugLog.e("DeepgramEngine: Error - $error")
                        onError(error)
                    },
                    language = currentLanguage,
                    model = currentModel,
                    terminalMode = terminalMode,
                    smartFormat = smartFormat,
                    punctuate = punctuate,
                    keywords = keywords,
                    replacements = replacements,
                    numerals = numerals,
                    dictation = dictation
                )
            }
        }
    }

    @Synchronized
    override fun processAudio(samples: ShortArray) {
        if (!isSessionActive) return

        if (deepgramClient.isConnected()) {
            if (!bufferFlushed) {
                flushPreConnectionBuffer()
            }

            val audioBytes = audioRecorder.shortArrayToByteArray(samples)
            deepgramClient.sendAudio(audioBytes)
        } else {
            synchronized(preConnectionBuffer) {
                preConnectionBuffer.addAll(samples.toList())

                if (preConnectionBuffer.size > MAX_BUFFER_SAMPLES) {
                    val excess = preConnectionBuffer.size - MAX_BUFFER_SAMPLES
                    repeat(excess) { preConnectionBuffer.removeAt(0) }
                    Log.d(TAG, "DeepgramEngine: Buffer exceeded max, trimmed $excess samples")
                }

                if (preConnectionBuffer.size == samples.size) {
                    Log.i(TAG, "DeepgramEngine: Buffering audio while waiting for connection...")
                    DebugLog.i("DeepgramEngine: Buffering audio (WebSocket connecting)")
                }
            }
        }
    }

    private fun flushPreConnectionBuffer() {
        synchronized(preConnectionBuffer) {
            if (preConnectionBuffer.isEmpty()) {
                bufferFlushed = true
                return
            }

            val bufferedSamples = preConnectionBuffer.toShortArray()
            preConnectionBuffer.clear()
            bufferFlushed = true

            Log.i(TAG, "DeepgramEngine: Flushing ${bufferedSamples.size} buffered samples (${bufferedSamples.size / 16}ms)")
            DebugLog.i("DeepgramEngine: Flushing ${bufferedSamples.size} buffered samples")

            val audioBytes = audioRecorder.shortArrayToByteArray(bufferedSamples)
            deepgramClient.sendAudio(audioBytes)
        }
    }

    @Synchronized
    override fun sendKeepAlive() {
        if (isSessionActive) deepgramClient.sendKeepAlive()
    }

    override fun endSession() {
        endSessionInternal(graceful = true)
    }

    @Synchronized
    private fun endSessionInternal(graceful: Boolean) {
        if (!isSessionActive && graceful) return

        Log.i(TAG, "DeepgramEngine ending session (graceful=$graceful)")
        DebugLog.i("DeepgramEngine: Ending session (graceful=$graceful)")

        isSessionActive = false

        if (graceful && deepgramClient.isConnected()) {
            deepgramClient.sendFinalize()

            closeJob = scope?.launch {
                delay(800)
                synchronized(this@DeepgramEngine) {
                    ensureActive()
                    deepgramClient.close()
                    onTranscriptCallback = null
                    onErrorCallback = null
                    DebugLog.d("DeepgramEngine: Graceful close completed after 800ms")
                }
            }
        } else {
            closeJob?.cancel()
            closeJob = null
            deepgramClient.close()
            onTranscriptCallback = null
            onErrorCallback = null
        }

        synchronized(preConnectionBuffer) {
            preConnectionBuffer.clear()
        }
        bufferFlushed = false
    }

    override fun hasPendingAudio(): Boolean {
        return isSessionActive && deepgramClient.isConnected()
    }

    @Synchronized
    override fun setLanguage(language: String) {
        if (currentLanguage == language) return

        Log.i(TAG, "DeepgramEngine changing language to $language")
        currentLanguage = language

        if (isSessionActive) {
            val transcript = onTranscriptCallback
            val error = onErrorCallback
            if (transcript != null && error != null) {
                deepgramClient.close()
                scope?.launch {
                    kotlinx.coroutines.delay(100)
                    synchronized(this@DeepgramEngine) {
                        ensureActive()
                        if (!isSessionActive || onTranscriptCallback !== transcript) {
                            return@synchronized
                        }
                        deepgramClient.connect(
                            apiKey = apiKey,
                            onInterim = { text -> transcript(text, false) },
                            onFinal = { text -> transcript(text, true) },
                            onError = { err -> error(err) },
                            language = currentLanguage,
                            model = currentModel,
                            terminalMode = terminalMode
                        )
                    }
                }
            }
        }
    }

    fun setAutoDetectLanguage(enabled: Boolean) {
        currentLanguage = if (enabled && currentModel == PreferenceManager.DEEPGRAM_NOVA_3) "auto" else "en"
        Log.i(TAG, "DeepgramEngine: Auto-detect language = $enabled (actual: $currentLanguage)")
    }

    override fun getSupportedLanguages(): List<String> {
        return listOf("en", "de", "es", "fr", "it", "pt", "nl", "ja", "ko", "zh")
    }

    override fun clearAudioBuffer() {
        Log.d(TAG, "DeepgramEngine: Clearing audio buffer")
        synchronized(preConnectionBuffer) {
            preConnectionBuffer.clear()
        }
        bufferFlushed = false
    }

    @Synchronized
    override fun release() {
        closeJob?.cancel()
        closeJob = null
        endSessionInternal(graceful = false)
        scope?.cancel()
        scope = null
    }
}
