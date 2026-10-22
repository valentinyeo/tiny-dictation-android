package com.tinydictation.android.history

data class TranscriptionEntry(
    val id: Long,
    val text: String,
    val timestamp: Long,
    val language: String
)
