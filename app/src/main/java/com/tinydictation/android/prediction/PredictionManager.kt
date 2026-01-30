package com.tinydictation.android.prediction

import android.content.Context
import com.tinydictation.android.util.DebugLog
import kotlinx.coroutines.*

/**
 * Manages word prediction engines and provides predictions to the keyboard UI.
 * Supports swapping engines at runtime for A/B testing different approaches.
 */
class PredictionManager(private val context: Context) {

    private var currentEngine: PredictionEngine? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Debounce tracking
    private var pendingPredictionJob: Job? = null
    private val debounceMs = 50L // Wait 50ms after last keystroke before predicting

    // Available engines
    private val engines = mapOf(
        "ngram" to { NGramPredictionEngine() },
        "groq" to { GroqPredictionEngine() }
    )

    /**
     * Initialize with the specified engine.
     * @param engineId Engine identifier (e.g., "ngram", "cloud_openai")
     */
    suspend fun initialize(engineId: String = "ngram"): Result<Unit> {
        return try {
            val engineFactory = engines[engineId]
                ?: return Result.failure(IllegalArgumentException("Unknown engine: $engineId"))

            currentEngine?.release()
            val engine = engineFactory()
            val result = engine.initialize(context)

            if (result.isSuccess) {
                currentEngine = engine
                DebugLog.i("PredictionManager: Initialized with engine '$engineId'")
            }
            result
        } catch (e: Exception) {
            DebugLog.e("PredictionManager: Failed to initialize - ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Switch to a different prediction engine.
     */
    suspend fun switchEngine(engineId: String): Result<Unit> {
        DebugLog.i("PredictionManager: Switching to engine '$engineId'")
        return initialize(engineId)
    }

    /**
     * Get available engine IDs.
     */
    fun getAvailableEngines(): List<String> = engines.keys.toList()

    /**
     * Get current engine ID.
     */
    fun getCurrentEngineId(): String? = currentEngine?.engineId

    /**
     * Request predictions for the given input text.
     * Uses debouncing to avoid excessive calls during rapid typing.
     *
     * @param textBeforeCursor Text before the cursor position
     * @param onPredictions Callback with prediction results
     */
    fun requestPredictions(
        textBeforeCursor: String,
        onPredictions: (List<Prediction>) -> Unit
    ) {
        // Cancel any pending prediction
        pendingPredictionJob?.cancel()

        pendingPredictionJob = scope.launch {
            delay(debounceMs) // Debounce

            val engine = currentEngine
            if (engine == null) {
                DebugLog.w("PredictionManager: requestPredictions called but currentEngine is null (not initialized yet?)")
                withContext(Dispatchers.Main) {
                    onPredictions(emptyList())
                }
                return@launch
            }

            // Parse input into context words and partial word
            val words = textBeforeCursor.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            val partialWord: String
            val contextWords: List<String>

            if (textBeforeCursor.endsWith(" ") || textBeforeCursor.isEmpty()) {
                // Cursor after space - predict next word
                partialWord = ""
                contextWords = words.takeLast(3)
            } else {
                // Cursor in middle of word - complete current word
                partialWord = words.lastOrNull() ?: ""
                contextWords = words.dropLast(1).takeLast(3)
            }

            val startTime = System.nanoTime()
            val predictions = engine.predict(contextWords, partialWord)
            val elapsedMs = (System.nanoTime() - startTime) / 1_000_000.0

            DebugLog.d("PredictionManager: Got ${predictions.size} predictions in ${String.format("%.2f", elapsedMs)}ms")

            withContext(Dispatchers.Main) {
                onPredictions(predictions)
            }
        }
    }

    /**
     * Notify the engine that user selected/typed these words.
     * Allows engines to learn from user behavior.
     */
    fun onWordsConfirmed(words: List<String>) {
        currentEngine?.learn(words)
    }

    /**
     * Release resources.
     */
    fun release() {
        scope.cancel()
        currentEngine?.release()
        currentEngine = null
        DebugLog.i("PredictionManager: Released")
    }
}
