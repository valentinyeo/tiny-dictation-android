package com.tinydictation.android.transcription.engines

import android.content.Context
import android.util.Log
import com.tinydictation.android.transcription.TranscriptionEngine
import com.tinydictation.android.transcription.TranscriptionError
import com.tinydictation.android.util.DebugLog
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.StorageService
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class VoskEngine : TranscriptionEngine {

    companion object {
        private const val TAG = "TinyDictation"
        private const val SAMPLE_RATE = 16000f

        private const val MODELS_DIR = "vosk_models"

        val MODEL_INFO = mapOf(
            "en" to ModelInfo(
                name = "vosk-model-small-en-us-0.15",
                url = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
                sizeMB = 41
            ),
            "de" to ModelInfo(
                name = "vosk-model-small-de-0.15",
                url = "https://alphacephei.com/vosk/models/vosk-model-small-de-0.15.zip",
                sizeMB = 45
            )
        )

        data class ModelInfo(val name: String, val url: String, val sizeMB: Int)

        fun getModelDir(context: Context, language: String): File {
            return File(context.filesDir, "$MODELS_DIR/$language")
        }

        fun isModelDownloaded(context: Context, language: String): Boolean {
            val modelDir = getModelDir(context, language)
            return modelDir.exists() && modelDir.isDirectory &&
                   (File(modelDir, "am/final.mdl").exists() ||
                    File(modelDir, "graph/HCLG.fst").exists() ||
                    modelDir.listFiles()?.isNotEmpty() == true)
        }
    }

    override val engineId = "vosk"
    override val displayName = "Vosk (Fast Offline)"
    override val requiresInternet = false

    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var currentLanguage = "en"
    private var onTranscriptCallback: ((String, Boolean) -> Unit)? = null
    private var onErrorCallback: ((TranscriptionError) -> Unit)? = null
    private var isSessionActive = false
    private var contextRef: Context? = null

    override suspend fun isReady(context: Context, language: String): Boolean {
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
            Log.i(TAG, "VoskEngine: Loading model from ${modelDir.absolutePath}")
            DebugLog.i("VoskEngine: Loading model for $language")

            model = Model(modelDir.absolutePath)
            Log.i(TAG, "VoskEngine: Model loaded successfully")
            DebugLog.i("VoskEngine: Model loaded")

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "VoskEngine: Failed to load model", e)
            DebugLog.e("VoskEngine: Failed to load model - ${e.message}")
            Result.failure(e)
        }
    }

    override fun startSession(
        onTranscript: (text: String, isFinal: Boolean) -> Unit,
        onError: (TranscriptionError) -> Unit
    ) {
        if (isSessionActive) {
            Log.d(TAG, "VoskEngine session already active")
            return
        }

        val currentModel = model
        if (currentModel == null) {
            onError(TranscriptionError("Model not initialized"))
            return
        }

        Log.i(TAG, "VoskEngine starting session")
        DebugLog.i("VoskEngine: Starting session")

        onTranscriptCallback = onTranscript
        onErrorCallback = onError
        isSessionActive = true

        try {
            recognizer = Recognizer(currentModel, SAMPLE_RATE)
            Log.i(TAG, "VoskEngine: Recognizer created")
        } catch (e: Exception) {
            Log.e(TAG, "VoskEngine: Failed to create recognizer", e)
            onError(TranscriptionError("Failed to create recognizer: ${e.message}"))
            isSessionActive = false
        }
    }

    override fun processAudio(samples: ShortArray) {
        if (!isSessionActive) return

        val rec = recognizer ?: return

        try {
            val bytes = shortArrayToByteArray(samples)

            if (rec.acceptWaveForm(bytes, bytes.size)) {
                val result = parseResult(rec.result)
                if (result.isNotEmpty()) {
                    onTranscriptCallback?.invoke(result, true)
                }
            } else {
                val partial = parsePartialResult(rec.partialResult)
                if (partial.isNotEmpty()) {
                    onTranscriptCallback?.invoke(partial, false)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "VoskEngine: Error processing audio", e)
            DebugLog.e("VoskEngine: Processing error - ${e.message}")
        }
    }

    override fun endSession() {
        if (!isSessionActive) return

        Log.i(TAG, "VoskEngine ending session")
        DebugLog.i("VoskEngine: Ending session")

        isSessionActive = false

        try {
            recognizer?.let { rec ->
                val finalResult = parseResult(rec.finalResult)
                if (finalResult.isNotEmpty()) {
                    onTranscriptCallback?.invoke(finalResult, true)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "VoskEngine: Error getting final result", e)
        }

        recognizer?.close()
        recognizer = null
        onTranscriptCallback = null
        onErrorCallback = null
    }

    override fun setLanguage(language: String) {
        if (currentLanguage == language) return

        Log.i(TAG, "VoskEngine changing language to $language")
        currentLanguage = language

        val ctx = contextRef
        if (ctx != null && isSessionActive) {
            val transcript = onTranscriptCallback
            val error = onErrorCallback

            endSession()

            try {
                if (isModelDownloaded(ctx, language)) {
                    val modelDir = getModelDir(ctx, language)
                    model?.close()
                    model = Model(modelDir.absolutePath)

                    if (transcript != null && error != null) {
                        startSession(transcript, error)
                    }
                } else {
                    error?.invoke(TranscriptionError("Model not downloaded for language: $language"))
                }
            } catch (e: Exception) {
                error?.invoke(TranscriptionError("Failed to switch language: ${e.message}"))
            }
        }
    }

    override fun getSupportedLanguages(): List<String> {
        return listOf("en", "de")
    }

    override fun release() {
        endSession()
        model?.close()
        model = null
        contextRef = null
    }

    private fun parseResult(jsonString: String): String {
        return try {
            val json = JSONObject(jsonString)
            json.optString("text", "").trim()
        } catch (e: Exception) {
            Log.e(TAG, "VoskEngine: Failed to parse result", e)
            ""
        }
    }

    private fun parsePartialResult(jsonString: String): String {
        return try {
            val json = JSONObject(jsonString)
            json.optString("partial", "").trim()
        } catch (e: Exception) {
            Log.e(TAG, "VoskEngine: Failed to parse partial result", e)
            ""
        }
    }

    private fun shortArrayToByteArray(samples: ShortArray): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        for (sample in samples) {
            buffer.putShort(sample)
        }
        return bytes
    }
}
