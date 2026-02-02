package com.tinydictation.android.correction

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
 * Stage 2: AI-powered sentence-level correction.
 *
 * Uses LLM APIs (Groq, Claude) for context-aware corrections:
 * - Spelling and grammar fixes
 * - Context-dependent word choice (by vs buy)
 * - Heavily garbled text recovery
 * - Proper formatting
 *
 * Latency target: <500ms (Groq), <1000ms (Claude)
 */
class SentenceProcessor(private val context: Context) {

    companion object {
        private const val TAG = "SentenceProcessor"
        private const val MAJOR_CHANGE_THRESHOLD = 0.4f // 40% change = major
    }

    private val prefs = PreferenceManager(context)

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS) // Keep it snappy
            .build()
    }

    /**
     * Process a sentence and return AI-corrected version.
     *
     * @param sentence The sentence to correct
     * @param config Correction configuration
     * @return Correction result, or null if no correction or error
     */
    suspend fun processSentence(
        sentence: String,
        config: CorrectionConfig = CorrectionConfig()
    ): Correction? = withContext(Dispatchers.IO) {
        if (!config.sentenceLevelEnabled) return@withContext null
        if (sentence.isBlank() || sentence.length < 5) return@withContext null

        val startTime = System.currentTimeMillis()
        DebugLog.d("$TAG: Processing sentence (${sentence.length} chars)")

        try {
            val corrected = when (config.sentenceProvider) {
                SentenceProvider.GROQ_FAST,
                SentenceProvider.GROQ_SMART -> callGroqApi(sentence, config.sentenceProvider)
                SentenceProvider.CLAUDE_HAIKU -> callClaudeApi(sentence)
            }

            val elapsed = System.currentTimeMillis() - startTime

            // Skip if no change
            if (corrected == sentence || corrected.isBlank()) {
                DebugLog.d("$TAG: No corrections needed (${elapsed}ms)")
                return@withContext null
            }

            // Calculate if this is a major change
            val isMajor = isMajorChange(sentence, corrected)

            DebugLog.i("$TAG: Corrected in ${elapsed}ms (major=$isMajor): '$sentence' -> '$corrected'")

            Correction(
                original = sentence,
                corrected = corrected,
                stage = CorrectionStage.SENTENCE,
                isMajorChange = isMajor
            )
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            DebugLog.e("$TAG: Failed after ${elapsed}ms - ${e.message}")
            null
        }
    }

    /**
     * Check if a sentence ends with sentence-ending punctuation.
     */
    fun isSentenceComplete(text: String): Boolean {
        val trimmed = text.trimEnd()
        if (trimmed.isEmpty()) return false

        val lastChar = trimmed.last()
        return lastChar in ".!?"
    }

    /**
     * Extract the last complete sentence from text.
     */
    fun extractLastSentence(text: String): String? {
        if (text.isBlank()) return null

        val trimmed = text.trimEnd()
        if (trimmed.isEmpty()) return null

        // Find the last sentence-ending punctuation
        val lastPunctIndex = trimmed.lastIndexOfAny(charArrayOf('.', '!', '?'))
        if (lastPunctIndex < 0) return null

        // Find the start of this sentence (previous punctuation or start)
        val previousPunctIndex = trimmed.lastIndexOfAny(charArrayOf('.', '!', '?'), lastPunctIndex - 1)
        val startIndex = if (previousPunctIndex >= 0) previousPunctIndex + 1 else 0

        return trimmed.substring(startIndex, lastPunctIndex + 1).trim()
    }

    /**
     * Calculate if the correction is a major change (>40% different).
     */
    private fun isMajorChange(original: String, corrected: String): Boolean {
        if (original.isEmpty()) return false

        val distance = levenshteinDistance(original, corrected)
        val changeRatio = distance.toFloat() / original.length.toFloat()

        return changeRatio > MAJOR_CHANGE_THRESHOLD
    }

    /**
     * Call Groq API for sentence correction.
     */
    private fun callGroqApi(sentence: String, provider: SentenceProvider): String {
        val apiKey = prefs.getGroqApiKey()

        if (apiKey.isNullOrBlank()) {
            throw IllegalStateException("Groq API key not configured")
        }

        val systemPrompt = """Fix this text. Correct spelling, grammar, word boundaries, and obvious errors.
Preserve the original meaning and tone. Keep casual text casual.
Output only the corrected text, nothing else.

Rules:
1. Fix typos and misspellings
2. Fix word boundaries (iwant -> I want)
3. Fix grammar errors
4. Fix context-dependent words (by -> buy if shopping context)
5. Keep the same general length
6. Keep casual language casual (don't make it formal)
7. Preserve names, technical terms, and intentional stylistic choices
8. If text seems correct, return it unchanged"""

        val jsonBody = JSONObject().apply {
            put("model", provider.modelId)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", sentence)
                })
            })
            put("temperature", 0.1) // Low temperature for deterministic output
            put("max_tokens", 512)
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

    /**
     * Call Claude API for sentence correction.
     * Note: Requires Anthropic API key (separate from Groq).
     */
    private fun callClaudeApi(sentence: String): String {
        // For now, fall back to Groq if Claude not configured
        // TODO: Add Claude API key to PreferenceManager and implement proper Claude API call
        val apiKey = prefs.getGroqApiKey()

        if (apiKey.isNullOrBlank()) {
            throw IllegalStateException("API key not configured")
        }

        // Use Groq as fallback for now
        DebugLog.w("$TAG: Claude not configured, falling back to Groq")
        return callGroqApi(sentence, SentenceProvider.GROQ_SMART)
    }

    /**
     * Calculate Levenshtein edit distance between two strings.
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        if (m == 0) return n
        if (n == 0) return m
        if (s1 == s2) return 0

        var prevRow = IntArray(n + 1) { it }
        var currRow = IntArray(n + 1)

        for (i in 1..m) {
            currRow[0] = i
            for (j in 1..n) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                currRow[j] = minOf(
                    prevRow[j] + 1,
                    currRow[j - 1] + 1,
                    prevRow[j - 1] + cost
                )
            }
            val temp = prevRow
            prevRow = currRow
            currRow = temp
        }
        return prevRow[n]
    }
}
