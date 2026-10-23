package com.tinydictation.android.service

import android.content.Context
import com.tinydictation.android.util.DebugLog
import java.io.BufferedReader
import java.io.InputStreamReader

class AutocorrectManager(private val context: Context) {

    private var dictionary = HashSet<String>(65536)
    private var wordFrequency = HashMap<String, Int>(65536)
    private var sortedWords: List<String> = emptyList()
    private var wordsByFirstLetter = HashMap<Char, MutableList<String>>()
    private val currentWord = StringBuilder()

    private var currentLanguage: String = "en"

    private val dictionaryCache = HashMap<String, DictionaryData>()

    private data class DictionaryData(
        val dictionary: HashSet<String>,
        val wordFrequency: HashMap<String, Int>,
        val sortedWords: List<String>,
        val wordsByFirstLetter: HashMap<Char, MutableList<String>>
    )

    var lastAutocorrect: AutocorrectRecord? = null
        private set

    var onSuggestionsUpdated: ((typed: String, suggestions: List<String>) -> Unit)? = null

    private var cachedSuggestions: List<String> = emptyList()

    data class AutocorrectRecord(
        val original: String,
        val replacement: String
    )

    init {
        loadDictionary("en")
    }

    fun setLanguage(language: String) {
        if (language == currentLanguage) return
        DebugLog.d("AutocorrectManager: Switching language from $currentLanguage to $language")
        currentLanguage = language
        loadDictionary(language)
        onWordBoundary()
    }

    fun getLanguage(): String = currentLanguage

    private fun getDictionaryFilename(language: String): String {
        return when (language) {
            "de" -> "dictionary_de.txt"
            else -> "dictionary.txt"
        }
    }

