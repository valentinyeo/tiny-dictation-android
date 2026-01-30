package com.tinydictation.android.prediction

import android.content.Context
import com.tinydictation.android.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Simple n-gram based prediction engine using word frequency and bigram data.
 * Fast, offline, no external dependencies - good baseline for comparison.
 */
class NGramPredictionEngine : PredictionEngine {

    override val engineId = "ngram"
    override val displayName = "N-gram (Offline)"
    override val requiresInternet = false

    // Word frequency map (word -> frequency score)
    private val wordFrequency = mutableMapOf<String, Int>()

    // Bigram map (previous word -> list of likely next words)
    private val bigrams = mutableMapOf<String, MutableList<String>>()

    // Trie for fast prefix matching
    private val trie = Trie()

    // User's personalized word frequency (persisted to SharedPreferences)
    private val userWordFrequency = mutableMapOf<String, Int>()
    private var appContext: Context? = null
    private val USER_WORDS_KEY = "user_word_frequency"
    private val MAX_USER_WORDS = 500

    private var isInitialized = false

    override suspend fun initialize(context: Context): Result<Unit> = withContext(Dispatchers.Default) {
        try {
            if (isInitialized) return@withContext Result.success(Unit)

            appContext = context.applicationContext
            val startTime = System.currentTimeMillis()

            // Load common English words with frequencies
            loadWordFrequencies()

            // Load common bigrams (word pairs)
            loadBigrams()

            // Build trie for prefix matching
            wordFrequency.keys.forEach { trie.insert(it) }

            // Load user's personalized word frequency
            loadUserWordFrequency()

            isInitialized = true
            val elapsed = System.currentTimeMillis() - startTime
            DebugLog.i("NGramPredictionEngine: Initialized in ${elapsed}ms, ${wordFrequency.size} words, ${bigrams.size} bigrams, ${userWordFrequency.size} user words")

            Result.success(Unit)
        } catch (e: Exception) {
            DebugLog.e("NGramPredictionEngine: Init failed - ${e.message}")
            Result.failure(e)
        }
    }

    override suspend fun predict(
        contextWords: List<String>,
        partialWord: String
    ): List<Prediction> = withContext(Dispatchers.Default) {
        if (!isInitialized) return@withContext emptyList()

        val startTime = System.nanoTime()
        val results = mutableListOf<Prediction>()

        val previousWord = contextWords.lastOrNull()?.lowercase()

        if (partialWord.isNotEmpty()) {
            // Complete the partial word
            val prefix = partialWord.lowercase()
            val completions = trie.findWordsWithPrefix(prefix, limit = 10)

            if (completions.isNotEmpty()) {
                // Score completions by frequency and bigram relevance
                val scored = completions.map { word ->
                    val freq = wordFrequency[word] ?: 1
                    val bigramBoost = if (previousWord != null && bigrams[previousWord]?.contains(word) == true) 3 else 1
                    word to (freq * bigramBoost)
                }.sortedByDescending { it.second }

                results.addAll(scored.take(3).map { (word, score) ->
                    Prediction(
                        word = word,
                        confidence = score.toFloat() / 1000f,
                        isCompletion = true
                    )
                })
            } else {
                // No prefix matches - try fuzzy matching for typo correction
                val fuzzyMatches = findFuzzyMatches(prefix, maxDistance = 2, limit = 10)

                // Score by edit distance (lower is better) and frequency
                val scored = fuzzyMatches.map { (word, distance) ->
                    val freq = wordFrequency[word] ?: 1
                    val bigramBoost = if (previousWord != null && bigrams[previousWord]?.contains(word) == true) 2 else 1
                    // Penalize by distance: distance 1 = 0.8x, distance 2 = 0.6x
                    val distancePenalty = 1.0 - (distance * 0.2)
                    word to (freq * bigramBoost * distancePenalty)
                }.sortedByDescending { it.second }

                results.addAll(scored.take(3).map { (word, score) ->
                    Prediction(
                        word = word,
                        confidence = (score / 1000.0).toFloat().coerceIn(0f, 1f),
                        isCompletion = true  // Treat as completion (replace the typo)
                    )
                })
            }
        } else if (previousWord != null) {
            // Next word prediction based on bigrams
            val nextWords = bigrams[previousWord] ?: emptyList()

            if (nextWords.isNotEmpty()) {
                results.addAll(nextWords.take(3).mapIndexed { index, word ->
                    Prediction(
                        word = word,
                        confidence = 1f - (index * 0.2f),
                        isCompletion = false
                    )
                })
            } else {
                // Fallback to most common words
                results.addAll(COMMON_NEXT_WORDS.take(3).mapIndexed { index, word ->
                    Prediction(
                        word = word,
                        confidence = 0.5f - (index * 0.1f),
                        isCompletion = false
                    )
                })
            }
        } else {
            // No context at all - show default starting words (like SwiftKey)
            // Prioritize user's frequently used words, then fall back to common starters
            val defaultWords = getDefaultPredictions()
            results.addAll(defaultWords.take(3).mapIndexed { index, word ->
                Prediction(
                    word = word,
                    confidence = 0.4f - (index * 0.1f),
                    isCompletion = false
                )
            })
        }

        val elapsed = (System.nanoTime() - startTime) / 1_000_000.0
        if (results.isNotEmpty()) {
            DebugLog.d("NGramPrediction: '$partialWord' after '$previousWord' -> ${results.map { it.word }} in ${String.format("%.2f", elapsed)}ms")
        }

        results
    }

