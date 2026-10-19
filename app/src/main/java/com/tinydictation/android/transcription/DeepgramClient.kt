package com.tinydictation.android.transcription

import android.util.Log
import com.tinydictation.android.util.DebugLog
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * OkHttp WebSocket client for Deepgram's streaming speech-to-text API.
 *
 * Connects to Deepgram's WebSocket endpoint and streams audio for real-time
 * transcription. Provides callbacks for interim and final transcription results.
 */
class DeepgramClient {

    companion object {
        private const val TAG = "TinyDictation"

        /** Base WebSocket URL for Deepgram's streaming API */
        private const val BASE_URL = "wss://api.deepgram.com/v1/listen"

        /** Base query parameters for transcription - optimized for low latency */
        private const val BASE_PARAMS =
            "encoding=linear16" +
            "&sample_rate=16000" +
            "&channels=1"

        /**
         * Builds params with specified language, model, and mode.
         *
         * @param terminalMode When true, disables smart_format, punctuate, and interim_results.
         *   Terminal apps (Termius, Termux) don't support deleteSurroundingText, so interim
         *   results cause duplication. Disabling formatting gives raw transcription output.
         * @param keywords List of keywords to boost recognition for
         * @param replacements List of find/replace pairs for word substitution
         * @param numerals When true, converts spoken numbers to digits
         * @param dictation When true, processes spoken punctuation commands (English only)
         */
        fun buildParams(
            language: String = "en",
            model: String = "nova-2",
            terminalMode: Boolean = false,
            keywords: List<String> = emptyList(),
            replacements: List<Pair<String, String>> = emptyList(),
            numerals: Boolean = false,
            dictation: Boolean = false
        ): String {
            val params = StringBuilder()
            params.append("model=$model")
            params.append("&$BASE_PARAMS")
            if (terminalMode) {
                // Terminal mode: no formatting, no interims, only raw final results
                params.append("&smart_format=false")
                params.append("&punctuate=false")
                params.append("&interim_results=false")
                params.append("&endpointing=150")
            } else {
                params.append("&smart_format=true")
                params.append("&punctuate=true")
                params.append("&interim_results=true")
                params.append("&endpointing=150")
            }
            // Nova-3 can auto-detect language if language is "auto"
            if (language != "auto") {
                params.append("&language=$language")
            }

            // Deepgram features
            if (numerals) {
                params.append("&numerals=true")
            }
            if (dictation) {
                params.append("&dictation=true")
            }
            for (keyword in keywords) {
                val encoded = URLEncoder.encode(keyword, "UTF-8")
                params.append("&keywords=$encoded")
            }
            for ((find, replace) in replacements) {
                val encodedFind = URLEncoder.encode(find, "UTF-8")
                val encodedReplace = URLEncoder.encode(replace, "UTF-8")
                params.append("&replace=$encodedFind:$encodedReplace")
            }

            return params.toString()
        }

        /** Connection timeout in seconds */
        private const val CONNECT_TIMEOUT_SECONDS = 10L

        /** Read timeout in seconds */
        private const val READ_TIMEOUT_SECONDS = 30L

        /** Write timeout in seconds */
        private const val WRITE_TIMEOUT_SECONDS = 10L
    }

    private var webSocket: WebSocket? = null
    private var okHttpClient: OkHttpClient? = null
    private var isConnected = false
    private var audioBytesSent = 0
    private var audioChunksSent = 0

    // Callbacks
    private var onInterimCallback: ((String) -> Unit)? = null
    private var onFinalCallback: ((String) -> Unit)? = null
    private var onErrorCallback: ((TranscriptionError) -> Unit)? = null

