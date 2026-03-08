package com.tinydictation.android.service

import android.content.Context
import com.tinydictation.android.util.DebugLog
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Offline autocorrect using a bundled dictionary and QWERTY-aware Levenshtein distance.
 * Designed to be fast (< 1ms per lookup on 3K words) and fully offline.
 */
class AutocorrectManager(context: Context) {

    private val dictionary = HashSet<String>(4096)
    private val currentWord = StringBuilder()

    // Last autocorrect for undo-on-backspace
    var lastAutocorrect: AutocorrectRecord? = null
        private set

    // Callback when suggestions change (for prediction strip)
    var onSuggestionsUpdated: ((typed: String, suggestions: List<String>) -> Unit)? = null

    // Cached suggestions for current word (updated as user types)
    private var cachedSuggestions: List<String> = emptyList()

    data class AutocorrectRecord(
        val original: String,
        val replacement: String
    )

    init {
        loadDictionary(context)
    }

    private fun loadDictionary(context: Context) {
        try {
            context.assets.open("dictionary.txt").use { stream ->
                BufferedReader(InputStreamReader(stream)).use { reader ->
                    reader.forEachLine { line ->
                        val word = line.trim().lowercase()
                        if (word.isNotEmpty()) {
                            dictionary.add(word)
                        }
                    }
                }
            }
            DebugLog.d("AutocorrectManager: Loaded ${dictionary.size} words")
        } catch (e: Exception) {
            DebugLog.e("AutocorrectManager: Failed to load dictionary: ${e.message}")
        }
    }

    fun isKnownWord(word: String): Boolean = dictionary.contains(word.lowercase())

    /**
     * Called when user types a letter on the QWERTY keyboard.
     */
    fun onLetterTyped(char: Char) {
        currentWord.append(char.lowercaseChar())
        clearUndoState()
        updateSuggestions()
    }

    /**
     * Called when user presses backspace.
     * Returns true if an autocorrect was undone (caller should skip normal backspace).
     */
    fun onBackspace(): Boolean {
        // Check for undo first
        val undo = lastAutocorrect
        if (undo != null) {
            lastAutocorrect = null
            return true // caller handles the actual undo
        }

        if (currentWord.isNotEmpty()) {
            currentWord.deleteCharAt(currentWord.length - 1)
            if (currentWord.isNotEmpty()) {
                updateSuggestions()
            } else {
                cachedSuggestions = emptyList()
                onSuggestionsUpdated?.invoke("", emptyList())
            }
        }
        return false
    }

    /**
     * Called when space is pressed. Returns the corrected word if autocorrect should fire,
     * or null if the word is correct / no suggestion available.
     */
    fun onSpace(): String? {
        val typed = currentWord.toString()
        currentWord.clear()
        cachedSuggestions = emptyList()
        onSuggestionsUpdated?.invoke("", emptyList())

        if (typed.isEmpty() || typed.length < 2) return null
        if (isKnownWord(typed)) return null

        // Get best suggestion
        val suggestions = getSuggestions(typed, 1)
        if (suggestions.isEmpty()) return null

        val best = suggestions.first()
        // Only autocorrect if edit distance is small (1-2) relative to word length
        val dist = levenshteinDistance(typed, best)
        if (dist > 2 || dist >= typed.length) return null

        lastAutocorrect = AutocorrectRecord(original = typed, replacement = best)
        DebugLog.d("AutocorrectManager: Corrected '$typed' → '$best'")
        return best
    }

    /**
     * Called when a word boundary (punctuation, cursor move) is reached without autocorrect.
     */
    fun onWordBoundary() {
        currentWord.clear()
        cachedSuggestions = emptyList()
        onSuggestionsUpdated?.invoke("", emptyList())
    }

    /**
     * Called when user taps a prediction suggestion.
     * Returns the replacement word (caller handles deletion + insertion).
     */
    fun onPredictionTapped(suggestion: String): String {
        val typed = currentWord.toString()
        currentWord.clear()
        cachedSuggestions = emptyList()
        onSuggestionsUpdated?.invoke("", emptyList())
        if (typed.isNotEmpty()) {
            lastAutocorrect = AutocorrectRecord(original = typed, replacement = suggestion)
        }
        return suggestion
    }

    fun clearUndoState() {
        lastAutocorrect = null
    }

    fun getCurrentWord(): String = currentWord.toString()