    override fun release() {
        // Save user word frequency before releasing
        saveUserWordFrequency()
        wordFrequency.clear()
        bigrams.clear()
        userWordFrequency.clear()
        isInitialized = false
        DebugLog.i("NGramPredictionEngine: Released")
    }

    override fun learn(words: List<String>) {
        // Track word frequency for personalization
        for (word in words) {
            val cleanWord = word.lowercase().trim()
            if (cleanWord.length >= 2) {  // Only track words with 2+ chars
                userWordFrequency[cleanWord] = (userWordFrequency[cleanWord] ?: 0) + 1
            }
        }

        // Update bigrams
        if (words.size >= 2) {
            val prev = words[words.size - 2].lowercase()
            val curr = words[words.size - 1].lowercase()
            bigrams.getOrPut(prev) { mutableListOf() }.let {
                if (!it.contains(curr)) {
                    it.add(0, curr) // Add to front as most recent
                    if (it.size > 10) it.removeLast()
                }
            }
        }

        // Periodically save user words (every 10 learns)
        if (userWordFrequency.size % 10 == 0) {
            saveUserWordFrequency()
        }
    }

    private fun loadWordFrequencies() {
        // Top 1000 English words with approximate frequencies
        // Higher number = more common
        COMMON_WORDS.forEachIndexed { index, word ->
            wordFrequency[word] = 1000 - index
        }
    }

    private fun loadBigrams() {
        // Common word pairs in English
        COMMON_BIGRAMS.forEach { (first, seconds) ->
            bigrams[first] = seconds.toMutableList()
        }
    }

    /**
     * Load user's personalized word frequency from SharedPreferences.
     */
    private fun loadUserWordFrequency() {
        try {
            val prefs = appContext?.getSharedPreferences("prediction_prefs", Context.MODE_PRIVATE) ?: return
            val stored = prefs.getString(USER_WORDS_KEY, null) ?: return

            // Format: "word1:count1,word2:count2,..."
            stored.split(",").forEach { entry ->
                val parts = entry.split(":")
                if (parts.size == 2) {
                    val word = parts[0]
                    val count = parts[1].toIntOrNull() ?: 0
                    if (word.isNotEmpty() && count > 0) {
                        userWordFrequency[word] = count
                    }
                }
            }
            DebugLog.d("NGramPredictionEngine: Loaded ${userWordFrequency.size} user words")
        } catch (e: Exception) {
            DebugLog.e("NGramPredictionEngine: Error loading user words - ${e.message}")
        }
    }

