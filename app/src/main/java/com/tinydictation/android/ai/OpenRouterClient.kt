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
 * Client for OpenRouter API to access models like Claude Opus.
 *
 * Used for smart text rewriting on double-tap space. Claude Opus excels at
 * understanding garbled text and inferring what the user meant to type.
 *
 * OpenRouter API: https://openrouter.ai/docs
 */
class OpenRouterClient(private val context: Context) {

    companion object {
        private const val TAG = "OpenRouterClient"
        private const val BASE_URL = "https://openrouter.ai/api/v1/chat/completions"

        // Claude Opus 4.5 - best for understanding typo'd text
        const val MODEL_CLAUDE_OPUS = "anthropic/claude-opus-4-5-20251101"

        // Fallback models (faster, cheaper)
        const val MODEL_CLAUDE_SONNET = "anthropic/claude-sonnet-4-20250514"
        const val MODEL_CLAUDE_HAIKU = "anthropic/claude-haiku-4-5-20251101"
    }

    private val prefs = PreferenceManager(context)

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS) // Opus can take a bit longer
            .build()
    }

    /**
     * Rewrite garbled text using Claude's pattern recognition.
     *
     * @param text The garbled/typo'd text to rewrite
     * @param model Model ID (defaults to Claude Opus)
     * @return Result containing rewritten text or error
     */
    suspend fun rewriteText(
        text: String,
        model: String = MODEL_CLAUDE_OPUS
    ): Result<String> = withContext(Dispatchers.IO) {
        val apiKey = prefs.getOpenRouterApiKey()

        if (apiKey.isNullOrBlank()) {
            DebugLog.w("$TAG: No OpenRouter API key configured")
            return@withContext Result.failure(
                IllegalStateException("OpenRouter API key not configured. Set it in Settings to use smart rewrite.")
            )
        }

        if (text.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("No text to rewrite"))
        }

        val startTime = System.currentTimeMillis()
        DebugLog.i("$TAG: Rewriting text (${text.length} chars) with $model")

        try {
            val rewritten = callApi(apiKey, text, model)
            val elapsedMs = System.currentTimeMillis() - startTime
            DebugLog.i("$TAG: Rewritten in ${elapsedMs}ms (${text.length} -> ${rewritten.length} chars)")
            Result.success(rewritten)
        } catch (e: Exception) {
            val elapsedMs = System.currentTimeMillis() - startTime
            DebugLog.e("$TAG: Failed after ${elapsedMs}ms - ${e.message}")
            Result.failure(e)
        }
    }

    private fun callApi(apiKey: String, text: String, model: String): String {
        val systemPrompt = """You are a text reconstruction assistant. Your job is to figure out what the user MEANT to type based on their garbled input.

Humans can read "tihs snetnece" and understand it means "this sentence". You can too. Use pattern recognition to infer intended words from typos.

Rules:
1. Reconstruct what the user intended to type
2. Fix all typos, misspellings, and keyboard mistakes
3. Fix word boundaries (stuck words like "iwant" -> "I want")
4. Preserve the meaning and casual tone
5. Keep the same language as input
6. Do NOT add extra text, greetings, or explanations
7. Output ONLY the corrected text

Examples:
- "i wnat to go tot he stoer" -> "I want to go to the store"
- "tihs is a tset" -> "This is a test"
- "iwill be tehre soon" -> "I will be there soon"
- "vwo analsis tool" -> "VWO analysis tool"

If the text seems already correct, return it unchanged."""

        val jsonBody = JSONObject().apply {
            put("model", model)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", text)
                })
            })
            put("temperature", 0.1) // Low temperature for deterministic output
            put("max_tokens", 1024)
        }

        val request = Request.Builder()
            .url(BASE_URL)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("HTTP-Referer", "https://tinydictation.com") // Required by OpenRouter
            .addHeader("X-Title", "Tiny Dictation Android") // Optional app identifier
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        val response = httpClient.newCall(request).execute()

        if (!response.isSuccessful) {
            val errorBody = response.body?.string() ?: "Unknown error"
            throw Exception("OpenRouter API error ${response.code}: $errorBody")
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

    /**
     * Check if OpenRouter is configured and ready to use.
     */
    fun isConfigured(): Boolean {
        return prefs.hasOpenRouterApiKey()
    }
}
