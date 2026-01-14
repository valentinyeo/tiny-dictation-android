package com.tinydictation.android.dictionary

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.tinydictation.android.util.DebugLog

/**
 * Manages word replacement entries for automatic correction of misspellings.
 * Uses SharedPreferences with GSON serialization for storage.
 * Thread-safe singleton pattern.
 */
class WordReplacementManager private constructor(context: Context) {

    companion object {
        private const val TAG = "TinyDictation"
        private const val PREFS_NAME = "word_replacements"
        private const val KEY_REPLACEMENTS = "replacements"

        @Volatile
        private var instance: WordReplacementManager? = null

        fun getInstance(context: Context): WordReplacementManager {
            return instance ?: synchronized(this) {
                instance ?: WordReplacementManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    /**
     * Gets all word replacements.
     */
    fun getAll(): List<WordReplacement> {
        val json = prefs.getString(KEY_REPLACEMENTS, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<WordReplacement>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing word replacements", e)
            DebugLog.e("WordReplacementManager: Error parsing - ${e.message}")
            emptyList()
        }
    }

    /**
     * Adds a new word replacement.
     * @param original The word to find (will be stored lowercase)
     * @param replacement The word to replace with
     * @return The created WordReplacement entry
     */
    fun add(original: String, replacement: String): WordReplacement {
        val entries = getAll().toMutableList()
        val entry = WordReplacement(
            id = System.currentTimeMillis(),
            original = original.lowercase().trim(),
            replacement = replacement.trim()
        )
        entries.add(entry)
        save(entries)
        DebugLog.i("WordReplacementManager: Added '${entry.original}' -> '${entry.replacement}'")
        return entry
    }

    /**
     * Updates an existing word replacement.
     */
    fun update(id: Long, original: String, replacement: String) {
        val entries = getAll().toMutableList()
        val index = entries.indexOfFirst { it.id == id }
        if (index >= 0) {
            entries[index] = WordReplacement(
                id = id,
                original = original.lowercase().trim(),
                replacement = replacement.trim()
            )
            save(entries)
            DebugLog.i("WordReplacementManager: Updated entry $id")
        }
    }

    /**
     * Deletes a word replacement by ID.
     */
    fun delete(id: Long) {
        val entries = getAll().toMutableList()
        val removed = entries.removeAll { it.id == id }
        if (removed) {
            save(entries)
            DebugLog.i("WordReplacementManager: Deleted entry $id")
        }
    }

    /**
     * Applies all word replacements to the given text.
     * Case-insensitive matching with case preservation.
     * Only replaces whole words (word boundaries).
     *
     * @param text The text to process
     * @return The text with replacements applied
     */
    fun applyReplacements(text: String): String {
        val entries = getAll()
        if (entries.isEmpty()) return text

        var result = text
        for (entry in entries) {
            // Use word boundary regex for whole-word matching
            // Case-insensitive matching
            val pattern = Regex("\\b${Regex.escape(entry.original)}\\b", RegexOption.IGNORE_CASE)
            result = pattern.replace(result) { match ->
                preserveCase(match.value, entry.replacement)
            }
        }

        if (result != text) {
            DebugLog.d("WordReplacementManager: Applied replacements: '$text' -> '$result'")
        }

        return result
    }

    /**
     * Preserves the case pattern of the original word in the replacement.
     * - ALL CAPS -> ALL CAPS
     * - Title Case -> Title Case
     * - lowercase -> lowercase
     */
    private fun preserveCase(original: String, replacement: String): String {
        if (original.isEmpty() || replacement.isEmpty()) return replacement

        return when {
            // All uppercase
            original.all { !it.isLetter() || it.isUpperCase() } -> replacement.uppercase()
            // First letter uppercase (title case)
            original[0].isUpperCase() -> replacement.replaceFirstChar { it.uppercase() }
            // All lowercase
            else -> replacement.lowercase()
        }
    }

    /**
     * Checks if a word already has a replacement entry.
     */
    fun hasReplacement(original: String): Boolean {
        return getAll().any { it.original == original.lowercase().trim() }
    }

    /**
     * Gets the count of word replacements.
     */
    fun count(): Int = getAll().size

    private fun save(entries: List<WordReplacement>) {
        val json = gson.toJson(entries)
        prefs.edit().putString(KEY_REPLACEMENTS, json).apply()
    }
}