    /**
     * Save user's personalized word frequency to SharedPreferences.
     */
    private fun saveUserWordFrequency() {
        try {
            val prefs = appContext?.getSharedPreferences("prediction_prefs", Context.MODE_PRIVATE) ?: return

            // Keep only top N words by frequency to prevent unbounded growth
            val topWords = userWordFrequency.entries
                .sortedByDescending { it.value }
                .take(MAX_USER_WORDS)

            // Format: "word1:count1,word2:count2,..."
            val serialized = topWords.joinToString(",") { "${it.key}:${it.value}" }

            prefs.edit().putString(USER_WORDS_KEY, serialized).apply()
            DebugLog.d("NGramPredictionEngine: Saved ${topWords.size} user words")
        } catch (e: Exception) {
            DebugLog.e("NGramPredictionEngine: Error saving user words - ${e.message}")
        }
    }

    /**
     * Get default predictions when no text is typed.
     * Prioritizes user's frequently typed words, falls back to common starters.
     */
    private fun getDefaultPredictions(): List<String> {
        // Get user's most frequent words
        val userTopWords = userWordFrequency.entries
            .sortedByDescending { it.value }
            .take(5)
            .map { it.key }

        // Common starting words (I, The, Hello, etc.)
        val commonStarters = DEFAULT_STARTING_WORDS

        // Blend: prioritize user words, fill with common starters
        val result = mutableListOf<String>()
        result.addAll(userTopWords)

        // Add common starters that aren't already in the list
        for (word in commonStarters) {
            if (!result.contains(word) && result.size < 6) {
                result.add(word)
            }
        }

        return result.take(6)
    }

