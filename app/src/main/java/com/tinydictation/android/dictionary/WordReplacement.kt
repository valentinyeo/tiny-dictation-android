package com.tinydictation.android.dictionary

/**
 * Data class representing a word replacement entry.
 * Used for automatically correcting misspellings in transcriptions.
 */
data class WordReplacement(
    val id: Long,           // Timestamp-based unique ID
    val original: String,   // Word to find (stored lowercase for matching)
    val replacement: String // Word to replace with
)
