package com.tinydictation.android.transcription

import android.content.Context

interface TranscriptionEngine {

    val engineId: String

    val displayName: String

    val requiresInternet: Boolean

    suspend fun isReady(context: Context, language: String): Boolean

    suspend fun initialize(context: Context, language: String): Result<Unit>

    fun startSession(
        onTranscript: (text: String, isFinal: Boolean) -> Unit,
        onError: (TranscriptionError) -> Unit
    )

    fun processAudio(samples: ShortArray)

    fun endSession()

    fun sendKeepAlive() {}

    fun setLanguage(language: String)

    fun getSupportedLanguages(): List<String>

    fun release()

    fun hasPendingAudio(): Boolean = false

    suspend fun flushPendingAudio(timeoutMs: Long = 10000): Boolean = true

    fun clearAudioBuffer() {  }
}