    companion object {
        // Default starting words (when nothing is typed) - common sentence starters
        private val DEFAULT_STARTING_WORDS = listOf(
            "I", "The", "Hello", "Hey", "Thanks", "Hi", "Yes", "No", "OK", "Sure"
        )
        // Top common words for completion
        private val COMMON_WORDS = listOf(
            "the", "be", "to", "of", "and", "a", "in", "that", "have", "i",
            "it", "for", "not", "on", "with", "he", "as", "you", "do", "at",
            "this", "but", "his", "by", "from", "they", "we", "say", "her", "she",
            "or", "an", "will", "my", "one", "all", "would", "there", "their", "what",
            "so", "up", "out", "if", "about", "who", "get", "which", "go", "me",
            "when", "make", "can", "like", "time", "no", "just", "him", "know", "take",
            "people", "into", "year", "your", "good", "some", "could", "them", "see", "other",
            "than", "then", "now", "look", "only", "come", "its", "over", "think", "also",
            "back", "after", "use", "two", "how", "our", "work", "first", "well", "way",
            "even", "new", "want", "because", "any", "these", "give", "day", "most", "us",
            "is", "are", "was", "were", "been", "being", "has", "had", "does", "did",
            "doing", "done", "going", "got", "getting", "made", "making", "said", "saying",
            "thank", "thanks", "please", "sorry", "okay", "yes", "no", "maybe", "sure", "right",
            "great", "nice", "good", "bad", "better", "best", "more", "less", "very", "really",
            "here", "there", "where", "when", "why", "how", "what", "which", "who", "whom",
            "hello", "hi", "hey", "bye", "goodbye", "morning", "afternoon", "evening", "night",
            "today", "tomorrow", "yesterday", "week", "month", "year", "Monday", "Tuesday",
            "Wednesday", "Thursday", "Friday", "Saturday", "Sunday",
            "January", "February", "March", "April", "May", "June", "July", "August",
            "September", "October", "November", "December",
            "love", "life", "world", "home", "house", "place", "thing", "man", "woman", "child",
            "friend", "family", "mother", "father", "brother", "sister", "son", "daughter",
            "email", "phone", "call", "text", "message", "send", "reply", "forward",
            "meeting", "schedule", "appointment", "reminder", "note", "task", "project",
            "address", "number", "name", "date", "time", "location", "direction",
            // Common words for typing
            "test", "testing", "try", "trying", "check", "checking", "start", "stop",
            "open", "close", "save", "delete", "edit", "copy", "paste", "cut",
            "search", "find", "help", "support", "question", "answer", "problem", "issue",
            "working", "works", "work", "done", "finish", "finished", "complete", "completed",
            "wait", "waiting", "ready", "set", "setting", "settings", "option", "options",
            "button", "click", "tap", "press", "type", "typing", "write", "writing",
            "read", "reading", "look", "looking", "watch", "watching", "listen", "listening",
            "talk", "talking", "speak", "speaking", "tell", "telling", "ask", "asking",
            "give", "giving", "take", "taking", "put", "putting", "keep", "keeping",
            "show", "showing", "hide", "hiding", "turn", "turning", "change", "changing",
            "add", "adding", "remove", "removing", "move", "moving", "update", "updating",
            "create", "creating", "build", "building", "run", "running", "install", "installing",
            "download", "downloading", "upload", "uploading", "share", "sharing",
            "login", "logout", "sign", "signup", "password", "username", "account",
            "file", "folder", "document", "image", "photo", "video", "music", "audio",
            "app", "application", "website", "page", "link", "url", "browser", "internet",
            "computer", "laptop", "desktop", "mobile", "tablet", "device", "screen", "display",
            "keyboard", "mouse", "touch", "scroll", "zoom", "rotate", "resize",
            "error", "warning", "success", "failed", "loading", "processing", "complete",
            // More common words
            "predict", "prediction", "predictions", "predicting", "predicted",
            "sentence", "sentences", "word", "words", "text", "texts",
            "auto", "automatic", "automatically", "correct", "correction", "correcting",
            "suggest", "suggestion", "suggestions", "suggesting", "suggested",
            "typing", "typed", "types", "feature", "features", "function", "functions",
            "new", "old", "next", "previous", "first", "last", "second", "third",
            "test", "testing", "tested", "tests", "example", "examples",
            "input", "output", "enter", "exit", "start", "end", "begin", "finish"
        )

        // Common next words when no context
        private val COMMON_NEXT_WORDS = listOf("I", "the", "a", "to", "and", "is", "it", "you", "that", "this")

        // Common bigrams: first word -> likely next words
        private val COMMON_BIGRAMS = mapOf(
            "i" to listOf("am", "have", "was", "will", "would", "can", "don't", "think", "want", "need"),
            "i'm" to listOf("going", "not", "sorry", "sure", "here", "coming", "done", "fine", "good", "happy"),
            "i'll" to listOf("be", "call", "see", "get", "let", "send", "do", "try", "have", "take"),
            "i've" to listOf("been", "got", "had", "never", "always", "just", "already", "done", "seen", "heard"),
            "you" to listOf("are", "have", "can", "will", "should", "could", "would", "need", "want", "know"),
            "you're" to listOf("right", "welcome", "going", "not", "the", "so", "very", "too", "such", "being"),
            "we" to listOf("are", "have", "can", "will", "should", "could", "would", "need", "want", "were"),
            "they" to listOf("are", "have", "can", "will", "would", "were", "said", "want", "need", "don't"),
            "he" to listOf("is", "was", "has", "will", "would", "said", "can", "could", "does", "did"),
            "she" to listOf("is", "was", "has", "will", "would", "said", "can", "could", "does", "did"),
            "it" to listOf("is", "was", "will", "would", "can", "could", "has", "does", "looks", "seems"),
            "that" to listOf("is", "was", "would", "will", "the", "I", "you", "we", "he", "she"),
            "this" to listOf("is", "was", "will", "would", "the", "one", "week", "year", "time", "morning"),
            "the" to listOf("best", "first", "most", "same", "other", "new", "next", "last", "only", "way"),
            "a" to listOf("lot", "few", "little", "bit", "new", "good", "great", "long", "big", "small"),
            "to" to listOf("be", "do", "get", "have", "make", "see", "go", "know", "take", "come"),
            "of" to listOf("the", "a", "my", "your", "our", "this", "that", "it", "course", "them"),
            "in" to listOf("the", "a", "my", "your", "this", "that", "order", "case", "fact", "addition"),
            "on" to listOf("the", "my", "your", "this", "that", "Monday", "Tuesday", "time", "top", "it"),
            "for" to listOf("the", "a", "you", "your", "me", "us", "this", "that", "now", "example"),
            "with" to listOf("the", "a", "my", "your", "you", "me", "us", "this", "that", "it"),
            "at" to listOf("the", "a", "all", "least", "once", "first", "home", "work", "this", "that"),
            "is" to listOf("a", "the", "not", "it", "this", "that", "there", "here", "very", "so"),
            "are" to listOf("you", "we", "they", "there", "not", "the", "a", "so", "very", "going"),
            "was" to listOf("a", "the", "not", "it", "there", "so", "very", "going", "able", "just"),
            "have" to listOf("a", "to", "been", "not", "the", "any", "some", "no", "you", "it"),
            "has" to listOf("been", "a", "the", "not", "to", "no", "some", "any", "already", "just"),
            "will" to listOf("be", "have", "not", "you", "do", "get", "take", "make", "see", "call"),
            "would" to listOf("be", "have", "like", "you", "not", "love", "want", "need", "prefer", "rather"),
            "can" to listOf("you", "I", "we", "be", "do", "get", "have", "make", "see", "help"),
            "could" to listOf("you", "I", "we", "be", "have", "not", "do", "get", "make", "see"),
            "should" to listOf("be", "have", "I", "you", "we", "not", "do", "get", "make", "take"),
            "do" to listOf("you", "not", "it", "this", "that", "the", "we", "I", "they", "so"),
            "don't" to listOf("know", "think", "want", "have", "need", "like", "worry", "forget", "be", "do"),
            "going" to listOf("to", "on", "out", "home", "back", "forward", "well", "ahead", "down", "up"),
            "want" to listOf("to", "you", "me", "it", "a", "the", "this", "that", "some", "any"),
            "need" to listOf("to", "a", "the", "your", "some", "any", "help", "more", "it", "this"),
            "know" to listOf("that", "what", "how", "if", "you", "I", "about", "the", "it", "this"),
            "think" to listOf("that", "about", "of", "so", "it", "I", "you", "we", "the", "this"),
            "get" to listOf("a", "the", "it", "to", "back", "out", "up", "in", "some", "this"),
            "got" to listOf("a", "the", "it", "to", "back", "home", "some", "any", "this", "that"),
            "make" to listOf("a", "the", "it", "sure", "sense", "up", "some", "this", "that", "an"),
            "take" to listOf("a", "the", "it", "care", "this", "that", "some", "time", "place", "off"),
            "see" to listOf("you", "if", "the", "what", "how", "it", "that", "this", "a", "my"),
            "let" to listOf("me", "us", "him", "her", "them", "it", "the", "you", "know", "go"),
            "let's" to listOf("go", "see", "do", "get", "make", "try", "talk", "meet", "start", "finish"),
            "please" to listOf("let", "do", "don't", "be", "call", "send", "help", "take", "give", "come"),
            "thank" to listOf("you", "goodness", "god", "him", "her", "them", "everyone", "all"),
            "thanks" to listOf("for", "a", "so", "very", "again", "anyway", "though", "man", "dude"),
            "sorry" to listOf("for", "about", "to", "I", "but", "if", "that", "but", "I'm"),
            "how" to listOf("are", "do", "is", "was", "about", "much", "many", "long", "can", "did"),
            "what" to listOf("is", "are", "do", "did", "does", "was", "about", "the", "a", "if"),
            "when" to listOf("I", "you", "we", "they", "is", "are", "will", "do", "did", "the"),
            "where" to listOf("is", "are", "do", "did", "the", "I", "you", "we", "can", "should"),
            "why" to listOf("do", "did", "is", "are", "would", "don't", "not", "the", "I", "you"),
            "just" to listOf("a", "the", "to", "in", "wanted", "need", "like", "got", "had", "saw"),
            "very" to listOf("much", "good", "nice", "well", "important", "happy", "sorry", "excited", "interesting"),
            "really" to listOf("good", "nice", "want", "need", "like", "appreciate", "sorry", "happy", "enjoyed"),
            "good" to listOf("morning", "afternoon", "evening", "night", "luck", "job", "work", "idea", "one", "to"),
            "look" to listOf("at", "for", "like", "forward", "into", "out", "up", "good", "great", "nice"),
            "looking" to listOf("for", "forward", "at", "into", "good", "great", "like", "to", "up"),
            "look forward" to listOf("to"),
            "looking forward" to listOf("to"),
            "forward" to listOf("to"),
            "talk" to listOf("to", "about", "with", "later", "soon", "tomorrow", "again"),
            "call" to listOf("me", "you", "back", "later", "tomorrow", "soon", "the", "a"),
            "send" to listOf("me", "you", "it", "the", "a", "an", "this", "that", "over"),
            "come" to listOf("back", "in", "on", "over", "here", "to", "with", "from", "home"),
            "go" to listOf("to", "ahead", "back", "home", "out", "on", "with", "for", "now"),
            "back" to listOf("to", "home", "in", "on", "up", "later", "soon", "tomorrow", "again")
        )
    }