    fun getCachedSuggestions(): List<String> = cachedSuggestions

    private fun updateSuggestions() {
        val typed = currentWord.toString()
        if (typed.length < 2) {
            cachedSuggestions = emptyList()
            onSuggestionsUpdated?.invoke(typed, emptyList())
            return
        }

        cachedSuggestions = getSuggestions(typed, 3)
        onSuggestionsUpdated?.invoke(typed, cachedSuggestions)
    }

    /**
     * Find dictionary words closest to the input using QWERTY-weighted Levenshtein distance.
     */
    fun getSuggestions(word: String, limit: Int = 3): List<String> {
        val lower = word.lowercase()
        if (lower.length < 2) return emptyList()

        // Pre-filter: only check words within ±2 chars of same length
        val minLen = (lower.length - 2).coerceAtLeast(1)
        val maxLen = lower.length + 2

        return dictionary
            .asSequence()
            .filter { it.length in minLen..maxLen }
            .map { it to weightedLevenshtein(lower, it) }
            .filter { it.second <= 2.0f && it.second > 0f }
            .sortedBy { it.second }
            .take(limit)
            .map { it.first }
            .toList()
    }

    /**
     * QWERTY-weighted Levenshtein distance.
     * Substitutions between adjacent keys cost 0.5 instead of 1.0.
     */
    private fun weightedLevenshtein(s1: String, s2: String): Float {
        val m = s1.length
        val n = s2.length
        val dp = Array(m + 1) { FloatArray(n + 1) }

        for (i in 0..m) dp[i][0] = i.toFloat()
        for (j in 0..n) dp[0][j] = j.toFloat()

        for (i in 1..m) {
            for (j in 1..n) {
                val subCost = if (s1[i - 1] == s2[j - 1]) 0f
                else if (areAdjacentKeys(s1[i - 1], s2[j - 1])) 0.5f
                else 1f

                dp[i][j] = minOf(
                    dp[i - 1][j] + 1f,        // deletion
                    dp[i][j - 1] + 1f,        // insertion
                    dp[i - 1][j - 1] + subCost // substitution
                )
            }
        }
        return dp[m][n]
    }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,
                    dp[i][j - 1] + 1,
                    dp[i - 1][j - 1] + if (s1[i - 1] == s2[j - 1]) 0 else 1
                )
            }
        }
        return dp[m][n]
    }

    /**
     * QWERTY key adjacency map for proximity-weighted corrections.
     */
    private fun areAdjacentKeys(a: Char, b: Char): Boolean {
        val neighbors = QWERTY_NEIGHBORS[a] ?: return false
        return b in neighbors
    }

    companion object {
        private val QWERTY_NEIGHBORS = mapOf(
            'q' to setOf('w', 'a'),
            'w' to setOf('q', 'e', 'a', 's'),
            'e' to setOf('w', 'r', 's', 'd'),
            'r' to setOf('e', 't', 'd', 'f'),
            't' to setOf('r', 'y', 'f', 'g'),
            'y' to setOf('t', 'u', 'g', 'h'),
            'u' to setOf('y', 'i', 'h', 'j'),
            'i' to setOf('u', 'o', 'j', 'k'),
            'o' to setOf('i', 'p', 'k', 'l'),
            'p' to setOf('o', 'l'),
            'a' to setOf('q', 'w', 's', 'z'),
            's' to setOf('a', 'w', 'e', 'd', 'z', 'x'),
            'd' to setOf('s', 'e', 'r', 'f', 'x', 'c'),
            'f' to setOf('d', 'r', 't', 'g', 'c', 'v'),
            'g' to setOf('f', 't', 'y', 'h', 'v', 'b'),
            'h' to setOf('g', 'y', 'u', 'j', 'b', 'n'),
            'j' to setOf('h', 'u', 'i', 'k', 'n', 'm'),
            'k' to setOf('j', 'i', 'o', 'l', 'm'),
            'l' to setOf('k', 'o', 'p'),
            'z' to setOf('a', 's', 'x'),
            'x' to setOf('z', 's', 'd', 'c'),
            'c' to setOf('x', 'd', 'f', 'v'),
            'v' to setOf('c', 'f', 'g', 'b'),
            'b' to setOf('v', 'g', 'h', 'n'),
            'n' to setOf('b', 'h', 'j', 'm'),
            'm' to setOf('n', 'j', 'k')
        )
    }
}
