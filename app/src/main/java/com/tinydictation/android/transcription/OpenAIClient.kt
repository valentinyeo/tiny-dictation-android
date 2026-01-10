package com.tinydictation.android.transcription

import android.util.Log
import com.tinydictation.android.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * REST client for OpenAI's transcription API.
 *
 * OpenAI provides transcription models including:
 * - gpt-4o-transcribe (best accuracy, improved WER)
 * - gpt-4o-mini-transcribe (half price)
 * - whisper-1 (original Whisper V2)
 */
class OpenAIClient {

    companion object {
        private const val TAG = "TinyDictation"
        private const val BASE_URL = "https://api.openai.com/v1/audio/transcriptions"
        private const val CONNECT_TIMEOUT_SECONDS = 30L
        private const val READ_TIMEOUT_SECONDS = 120L  // Longer for large audio files
        private const val WRITE_TIMEOUT_SECONDS = 60L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * Transcribes audio using OpenAI's transcription API.
     *
     * @param apiKey OpenAI API key
     * @param audioBytes WAV-formatted audio data
     * @param model Model ID (e.g., "gpt-4o-transcribe")
     * @param language Optional language code (null for auto-detect)
     * @return Result containing transcription text or error
     */
    suspend fun transcribe(
        apiKey: String,
        audioBytes: ByteArray,
        model: String,
        language: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            Log.e(TAG, "OpenAIClient: API key is blank")
            DebugLog.e("OpenAIClient: API key is blank")
            return@withContext Result.failure(IllegalArgumentException("API key is required"))
        }

        if (audioBytes.isEmpty()) {
            Log.e(TAG, "OpenAIClient: Audio data is empty")
            DebugLog.e("OpenAIClient: Audio data is empty")
            return@withContext Result.failure(IllegalArgumentException("Audio data is required"))
        }

        Log.i(TAG, "OpenAIClient: Transcribing ${audioBytes.size} bytes with model $model")
        DebugLog.i("OpenAIClient: Transcribing ${audioBytes.size} bytes, model=$model, language=$language")

        try {
            // Build multipart form data
            val multipartBuilder = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart(
                    "file",
                    "audio.wav",
                    audioBytes.toRequestBody("audio/wav".toMediaType())
                )
                .addFormDataPart("model", model)

            // For whisper-1, we can specify response_format
            // For gpt-4o models, only json and text are supported
            if (model == "whisper-1") {
                multipartBuilder.addFormDataPart("response_format", "json")
            }

            // Only add language if specified (otherwise auto-detect)
            if (!language.isNullOrBlank()) {
                multipartBuilder.addFormDataPart("language", language)
            }

            val requestBody = multipartBuilder.build()

            val request = Request.Builder()
                .url(BASE_URL)
                .header("Authorization", "Bearer $apiKey")
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val errorMessage = parseErrorMessage(responseBody, response.code)
                Log.e(TAG, "OpenAIClient: API error ${response.code}: $errorMessage")
                DebugLog.e("OpenAIClient: API error ${response.code}: $errorMessage")
                return@withContext Result.failure(Exception(errorMessage))
            }

            val transcript = parseTranscript(responseBody)
            Log.i(TAG, "OpenAIClient: Transcription successful: ${transcript.take(50)}...")
            DebugLog.i("OpenAIClient: Transcription successful (${transcript.length} chars)")
            Result.success(transcript)
        } catch (e: Exception) {
            Log.e(TAG, "OpenAIClient: Request failed", e)
            DebugLog.e("OpenAIClient: Request failed - ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Parses the transcription text from the API response.
     * OpenAI returns either JSON with "text" field or plain text.
     */
    private fun parseTranscript(responseBody: String): String {
        return try {
            val json = JSONObject(responseBody)
            json.optString("text", responseBody)
        } catch (e: Exception) {
            // If not JSON, return as plain text
            responseBody
        }
    }

    /**
     * Parses an error message from the API response.
     */
    private fun parseErrorMessage(responseBody: String, statusCode: Int): String {
        return try {
            val json = JSONObject(responseBody)
            val error = json.optJSONObject("error")
            error?.optString("message") ?: "API error $statusCode"
        } catch (e: Exception) {
            "API error $statusCode: $responseBody"
        }
    }
}
