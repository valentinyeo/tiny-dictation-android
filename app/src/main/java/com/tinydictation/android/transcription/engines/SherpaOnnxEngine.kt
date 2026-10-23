package com.tinydictation.android.transcription.engines

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.getFeatureConfig
import com.tinydictation.android.transcription.TranscriptionEngine
import com.tinydictation.android.transcription.TranscriptionError
import com.tinydictation.android.util.DebugLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class SherpaOnnxEngine : TranscriptionEngine {

    companion object {
        private const val TAG = "TinyDictation"
        private const val SAMPLE_RATE = 16000
        private const val MODELS_DIR = "sherpa_models"

        private const val BUFFER_THRESHOLD_SAMPLES = SAMPLE_RATE * 5
        private const val MAX_BUFFER_SAMPLES = SAMPLE_RATE * 30

        private const val SILENCE_THRESHOLD = 0.005f

        fun getModelDir(context: Context, modelVariant: String): File {
            return File(context.filesDir, "$MODELS_DIR/$modelVariant")
        }

        fun isModelDownloaded(context: Context, modelVariant: String): Boolean {
            val modelDir = getModelDir(context, modelVariant)
            if (!modelDir.exists() || !modelDir.isDirectory) return false

            val encoderNames = listOf(
                "encoder.onnx", "encoder.int8.onnx",
                "tiny-encoder.onnx", "tiny-encoder.int8.onnx",
                "base-encoder.onnx", "base-encoder.int8.onnx",
                "small-encoder.onnx", "small-encoder.int8.onnx"
            )
            val decoderNames = listOf(
                "decoder.onnx", "decoder.int8.onnx",
                "tiny-decoder.onnx", "tiny-decoder.int8.onnx",
                "base-decoder.onnx", "base-decoder.int8.onnx",
                "small-decoder.onnx", "small-decoder.int8.onnx"
            )
            val tokenNames = listOf(
                "tokens.txt",
                "tiny-tokens.txt", "base-tokens.txt", "small-tokens.txt"
            )

            val hasEncoder = encoderNames.any { File(modelDir, it).exists() }
            val hasDecoder = decoderNames.any { File(modelDir, it).exists() }
            val hasTokens = tokenNames.any { File(modelDir, it).exists() }

            return hasEncoder && hasDecoder && hasTokens
        }
    }

    override val engineId = "sherpa-onnx"
    override var displayName = "Whisper (High Quality)"
        private set
    override val requiresInternet = false

    private var currentLanguage = "en"
    private var currentModelVariant = "whisper-tiny"
    private var onTranscriptCallback: ((String, Boolean) -> Unit)? = null
    private var onErrorCallback: ((TranscriptionError) -> Unit)? = null
    private var isSessionActive = false
    private var contextRef: Context? = null

    private var recognizer: OfflineRecognizer? = null

    private val audioBuffer = mutableListOf<Float>()
    private var processingJob: Job? = null
    private val processingScope = CoroutineScope(Dispatchers.Default)

    fun setModelVariant(modelVariant: String) {
        currentModelVariant = modelVariant
        displayName = when (modelVariant) {
            "whisper-tiny" -> "Whisper Tiny (Fast)"
            "whisper-base" -> "Whisper Base (Balanced)"
            "whisper-small" -> "Whisper Small (Accurate)"
            else -> "Whisper (High Quality)"
        }
        Log.i(TAG, "SherpaOnnxEngine: Model variant set to $modelVariant")
    }

    fun getModelVariant(): String = currentModelVariant

    override suspend fun isReady(context: Context, language: String): Boolean {
        return isModelDownloaded(context, currentModelVariant)
    }

    override suspend fun initialize(context: Context, language: String): Result<Unit> {
        contextRef = context
        currentLanguage = language

        if (!isModelDownloaded(context, currentModelVariant)) {
            return Result.failure(IllegalStateException("Model not downloaded: $currentModelVariant"))
        }

        return try {
            val modelDir = getModelDir(context, currentModelVariant)
            Log.i(TAG, "SherpaOnnxEngine: Initializing from ${modelDir.absolutePath}")
            DebugLog.i("SherpaOnnxEngine: Initializing $currentModelVariant for language $language")

            val encoderFile = findFile(modelDir, listOf(
                "encoder.onnx", "encoder.int8.onnx",
                "tiny-encoder.onnx", "tiny-encoder.int8.onnx",
                "base-encoder.onnx", "base-encoder.int8.onnx",
                "small-encoder.onnx", "small-encoder.int8.onnx"
            ))
            val decoderFile = findFile(modelDir, listOf(
                "decoder.onnx", "decoder.int8.onnx",
                "tiny-decoder.onnx", "tiny-decoder.int8.onnx",
                "base-decoder.onnx", "base-decoder.int8.onnx",
                "small-decoder.onnx", "small-decoder.int8.onnx"
            ))
            val tokensFile = findFile(modelDir, listOf(
                "tokens.txt",
                "tiny-tokens.txt", "base-tokens.txt", "small-tokens.txt"
            ))

            if (encoderFile == null || decoderFile == null || tokensFile == null) {
                val files = modelDir.listFiles()?.map { it.name } ?: emptyList()
                Log.e(TAG, "SherpaOnnxEngine: Missing model files in $modelDir")
                Log.e(TAG, "SherpaOnnxEngine: Available files: $files")
                Log.e(TAG, "SherpaOnnxEngine: encoder=$encoderFile, decoder=$decoderFile, tokens=$tokensFile")
                DebugLog.e("SherpaOnnxEngine: Missing files. Available: $files")
                return Result.failure(IllegalStateException("Missing model files. Found: $files"))
            }

            Log.i(TAG, "SherpaOnnxEngine: Found encoder=${encoderFile.name}, decoder=${decoderFile.name}, tokens=${tokensFile.name}")

            val whisperConfig = OfflineWhisperModelConfig(
                encoder = encoderFile.absolutePath,
                decoder = decoderFile.absolutePath,
                language = mapLanguageCode(language),
                task = "transcribe"
            )

            val modelConfig = OfflineModelConfig(
                whisper = whisperConfig,
                tokens = tokensFile.absolutePath,
                numThreads = 2,
                debug = false
            )

            val config = OfflineRecognizerConfig(
                featConfig = getFeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = modelConfig,
                decodingMethod = "greedy_search"
            )

            recognizer = OfflineRecognizer(config = config)

            Log.i(TAG, "SherpaOnnxEngine: Recognizer created successfully")
            DebugLog.i("SherpaOnnxEngine: Initialized successfully")

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "SherpaOnnxEngine: Failed to initialize", e)
            DebugLog.e("SherpaOnnxEngine: Initialization failed - ${e.message}")
            Result.failure(e)
        }
    }

    private fun findFile(dir: File, candidates: List<String>): File? {
        for (name in candidates) {
            val file = File(dir, name)
            if (file.exists()) return file
        }
        return null
    }

    private fun mapLanguageCode(language: String): String {
        return when (language) {
            "en" -> "en"
            "de" -> "de"
            "es" -> "es"
            "fr" -> "fr"
            "it" -> "it"
            "pt" -> "pt"
            "nl" -> "nl"
            "pl" -> "pl"
            "ru" -> "ru"
            "zh" -> "zh"
            "ja" -> "ja"
            "ko" -> "ko"
            else -> "en"
        }
    }

    override fun startSession(
        onTranscript: (text: String, isFinal: Boolean) -> Unit,
        onError: (TranscriptionError) -> Unit
    ) {
        if (isSessionActive) {
            Log.d(TAG, "SherpaOnnxEngine session already active")
            return
        }

        if (recognizer == null) {
            Log.e(TAG, "SherpaOnnxEngine: Recognizer not initialized")
            onError(TranscriptionError(
                "Whisper engine not initialized. Please download the model first."
            ))
            return
        }

        Log.i(TAG, "SherpaOnnxEngine starting session")
        DebugLog.i("SherpaOnnxEngine: Starting session")

        onTranscriptCallback = onTranscript
        onErrorCallback = onError
        isSessionActive = true
        consecutiveSilentChunks = 0
        hasDetectedSpeech = false
    }

    private var consecutiveSilentChunks = 0
    private var hasDetectedSpeech = false
    private val SILENT_CHUNKS_THRESHOLD = 10

    override fun processAudio(samples: ShortArray) {
        if (!isSessionActive || recognizer == null) return

        var sumSquares = 0.0
        for (sample in samples) {
            val normalized = sample / 32768.0f
            sumSquares += normalized * normalized
        }
        val rms = kotlin.math.sqrt(sumSquares / samples.size).toFloat()

        val isSilent = rms < SILENCE_THRESHOLD
        if (isSilent) {
            consecutiveSilentChunks++
        } else {
            consecutiveSilentChunks = 0
            hasDetectedSpeech = true
        }

        synchronized(audioBuffer) {
            for (sample in samples) {
                audioBuffer.add(sample / 32768.0f)
            }
        }

        val shouldProcess = (audioBuffer.size >= BUFFER_THRESHOLD_SAMPLES &&
                hasDetectedSpeech &&
                consecutiveSilentChunks >= SILENT_CHUNKS_THRESHOLD &&
                processingJob?.isActive != true)

        if (shouldProcess) {
            processBufferAsync()
            hasDetectedSpeech = false
            consecutiveSilentChunks = 0
        }

        if (audioBuffer.size > MAX_BUFFER_SAMPLES && processingJob?.isActive != true) {
            processBufferAsync()
            hasDetectedSpeech = false
            consecutiveSilentChunks = 0
        }
    }

    private fun processBufferAsync() {
        processingJob = processingScope.launch {
            processBuffer()
        }
    }

    private val HALLUCINATION_PATTERNS = listOf(
        "\\[Music\\]", "\\[Bell\\]", "\\[Applause\\]", "\\[Laughter\\]",
        "\\[MUSIC\\]", "\\[BELL\\]", "\\[APPLAUSE\\]", "\\[LAUGHTER\\]",
        "\\[music\\]", "\\[bell\\]", "\\[applause\\]", "\\[laughter\\]",
        "\\[Sound\\]", "\\[Noise\\]", "\\[Silence\\]", "\\[Background\\]",
        "\\[SOUND\\]", "\\[NOISE\\]", "\\[SILENCE\\]", "\\[BACKGROUND\\]",
        "♪.*?♪", "♫.*?♫",
        "\\(.*?music.*?\\)", "\\(.*?inaudible.*?\\)",
        "<.*?>",
        "Thank you for watching.*", "Thanks for watching.*",
        "Subscribe.*", "Like and subscribe.*",
        "\\.\\.\\.",
    ).map { it.toRegex(RegexOption.IGNORE_CASE) }

    private fun filterHallucinations(text: String): String {
        var filtered = text
        for (pattern in HALLUCINATION_PATTERNS) {
            filtered = filtered.replace(pattern, "")
        }
        filtered = filtered.replace(Regex("\\s+"), " ").trim()
        return filtered
    }

    private fun processBuffer() {
        val rec = recognizer ?: return

        val samples: FloatArray
        synchronized(audioBuffer) {
            if (audioBuffer.isEmpty()) return
            samples = audioBuffer.toFloatArray()
            audioBuffer.clear()
        }

        var sumSquares = 0.0
        for (sample in samples) {
            sumSquares += sample * sample
        }
        val rms = kotlin.math.sqrt(sumSquares / samples.size).toFloat()
        if (rms < SILENCE_THRESHOLD) {
            Log.d(TAG, "SherpaOnnxEngine: Skipping silent audio chunk (RMS=$rms)")
            return
        }

        try {
            val stream = rec.createStream()
            stream.acceptWaveform(samples, SAMPLE_RATE)
            rec.decode(stream)

            val result = rec.getResult(stream)
            stream.release()

            var text = result.text.trim()

            text = filterHallucinations(text)

            if (text.isNotEmpty()) {
                Log.d(TAG, "SherpaOnnxEngine: Transcribed: $text")
                DebugLog.d("SherpaOnnxEngine: Result:", text)

                onTranscriptCallback?.invoke(text, true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "SherpaOnnxEngine: Error processing audio", e)
            DebugLog.e("SherpaOnnxEngine: Processing error - ${e.message}")
            onErrorCallback?.invoke(TranscriptionError("Transcription error: ${e.message}"))
        }
    }

    override fun endSession() {
        if (!isSessionActive) return

        Log.i(TAG, "SherpaOnnxEngine ending session")
        DebugLog.i("SherpaOnnxEngine: Ending session")

        isSessionActive = false

        if (audioBuffer.isNotEmpty()) {
            processBuffer()
        }

        processingJob?.cancel()
        processingJob = null
        onTranscriptCallback = null
        onErrorCallback = null
    }

    override fun setLanguage(language: String) {
        if (currentLanguage == language) return

        Log.i(TAG, "SherpaOnnxEngine changing language to $language")
        DebugLog.i("SherpaOnnxEngine: Changing language from $currentLanguage to $language")
        currentLanguage = language

        val context = contextRef ?: run {
            Log.e(TAG, "SherpaOnnxEngine: Cannot reinitialize - no context")
            DebugLog.e("SherpaOnnxEngine: Language change failed - no context")
            return
        }

        processingJob?.cancel()
        processingJob = null

        synchronized(audioBuffer) {
            audioBuffer.clear()
        }

        processingScope.launch {
            try {
                recognizer?.release()
                recognizer = null

                val result = reinitializeRecognizer(context, language)
                if (result.isFailure) {
                    Log.e(TAG, "SherpaOnnxEngine: Failed to reinitialize for $language")
                    DebugLog.e("SherpaOnnxEngine: Reinit failed - ${result.exceptionOrNull()?.message}")
                    onErrorCallback?.invoke(TranscriptionError(
                        "Failed to switch language: ${result.exceptionOrNull()?.message}"
                    ))
                } else {
                    Log.i(TAG, "SherpaOnnxEngine: Reinitialized for $language")
                    DebugLog.i("SherpaOnnxEngine: Language switched to $language")
                }
            } catch (e: Exception) {
                Log.e(TAG, "SherpaOnnxEngine: Error during language switch", e)
                DebugLog.e("SherpaOnnxEngine: Language switch error - ${e.message}")
            }
        }
    }

    private fun reinitializeRecognizer(context: Context, language: String): Result<Unit> {
        val modelDir = getModelDir(context, currentModelVariant)

        val encoderFile = findFile(modelDir, listOf(
            "encoder.onnx", "encoder.int8.onnx",
            "tiny-encoder.onnx", "tiny-encoder.int8.onnx",
            "base-encoder.onnx", "base-encoder.int8.onnx",
            "small-encoder.onnx", "small-encoder.int8.onnx"
        ))
        val decoderFile = findFile(modelDir, listOf(
            "decoder.onnx", "decoder.int8.onnx",
            "tiny-decoder.onnx", "tiny-decoder.int8.onnx",
            "base-decoder.onnx", "base-decoder.int8.onnx",
            "small-decoder.onnx", "small-decoder.int8.onnx"
        ))
        val tokensFile = findFile(modelDir, listOf(
            "tokens.txt",
            "tiny-tokens.txt", "base-tokens.txt", "small-tokens.txt"
        ))

        if (encoderFile == null || decoderFile == null || tokensFile == null) {
            return Result.failure(IllegalStateException("Missing model files"))
        }

        val whisperConfig = OfflineWhisperModelConfig(
            encoder = encoderFile.absolutePath,
            decoder = decoderFile.absolutePath,
            language = mapLanguageCode(language),
            task = "transcribe"
        )

        val modelConfig = OfflineModelConfig(
            whisper = whisperConfig,
            tokens = tokensFile.absolutePath,
            numThreads = 2,
            debug = false
        )

        val config = OfflineRecognizerConfig(
            featConfig = getFeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
            modelConfig = modelConfig,
            decodingMethod = "greedy_search"
        )

        recognizer = OfflineRecognizer(config = config)
        return Result.success(Unit)
    }

    override fun getSupportedLanguages(): List<String> {
        return listOf("en", "de", "es", "fr", "it", "pt", "nl", "pl", "ru", "zh", "ja", "ko")
    }

    override fun clearAudioBuffer() {
        Log.d(TAG, "SherpaOnnxEngine: Clearing audio buffer")
        synchronized(audioBuffer) {
            audioBuffer.clear()
        }
        consecutiveSilentChunks = 0
        hasDetectedSpeech = false
    }

    override fun hasPendingAudio(): Boolean {
        val hasBufferedAudio = synchronized(audioBuffer) { audioBuffer.isNotEmpty() }
        val isProcessing = processingJob?.isActive == true
        Log.d(TAG, "SherpaOnnxEngine: hasPendingAudio - buffer=${hasBufferedAudio}, processing=${isProcessing}")
        return hasBufferedAudio || isProcessing
    }

    override suspend fun flushPendingAudio(timeoutMs: Long): Boolean {
        Log.i(TAG, "SherpaOnnxEngine: Flushing pending audio (timeout=${timeoutMs}ms)")
        DebugLog.i("SherpaOnnxEngine: Flushing pending audio")

        processingJob?.let { job ->
            Log.d(TAG, "SherpaOnnxEngine: Waiting for current processing job")
            withTimeoutOrNull(timeoutMs / 2) {
                job.join()
            }
        }

        val hasBufferedAudio = synchronized(audioBuffer) { audioBuffer.isNotEmpty() }
        if (hasBufferedAudio && recognizer != null) {
            Log.d(TAG, "SherpaOnnxEngine: Processing remaining buffer")
            DebugLog.d("SherpaOnnxEngine: Processing remaining ${audioBuffer.size} samples")

            processBuffer()
        }

        processingJob?.let { job ->
            Log.d(TAG, "SherpaOnnxEngine: Waiting for final processing")
            val completed = withTimeoutOrNull(timeoutMs / 2) {
                job.join()
                true
            } ?: false

            if (!completed) {
                Log.w(TAG, "SherpaOnnxEngine: Flush timed out")
                DebugLog.w("SherpaOnnxEngine: Flush timed out")
                return false
            }
        }

        Log.i(TAG, "SherpaOnnxEngine: Flush completed")
        DebugLog.i("SherpaOnnxEngine: Flush completed")
        return true
    }

    override fun release() {
        endSession()

        try {
            recognizer?.release()
        } catch (e: Exception) {
            Log.e(TAG, "SherpaOnnxEngine: Error releasing recognizer", e)
        }
        recognizer = null
        contextRef = null
    }
}
