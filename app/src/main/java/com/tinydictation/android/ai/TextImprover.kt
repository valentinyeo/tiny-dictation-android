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
 * AI-powered text improvement using Anthropic's Claude API.
 *
 * Corrects grammar, spelling, and punctuation only.
 * Does NOT rephrase, shorten, or restructure text.
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
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Improve the given text by fixing grammar, spelling, and punctuation.
     *
     * @param text The text to improve
     * @return Result containing improved text or error
     */
    suspend fun improve(text: String): Result<String> = withContext(Dispatchers.IO) {
        if (text.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("No text to improve"))
        }

        val startTime = System.currentTimeMillis()
        DebugLog.i("$TAG: Improving text (${text.length} chars)")

        try {
            // Prefer Anthropic API, fall back to Groq
            val improvedText = if (prefs.hasAnthropicApiKey()) {
                val apiKey = prefs.getAnthropicApiKey()!!
                callAnthropicApi(apiKey, text)
            } else {
                val apiKey = prefs.getGroqApiKey()
                if (apiKey.isNullOrBlank()) {
                    return@withContext Result.failure(
                        IllegalStateException("No API key configured. Set Anthropic or Groq API key in Settings.")
                    )
                }
                callGroqApi(apiKey, text)
            }

            val elapsedMs = System.currentTimeMillis() - startTime
            DebugLog.i("$TAG: Text improved in ${elapsedMs}ms (${text.length} -> ${improvedText.length} chars)")
            Result.success(improvedText)
        } catch (e: Exception) {
            val elapsedMs = System.currentTimeMillis() - startTime
            DebugLog.e("$TAG: Failed after ${elapsedMs}ms - ${e.message}")
            Result.failure(e)
        }
    }

    private fun callAnthropicApi(apiKey: String, text: String): String {
        val systemPrompt = """You are a grammar and spelling correction assistant. Fix ONLY grammar, spelling, and punctuation errors.

Rules:
1. Fix typos, misspellings, and grammatical errors
2. Fix punctuation (missing periods, commas, capitalization)
3. Do NOT rephrase, restructure, or change word choices
4. Do NOT shorten or lengthen the text
5. Do NOT add bullet points, formatting, or structure changes
6. Do NOT change the tone, style, or voice
7. NEVER modify technical terms, shell commands, CLI flags, programming jargon, brand names, or proper nouns
8. NEVER modify URLs, links, or web addresses
9. Keep the same language as the input (if German, respond in German, etc.)
10. Your output must be nearly identical to the input — only fix clear errors
11. If the text has no errors, return it unchanged
12. Return ONLY the corrected text, no explanations or comments"""

        val jsonBody = JSONObject().apply {
            put("model", "claude-opus-4-5-20251101")
            put("max_tokens", 2048)
            put("system", systemPrompt)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "Fix only grammar, spelling, and punctuation in this text:\n\n$text")
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
            throw Exception("Anthropic API error ${response.code}: $errorBody")
        }

        val responseBody = response.body?.string() ?: throw Exception("Empty response from Anthropic API")
        val json = JSONObject(responseBody)

        return json
            .getJSONArray("content")
            .getJSONObject(0)
            .getString("text")
            .trim()
    }

    private fun callGroqApi(apiKey: String, text: String): String {
        val systemPrompt = """You are a grammar and spelling correction assistant. Fix ONLY grammar, spelling, and punctuation errors.

Rules:
1. Fix typos, misspellings, and grammatical errors
2. Fix punctuation (missing periods, commas, capitalization)
3. Do NOT rephrase, restructure, or change word choices
4. Do NOT shorten or lengthen the text
5. Do NOT add bullet points, formatting, or structure changes
6. Do NOT change the tone, style, or voice
7. NEVER modify technical terms, shell commands, CLI flags, programming jargon, brand names, or proper nouns
8. NEVER modify URLs, links, or web addresses
9. Keep the same language as the input (if German, respond in German, etc.)
10. Your output must be nearly identical to the input — only fix clear errors
11. If the text has no errors, return it unchanged
12. Return ONLY the corrected text, no explanations or comments"""

        val jsonBody = JSONObject().apply {
            put("model", "llama-3.1-8b-instant")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "Fix only grammar, spelling, and punctuation in this text:\n\n$text")
                })
            })
            put("temperature", 0.1)
            put("max_tokens", 2048)
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