    /**
     * Find words similar to the input using edit distance (Levenshtein distance).
     * Used for typo correction when no prefix matches are found.
     *
     * @param input The misspelled word to find matches for
     * @param maxDistance Maximum edit distance to consider (1-2 recommended)
     * @param limit Maximum number of results to return
     * @return List of pairs (word, editDistance) sorted by distance
     */
    private fun findFuzzyMatches(input: String, maxDistance: Int, limit: Int): List<Pair<String, Int>> {
        val inputLower = input.lowercase()
        val inputLen = inputLower.length

        // Only check words with similar length (within maxDistance chars)
        val candidates = wordFrequency.keys.filter { word ->
            val lenDiff = kotlin.math.abs(word.length - inputLen)
            lenDiff <= maxDistance
        }

        // Calculate edit distance for each candidate
        val matches = candidates.mapNotNull { word ->
            val distance = editDistance(inputLower, word)
            if (distance <= maxDistance) {
                word to distance
            } else {
                null
            }
        }

        // Sort by distance (lower is better), then by frequency
        return matches.sortedWith(
            compareBy<Pair<String, Int>> { it.second }
                .thenByDescending { wordFrequency[it.first] ?: 0 }
        ).take(limit)
    }

    /**
     * Calculate Levenshtein edit distance between two strings.
     * Optimized with early termination for performance.
     */
    private fun editDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length

