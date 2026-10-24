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

class DeepgramClient(private val onStreamClosed: (Long) -> Unit = {}) {
    companion object {
        private const val TAG = "TinyDictation"
        private const val BASE_URL = "wss://api.deepgram.com/v1/listen"
        private const val BASE_PARAMS = "encoding=linear16&sample_rate=16000&channels=1"
        const val KEEP_ALIVE_MESSAGE = "{\"type\":\"KeepAlive\"}"

        fun buildParams(
            language: String = "en",
            model: String = "nova-2",
            terminalMode: Boolean = false,
            smartFormat: Boolean = true,
            punctuate: Boolean = true,
            keywords: List<String> = emptyList(),
            replacements: List<Pair<String, String>> = emptyList(),
            numerals: Boolean = false,
            dictation: Boolean = false
        ): String {
            val params = StringBuilder()
            params.append("model=$model")
            params.append("&$BASE_PARAMS")
            if (terminalMode) {
                params.append("&smart_format=false")
                params.append("&punctuate=false")
                params.append("&interim_results=false")
                params.append("&endpointing=150")
            } else {
                params.append("&smart_format=$smartFormat")
                params.append("&punctuate=$punctuate")
                params.append("&interim_results=true")
                params.append("&endpointing=150")
            }
            val apiLanguage = if (language == "auto") "multi" else language
            params.append("&language=$apiLanguage")

            if (numerals) {
                params.append("&numerals=true")
            }
            if (dictation) {
                params.append("&dictation=true")
            }
            for (keyword in keywords) {
                val encoded = URLEncoder.encode(keyword, "UTF-8")
                val parameter = if (model == "nova-3") "keyterm" else "keywords"
                params.append("&$parameter=$encoded")
            }
            for ((find, replace) in replacements) {
                val encodedFind = URLEncoder.encode(find, "UTF-8")
                val encodedReplace = URLEncoder.encode(replace, "UTF-8")
                params.append("&replace=$encodedFind:$encodedReplace")
            }

            params.append("&tag=tiny-dictation")
            return params.toString()
        }
    }

    private class Connection(val onError: (TranscriptionError) -> Unit) {
        var closedByClient = false
        var opened = false
        var accounted = false
        var errorReported = false
        var audioBytesSent = 0L
    }

    private var webSocket: WebSocket? = null
    private var okHttpClient: OkHttpClient? = null
    private var connection: Connection? = null
    private var connected = false

    @Synchronized
    fun connect(
        apiKey: String,
        onInterim: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (TranscriptionError) -> Unit,
        language: String = "en",
        model: String = "nova-2",
        terminalMode: Boolean = false,
        smartFormat: Boolean = true,
        punctuate: Boolean = true,
        keywords: List<String> = emptyList(),
        replacements: List<Pair<String, String>> = emptyList(),
        numerals: Boolean = false,
        dictation: Boolean = false
    ) {
        forceCleanup()
        if (apiKey.isBlank()) {
            onError(TranscriptionError("API key is required"))
            return
        }

        val session = Connection(onError)
        connection = session
        try {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build()
            okHttpClient = client
            val params = buildParams(
                language, model, terminalMode, smartFormat, punctuate,
                keywords, replacements, numerals, dictation
            )
            val request = Request.Builder()
                .url("$BASE_URL?$params")
                .header("Authorization", "Token $apiKey")
                .build()
            webSocket = client.newWebSocket(
                request, createWebSocketListener(session, onInterim, onFinal, onError)
            )
            DebugLog.i("DeepgramClient: Connecting to Deepgram WebSocket")
        } catch (e: Exception) {
            forceCleanup()
            onError(TranscriptionError("Failed to connect: ${e.message}"))
        }
    }