    /**
     * Connects to Deepgram's WebSocket API.
     *
     * @param apiKey Deepgram API key for authentication
     * @param onInterim Callback for interim (partial) transcription results
     * @param onFinal Callback for final transcription results
     * @param onError Callback for error messages
     * @param language Language code ("en" for English, "de" for German, "auto" for auto-detect)
     * @param model Deepgram model ("nova-3", "nova-2", "enhanced", "base")
     * @param terminalMode When true, disables formatting for terminal apps
     * @param keywords List of keywords to boost recognition for
     * @param replacements List of find/replace pairs for word substitution
     * @param numerals When true, converts spoken numbers to digits
     * @param dictation When true, processes spoken punctuation commands
     */
    fun connect(
        apiKey: String,
        onInterim: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (TranscriptionError) -> Unit,
        language: String = "en",
        model: String = "nova-2",
        terminalMode: Boolean = false,
        keywords: List<String> = emptyList(),
        replacements: List<Pair<String, String>> = emptyList(),
        numerals: Boolean = false,
        dictation: Boolean = false
    ) {
        // Force-close any existing connection to avoid race conditions
        // This makes connect() idempotent and prevents "Already connected" errors
        // when rapid start/stop cycles occur (e.g., pause mode toggling)
        if (isConnected || webSocket != null) {
            Log.d(TAG, "DeepgramClient: Cleaning up existing connection before reconnecting")
            DebugLog.d("DeepgramClient: Cleaning up existing connection")
            forceCleanup()
        }

        if (apiKey.isBlank()) {
            Log.e(TAG, "Cannot connect - API key is blank")
            DebugLog.e("DeepgramClient: Cannot connect - API key is blank")
            onError(TranscriptionError("API key is required"))
            return
        }

        Log.i(TAG, "Connecting to Deepgram WebSocket")
        DebugLog.i("DeepgramClient: Connecting to Deepgram WebSocket")
        onInterimCallback = onInterim
        onFinalCallback = onFinal
        onErrorCallback = onError
        audioBytesSent = 0
        audioChunksSent = 0

        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build()
            okHttpClient = client

            val params = buildParams(language, model, terminalMode, keywords, replacements, numerals, dictation)
            val url = "$BASE_URL?$params"
            Log.d(TAG, "Deepgram URL: $url (language=$language, model=$model)")

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Token $apiKey")
                .build()

            webSocket = client.newWebSocket(request, createWebSocketListener())
            DebugLog.d("DeepgramClient: WebSocket request initiated, waiting for connection...")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create WebSocket connection", e)
            DebugLog.e("DeepgramClient: Failed to create WebSocket - ${e.message}")
            onError(TranscriptionError("Failed to connect: ${e.message}"))
        }
    }

    /**
     * Creates the WebSocket listener that handles messages from Deepgram.
     */
    private fun createWebSocketListener(): WebSocketListener {
        return object : WebSocketListener() {
            private val errorReported = AtomicBoolean(false)

            private fun reportError(error: TranscriptionError?) {
                if (error != null && errorReported.compareAndSet(false, true)) {
                    onErrorCallback?.invoke(error)
                }
            }

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "Deepgram WebSocket connection opened")
                DebugLog.i("DeepgramClient: WebSocket connection opened")
                isConnected = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.length > 100) {
                    Log.d(TAG, "Received Deepgram message: ${text.take(100)}...")
                } else {
                    Log.d(TAG, "Received Deepgram message: $text")
                }
                DebugLog.d("DeepgramClient: Received message (${text.length} chars)")
                parseAndDispatchMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.d(TAG, "Received Deepgram binary message")
                // Deepgram sends text messages, not binary
                // But handle just in case
                parseAndDispatchMessage(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Deepgram WebSocket closing: code=$code, reason=$reason")
                DebugLog.d("DeepgramClient: WebSocket closing - code=$code")
                webSocket.close(1000, null)
                isConnected = false
                reportError(mapDeepgramError(closeCode = code, causeMessage = reason))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Deepgram WebSocket closed: code=$code, reason=$reason")
                DebugLog.i("DeepgramClient: WebSocket closed")
                isConnected = false
                reportError(mapDeepgramError(closeCode = code, causeMessage = reason))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code
                val dgError = response?.header("dg-error")
                val requestId = response?.header("dg-request-id")
                val details = "code=$code, dg-error=$dgError, dg-request-id=$requestId"
                Log.e(TAG, "Deepgram WebSocket failure: $details", t)
                DebugLog.e("DeepgramClient: WebSocket failure - $details, ${t.message}")
                isConnected = false
                reportError(mapDeepgramError(code, dgError, causeMessage = t.message))
            }
        }
    }

    /**
     * Parses a JSON message from Deepgram and dispatches to appropriate callback.
     */
    private fun parseAndDispatchMessage(jsonString: String) {
        try {
            if (jsonString.isBlank()) {
                Log.w(TAG, "Received empty message from Deepgram")
                return
            }

            val json = JSONObject(jsonString)

            // Check for error messages
            if (json.has("error")) {
                val error = json.optString("error", "Unknown error")
                Log.e(TAG, "Deepgram error: $error")
                DebugLog.e("DeepgramClient: API error - $error")
                onErrorCallback?.invoke(TranscriptionError(error))
                return
            }

            // Check for transcription results
            if (json.has("channel")) {
                val channel = json.optJSONObject("channel")
                if (channel == null) {
                    Log.w(TAG, "Channel object is null")
                    return
                }

                val alternatives = channel.optJSONArray("alternatives")
                if (alternatives != null && alternatives.length() > 0) {
                    val firstAlternative = alternatives.optJSONObject(0)
                    if (firstAlternative == null) {
                        Log.w(TAG, "First alternative is null")
                        return
                    }

                    val transcript = firstAlternative.optString("transcript", "")
                    if (transcript.isNotEmpty()) {
                        val isFinal = json.optBoolean("is_final", false)
                        Log.i(TAG, "Transcript (final=$isFinal): $transcript")
                        DebugLog.i("DeepgramClient: Transcript received - final=$isFinal, text='$transcript'")

                        if (isFinal) {
                            onFinalCallback?.invoke(transcript)
                        } else {
                            onInterimCallback?.invoke(transcript)
                        }
                    } else {
                        Log.d(TAG, "Received empty transcript")
                    }
                }
            }

            // Handle metadata messages (optional - for debugging)
            if (json.has("type")) {
                val type = json.optString("type")
                when (type) {
                    "Metadata" -> {
                        Log.d(TAG, "Received Deepgram metadata")
                        // Connection metadata received
                    }
                    "UtteranceEnd" -> {
                        Log.d(TAG, "Utterance end detected")
                        // Utterance ended (based on endpointing)
                    }
                }
            }
        } catch (e: org.json.JSONException) {
            val errorMessage = "Invalid JSON from Deepgram: ${e.message}"
            Log.e(TAG, errorMessage, e)
            onErrorCallback?.invoke(TranscriptionError(errorMessage))
        } catch (e: Exception) {
            val errorMessage = "Failed to parse Deepgram response: ${e.message}"
            Log.e(TAG, errorMessage, e)
            onErrorCallback?.invoke(TranscriptionError(errorMessage))
        }
    }

    /**
     * Sends audio data to Deepgram for transcription.
     * Audio should be PCM 16-bit, 16kHz, mono, in little-endian byte order.
     *
     * @param audioBytes Raw audio bytes to send
     * @return true if the data was sent, false if not connected
     */
    fun sendAudio(audioBytes: ByteArray): Boolean {
        val socket = webSocket
        if (!isConnected || socket == null) {
            Log.d(TAG, "Cannot send audio - not connected (isConnected=$isConnected, socket=$socket)")
            DebugLog.d("DeepgramClient: Cannot send audio - not connected")
            return false
        }

        return try {
            socket.send(audioBytes.toByteString())
            audioBytesSent += audioBytes.size
            audioChunksSent++

            // Log periodically (every 50 chunks = ~1 second at 20ms chunks)
            if (audioChunksSent % 50 == 0) {
                Log.d(TAG, "DeepgramClient: Sent $audioChunksSent chunks ($audioBytesSent bytes total)")
                DebugLog.d("DeepgramClient: Sent $audioChunksSent chunks")
            }

            // Log first chunk to confirm sending works
            if (audioChunksSent == 1) {
                Log.i(TAG, "DeepgramClient: First audio chunk sent successfully (${audioBytes.size} bytes)")
                DebugLog.i("DeepgramClient: First audio chunk sent (${audioBytes.size} bytes)")
            }

            true
        } catch (e: IllegalStateException) {
            Log.e(TAG, "WebSocket in invalid state", e)
            DebugLog.e("DeepgramClient: WebSocket invalid state - ${e.message}")
            onErrorCallback?.invoke(TranscriptionError("Connection error: ${e.message}"))
            isConnected = false
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send audio", e)
            DebugLog.e("DeepgramClient: Failed to send audio - ${e.message}")
            onErrorCallback?.invoke(TranscriptionError("Failed to send audio: ${e.message}"))
            false
        }
    }

    /**
     * Force cleanup of existing connection without waiting for callbacks.
     * Used internally to ensure clean state before reconnecting.
     */
    private fun forceCleanup() {
        isConnected = false

        try {
            webSocket?.cancel()  // Immediate cancellation, no close handshake
        } catch (e: Exception) {
            Log.d(TAG, "Error during force cleanup: ${e.message}")
        }
        webSocket = null

        try {
            okHttpClient?.dispatcher?.executorService?.shutdown()
            okHttpClient?.connectionPool?.evictAll()
        } catch (e: Exception) {
            Log.d(TAG, "Error cleaning up OkHttp client: ${e.message}")
        }
        okHttpClient = null

        // Clear callbacks from previous connection
        onInterimCallback = null
        onFinalCallback = null
        onErrorCallback = null
    }

    /**
     * Sends a Finalize message to Deepgram, requesting it to immediately
     * generate a final transcript for any buffered audio.
     * Call this before closing to avoid dropping the last few words.
     *
     * @return true if the message was sent successfully
     */
    fun sendFinalize(): Boolean {
        val socket = webSocket
        if (!isConnected || socket == null) {
            Log.d(TAG, "DeepgramClient: Cannot send Finalize - not connected")
            return false
        }

        return try {
            val message = "{\"type\": \"Finalize\"}"
            socket.send(message)
            Log.i(TAG, "DeepgramClient: Sent Finalize message to flush buffer")
            DebugLog.i("DeepgramClient: Sent Finalize to flush pending audio")
            true
        } catch (e: Exception) {
            Log.e(TAG, "DeepgramClient: Failed to send Finalize", e)
            DebugLog.e("DeepgramClient: Failed to send Finalize - ${e.message}")
            false
        }
    }

    /**
     * Closes the WebSocket connection to Deepgram.
     * Sends a close frame and cleans up resources.
     */
    fun close() {
        Log.i(TAG, "Closing Deepgram WebSocket connection (sent $audioChunksSent chunks, $audioBytesSent bytes)")
        DebugLog.i("DeepgramClient: Closing WebSocket connection (sent $audioChunksSent chunks)")
        isConnected = false

        val socket = webSocket
        val client = okHttpClient

        try {
            // Send close frame to Deepgram
            socket?.close(1000, "Client closing")
        } catch (e: Exception) {
            Log.e(TAG, "Error during WebSocket close", e)
            // Ignore errors during close
        }

        webSocket = null

        try {
            // Shutdown the OkHttp client's connection pool
            client?.dispatcher?.executorService?.shutdown()
            client?.connectionPool?.evictAll()
        } catch (e: Exception) {
            Log.e(TAG, "Error shutting down OkHttp client", e)
            // Ignore errors during shutdown
        }

        okHttpClient = null

        // Clear callbacks
        onInterimCallback = null
        onFinalCallback = null
        onErrorCallback = null
        Log.i(TAG, "Deepgram connection closed")
    }

    /**
     * Returns whether the client is currently connected to Deepgram.
     */
    fun isConnected(): Boolean = isConnected

    /**
     * Sends an empty audio frame to keep the connection alive.
     * Call this periodically if there are gaps in audio streaming.
     */
    fun sendKeepAlive(): Boolean {
        return sendAudio(ByteArray(0))
    }
}
