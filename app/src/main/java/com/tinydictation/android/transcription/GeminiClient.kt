package com.tinydictation.android.transcription

import android.util.Base64
import android.util.Log
import com.tinydictation.android.util.DebugLog
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * REST client for Google's Gemini API for audio transcription.
 *
 * Uses the Gemini 2.5 Flash model to transcribe audio.
 * Audio is sent as inline base64-encoded data.
 */
class GeminiClient {

    companion object {
        private const val TAG = "TinyDictation"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val MODEL = "gemini-2.5-flash"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Transcribes audio using Gemini's API.
     *
     * @param apiKey Gemini API key
     * @param audioBytes WAV audio bytes
     * @param language Language hint (not strictly required, Gemini auto-detects)
     * @return Result containing transcript text or error
     */
    suspend fun transcribe(
        apiKey: String,
        audioBytes: ByteArray,
        language: String? = null
    ): Result<String> {
        return try {
            Log.i(TAG, "GeminiClient: Starting transcription (${audioBytes.size} bytes)")
            DebugLog.i("GeminiClient: Transcribing ${audioBytes.size} bytes")

            // Base64 encode the audio
            val audioBase64 = Base64.encodeToString(audioBytes, Base64.NO_WRAP)

            // Build the prompt
            val prompt = if (language.isNullOrBlank() || language == "auto") {
                "Transcribe this audio accurately. Output only the transcribed text, nothing else."
            } else {
                "Transcribe this audio in $language. Output only the transcribed text, nothing else."
            }

            // Build request body
            val requestBody = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            // Text prompt
                            put(JSONObject().apply {
                                put("text", prompt)
                            })
                            // Inline audio data
                            put(JSONObject().apply {
                                put("inlineData", JSONObject().apply {
                                    put("mimeType", "audio/wav")
                                    put("data", audioBase64)
                                })
                            })
                        })
                    })
                })
            }

            val url = "$BASE_URL/$MODEL:generateContent?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(requestBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            DebugLog.d("GeminiClient: Sending request to $MODEL")

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string()

            if (!response.isSuccessful) {
                val errorMsg = parseError(responseBody) ?: "HTTP ${response.code}"
                Log.e(TAG, "GeminiClient: Request failed - $errorMsg")
                DebugLog.e("GeminiClient: Error - $errorMsg")
                return Result.failure(Exception(errorMsg))
            }

            if (responseBody == null) {
                return Result.failure(Exception("Empty response"))
            }

            val transcript = parseTranscript(responseBody)
            if (transcript.isNullOrBlank()) {
                Log.w(TAG, "GeminiClient: Empty transcript")
                DebugLog.w("GeminiClient: Received empty transcript")
                return Result.success("")
            }

            Log.i(TAG, "GeminiClient: Transcription complete: ${transcript.take(50)}...")
            DebugLog.i("GeminiClient: Got transcript (${transcript.length} chars)")
            Result.success(transcript)
        } catch (e: Exception) {
            Log.e(TAG, "GeminiClient: Transcription failed", e)
            DebugLog.e("GeminiClient: Error - ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Parses the transcript from Gemini's response.
     */
    private fun parseTranscript(responseBody: String): String? {
        return try {
            val json = JSONObject(responseBody)
            val candidates = json.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val candidate = candidates.getJSONObject(0)
                val content = candidate.optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                if (parts != null && parts.length() > 0) {
                    val textPart = parts.getJSONObject(0)
                    return textPart.optString("text", "").trim()
                }
            }
            null
        } catch (e: Exception) {
            Log.e(TAG, "GeminiClient: Failed to parse response", e)
            null
        }
    }

    /**
     * Parses error message from Gemini's error response.
     */
    private fun parseError(responseBody: String?): String? {
        if (responseBody == null) return null
        return try {
            val json = JSONObject(responseBody)
            val error = json.optJSONObject("error")
            error?.optString("message")
        } catch (e: Exception) {
            null
        }
    }
}
