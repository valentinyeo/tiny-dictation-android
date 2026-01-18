package com.tinydictation.android.history

import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tinydictation.android.util.DebugLog

/**
 * Manages clipboard history storage and retrieval.
 * Captures clipboard changes and stores them for later use.
 * Uses SharedPreferences with JSON serialization.
 */
class ClipboardHistoryManager(private val context: Context) {

    companion object {
        private const val PREFS_NAME = "clipboard_history"
        private const val KEY_HISTORY = "clipboard_entries"
        private const val MAX_ENTRIES = 50 // Keep last 50 entries
        private const val MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L // 7 days in milliseconds

        @Volatile
        private var instance: ClipboardHistoryManager? = null

        fun getInstance(context: Context): ClipboardHistoryManager {
            return instance ?: synchronized(this) {
                instance ?: ClipboardHistoryManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()
    private var clipboardListener: ClipboardManager.OnPrimaryClipChangedListener? = null

    /**
     * Starts listening to clipboard changes.
     * Call this when the keyboard service starts.
     */
    fun startListening() {
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboardManager == null) {
            DebugLog.e("ClipboardHistoryManager: Failed to get ClipboardManager")
            return
        }

        // Remove existing listener if any
        clipboardListener?.let { clipboardManager.removePrimaryClipChangedListener(it) }

        clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
            captureClipboard()
        }
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
        DebugLog.i("ClipboardHistoryManager: Started listening to clipboard changes")

        // Capture current clipboard content on start
        captureClipboard()
    }

    /**
     * Stops listening to clipboard changes.
     * Call this when the keyboard service stops.
     */
    fun stopListening() {
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardListener?.let {
            clipboardManager?.removePrimaryClipChangedListener(it)
            clipboardListener = null
        }
        DebugLog.i("ClipboardHistoryManager: Stopped listening to clipboard changes")
    }

    /**
     * Captures the current clipboard content and saves it to history.
     */
    private fun captureClipboard() {
        try {
            val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = clipboardManager?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).coerceToText(context)?.toString()
                if (!text.isNullOrBlank()) {
                    save(text)
                }
            }
        } catch (e: Exception) {
            DebugLog.e("ClipboardHistoryManager: Error capturing clipboard: ${e.message}")
        }
    }

    /**
     * Saves a new clipboard entry to history.
     * Avoids duplicates by checking if the same text was just added.
     *
     * @param text The clipboard text content
     */
    fun save(text: String) {
        if (text.isBlank()) {
            return
        }

        val entries = getAllMutable()

        // Check if this exact text is already at the top (avoid duplicates)
        if (entries.isNotEmpty() && entries[0].text == text) {
            DebugLog.d("ClipboardHistoryManager: Duplicate clipboard content, skipping")
            return
        }

        // Remove any existing entry with the same text (to move it to top)
        entries.removeAll { it.text == text && !it.isPinned }

        // Clean up old non-pinned entries (older than 7 days)
        val cutoffTime = System.currentTimeMillis() - MAX_AGE_MS
        entries.removeAll { it.timestamp < cutoffTime && !it.isPinned }

        val newEntry = ClipboardEntry(
            id = System.currentTimeMillis(),
            text = text.trim(),
            timestamp = System.currentTimeMillis(),
            isPinned = false
        )

        entries.add(0, newEntry) // Add to beginning (most recent first)

        // Trim to max entries, but keep pinned entries
        val pinnedCount = entries.count { it.isPinned }
        while (entries.size > MAX_ENTRIES + pinnedCount) {
            // Find the oldest non-pinned entry and remove it
            val oldestNonPinned = entries.lastOrNull { !it.isPinned }
            if (oldestNonPinned != null) {
                entries.remove(oldestNonPinned)
            } else {
                break
            }
        }

        saveEntries(entries)
        DebugLog.i("ClipboardHistoryManager: Saved clipboard entry, total: ${entries.size}")
    }

    /**
     * Gets all clipboard entries with pinned items first, then by recency.
     *
     * @return List of clipboard entries (pinned first, then most recent)
     */
    fun getAll(): List<ClipboardEntry> {
        val all = getAllMutable()
        // Partition: pinned first (sorted by timestamp desc), then unpinned (sorted by timestamp desc)
        val (pinned, unpinned) = all.partition { it.isPinned }
        return pinned.sortedByDescending { it.timestamp } + unpinned.sortedByDescending { it.timestamp }
    }

    /**
     * Toggles the pinned status of an entry.
     *
     * @param id The entry ID
     * @return The updated entry, or null if not found
     */
    fun togglePin(id: Long): ClipboardEntry? {
        val entries = getAllMutable()
        val index = entries.indexOfFirst { it.id == id }
        if (index >= 0) {
            val entry = entries[index]
            val updated = entry.copy(isPinned = !entry.isPinned)
            entries[index] = updated
            saveEntries(entries)
            DebugLog.i("ClipboardHistoryManager: Toggled pin for entry $id to ${updated.isPinned}")
            return updated
        }
        return null
    }

    /**
     * Deletes a specific entry by ID.
     *
     * @param id The entry ID to delete
     * @return true if the entry was deleted, false if not found
     */
    fun delete(id: Long): Boolean {
        val entries = getAllMutable()
        val removed = entries.removeAll { it.id == id }
        if (removed) {
            saveEntries(entries)
            DebugLog.i("ClipboardHistoryManager: Deleted entry $id")
        }
        return removed
    }

    /**
     * Clears all clipboard history (including pinned items).
     */
    fun clear() {
        prefs.edit().remove(KEY_HISTORY).apply()
        DebugLog.i("ClipboardHistoryManager: Cleared all history")
    }

    /**
     * Clears only non-pinned clipboard entries, preserving pinned items.
     */
    fun clearNonPinned() {
        val entries = getAllMutable()
        val pinnedCount = entries.count { it.isPinned }
        entries.removeAll { !it.isPinned }
        saveEntries(entries)
        DebugLog.i("ClipboardHistoryManager: Cleared non-pinned items, kept $pinnedCount pinned")
    }

    /**
     * Gets the total number of entries.
     */
    fun getCount(): Int {
        return getAll().size
    }

    private fun getAllMutable(): MutableList<ClipboardEntry> {
        val json = prefs.getString(KEY_HISTORY, null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<ClipboardEntry>>() {}.type
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            DebugLog.e("ClipboardHistoryManager: Error parsing history: ${e.message}")
            mutableListOf()
        }
    }

    private fun saveEntries(entries: List<ClipboardEntry>) {
        val json = gson.toJson(entries)
        prefs.edit().putString(KEY_HISTORY, json).apply()
    }
}
