package com.tinydictation.android.prediction

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.tinydictation.android.util.DebugLog
import com.tinydictation.android.util.PreferenceManager
import kotlinx.coroutines.*

/**
 * Manages word prediction engines with cloud-first, offline-fallback strategy.
 *
 * Architecture:
 * - Groq (cloud AI) is tried first for high-quality predictions
 * - NGram (offline) provides instant fallback if Groq fails/times out
 * - Both engines stay initialized for seamless switching
 */
class PredictionManager(private val context: Context) {

    // Cloud engine (Groq) - optional, requires API key
    private var groqEngine: GroqPredictionEngine? = null
    private var groqInitialized = false

    // Offline engine (NGram) - always available
    private var ngramEngine: NGramPredictionEngine? = null
    private var ngramInitialized = false

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // Debounce tracking
    private var pendingPredictionJob: Job? = null
    private val debounceMs = 50L // Wait 50ms after last keystroke before predicting

    // Groq timeout - if Groq takes longer, use offline fallback
    private val groqTimeoutMs = 2000L

    /**
     * Check if network is available. Used to skip Groq when offline
     * and use NGram directly for instant predictions.
     */
    private fun isNetworkAvailable(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
               capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Initialize both engines. Groq is optional (requires API key), NGram always initializes.
     */
    suspend fun initialize(preferredEngine: String = "groq"): Result<Unit> {
        return try {
            // Always initialize NGram (offline) first - it's our fallback
            ngramEngine = NGramPredictionEngine()
            val ngramResult = ngramEngine?.initialize(context)
            ngramInitialized = ngramResult?.isSuccess == true
            DebugLog.i("PredictionManager: NGram init: success=$ngramInitialized")

            // Try to initialize Groq if API key is configured
            val prefs = PreferenceManager(context)
            val groqApiKey = prefs.getGroqApiKey()

            if (!groqApiKey.isNullOrBlank()) {
                groqEngine = GroqPredictionEngine()
                val groqResult = groqEngine?.initialize(context)
                groqInitialized = groqResult?.isSuccess == true
                DebugLog.i("PredictionManager: Groq init: success=$groqInitialized")
            } else {
                DebugLog.i("PredictionManager: Groq skipped (no API key)")
            }

            if (ngramInitialized) {
                DebugLog.i("PredictionManager: Ready! Groq=$groqInitialized, NGram=$ngramInitialized")
                Result.success(Unit)
            } else {
                Result.failure(IllegalStateException("NGram engine failed to initialize"))
            }
        } catch (e: Exception) {
            DebugLog.e("PredictionManager: Failed to initialize - ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Switch to a different prediction engine (for compatibility).
     */
    suspend fun switchEngine(engineId: String): Result<Unit> {
        DebugLog.i("PredictionManager: switchEngine('$engineId') called - now using cloud-first with fallback")
        return initialize(engineId)
    }

    /**
     * Get available engine IDs.
     */
    fun getAvailableEngines(): List<String> = listOf("ngram", "groq")

    /**
     * Get current engine ID.
     */
    fun getCurrentEngineId(): String? = if (groqInitialized) "groq" else if (ngramInitialized) "ngram" else null

    /**
     * Request predictions for the given input text.
     * Strategy: Try Groq first with timeout, fallback to NGram if Groq fails.
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

            if (!ngramInitialized) {
                DebugLog.w("PredictionManager: Not initialized!")
                withContext(Dispatchers.Main) { onPredictions(emptyList()) }
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
            var predictions: List<Prediction>
            var usedEngine: String

            // Check network availability to skip Groq when offline (instant NGram fallback)
            val hasNetwork = isNetworkAvailable()

            // Strategy: Try Groq first (if available AND online), fallback to NGram
            if (groqInitialized && groqEngine != null && hasNetwork) {
                try {
                    // Try Groq with timeout
                    predictions = withTimeout(groqTimeoutMs) {
                        groqEngine!!.predict(contextWords, partialWord)
                    }
                    usedEngine = "groq"

                    // If Groq returns empty, fallback to NGram
                    if (predictions.isEmpty()) {
                        predictions = ngramEngine!!.predict(contextWords, partialWord)
                        usedEngine = "ngram (groq empty)"
                    }
                } catch (e: TimeoutCancellationException) {
                    // Groq timed out - use NGram
                    DebugLog.w("PredictionManager: Groq timeout, using NGram")
                    predictions = ngramEngine!!.predict(contextWords, partialWord)
                    usedEngine = "ngram (groq timeout)"
                } catch (e: Exception) {
                    // Groq failed - use NGram
                    DebugLog.w("PredictionManager: Groq failed (${e.message}), using NGram")
                    predictions = ngramEngine!!.predict(contextWords, partialWord)
                    usedEngine = "ngram (groq error)"
                }
            } else {
                // No Groq available or offline - use NGram directly
                predictions = ngramEngine!!.predict(contextWords, partialWord)
                usedEngine = if (!hasNetwork && groqInitialized) "ngram (offline)" else "ngram"
            }

            val elapsedMs = (System.nanoTime() - startTime) / 1_000_000.0
            DebugLog.d("PredictionManager[$usedEngine]: ${predictions.size} predictions in ${String.format("%.0f", elapsedMs)}ms")

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
        ngramEngine?.learn(words)
    }

    /**
     * Release resources.
     */
    fun release() {
        scope.cancel()
        groqEngine?.release()
        ngramEngine?.release()
        groqEngine = null
        ngramEngine = null
        groqInitialized = false
        ngramInitialized = false
        DebugLog.i("PredictionManager: Released")
    }
}
