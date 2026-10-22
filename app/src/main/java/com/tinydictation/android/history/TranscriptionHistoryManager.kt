package com.tinydictation.android.history

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tinydictation.android.util.DebugLog

class TranscriptionHistoryManager(context: Context) {

    companion object {
        private const val PREFS_NAME = "transcription_history"
        private const val KEY_HISTORY = "history_entries"
        private const val MAX_ENTRIES = 100
        private const val MAX_AGE_MS = 2 * 24 * 60 * 60 * 1000L

        @Volatile
        private var instance: TranscriptionHistoryManager? = null

        fun getInstance(context: Context): TranscriptionHistoryManager {
            return instance ?: synchronized(this) {
                instance ?: TranscriptionHistoryManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    fun save(text: String, language: String) {
        if (text.isBlank()) {
            DebugLog.d("TranscriptionHistoryManager: Skipping empty text")
            return
        }

        val entries = getAllMutable()

        val cutoffTime = System.currentTimeMillis() - MAX_AGE_MS
        val sizeBefore = entries.size
        entries.removeAll { it.timestamp < cutoffTime }
        val removed = sizeBefore - entries.size
        if (removed > 0) {
            DebugLog.i("TranscriptionHistoryManager: Cleaned up $removed old entries (>2 days)")
        }

        val newEntry = TranscriptionEntry(
            id = System.currentTimeMillis(),
            text = text.trim(),
            timestamp = System.currentTimeMillis(),
            language = language
        )

        entries.add(0, newEntry)

        while (entries.size > MAX_ENTRIES) {
            entries.removeAt(entries.size - 1)
        }

        saveEntries(entries)
        DebugLog.i("TranscriptionHistoryManager: Saved entry, total: ${entries.size}")
    }

    fun getAll(): List<TranscriptionEntry> {
        return getAllMutable().toList()
    }

    fun getById(id: Long): TranscriptionEntry? {
        return getAll().find { it.id == id }
    }

    fun delete(id: Long): Boolean {
        val entries = getAllMutable()
        val removed = entries.removeAll { it.id == id }
        if (removed) {
            saveEntries(entries)
            DebugLog.i("TranscriptionHistoryManager: Deleted entry $id")
        }
        return removed
    }

    fun clear() {
        prefs.edit().remove(KEY_HISTORY).apply()
        DebugLog.i("TranscriptionHistoryManager: Cleared all history")
    }

    fun getCount(): Int {
        return getAll().size
    }

    fun search(query: String): List<TranscriptionEntry> {
        if (query.isBlank()) return getAll()
        val lowerQuery = query.lowercase()
        return getAll().filter { it.text.lowercase().contains(lowerQuery) }
    }

    private fun getAllMutable(): MutableList<TranscriptionEntry> {
        val json = prefs.getString(KEY_HISTORY, null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<TranscriptionEntry>>() {}.type
            gson.fromJson(json, type) ?: mutableListOf()
        } catch (e: Exception) {
            DebugLog.e("TranscriptionHistoryManager: Error parsing history: ${e.message}")
            mutableListOf()
        }
    }

    private fun saveEntries(entries: List<TranscriptionEntry>) {
        val json = gson.toJson(entries)
        prefs.edit().putString(KEY_HISTORY, json).apply()
    }
}