        // Quick checks
        if (m == 0) return n
        if (n == 0) return m
        if (s1 == s2) return 0

        // Use two rows instead of full matrix for memory efficiency
        var prevRow = IntArray(n + 1) { it }
        var currRow = IntArray(n + 1)

        for (i in 1..m) {
            currRow[0] = i
            for (j in 1..n) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                currRow[j] = minOf(
                    prevRow[j] + 1,      // deletion
                    currRow[j - 1] + 1,  // insertion
                    prevRow[j - 1] + cost // substitution
                )
            }
            // Swap rows
            val temp = prevRow
            prevRow = currRow
            currRow = temp
        }

        return prevRow[n]
    }

    /**
     * Simple Trie for fast prefix matching
     */
    private class Trie {
        private val root = TrieNode()

        fun insert(word: String) {
            var node = root
            for (char in word.lowercase()) {
                node = node.children.getOrPut(char) { TrieNode() }
            }
            node.isWord = true
            node.word = word
        }

        fun findWordsWithPrefix(prefix: String, limit: Int = 10): List<String> {
            var node = root
            for (char in prefix.lowercase()) {
                node = node.children[char] ?: return emptyList()
            }
            return collectWords(node, limit)
        }

        private fun collectWords(node: TrieNode, limit: Int): List<String> {
            val results = mutableListOf<String>()
            collectWordsRecursive(node, results, limit)
            return results
        }

        private fun collectWordsRecursive(node: TrieNode, results: MutableList<String>, limit: Int) {
            if (results.size >= limit) return
            if (node.isWord && node.word != null) {
                results.add(node.word!!)
            }
            for (child in node.children.values) {
                if (results.size >= limit) break
                collectWordsRecursive(child, results, limit)
            }
        }

        private class TrieNode {
            val children = mutableMapOf<Char, TrieNode>()
            var isWord = false
            var word: String? = null
        }
    }
}
