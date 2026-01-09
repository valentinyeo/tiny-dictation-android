package com.tinydictation.android.history

/**
 * Represents a single transcription entry in the history.
 *
 * @param id Unique identifier for the entry
 * @param text The transcribed text
 * @param timestamp Unix timestamp when the transcription was created
 * @param language The language code used for transcription (e.g., "en", "de")
 */
data class TranscriptionEntry(
    val id: Long,
    val text: String,
    val timestamp: Long,
    val language: String
)
