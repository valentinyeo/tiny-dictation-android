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
import java.util.concurrent.TimeUnit

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

        /** Default query parameters for transcription */
        private const val DEFAULT_PARAMS =
            "model=nova-2" +
            "&language=en" +
            "&smart_format=true" +
            "&punctuate=true" +
            "&interim_results=true" +
            "&endpointing=300"

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
    private var onErrorCallback: ((String) -> Unit)? = null

    /**
     * Connects to Deepgram's WebSocket API.
     *
     * @param apiKey Deepgram API key for authentication
     * @param onInterim Callback for interim (partial) transcription results
     * @param onFinal Callback for final transcription results
     * @param onError Callback for error messages
     * @param customParams Optional custom query parameters (overrides defaults)
     */
    fun connect(
        apiKey: String,
        onInterim: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
        customParams: String? = null
    ) {
        if (isConnected) {
            Log.d(TAG, "DeepgramClient already connected")
            DebugLog.d("DeepgramClient: Already connected")
            onError("Already connected")
            return
        }

        if (apiKey.isBlank()) {
            Log.e(TAG, "Cannot connect - API key is blank")
            DebugLog.e("DeepgramClient: Cannot connect - API key is blank")
            onError("API key is required")
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

            val params = customParams ?: DEFAULT_PARAMS
            val url = "$BASE_URL?$params"
            Log.d(TAG, "Deepgram URL: $url")

            val request = Request.Builder()
                .url(url)
                .header("Authorization", "Token $apiKey")
                .build()

            webSocket = client.newWebSocket(request, createWebSocketListener())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create WebSocket connection", e)
            DebugLog.e("DeepgramClient: Failed to create WebSocket - ${e.message}")
            onError("Failed to connect: ${e.message}")
        }
    }

    /**
     * Creates the WebSocket listener that handles messages from Deepgram.
     */
    private fun createWebSocketListener(): WebSocketListener {
        return object : WebSocketListener() {
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
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "Deepgram WebSocket closed: code=$code, reason=$reason")
                DebugLog.i("DeepgramClient: WebSocket closed")
                isConnected = false
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Deepgram WebSocket failure", t)
                DebugLog.e("DeepgramClient: WebSocket failure - ${t.message}")
                isConnected = false
                val errorMessage = t.message ?: "Unknown WebSocket error"
                onErrorCallback?.invoke(errorMessage)
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
                onErrorCallback?.invoke(error)
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
            onErrorCallback?.invoke(errorMessage)
        } catch (e: Exception) {
            val errorMessage = "Failed to parse Deepgram response: ${e.message}"
            Log.e(TAG, errorMessage, e)
            onErrorCallback?.invoke(errorMessage)
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
            onErrorCallback?.invoke("Connection error: ${e.message}")
            isConnected = false
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send audio", e)
            DebugLog.e("DeepgramClient: Failed to send audio - ${e.message}")
            onErrorCallback?.invoke("Failed to send audio: ${e.message}")
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
