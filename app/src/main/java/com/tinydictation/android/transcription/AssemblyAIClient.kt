package com.tinydictation.android.transcription

import android.util.Log
import com.tinydictation.android.util.DebugLog
import kotlinx.coroutines.delay
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * REST client for AssemblyAI's transcription API.
 *
 * AssemblyAI uses an async approach:
 * 1. Upload audio and get upload URL
 * 2. Submit transcription request with audio URL
 * 3. Poll for completion
 * 4. Return transcript
 */
class AssemblyAIClient {

    companion object {
        private const val TAG = "TinyDictation"
        private const val BASE_URL = "https://api.assemblyai.com/v2"
        private const val UPLOAD_URL = "$BASE_URL/upload"
        private const val TRANSCRIPT_URL = "$BASE_URL/transcript"
        private const val POLL_INTERVAL_MS = 500L
        private const val MAX_POLL_ATTEMPTS = 60 // 30 seconds max wait
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Transcribes audio using AssemblyAI's API.
     *
     * @param apiKey AssemblyAI API key
     * @param audioBytes WAV audio bytes
     * @param language Language code (e.g., "en") or null for auto-detect
     * @return Result containing transcript text or error
     */
    suspend fun transcribe(
        apiKey: String,
        audioBytes: ByteArray,
        language: String? = null
    ): Result<String> {
        return try {
            Log.i(TAG, "AssemblyAIClient: Starting transcription (${audioBytes.size} bytes)")
            DebugLog.i("AssemblyAIClient: Transcribing ${audioBytes.size} bytes")

            // Step 1: Upload audio
            val uploadUrl = uploadAudio(apiKey, audioBytes)
            if (uploadUrl == null) {
                return Result.failure(Exception("Failed to upload audio"))
            }
            DebugLog.d("AssemblyAIClient: Audio uploaded, URL: ${uploadUrl.take(50)}...")

            // Step 2: Submit transcription request
            val transcriptId = submitTranscription(apiKey, uploadUrl, language)
            if (transcriptId == null) {
                return Result.failure(Exception("Failed to submit transcription"))
            }
            DebugLog.d("AssemblyAIClient: Transcription submitted, ID: $transcriptId")

            // Step 3: Poll for result
            val transcript = pollForResult(apiKey, transcriptId)
            if (transcript == null) {
                return Result.failure(Exception("Transcription failed or timed out"))
            }

            Log.i(TAG, "AssemblyAIClient: Transcription complete: ${transcript.take(50)}...")
            DebugLog.i("AssemblyAIClient: Got transcript (${transcript.length} chars)")
            Result.success(transcript)
        } catch (e: Exception) {
            Log.e(TAG, "AssemblyAIClient: Transcription failed", e)
            DebugLog.e("AssemblyAIClient: Error - ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Uploads audio bytes to AssemblyAI and returns the upload URL.
     */
    private fun uploadAudio(apiKey: String, audioBytes: ByteArray): String? {
        val requestBody = audioBytes.toRequestBody("application/octet-stream".toMediaType())

        val request = Request.Builder()
            .url(UPLOAD_URL)
            .header("Authorization", apiKey)
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            Log.e(TAG, "AssemblyAIClient: Upload failed with ${response.code}")
            DebugLog.e("AssemblyAIClient: Upload failed - ${response.code}")
            return null
        }

        val responseBody = response.body?.string() ?: return null
        val json = JSONObject(responseBody)
        return json.optString("upload_url", null)
    }

    /**
     * Submits a transcription request and returns the transcript ID.
     */
    private fun submitTranscription(
        apiKey: String,
        audioUrl: String,
        language: String?
    ): String? {
        val jsonBody = JSONObject().apply {
            put("audio_url", audioUrl)
            // AssemblyAI auto-detects language by default
            // Only set language_code if explicitly specified
            if (!language.isNullOrBlank() && language != "auto") {
                put("language_code", language)
            }
            // Enable punctuation and formatting
            put("punctuate", true)
            put("format_text", true)
        }

        val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(TRANSCRIPT_URL)
            .header("Authorization", apiKey)
            .header("Content-Type", "application/json")
            .post(requestBody)
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            Log.e(TAG, "AssemblyAIClient: Submit failed with ${response.code}")
            DebugLog.e("AssemblyAIClient: Submit failed - ${response.code}")
            return null
        }

        val responseBody = response.body?.string() ?: return null
        val json = JSONObject(responseBody)
        return json.optString("id", null)
    }

    /**
     * Polls for the transcription result until complete or error.
     */
    private suspend fun pollForResult(apiKey: String, transcriptId: String): String? {
        val pollUrl = "$TRANSCRIPT_URL/$transcriptId"

        repeat(MAX_POLL_ATTEMPTS) { attempt ->
            val request = Request.Builder()
                .url(pollUrl)
                .header("Authorization", apiKey)
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "AssemblyAIClient: Poll failed with ${response.code}")
                return null
            }

            val responseBody = response.body?.string() ?: return null
            val json = JSONObject(responseBody)
            val status = json.optString("status", "")

            when (status) {
                "completed" -> {
                    return json.optString("text", "")
                }
                "error" -> {
                    val error = json.optString("error", "Unknown error")
                    Log.e(TAG, "AssemblyAIClient: Transcription error - $error")
                    DebugLog.e("AssemblyAIClient: Transcription error - $error")
                    return null
                }
                "queued", "processing" -> {
                    // Continue polling
                    if (attempt % 10 == 0) {
                        DebugLog.d("AssemblyAIClient: Still processing... (attempt ${attempt + 1})")
                    }
                }
            }

            delay(POLL_INTERVAL_MS)
        }

        Log.e(TAG, "AssemblyAIClient: Polling timed out")
        DebugLog.e("AssemblyAIClient: Polling timed out after $MAX_POLL_ATTEMPTS attempts")
        return null
    }
}