    private fun loadDictionary(language: String) {
        val cached = dictionaryCache[language]
        if (cached != null) {
            dictionary = cached.dictionary
            wordFrequency = cached.wordFrequency
            sortedWords = cached.sortedWords
            wordsByFirstLetter = cached.wordsByFirstLetter
            currentLanguage = language
            DebugLog.d("AutocorrectManager: Loaded $language dictionary from cache (${dictionary.size} words)")
            return
        }

        val filename = getDictionaryFilename(language)
        try {
            val newDictionary = HashSet<String>(65536)
            val newWordFrequency = HashMap<String, Int>(65536)
            val newWordsByFirstLetter = HashMap<Char, MutableList<String>>()
            val words = mutableListOf<String>()

            context.assets.open(filename).use { stream ->
                BufferedReader(InputStreamReader(stream)).use { reader ->
                    reader.forEachLine { line ->
                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) return@forEachLine

                        val parts = trimmed.split('\t')
                        val word = parts[0].lowercase()
                        val rank = parts.getOrNull(1)?.toIntOrNull() ?: 99999

                        if (word.isNotEmpty()) {
                            newDictionary.add(word)
                            newWordFrequency[word] = rank
                            words.add(word)

                            newWordsByFirstLetter.getOrPut(word[0]) { mutableListOf() }.add(word)
                        }
                    }
                }
            }

            val newSortedWords = words.sorted()

            dictionaryCache[language] = DictionaryData(
                dictionary = newDictionary,
                wordFrequency = newWordFrequency,
                sortedWords = newSortedWords,
                wordsByFirstLetter = newWordsByFirstLetter
            )

            dictionary = newDictionary
            wordFrequency = newWordFrequency
            sortedWords = newSortedWords
            wordsByFirstLetter = newWordsByFirstLetter
            currentLanguage = language

            val rankedCount = newWordFrequency.count { it.value < 99999 }
            DebugLog.d("AutocorrectManager: Loaded ${dictionary.size} words for '$language' ($rankedCount with frequency data)")
        } catch (e: Exception) {
            DebugLog.e("AutocorrectManager: Failed to load dictionary for '$language': ${e.message}")
            if (language != "en" && dictionary.isEmpty()) {
                DebugLog.d("AutocorrectManager: Falling back to English dictionary")
                loadDictionary("en")
            }
        }
    }

    private fun getFrequency(word: String): Int = wordFrequency[word.lowercase()] ?: 99999

    fun isKnownWord(word: String): Boolean = dictionary.contains(word.lowercase())

    fun onLetterTyped(char: Char) {
        currentWord.append(char)
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

        val split = tryWordSplit(typed)
        if (split != null) {
            lastAutocorrect = AutocorrectRecord(original = typed, replacement = split)
            DebugLog.d("AutocorrectManager: Split", "'$typed' → '$split'")
            return split
        }

        val corrections = getSpellCorrections(typed, 1)
        if (corrections.isEmpty()) return null

        val best = corrections.first()
        val dist = levenshteinDistance(typed, best)
        if (dist > 2 || dist >= typed.length) return null

        lastAutocorrect = AutocorrectRecord(original = typed, replacement = best)
        DebugLog.d("AutocorrectManager: Corrected", "'$typed' → '$best'")
        return best
    }

    private fun tryWordSplit(word: String): String? {
        val lower = word.lowercase()
        if (lower.length < 3) return null

        var bestSplit: String? = null
        var bestScore = Int.MAX_VALUE

        for (i in 1..lower.length - 2) {
            val left = lower.substring(0, i)
            val right = lower.substring(i)

            if (isKnownWord(left) && isKnownWord(right)) {
                val score = getFrequency(left) + getFrequency(right)
                if (score < bestScore) {
                    bestScore = score
                    bestSplit = "$left $right"
                }
            }
        }

        if (bestSplit != null && bestScore < 50000) {
            return bestSplit
        }
        return null
    }

    fun tryCorrectWord(typed: String): String? {
        if (typed.isEmpty() || typed.length < 2) return null
        if (isKnownWord(typed)) return null

        val split = tryWordSplit(typed)
        if (split != null) {
            DebugLog.d("AutocorrectManager: Split", "'$typed' → '$split'")
            return split
        }

        val corrections = getSpellCorrections(typed, 1)
        if (corrections.isEmpty()) return null

        val best = corrections.first()
        val dist = levenshteinDistance(typed, best)
        if (dist > 2 || dist >= typed.length) return null

        DebugLog.d("AutocorrectManager: Corrected", "'$typed' → '$best'")
        return best
    }

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

        val candidates = mutableListOf<String>()

        if (isKnownWord(typed)) {
            candidates.add(typed.lowercase())
        }

        val completions = getPrefixCompletions(typed, 5)
            .filter { it != typed.lowercase() }
        candidates.addAll(completions)

        val wordSplit = if (!isKnownWord(typed) && completions.isEmpty()) {
            tryWordSplit(typed.lowercase())
        } else null

        if (wordSplit != null) {
            candidates.add(0, wordSplit)
        }

        if (candidates.size < 3) {
            val corrections = getSpellCorrections(typed, 3 - candidates.size)
                .filter { it !in candidates }
            candidates.addAll(corrections)
        }

        cachedSuggestions = if (wordSplit != null && candidates.firstOrNull() == wordSplit) {
            listOf(wordSplit) + candidates.drop(1).sortedBy { getFrequency(it) }.take(2)
        } else {
            candidates.sortedBy { getFrequency(it) }.take(3)
        }
        onSuggestionsUpdated?.invoke(typed, cachedSuggestions)
    }

    private fun getPrefixCompletions(prefix: String, limit: Int): List<String> {
        val lower = prefix.lowercase()

        var lo = 0
        var hi = sortedWords.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (sortedWords[mid] < lower) lo = mid + 1 else hi = mid
        }

        val matches = mutableListOf<String>()
        var i = lo
        while (i < sortedWords.size && sortedWords[i].startsWith(lower)) {
            matches.add(sortedWords[i])
            i++
        }

        return matches
            .sortedBy { getFrequency(it) }
            .take(limit)
    }

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
            .sortedWith(compareBy({ it.second }, { it.third }))
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
            'm' to setOf('n', 'j', 'k'),
            'ä' to setOf('a', 's', 'q', 'w'),
            'ö' to setOf('o', 'i', 'p', 'k', 'l'),
            'ü' to setOf('u', 'y', 'i', 'h', 'j'),
            'ß' to setOf('s', 'a', 'd')
        )
    }
}
