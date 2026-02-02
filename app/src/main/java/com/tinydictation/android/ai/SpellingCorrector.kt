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
 * AI-powered spelling and spacing correction using Groq's fast LLM API.
 *
 * Unlike TextImprover, this class ONLY fixes spelling and spacing errors.
 * It does not shorten, restructure, or rephrase the text.
 *
 * Designed to be called automatically at sentence boundaries (after . ? !)
 */
class SpellingCorrector(private val context: Context) {

    companion object {
        private const val TAG = "SpellingCorrector"
    }

    private val prefs = PreferenceManager(context)

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS) // Shorter timeout for quick corrections
            .build()
    }

    /**
     * Correct spelling and spacing errors in the given text.
     *
     * @param text The text to correct (typically a single sentence)
     * @return Result containing corrected text or error
     */
    suspend fun correct(text: String): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = prefs.getGroqApiKey()

        if (apiKey.isNullOrBlank()) {
            DebugLog.w("$TAG: No Groq API key configured")
            return@withContext Result.failure(
                IllegalStateException("Groq API key not configured")
            )
        }

        if (text.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("No text to correct"))
        }

        // Skip very short text (likely not worth API call)
        if (text.length < 5) {
            return@withContext Result.success(text)
        }

        val startTime = System.currentTimeMillis()
        DebugLog.d("$TAG: Correcting text (${text.length} chars): '${text.take(50)}...'")

        try {
            val correctedText = callGroqApi(apiKey, text)
            val elapsedMs = System.currentTimeMillis() - startTime

            // Check if anything actually changed
            if (correctedText == text) {
                DebugLog.d("$TAG: No corrections needed (${elapsedMs}ms)")
            } else {
                DebugLog.i("$TAG: Text corrected in ${elapsedMs}ms: '$text' -> '$correctedText'")
            }

            Result.success(correctedText)
        } catch (e: Exception) {
            val elapsedMs = System.currentTimeMillis() - startTime
            DebugLog.e("$TAG: Failed after ${elapsedMs}ms - ${e.message}")
            Result.failure(e)
        }
    }

    private fun callGroqApi(apiKey: String, text: String): String {
        val systemPrompt = """You are a spelling and spacing correction assistant. Your ONLY job is to fix typos and missing spaces.

STRICT RULES:
1. Fix spelling errors (typos, misspellings)
2. Add missing spaces between words
3. DO NOT change any words - only fix how they're spelled
4. DO NOT shorten, restructure, or rephrase anything
5. DO NOT add or remove punctuation
6. DO NOT change capitalization unless it's clearly wrong
7. Keep the EXACT same meaning and word count (except for split words)
8. If the text looks correct, return it unchanged
9. Return ONLY the corrected text, nothing else

Examples:
- "I wnet to teh store" -> "I went to the store"
- "thisis atest" -> "this is a test"
- "Hello world" -> "Hello world" (no change needed)"""

        val userPrompt = text

        val jsonBody = JSONObject().apply {
            put("model", "llama-3.1-8b-instant")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userPrompt)
                })
            })
            put("temperature", 0.1) // Very low temperature for deterministic output
            put("max_tokens", 512) // Sentences are usually short
        }

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            throw Exception("Groq API error ${response.code}: $errorBody")
        }

        val responseBody = response.body?.string() ?: throw Exception("Empty response from API")
        val json = JSONObject(responseBody)

        return json
            .getJSONArray("choices")
            .getJSONObject(0)
            .getJSONObject("message")
            .getString("content")
            .trim()
    }
}
