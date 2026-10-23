package com.tinydictation.android.history

import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tinydictation.android.util.DebugLog

class ClipboardHistoryManager(private val context: Context) {

    interface OnClipboardHistoryChangedListener {
        fun onClipboardHistoryChanged()
    }

    companion object {
        private const val PREFS_NAME = "clipboard_history"
        private const val KEY_HISTORY = "clipboard_entries"
        private const val MAX_ENTRIES = 50
        private const val MAX_AGE_MS = 7 * 24 * 60 * 60 * 1000L

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
    private val historyChangeListeners = mutableListOf<OnClipboardHistoryChangedListener>()

    fun startListening() {
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        if (clipboardManager == null) {
            DebugLog.e("ClipboardHistoryManager: Failed to get ClipboardManager")
            return
        }

        clipboardListener?.let { clipboardManager.removePrimaryClipChangedListener(it) }

        clipboardListener = ClipboardManager.OnPrimaryClipChangedListener {
            captureClipboard()
        }
        clipboardManager.addPrimaryClipChangedListener(clipboardListener)
        DebugLog.i("ClipboardHistoryManager: Started listening to clipboard changes")

        captureClipboard()
    }

    fun stopListening() {
        val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboardListener?.let {
            clipboardManager?.removePrimaryClipChangedListener(it)
            clipboardListener = null
        }
        DebugLog.i("ClipboardHistoryManager: Stopped listening to clipboard changes")
    }

    fun addOnHistoryChangedListener(listener: OnClipboardHistoryChangedListener) {
        if (!historyChangeListeners.contains(listener)) {
            historyChangeListeners.add(listener)
        }
    }

    fun removeOnHistoryChangedListener(listener: OnClipboardHistoryChangedListener) {
        historyChangeListeners.remove(listener)
    }

    private fun notifyHistoryChanged() {
        historyChangeListeners.forEach { listener ->
            try {
                listener.onClipboardHistoryChanged()
            } catch (e: Exception) {
                DebugLog.e("ClipboardHistoryManager: Error notifying listener: ${e.message}")
            }
        }
    }

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

    fun save(text: String) {
        if (text.isBlank()) {
            return
        }

        val entries = getAllMutable()

        if (entries.isNotEmpty() && entries[0].text == text) {
            DebugLog.d("ClipboardHistoryManager: Duplicate clipboard content, skipping")
            return
        }

        entries.removeAll { it.text == text && !it.isPinned }

        val cutoffTime = System.currentTimeMillis() - MAX_AGE_MS
        entries.removeAll { it.timestamp < cutoffTime && !it.isPinned }

        val newEntry = ClipboardEntry(
            id = System.currentTimeMillis(),
            text = text.trim(),
            timestamp = System.currentTimeMillis(),
            isPinned = false
        )

        entries.add(0, newEntry)

        val pinnedCount = entries.count { it.isPinned }
        while (entries.size > MAX_ENTRIES + pinnedCount) {
            val oldestNonPinned = entries.lastOrNull { !it.isPinned }
            if (oldestNonPinned != null) {
                entries.remove(oldestNonPinned)
            } else {
                break
            }
        }

        saveEntries(entries)
        DebugLog.i("ClipboardHistoryManager: Saved clipboard entry, total: ${entries.size}")
        notifyHistoryChanged()
    }

    fun getAll(): List<ClipboardEntry> {
        val all = getAllMutable()
        val (pinned, unpinned) = all.partition { it.isPinned }
        return pinned.sortedByDescending { it.timestamp } + unpinned.sortedByDescending { it.timestamp }
    }

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

    fun delete(id: Long): Boolean {
        val entries = getAllMutable()
        val removed = entries.removeAll { it.id == id }
        if (removed) {
            saveEntries(entries)
            DebugLog.i("ClipboardHistoryManager: Deleted entry $id")
        }
        return removed
    }

    fun clear() {
        prefs.edit().remove(KEY_HISTORY).apply()
        DebugLog.i("ClipboardHistoryManager: Cleared all history")
    }

    fun clearNonPinned() {
        val entries = getAllMutable()
        val pinnedCount = entries.count { it.isPinned }
        entries.removeAll { !it.isPinned }
        saveEntries(entries)
        DebugLog.i("ClipboardHistoryManager: Cleared non-pinned items, kept $pinnedCount pinned")
    }

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
