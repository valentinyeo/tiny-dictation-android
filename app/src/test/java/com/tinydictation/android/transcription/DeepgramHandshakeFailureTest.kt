package com.tinydictation.android.transcription

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.IOException
import java.lang.reflect.Proxy

class DeepgramHandshakeFailureTest {
    private val reason =
        "Keywords are not supported for Nova-3. Please use `keyterm` instead."
    private val real400Body =
        """{"err_code":"INVALID_QUERY_PARAMETER","err_msg":"$reason"}"""

    @Test
    fun realHttp400BodyReturnsDeepgramReason() {
        val error = handshakeFailure(real400Body)

        assertEquals(reason, error.message.removePrefix("Deepgram error 400: "))
    }

    @Test
    fun shownHttp400FailureTextCarriesDeepgramReason() {
        val error = handshakeFailure(real400Body)

        assertEquals("Deepgram error 400: $reason", error.message)
        assertFalse(error.fatal)
    }

    private fun handshakeFailure(body: String): TranscriptionError {
        val client = DeepgramClient()
        val socket = Proxy.newProxyInstance(
            WebSocket::class.java.classLoader, arrayOf(WebSocket::class.java)
        ) { _, _, _ -> throw AssertionError("Unexpected WebSocket call") } as WebSocket
        client.javaClass.getDeclaredField("webSocket").apply {
            isAccessible = true
            set(client, socket)
        }
        var failure: TranscriptionError? = null
        val onError: (TranscriptionError) -> Unit = { failure = it }
        val connectionClass = client.javaClass.declaredClasses.single { it.simpleName == "Connection" }
        val session = connectionClass.declaredConstructors.single().apply {
            isAccessible = true
        }.newInstance(onError)
        val listener = client.javaClass.declaredMethods.single {
            it.name == "createWebSocketListener"
        }.apply {
            isAccessible = true
        }.invoke(client, session, { _: String -> }, { _: String -> }, onError) as WebSocketListener
        val response = Response.Builder()
            .request(Request.Builder().url("https://api.deepgram.com/v1/listen").build())
            .protocol(Protocol.HTTP_1_1)
            .code(400)
            .message("Bad Request")
            .body(body.toResponseBody())
            .build()

        listener.onFailure(socket, IOException("Unexpected HTTP response: 400"), response)

        return requireNotNull(failure)
    }
}
