package com.tinydictation.android.prediction

import android.content.Context
import com.tinydictation.android.util.DebugLog
import com.tinydictation.android.util.PreferenceManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Cloud-based prediction engine using Groq's ultra-fast LLM API.
 * Groq typically responds in <100ms, making it viable for real-time predictions.
 *
 * Features:
 * - Word completion for partial words
 * - Next word prediction
 * - Sentence completion (when 3+ words of context)
 */
class GroqPredictionEngine : PredictionEngine {

    override val engineId = "groq"
    override val displayName = "Groq Cloud (Fast AI)"
    override val requiresInternet = true

    private var apiKey: String? = null
    private var isInitialized = false

    // Minimum words needed for sentence completion mode
    private val SENTENCE_COMPLETION_MIN_WORDS = 3

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    override suspend fun initialize(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // Get API key from preferences
            val prefs = PreferenceManager(context)
            apiKey = prefs.getGroqApiKey()

            if (apiKey.isNullOrBlank()) {
                DebugLog.w("GroqPredictionEngine: No API key configured")
                return@withContext Result.failure(IllegalStateException("Groq API key not configured. Set it in Settings."))
            }

            isInitialized = true
            DebugLog.i("GroqPredictionEngine: Initialized")
            Result.success(Unit)
        } catch (e: Exception) {
            DebugLog.e("GroqPredictionEngine: Init failed - ${e.message}")
            Result.failure(e)
        }
    }

    override suspend fun predict(
        contextWords: List<String>,
        partialWord: String
    ): List<Prediction> = withContext(Dispatchers.IO) {
        if (!isInitialized || apiKey.isNullOrBlank()) {
            return@withContext emptyList()
        }

        val startTime = System.nanoTime()

        try {
            val context = contextWords.takeLast(8).joinToString(" ")

            // Determine prediction mode based on context
            val useSentenceCompletion = contextWords.size >= SENTENCE_COMPLETION_MIN_WORDS && partialWord.isEmpty()

            val prompt = if (useSentenceCompletion) {
                buildSentenceCompletionPrompt(context)
            } else {
                buildWordPrompt(context, partialWord)
            }

            val response = callGroqApi(prompt, useSentenceCompletion)
            val predictions = if (useSentenceCompletion) {
                parseSentenceResponse(response)
            } else {
                parseWordResponse(response, partialWord.isNotEmpty())
            }

            val elapsedMs = (System.nanoTime() - startTime) / 1_000_000.0
            val mode = if (useSentenceCompletion) "sentence" else "word"
            DebugLog.i("GroqPrediction[$mode]: '$partialWord' after '$context' -> ${predictions.map { it.word }} in ${String.format("%.0f", elapsedMs)}ms")

            predictions
        } catch (e: Exception) {
            val elapsedMs = (System.nanoTime() - startTime) / 1_000_000.0
            DebugLog.e("GroqPrediction: Failed after ${String.format("%.0f", elapsedMs)}ms - ${e.message}")
            emptyList()
        }
    }

    /**
     * Build prompt for word completion or next word prediction.
     * Auto-detects language from context.
     */
    private fun buildWordPrompt(context: String, partialWord: String): String {
        return if (partialWord.isNotEmpty()) {
            """Complete this partial word. Context: "$context" Partial: "$partialWord"
Detect the language from context and return completions in the SAME language.
Return exactly 3 word completions, one per line, nothing else. Just the complete words."""
        } else {
            """Predict the next word. Context: "$context"
Detect the language from context and return predictions in the SAME language.
Return exactly 3 likely next words, one per line, nothing else. Just single words."""
        }
    }

    /**
     * Build prompt for sentence completion (multi-word predictions).
     * Auto-detects language from context.
     */
    private fun buildSentenceCompletionPrompt(context: String): String {
        return """Complete this sentence naturally. Context: "$context"
IMPORTANT: Detect the language and respond in the SAME language as the context.
If context is German, respond in German. If English, respond in English. Etc.
Return exactly 3 different completions, one per line.
Each completion should be 2-6 words that naturally continue the sentence.
Just the completion words, no quotes or punctuation at start."""
    }

    private fun callGroqApi(prompt: String, isSentenceMode: Boolean = false): String {
        val systemPrompt = if (isSentenceMode) {
            "You are a multilingual sentence completion assistant. Detect the language of the input and respond in the SAME language. Return natural continuations of sentences, one per line, no explanations or numbering."
        } else {
            "You are a multilingual word prediction assistant. Detect the language of the input and respond in the SAME language. Return only the requested words, one per line, no explanations."
        }

        val jsonBody = JSONObject().apply {
            put("model", "llama-3.1-8b-instant") // Fast model
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", prompt)
                })
            })
            put("temperature", 0.3)
            put("max_tokens", if (isSentenceMode) 60 else 30)
        }

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            throw Exception("Groq API error: ${response.code} - ${response.body?.string()}")
        }

        val responseBody = response.body?.string() ?: throw Exception("Empty response")
        val json = JSONObject(responseBody)

        return json
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
    }

    /**
     * Parse response for single word predictions.
     */
    private fun parseWordResponse(response: String, isCompletion: Boolean): List<Prediction> {
        return response
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() && it.matches(Regex("^[a-zA-Z']+$")) }
            .take(3)
            .mapIndexed { index, word ->
                Prediction(
                    word = word.lowercase(),
                    confidence = 1f - (index * 0.2f),
                    isCompletion = isCompletion
                )
            }
    }

    /**
     * Parse response for sentence completions (multi-word).
     */
    private fun parseSentenceResponse(response: String): List<Prediction> {
        return response
            .split("\n")
            .map { line ->
                // Clean up: remove numbering, quotes, leading punctuation
                line.trim()
                    .removePrefix("1.")
                    .removePrefix("2.")
                    .removePrefix("3.")
                    .removePrefix("-")
                    .removePrefix("•")
                    .trim()
                    .removeSurrounding("\"")
                    .trim()
            }
            .filter { it.isNotBlank() && it.length >= 2 && it.length <= 50 }
            .take(3)
            .mapIndexed { index, phrase ->
                Prediction(
                    word = phrase,
                    confidence = 1f - (index * 0.2f),
                    isCompletion = false  // Not a word completion, it's a phrase to append
                )
            }
    }

    override fun release() {
        isInitialized = false
        DebugLog.i("GroqPredictionEngine: Released")
    }

    override fun learn(words: List<String>) {
        // Cloud API doesn't support learning
    }
}
