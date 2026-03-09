package com.tinydictation.android.ai

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
 * AI-powered next-word prediction using Anthropic's Claude Haiku.
 *
 * After the user finishes a word (space press), predicts the 3 most likely
 * next words based on context. Results shown in the prediction strip.
 */
class NextWordPredictor(private val context: Context) {

    companion object {
        private const val TAG = "NextWordPredictor"
        private const val CONTEXT_CHARS = 100  // chars of context to send
    }

    private val prefs = PreferenceManager(context)

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Predict the 3 most likely next words given the text context.
     *
     * @param contextText The text before the cursor (last ~100 chars)
     * @return List of 1-3 predicted next words, or empty on error
     */
    suspend fun predict(contextText: String): List<String> = withContext(Dispatchers.IO) {
        if (contextText.isBlank()) return@withContext emptyList()

        val apiKey = prefs.getAnthropicApiKey()
        if (apiKey.isNullOrBlank()) return@withContext emptyList()

        val startTime = System.currentTimeMillis()

        try {
            val trimmedContext = if (contextText.length > CONTEXT_CHARS) {
                contextText.takeLast(CONTEXT_CHARS)
            } else {
                contextText
            }

            val jsonBody = JSONObject().apply {
                put("model", "claude-haiku-4-5-20251001")
                put("max_tokens", 30)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", "Predict the 3 most likely next words the user will type. Return ONLY the 3 words separated by commas, nothing else. No explanations, no numbering.\n\nText so far: \"$trimmedContext\"")
                    })
                })
            }

            val request = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                DebugLog.e("$TAG: API error ${response.code}: $errorBody")
                return@withContext emptyList()
            }

            val responseBody = response.body?.string() ?: return@withContext emptyList()
            val json = JSONObject(responseBody)

            val text = json
                .getJSONArray("content")
                .getJSONObject(0)
                .getString("text")
                .trim()

            val words = text.split(",")
                .map { it.trim().lowercase().replace(".", "").replace("\"", "") }
                .filter { it.isNotBlank() && it.length > 1 && it.length < 20 && !it.contains(" ") }
                .take(3)

            val elapsedMs = System.currentTimeMillis() - startTime
            DebugLog.d("$TAG: Predicted $words in ${elapsedMs}ms")
            words
        } catch (e: Exception) {
            val elapsedMs = System.currentTimeMillis() - startTime
            DebugLog.e("$TAG: Failed after ${elapsedMs}ms - ${e.message}")
            emptyList()
        }
    }

    /**
     * Complete the word the user is currently typing, using context for accuracy.
     *
     * @param contextText Text before the partial word (for context)
     * @param partialWord The partial word being typed (e.g. "exper")
     * @return The completed word (e.g. "experience"), or null if no completion
     */
    suspend fun completeWord(contextText: String, partialWord: String): String? = withContext(Dispatchers.IO) {
        if (partialWord.isEmpty()) return@withContext null

        val apiKey = prefs.getAnthropicApiKey()
        if (apiKey.isNullOrBlank()) return@withContext null

        val startTime = System.currentTimeMillis()

        try {
            val trimmedContext = if (contextText.length > CONTEXT_CHARS) {
                contextText.takeLast(CONTEXT_CHARS)
            } else {
                contextText
            }

            val jsonBody = JSONObject().apply {
                put("model", "claude-haiku-4-5-20251001")
                put("max_tokens", 15)
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", "Complete this partial word. Context: \"$trimmedContext\" Partial word: \"$partialWord\". Return ONLY the single completed word, nothing else.")
                    })
                })
            }

            val request = Request.Builder()
                .url("https://api.anthropic.com/v1/messages")
                .addHeader("x-api-key", apiKey)
                .addHeader("anthropic-version", "2023-06-01")
                .addHeader("Content-Type", "application/json")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                response.body?.close()
                return@withContext null
            }

            val responseBody = response.body?.string() ?: return@withContext null
            val json = JSONObject(responseBody)

            val word = json
                .getJSONArray("content")
                .getJSONObject(0)
                .getString("text")
                .trim()
                .lowercase()
                .replace(".", "")
                .replace("\"", "")
                .split(" ").first()  // Take only first word if multiple returned

            if (word.isBlank() || word.length > 30 || !word.startsWith(partialWord.lowercase())
                || word == partialWord.lowercase()) {
                // Don't return the same word user already typed - that's useless
                return@withContext null
            }

            val elapsedMs = System.currentTimeMillis() - startTime
            DebugLog.d("$TAG: Completed '$partialWord' → '$word' in ${elapsedMs}ms")
            word
        } catch (e: Exception) {
            val elapsedMs = System.currentTimeMillis() - startTime
            DebugLog.e("$TAG: completeWord failed after ${elapsedMs}ms - ${e.message}")
            null
        }
    }


    fun isAvailable(): Boolean = prefs.hasAnthropicApiKey()
}
