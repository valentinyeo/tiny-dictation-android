package com.tinydictation.android.transcription.engines

import android.content.Context
import android.util.Log
import com.tinydictation.android.transcription.TranscriptionEngine
import com.tinydictation.android.util.DebugLog
import java.io.File

/**
 * Offline transcription engine using Sherpa-ONNX with Whisper models.
 *
 * Provides high-quality transcription using the Whisper tiny model.
 * Note: This is currently a placeholder - Sherpa-ONNX AAR integration pending.
 *
 * When integrated, this engine buffers audio and processes it in chunks
 * (Whisper is not a streaming model like Vosk).
 */
class SherpaOnnxEngine : TranscriptionEngine {

    companion object {
        private const val TAG = "TinyDictation"
        private const val SAMPLE_RATE = 16000
        private const val MODELS_DIR = "sherpa_models"

        // Buffer settings - Whisper works best with ~3-5 second chunks
        private const val BUFFER_SIZE_SAMPLES = SAMPLE_RATE * 3 // 3 seconds

        fun getModelDir(context: Context, language: String): File {
            return File(context.filesDir, "$MODELS_DIR/$language")
        }

        fun isModelDownloaded(context: Context, language: String): Boolean {
            val modelDir = getModelDir(context, language)
            // Check for ONNX model files
            return modelDir.exists() && modelDir.isDirectory &&
                   (File(modelDir, "encoder.onnx").exists() ||
                    File(modelDir, "model.onnx").exists() ||
                    modelDir.listFiles()?.any { it.extension == "onnx" } == true)
        }
    }

    override val engineId = "sherpa-onnx"
    override val displayName = "Whisper (High Quality)"
    override val requiresInternet = false

    private var currentLanguage = "multi"
    private var onTranscriptCallback: ((String, Boolean) -> Unit)? = null
    private var onErrorCallback: ((String) -> Unit)? = null
    private var isSessionActive = false
    private var contextRef: Context? = null

    // Audio buffer for batch processing
    private val audioBuffer = mutableListOf<Short>()

    override suspend fun isReady(context: Context, language: String): Boolean {
        // Check if model is downloaded
        // Note: Full integration requires Sherpa-ONNX AAR to be added to the project
        return isModelDownloaded(context, language)
    }

    override suspend fun initialize(context: Context, language: String): Result<Unit> {
        contextRef = context
        currentLanguage = language

        if (!isModelDownloaded(context, language)) {
            return Result.failure(IllegalStateException("Model not downloaded for language: $language"))
        }

        return try {
            val modelDir = getModelDir(context, language)
            Log.i(TAG, "SherpaOnnxEngine: Model directory at ${modelDir.absolutePath}")
            DebugLog.i("SherpaOnnxEngine: Initializing for $language")

            // TODO: Initialize Sherpa-ONNX recognizer when AAR is integrated
            // For now, we verify the model exists but can't actually use it
            // until the sherpa-onnx-android AAR is added to the project

            Log.w(TAG, "SherpaOnnxEngine: Library not yet integrated - using placeholder")
            DebugLog.w("SherpaOnnxEngine: AAR not integrated, will fall back to Vosk")

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "SherpaOnnxEngine: Failed to initialize", e)
            DebugLog.e("SherpaOnnxEngine: Initialization failed - ${e.message}")
            Result.failure(e)
        }
    }

    override fun startSession(
        onTranscript: (text: String, isFinal: Boolean) -> Unit,
        onError: (String) -> Unit
    ) {
        if (isSessionActive) {
            Log.d(TAG, "SherpaOnnxEngine session already active")
            return
        }

        Log.i(TAG, "SherpaOnnxEngine starting session")
        DebugLog.i("SherpaOnnxEngine: Starting session")

        onTranscriptCallback = onTranscript
        onErrorCallback = onError
        isSessionActive = true
        audioBuffer.clear()

        // TODO: Create Sherpa-ONNX recognizer when AAR is integrated
        // For now, inform user that this engine isn't fully available yet
        onError("Whisper engine not yet fully integrated. Please use Vosk or Deepgram.")
    }

    override fun processAudio(samples: ShortArray) {
        if (!isSessionActive) return

        // Buffer the audio
        audioBuffer.addAll(samples.toList())

        // When we have enough audio, process it
        if (audioBuffer.size >= BUFFER_SIZE_SAMPLES) {
            processBuffer()
        }
    }

    private fun processBuffer() {
        if (audioBuffer.isEmpty()) return

        // TODO: Process audio with Sherpa-ONNX when integrated
        // val samples = audioBuffer.toShortArray()
        // val result = recognizer.decode(samples)
        // onTranscriptCallback?.invoke(result, true)

        // For now, just clear the buffer
        audioBuffer.clear()
    }

    override fun endSession() {
        if (!isSessionActive) return

        Log.i(TAG, "SherpaOnnxEngine ending session")
        DebugLog.i("SherpaOnnxEngine: Ending session")

        isSessionActive = false

        // Process any remaining audio
        if (audioBuffer.isNotEmpty()) {
            processBuffer()
        }

        onTranscriptCallback = null
        onErrorCallback = null
    }

    override fun setLanguage(language: String) {
        if (currentLanguage == language) return

        Log.i(TAG, "SherpaOnnxEngine changing language to $language")
        currentLanguage = language

        // Whisper tiny is multilingual, so language change doesn't require model reload
        // We just need to set the language hint for the decoder
    }

    override fun getSupportedLanguages(): List<String> {
        // Whisper tiny supports many languages
        return listOf("en", "de", "es", "fr", "it", "pt", "nl", "pl", "ru", "zh", "ja", "ko")
    }

    override fun release() {
        endSession()
        contextRef = null

        // TODO: Release Sherpa-ONNX resources when integrated
    }
}
