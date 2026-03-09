package com.tinydictation.android.service

import android.content.Context
import com.tinydictation.android.util.DebugLog
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Offline word completion and autocorrect using a frequency-ranked dictionary.
 *
 * Dictionary format: word\trank (lower rank = more common, 99999 = no frequency data)
 *
 * Prediction priority:
 * 1. Prefix completions sorted by frequency (typing "that" → "that's" not "thatch")
 * 2. Spell corrections via QWERTY-weighted Levenshtein distance (typing "hrlp" → "help")
 *
 * Results shown in prediction strip: [left=alt] [MIDDLE=best] [right=alt]
 */
class AutocorrectManager(context: Context) {

    private val dictionary = HashSet<String>(65536)
    // Word → frequency rank (lower = more common). 99999 = unranked.
    private val wordFrequency = HashMap<String, Int>(65536)
    // Sorted list for efficient prefix search
    private var sortedWords: List<String> = emptyList()
    // Words grouped by first letter for fast spell correction
    private val wordsByFirstLetter = HashMap<Char, MutableList<String>>()
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
                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) return@forEachLine

                        // Format: word\trank or just word
                        val parts = trimmed.split('\t')
                        val word = parts[0].lowercase()
                        val rank = parts.getOrNull(1)?.toIntOrNull() ?: 99999

                        if (word.isNotEmpty()) {
                            dictionary.add(word)
                            wordFrequency[word] = rank
                            words.add(word)

                            // Group by first letter
                            wordsByFirstLetter.getOrPut(word[0]) { mutableListOf() }.add(word)
                        }
                    }
                }
            }
            sortedWords = words.sorted()
            val rankedCount = wordFrequency.count { it.value < 99999 }
            DebugLog.d("AutocorrectManager: Loaded ${dictionary.size} words ($rankedCount with frequency data)")
        } catch (e: Exception) {
            DebugLog.e("AutocorrectManager: Failed to load dictionary: ${e.message}")
        }
    }

    /** Get frequency rank for a word (lower = more common, 99999 = unknown) */
    private fun getFrequency(word: String): Int = wordFrequency[word.lowercase()] ?: 99999

    fun isKnownWord(word: String): Boolean = dictionary.contains(word.lowercase())

    fun onLetterTyped(char: Char) {
        currentWord.append(char)  // Preserve original case for capitalization detection
        clearUndoState()
        updateSuggestions()
    }

    fun onBackspace(): Boolean {
        val undo = lastAutocorrect
        if (undo != null) {
            lastAutocorrect = null
            return true
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

    fun onSpace(): String? {
        val typed = currentWord.toString()
        currentWord.clear()
        cachedSuggestions = emptyList()
        onSuggestionsUpdated?.invoke("", emptyList())

        if (typed.isEmpty() || typed.length < 2) return null
        if (isKnownWord(typed)) return null

        // 1. Try word segmentation (missing space detection)
        // e.g., "thisis" → "this is", "ofthe" → "of the"
        val split = tryWordSplit(typed)
        if (split != null) {
            lastAutocorrect = AutocorrectRecord(original = typed, replacement = split)
            DebugLog.d("AutocorrectManager: Split '$typed' → '$split'")
            return split
        }

        // 2. Try spell correction
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
     * Try splitting an unknown word into two known words (missing space detection).
     * e.g., "thisis" → "this is", "ofthe" → "of the"
     * Returns the best split as "word1 word2", or null if no valid split found.
     * Prefers splits where both words are common (low frequency rank).
     */
    private fun tryWordSplit(word: String): String? {
        val lower = word.lowercase()
        if (lower.length < 4) return null // minimum 2+2 chars

        var bestSplit: String? = null
        var bestScore = Int.MAX_VALUE // lower = better (sum of frequency ranks)

        for (i in 2..lower.length - 2) {
            val left = lower.substring(0, i)
            val right = lower.substring(i)

            if (isKnownWord(left) && isKnownWord(right)) {
                // Both parts are valid words - score by combined frequency
                val score = getFrequency(left) + getFrequency(right)
                if (score < bestScore) {
                    bestScore = score
                    bestSplit = "$left $right"
                }
            }
        }

        // Only accept splits where both words are reasonably common
        // (prevents splitting "together" into "to get her")
        if (bestSplit != null && bestScore < 50000) {
            return bestSplit
        }
        return null
    }

    /**
     * Try to correct a word (spell correction + word split) without modifying internal state.
     * Used when the word was already captured on space touch-down.
     */
    fun tryCorrectWord(typed: String): String? {
        if (typed.isEmpty() || typed.length < 2) return null
        if (isKnownWord(typed)) return null

        // 1. Try word segmentation (missing space detection)
        val split = tryWordSplit(typed)
        if (split != null) {
            DebugLog.d("AutocorrectManager: Split '$typed' → '$split'")
            return split
        }

        // 2. Try spell correction
        val corrections = getSpellCorrections(typed, 1)
        if (corrections.isEmpty()) return null

        val best = corrections.first()
        val dist = levenshteinDistance(typed, best)
        if (dist > 2 || dist >= typed.length) return null

        DebugLog.d("AutocorrectManager: Corrected '$typed' → '$best'")
        return best
    }

    /**
     * Record an autocorrect for undo-on-backspace support.
     */
    fun recordAutocorrect(original: String, replacement: String) {
        lastAutocorrect = AutocorrectRecord(original = original, replacement = replacement)
    }

    fun onWordBoundary() {
        currentWord.clear()
        cachedSuggestions = emptyList()
        onSuggestionsUpdated?.invoke("", emptyList())
    }

    fun onPredictionTapped(suggestion: String): String {
        val typed = currentWord.toString()
        currentWord.clear()
        cachedSuggestions = emptyList()
        onSuggestionsUpdated?.invoke("", emptyList())
        // Only record autocorrect if the suggestion differs from what was typed (case-insensitive)
        if (typed.isNotEmpty() && !suggestion.equals(typed, ignoreCase = true)) {
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

        // Check for missing space first (e.g. "andstill" → "and still")
        // If found, show split as top suggestion
        val wordSplit = if (!isKnownWord(typed)) tryWordSplit(typed.lowercase()) else null

        // All candidates: typed word (if valid) + prefix completions + spell corrections
        // Sorted by frequency. The typed word competes fairly with completions.
        val candidates = mutableListOf<String>()

        // Word split gets highest priority
        if (wordSplit != null) {
            candidates.add(wordSplit)
        }

        // Include the typed word itself if it's a dictionary word
        if (isKnownWord(typed)) {
            candidates.add(typed.lowercase())
        }

        // Add prefix completions (excluding exact match, already added above)
        val completions = getPrefixCompletions(typed, 5)
            .filter { it != typed.lowercase() && it != wordSplit }
        candidates.addAll(completions)

        // Add spell corrections as fallback
        if (candidates.size < 3) {
            val corrections = getSpellCorrections(typed, 3 - candidates.size)
                .filter { it !in candidates }
            candidates.addAll(corrections)
        }

        // Word split stays first, rest sorted by frequency
        val split = if (wordSplit != null && candidates.firstOrNull() == wordSplit) {
            listOf(wordSplit) + candidates.drop(1).sortedBy { getFrequency(it) }.take(2)
        } else {
            candidates.sortedBy { getFrequency(it) }.take(3)
        }
        cachedSuggestions = split
        onSuggestionsUpdated?.invoke(typed, cachedSuggestions)
    }

    /**
     * Find dictionary words that start with the given prefix.
     * Returns results sorted by frequency (most common first).
     */
    private fun getPrefixCompletions(prefix: String, limit: Int): List<String> {
        val lower = prefix.lowercase()

        // Binary search for prefix range start
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

        // Sort by frequency (lower rank = more common first)
        return matches
            .sortedBy { getFrequency(it) }
            .take(limit)
    }

    /**
     * Find dictionary words closest to the input using QWERTY-weighted Levenshtein distance.
     * Uses first-letter index for performance. Also checks adjacent first-letter keys.
     * Results sorted by: edit distance first, then frequency for ties.
     */
    private fun getSpellCorrections(word: String, limit: Int): List<String> {
        val lower = word.lowercase()
        if (lower.length < 2) return emptyList()

        val minLen = (lower.length - 2).coerceAtLeast(1)
        val maxLen = lower.length + 2

        val firstLetterCandidates = mutableSetOf(lower[0])
        QWERTY_NEIGHBORS[lower[0]]?.let { firstLetterCandidates.addAll(it) }

        val candidates = firstLetterCandidates
            .flatMap { wordsByFirstLetter[it] ?: emptyList() }

        return candidates
            .asSequence()
            .filter { it.length in minLen..maxLen }
            .map { Triple(it, weightedLevenshtein(lower, it), getFrequency(it)) }
            .filter { it.second <= 2.0f && it.second > 0f }
            .sortedWith(compareBy({ it.second }, { it.third })) // edit distance, then frequency
            .take(limit)
            .map { it.first }
            .toList()
    }

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
                    dp[i - 1][j] + 1f,
                    dp[i][j - 1] + 1f,
                    dp[i - 1][j - 1] + subCost
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
