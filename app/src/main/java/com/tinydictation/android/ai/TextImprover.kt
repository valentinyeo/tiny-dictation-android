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
 * AI-powered text improvement using Groq's fast LLM API.
 *
 * Improves text readability by:
 * - Making text 20-30% shorter/more concise
 * - Adding structure (bullet points where appropriate)
 * - Using clear, professional, nonviolent language
 * - Preserving the user's tone and intent
 */
class TextImprover(private val context: Context) {

    companion object {
        private const val TAG = "TextImprover"
    }

    private val prefs = PreferenceManager(context)

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS) // Longer timeout for text improvement
            .build()
    }

    /**
     * Improve the given text for better readability.
     *
     * @param text The text to improve
     * @return Result containing improved text or error
     */
    suspend fun improve(text: String): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = prefs.getGroqApiKey()

        if (apiKey.isNullOrBlank()) {
            DebugLog.w("$TAG: No Groq API key configured")
            return@withContext Result.failure(
                IllegalStateException("Groq API key not configured. Set it in Settings to use text improvement.")
            )
        }

        if (text.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("No text to improve"))
        }

        val startTime = System.currentTimeMillis()
        DebugLog.i("$TAG: Improving text (${text.length} chars)")

        try {
            val improvedText = callGroqApi(apiKey, text)
            val elapsedMs = System.currentTimeMillis() - startTime
            DebugLog.i("$TAG: Text improved in ${elapsedMs}ms (${text.length} -> ${improvedText.length} chars)")
            Result.success(improvedText)
        } catch (e: Exception) {
            val elapsedMs = System.currentTimeMillis() - startTime
            DebugLog.e("$TAG: Failed after ${elapsedMs}ms - ${e.message}")
            Result.failure(e)
        }
    }

    private fun callGroqApi(apiKey: String, text: String): String {
        val systemPrompt = """You are a text improvement assistant. Your job is to make text more readable and professional.

Rules:
1. Make text 20-30% shorter - remove filler words, redundancy, and unnecessary phrases
2. Use clear, professional language while preserving the author's voice and tone
3. Use nonviolent/neutral language - avoid aggressive or confrontational phrasing
4. If text contains multiple points, consider using bullet points (use • character)
5. Fix grammar and punctuation errors
6. Preserve all key information and meaning
7. Keep the same language as the input (if German, respond in German, etc.)
8. Do NOT add greetings, sign-offs, or any text not in the original
9. Return ONLY the improved text, no explanations or comments

Output the improved text directly, nothing else."""

        val userPrompt = """Improve this text for better readability:

$text"""

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
            put("temperature", 0.3) // Low temperature for consistent output
            put("max_tokens", 2048) // Allow for longer texts
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
