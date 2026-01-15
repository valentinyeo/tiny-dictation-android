package com.tinydictation.android.history

/**
 * Represents a single clipboard entry in the history.
 *
 * @param id Unique identifier for the entry
 * @param text The clipboard text content
 * @param timestamp Unix timestamp when the clipboard was copied
 * @param isPinned Whether this entry is pinned (won't be auto-deleted)
 */
data class ClipboardEntry(
    val id: Long,
    val text: String,
    val timestamp: Long,
    val isPinned: Boolean = false
)
