package com.tinydictation.android.service

import android.content.Context
import com.tinydictation.android.util.DebugLog
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Offline word completion and autocorrect using a bundled dictionary.
 *
 * Prediction priority:
 * 1. Prefix completions (typing "experi" → "experience", "experiment", "experiences")
 * 2. Spell corrections via QWERTY-weighted Levenshtein distance (typing "hrlp" → "help")
 *
 * Results shown in prediction strip: [left=alt] [MIDDLE=best] [right=alt]
 */
class AutocorrectManager(context: Context) {

    private val dictionary = HashSet<String>(65536)
    // Sorted list for efficient prefix search
    private var sortedWords: List<String> = emptyList()
    // Words grouped by first letter for fast spell correction
    private val wordsByFirstLetter = HashMap<Char, List<String>>()
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
            val words = mutableListOf<String>()
            context.assets.open("dictionary.txt").use { stream ->
                BufferedReader(InputStreamReader(stream)).use { reader ->
                    reader.forEachLine { line ->
                        val word = line.trim().lowercase()
                        if (word.isNotEmpty()) {
                            dictionary.add(word)
                            words.add(word)
                        }
                    }
                }
            }
            sortedWords = words.sorted()
            // Group by first letter for fast spell correction on large dictionaries
            for (word in words) {
                if (word.isNotEmpty()) {
                    wordsByFirstLetter.getOrPut(word[0]) { mutableListOf() }
                }
            }
            // Convert to immutable lists
            val grouped = HashMap<Char, List<String>>()
            for (word in words) {
                if (word.isNotEmpty()) {
                    val list = grouped.getOrPut(word[0]) { mutableListOf() }
                    (list as MutableList).add(word)
                }
            }
            wordsByFirstLetter.clear()
            wordsByFirstLetter.putAll(grouped)
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
        // Don't autocorrect if word is already in dictionary
        if (isKnownWord(typed)) return null

        // Try spell corrections (not completions - user pressed space so they think they finished the word)
        val corrections = getSpellCorrections(typed, 1)
        if (corrections.isEmpty()) return null

        val best = corrections.first()
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

        // Combine completions and corrections, prioritizing completions
        val suggestions = mutableListOf<String>()

        // 1. Prefix completions (e.g., "experi" → "experience", "experiment")
        // Only show completions that are longer than what's typed (actual completions)
        val completions = getPrefixCompletions(typed, 5)
            .filter { it != typed } // don't suggest what they already typed
        suggestions.addAll(completions)

        // 2. If we don't have enough completions, add spell corrections
        if (suggestions.size < 3) {
            val corrections = getSpellCorrections(typed, 3 - suggestions.size)
                .filter { it !in suggestions } // no duplicates
            suggestions.addAll(corrections)
        }

        cachedSuggestions = suggestions.take(3)
        onSuggestionsUpdated?.invoke(typed, cachedSuggestions)
    }

    /**
     * Find dictionary words that start with the given prefix.
     * Returns results sorted by length (shorter = more common), then alphabetically.
     */
    private fun getPrefixCompletions(prefix: String, limit: Int): List<String> {
        val lower = prefix.lowercase()

        // Binary search to find the start of the prefix range in sorted words
        var lo = 0
        var hi = sortedWords.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (sortedWords[mid] < lower) lo = mid + 1 else hi = mid
        }

        // Collect all words starting with prefix
        val matches = mutableListOf<String>()
        var i = lo
        while (i < sortedWords.size && sortedWords[i].startsWith(lower)) {
            matches.add(sortedWords[i])
            i++
        }

        // Sort by length (shorter words first = more common), then alphabetically
        return matches
            .sortedWith(compareBy({ it.length }, { it }))
            .take(limit)
    }

    /**
     * Find dictionary words closest to the input using QWERTY-weighted Levenshtein distance.
     * Uses first-letter index for performance on large dictionaries (63K+ words).
     * Also checks adjacent first-letter keys to handle first-letter typos.
     */
    private fun getSpellCorrections(word: String, limit: Int): List<String> {
        val lower = word.lowercase()
        if (lower.length < 2) return emptyList()

        val minLen = (lower.length - 2).coerceAtLeast(1)
        val maxLen = lower.length + 2

        // Check words starting with same letter + adjacent keys (handles first-letter typos)
        val firstLetterCandidates = mutableSetOf(lower[0])
        QWERTY_NEIGHBORS[lower[0]]?.let { firstLetterCandidates.addAll(it) }

        val candidates = firstLetterCandidates
            .flatMap { wordsByFirstLetter[it] ?: emptyList() }

        return candidates
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
