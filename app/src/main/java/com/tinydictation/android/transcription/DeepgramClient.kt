package com.tinydictation.android.transcription

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
            onError("Already connected")
            return
        }

        onInterimCallback = onInterim
        onFinalCallback = onFinal
        onErrorCallback = onError

        val client = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
        okHttpClient = client

        val params = customParams ?: DEFAULT_PARAMS
        val url = "$BASE_URL?$params"

        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Token $apiKey")
            .build()

        webSocket = client.newWebSocket(request, createWebSocketListener())
    }

    /**
     * Creates the WebSocket listener that handles messages from Deepgram.
     */
    private fun createWebSocketListener(): WebSocketListener {
        return object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                parseAndDispatchMessage(text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // Deepgram sends text messages, not binary
                // But handle just in case
                parseAndDispatchMessage(bytes.utf8())
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                isConnected = false
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
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
            val json = JSONObject(jsonString)

            // Check for error messages
            if (json.has("error")) {
                val error = json.optString("error", "Unknown error")
                onErrorCallback?.invoke(error)
                return
            }

            // Check for transcription results
            if (json.has("channel")) {
                val channel = json.getJSONObject("channel")
                val alternatives = channel.optJSONArray("alternatives")

                if (alternatives != null && alternatives.length() > 0) {
                    val firstAlternative = alternatives.getJSONObject(0)
                    val transcript = firstAlternative.optString("transcript", "")

                    if (transcript.isNotEmpty()) {
                        val isFinal = json.optBoolean("is_final", false)

                        if (isFinal) {
                            onFinalCallback?.invoke(transcript)
                        } else {
                            onInterimCallback?.invoke(transcript)
                        }
                    }
                }
            }

            // Handle metadata messages (optional - for debugging)
            if (json.has("type")) {
                val type = json.optString("type")
                when (type) {
                    "Metadata" -> {
                        // Connection metadata received
                    }
                    "UtteranceEnd" -> {
                        // Utterance ended (based on endpointing)
                    }
                }
            }
        } catch (e: Exception) {
            val errorMessage = "Failed to parse Deepgram response: ${e.message}"
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
        if (!isConnected || webSocket == null) {
            return false
        }

        return try {
            webSocket?.send(audioBytes.toByteString())
            true
        } catch (e: Exception) {
            onErrorCallback?.invoke("Failed to send audio: ${e.message}")
            false
        }
    }

    /**
     * Closes the WebSocket connection to Deepgram.
     * Sends a close frame and cleans up resources.
     */
    fun close() {
        isConnected = false

        try {
            // Send close frame to Deepgram
            webSocket?.close(1000, "Client closing")
        } catch (e: Exception) {
            // Ignore errors during close
        }

        webSocket = null

        // Shutdown the OkHttp client's connection pool
        okHttpClient?.dispatcher?.executorService?.shutdown()
        okHttpClient?.connectionPool?.evictAll()
        okHttpClient = null

        // Clear callbacks
        onInterimCallback = null
        onFinalCallback = null
        onErrorCallback = null
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