    private fun createWebSocketListener(
        session: Connection,
        onInterim: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (TranscriptionError) -> Unit
    ): WebSocketListener = object : WebSocketListener() {
        private fun accepts(socket: WebSocket): Boolean {
            return acceptsDeepgramEvent(socket, webSocket, session.closedByClient)
        }

        private fun reportError(error: TranscriptionError?) {
            if (error != null && !session.errorReported) {
                session.errorReported = true
                onError(error)
            }
        }

        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(this@DeepgramClient) {
                if (!accepts(webSocket)) return
                session.opened = true
                connected = true
                DebugLog.i("DeepgramClient: WebSocket connection opened")
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            synchronized(this@DeepgramClient) {
                if (!accepts(webSocket)) return
                parseAndDispatchMessage(text, onInterim, onFinal, ::reportError)
            }
        }

        override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
            synchronized(this@DeepgramClient) {
                if (!accepts(webSocket)) return
                parseAndDispatchMessage(bytes.utf8(), onInterim, onFinal, ::reportError)
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(this@DeepgramClient) {
                if (!accepts(webSocket)) return
                connected = false
                accountUsage(session)
                webSocket.close(1000, null)
                reportError(mapDeepgramError(closeCode = code, causeMessage = reason))
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            synchronized(this@DeepgramClient) {
                if (!accepts(webSocket)) return
                connected = false
                accountUsage(session)
                reportError(mapDeepgramError(closeCode = code, causeMessage = reason))
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            synchronized(this@DeepgramClient) {
                if (!accepts(webSocket)) return
                connected = false
                accountUsage(session)
                DebugLog.e("DeepgramClient: WebSocket failure - ${t.message}")
                reportError(mapDeepgramError(
                    httpCode = response?.code,
                    dgError = response?.header("dg-error"),
                    causeMessage = t.message
                ))
            }
        }
    }

    private fun parseAndDispatchMessage(
        text: String,
        onInterim: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (TranscriptionError) -> Unit
    ) {
        if (text.isBlank()) return
        try {
            val json = JSONObject(text)
            if (json.optString("type") == "Error") {
                val description = json.optString("description", "Unknown error")
                onError(TranscriptionError("Deepgram error: $description"))
                return
            }
            if (json.has("error")) {
                onError(TranscriptionError(json.optString("error", "Unknown error")))
                return
            }
            val alternative = json.optJSONObject("channel")
                ?.optJSONArray("alternatives")?.optJSONObject(0) ?: return
            val transcript = alternative.optString("transcript", "")
            if (transcript.isEmpty()) return
            if (json.optBoolean("is_final", false)) onFinal(transcript) else onInterim(transcript)
        } catch (e: org.json.JSONException) {
            onError(TranscriptionError("Invalid JSON from Deepgram: ${e.message}"))
        }
    }

    @Synchronized
    fun sendAudio(audioBytes: ByteArray): Boolean {
        val socket = webSocket ?: return false
        val session = connection ?: return false
        if (!connected || session.closedByClient) return false
        return try {
            val sent = socket.send(audioBytes.toByteString())
            if (sent) session.audioBytesSent += audioBytes.size
            sent
        } catch (e: IllegalStateException) {
            connected = false
            session.onError(TranscriptionError("Connection error: ${e.message}"))
            false
        }
    }

    private fun accountUsage(session: Connection) {
        if (!session.opened || session.accounted) return
        session.accounted = true
        onStreamClosed(session.audioBytesSent)
    }

    private fun forceCleanup() {
        disconnect(cancel = true)
    }

    private fun disconnect(cancel: Boolean) {
        val session = connection
        session?.closedByClient = true
        val socket = webSocket
        webSocket = null
        connection = null
        connected = false
        if (session != null) accountUsage(session)
        try {
            if (cancel) socket?.cancel() else socket?.close(1000, "Client closing")
        } catch (e: IllegalStateException) {
            Log.d(TAG, "DeepgramClient: Error closing WebSocket: ${e.message}")
        }
        okHttpClient?.dispatcher?.executorService?.shutdown()
        okHttpClient?.connectionPool?.evictAll()
        okHttpClient = null
    }

    @Synchronized
    fun sendFinalize(): Boolean {
        if (!connected) return false
        return webSocket?.send("{\"type\":\"Finalize\"}") ?: false
    }

    @Synchronized
    fun close() {
        disconnect(cancel = false)
    }

    @Synchronized
    fun isConnected(): Boolean = connected

    @Synchronized
    fun sendKeepAlive(): Boolean {
        if (!connected) return false
        return webSocket?.send(KEEP_ALIVE_MESSAGE) ?: false
    }
}
