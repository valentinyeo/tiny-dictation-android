package com.tinydictation.android.snippet

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tinydictation.android.util.DebugLog

/**
 * Manages persistent storage of text snippets.
 * Snippets are stored in SharedPreferences as a JSON array.
 */
class SnippetManager private constructor(context: Context) {

    companion object {
        private const val PREFS_NAME = "snippets_prefs"
        private const val KEY_SNIPPETS = "snippets"
        private const val MAX_SNIPPETS = 100

        @Volatile
        private var instance: SnippetManager? = null

        fun getInstance(context: Context): SnippetManager {
            return instance ?: synchronized(this) {
                instance ?: SnippetManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    /**
     * Returns all snippets sorted alphabetically (case-insensitive).
     */
    fun getAll(): List<String> {
        val json = prefs.getString(KEY_SNIPPETS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            val snippets: List<String> = gson.fromJson(json, type)
            snippets.sortedBy { it.lowercase() }
        } catch (e: Exception) {
            DebugLog.e("SnippetManager: Failed to load snippets: ${e.message}")
            emptyList()
        }
    }

    /**
     * Adds a new snippet if it doesn't already exist.
     * Returns true if added, false if already exists or limit reached.
     */
    fun add(text: String): Boolean {
        if (text.isBlank()) return false

        val snippets = getAll().toMutableList()

        // Check for duplicate (case-insensitive)
        if (snippets.any { it.equals(text, ignoreCase = true) }) {
            DebugLog.d("SnippetManager: Snippet already exists")
            return false
        }

        // Check limit
        if (snippets.size >= MAX_SNIPPETS) {
            DebugLog.w("SnippetManager: Max snippets reached ($MAX_SNIPPETS)")
            return false
        }

        snippets.add(text)
        save(snippets)
        DebugLog.i("SnippetManager: Added snippet (${snippets.size} total)")
        return true
    }

    /**
     * Deletes a snippet by its exact text.
     * Returns true if deleted, false if not found.
     */
    fun delete(text: String): Boolean {
        val snippets = getAll().toMutableList()
        val removed = snippets.removeIf { it == text }
        if (removed) {
            save(snippets)
            DebugLog.i("SnippetManager: Deleted snippet (${snippets.size} remaining)")
        }
        return removed
    }

    /**
     * Checks if a snippet already exists (case-insensitive).
     */
    fun exists(text: String): Boolean {
        return getAll().any { it.equals(text, ignoreCase = true) }
    }

    /**
     * Clears all snippets.
     */
    fun clearAll() {
        prefs.edit().remove(KEY_SNIPPETS).apply()
        DebugLog.i("SnippetManager: All snippets cleared")
    }

    private fun save(snippets: List<String>) {
        val json = gson.toJson(snippets)
        prefs.edit().putString(KEY_SNIPPETS, json).apply()
    }
}
