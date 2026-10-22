package com.tinydictation.android.history

data class ClipboardEntry(
    val id: Long,
    val text: String,
    val timestamp: Long,
    val isPinned: Boolean = false
)
