package com.tinydictation.android.correction

import android.content.Context
import com.tinydictation.android.util.DebugLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Dictionary system for Stage 1 word-level correction.
 *
 * Uses a Trie structure for fast prefix matching and word lookup,
 * plus maps for typo corrections and skip lists.
 */
class Dictionary(private val context: Context) {

    companion object {
        private const val TAG = "Dictionary"
        private const val LEARNED_PREFS = "correction_learned_prefs"
        private const val KEY_SKIP_LIST = "skip_list"
        private const val KEY_LEARNED_CORRECTIONS = "learned_corrections"
        private const val KEY_PENDING_LEARN = "pending_learn"
        private const val KEY_REJECTED_CORRECTIONS = "rejected_corrections"
        private const val LEARN_THRESHOLD = 3 // Occurrences before learning

        // Common English words (fallback if dictionary.txt not present)
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
            "today", "tomorrow", "yesterday", "week", "month", "store", "buy", "milk", "water",
            "food", "eat", "drink", "sleep", "walk", "run", "drive", "car", "home", "house",
            "need", "want", "love", "hate", "feel", "think", "believe", "remember", "forget",
            "start", "stop", "begin", "end", "open", "close", "read", "write", "speak", "listen",
            "call", "text", "email", "send", "receive", "meet", "talk", "tell", "ask", "answer",
            "help", "try", "work", "play", "watch", "wait", "leave", "stay", "come", "bring",
            "take", "give", "put", "keep", "let", "show", "turn", "change", "move", "pay"
        )

        // Common typo corrections (fallback if typos.json not present)
        private val COMMON_TYPOS = mapOf(
            "teh" to "the",
            "thier" to "their",
            "recieve" to "receive",
            "occured" to "occurred",
            "seperate" to "separate",
            "definately" to "definitely",
            "occassion" to "occasion",
            "accomodate" to "accommodate",
            "acheive" to "achieve",
            "aquire" to "acquire",
            "begining" to "beginning",
            "beleive" to "believe",
            "calender" to "calendar",
            "collegue" to "colleague",
            "concious" to "conscious",
            "definite" to "definite",
            "enviroment" to "environment",
            "existance" to "existence",
            "foriegn" to "foreign",
            "freind" to "friend",
            "goverment" to "government",
            "grammer" to "grammar",
            "harrass" to "harass",
            "immediatly" to "immediately",
            "independant" to "independent",
            "knowlege" to "knowledge",
            "liason" to "liaison",
            "maintainance" to "maintenance",
            "neccessary" to "necessary",
            "noticable" to "noticeable",
            "occurence" to "occurrence",
            "parliment" to "parliament",
            "posession" to "possession",
            "priviledge" to "privilege",
            "pronounciation" to "pronunciation",
            "publically" to "publicly",
            "recomend" to "recommend",
            "refering" to "referring",
            "relevent" to "relevant",
            "resistence" to "resistance",
            "responsability" to "responsibility",
            "seize" to "seize",
            "succesful" to "successful",
            "suprise" to "surprise",
            "tommorow" to "tomorrow",
            "truely" to "truly",
            "untill" to "until",
            "wierd" to "weird",
            "wether" to "whether",
            "writting" to "writing",
            "youre" to "you're",
            "dont" to "don't",
            "cant" to "can't",
            "wont" to "won't",
            "didnt" to "didn't",
            "doesnt" to "doesn't",
            "isnt" to "isn't",
            "wasnt" to "wasn't",
            "arent" to "aren't",
            "werent" to "weren't",
            "havent" to "haven't",
            "hasnt" to "hasn't",
            "hadnt" to "hadn't",
            "wouldnt" to "wouldn't",
            "couldnt" to "couldn't",
            "shouldnt" to "shouldn't",
            "ive" to "i've",
            "im" to "i'm",
            "ill" to "i'll",
            "id" to "i'd",
            "youve" to "you've",
            "youll" to "you'll",
            "youd" to "you'd",
            "hes" to "he's",
            "shes" to "she's",
            "its" to "it's",
            "theyre" to "they're",
            "theyve" to "they've",
            "theyll" to "they'll",
            "weve" to "we've",
            "were" to "we're",
            "whats" to "what's",
            "thats" to "that's",
            "heres" to "here's",
            "theres" to "there's",
            "wheres" to "where's",
            "lets" to "let's",
            "alot" to "a lot",
            "gonna" to "going to",
            "wanna" to "want to",
            "gotta" to "got to",
            "kinda" to "kind of",
            "sorta" to "sort of",
            "coulda" to "could have",
            "woulda" to "would have",
            "shoulda" to "should have",
            "musta" to "must have",
            "oughta" to "ought to"
        )
    }

    // Core dictionary (Trie for fast lookup)
    private val wordTrie = Trie()
    private val wordSet = mutableSetOf<String>()

    // Skip list - words we should never correct (contacts, user-added)
    private val skipList = mutableSetOf<String>()

    // Static typo corrections (teh -> the)
    private val staticTypos = mutableMapOf<String, String>()

    // Learned typo corrections (promoted from pending)
    private val learnedTypos = mutableMapOf<String, String>()

    // Pending corrections (tracking occurrences before learning)
    private val pendingLearn = mutableMapOf<String, PendingLearn>()

    // Rejected corrections - maps original word to set of corrections the user rejected
    // e.g., "vwo" -> setOf("two", "tow") means never correct "vwo" to "two" or "tow"
    private val rejectedCorrections = mutableMapOf<String, MutableSet<String>>()

    private var isInitialized = false

    /**
     * Initialize the dictionary by loading word lists and typo maps.
     */
    suspend fun initialize(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (isInitialized) return@withContext Result.success(Unit)

            val startTime = System.currentTimeMillis()

            // Load base dictionary
            loadBaseDictionary()

            // Load static typo corrections
            loadStaticTypos()

            // Load persisted data
            loadPersistedData()

            isInitialized = true
            val elapsed = System.currentTimeMillis() - startTime
            DebugLog.i("$TAG: Initialized in ${elapsed}ms - ${wordSet.size} words, ${skipList.size} skip, ${staticTypos.size} typos, ${learnedTypos.size} learned")

            Result.success(Unit)
        } catch (e: Exception) {
            DebugLog.e("$TAG: Init failed - ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Check if a word is in the dictionary.
     */
    fun isValidWord(word: String): Boolean {
        return wordSet.contains(word.lowercase())
    }

    /**
     * Check if a word should be skipped (in skip list).
     */
    fun shouldSkip(word: String): Boolean {
        return skipList.contains(word.lowercase())
    }

    /**
     * Add a word to the skip list (won't be corrected).
     */
    fun addToSkipList(word: String) {
        skipList.add(word.lowercase())
        savePersistedData()
    }

    /**
     * Add multiple words to the skip list (e.g., contact names).
     */
    fun addToSkipList(words: Collection<String>) {
        words.forEach { skipList.add(it.lowercase()) }
        savePersistedData()
    }

    /**
     * Check if a specific correction was rejected by the user.
     * E.g., if user typed "vwo" and we corrected to "two", but they reverted,
     * this returns true for isRejectedCorrection("vwo", "two").
     */
    fun isRejectedCorrection(original: String, correction: String): Boolean {
        val rejected = rejectedCorrections[original.lowercase()] ?: return false
        return rejected.contains(correction.lowercase())
    }

    /**
     * Add a rejected correction pair. The user manually reverted this correction,
     * so we should never apply it again.
     *
     * @param original The original word the user typed (e.g., "vwo")
     * @param rejectedCorrection The correction they rejected (e.g., "two")
     */
    fun addRejectedCorrection(original: String, rejectedCorrection: String) {
        val originalLower = original.lowercase()
        val rejectedLower = rejectedCorrection.lowercase()

        val rejected = rejectedCorrections.getOrPut(originalLower) { mutableSetOf() }
        rejected.add(rejectedLower)

        DebugLog.i("$TAG: Added rejected correction: '$originalLower' -> '$rejectedLower' (never correct this pair again)")
        savePersistedData()
    }

    /**
     * Get all rejected corrections for debugging/settings UI.
     */
    fun getRejectedCorrections(): Map<String, Set<String>> {
        return rejectedCorrections.mapValues { it.value.toSet() }
    }

    /**
     * Clear all rejected corrections.
     */
    fun clearRejectedCorrections() {
        rejectedCorrections.clear()
        savePersistedData()
        DebugLog.i("$TAG: Cleared all rejected corrections")
    }

    /**
     * Get correction for a typo, checking static and learned maps.
     */
    fun getTypoCorrection(word: String): String? {
        val lower = word.lowercase()

        // Check learned first (user-specific)
        learnedTypos[lower]?.let { return it }

        // Check static typos
        staticTypos[lower]?.let { return it }

        return null
    }

    /**
     * Find words similar to the input using QWERTY-weighted edit distance.
     * Adjacent key substitutions cost less than distant key substitutions,
     * improving fat-finger tolerance on the QWERTY keyboard.
     *
     * @param word The potentially misspelled word
     * @param maxDistance Maximum edit distance (1-2 recommended)
     * @return Best matching word, or null if no good match
     */
    fun findSimilarWord(word: String, maxDistance: Int = 2): String? {
        val lower = word.lowercase()
        val inputLen = lower.length

        // Quick check - if it's already valid, no correction needed
        if (isValidWord(lower)) return null

        var bestMatch: String? = null
        // Use scaled distance (x2) to allow half-cost for adjacent keys
        var bestScaledDistance = maxDistance * 2 + 1

        // Only check words with similar length
        for (dictWord in wordSet) {
            val lenDiff = kotlin.math.abs(dictWord.length - inputLen)
            if (lenDiff > maxDistance) continue

            // First-character guard: skip candidates where first char differs
            // and is not an adjacent QWERTY key (prevents "simon" -> "iron")
            if (lower[0] != dictWord[0] && !areAdjacentKeys(lower[0], dictWord[0])) continue

            val scaledDistance = qwertyWeightedDistance(lower, dictWord, maxDistance * 2)
            if (scaledDistance <= maxDistance * 2 && scaledDistance < bestScaledDistance) {
                bestScaledDistance = scaledDistance
                bestMatch = dictWord

                // Early exit if we found a very close match (adjacent key swap = cost 1 scaled)
                if (scaledDistance <= 1) break
            }
        }

        return bestMatch
    }

    /**
     * Find words with a given prefix (for completion suggestions).
     */
    fun findWordsWithPrefix(prefix: String, limit: Int = 10): List<String> {
        return wordTrie.findWordsWithPrefix(prefix.lowercase(), limit)
    }

    /**
     * Record a correction made by Stage 2 for potential learning.
     * Will promote to learned after LEARN_THRESHOLD occurrences.
     */
    fun recordCorrection(typo: String, correction: String) {
        val typoLower = typo.lowercase()
        val correctionLower = correction.lowercase()

        // Only learn single-word corrections
        if (typoLower.contains(' ') || correctionLower.contains(' ')) {
            return
        }

        // Only learn if edit distance is small (clear typo)
        val distance = levenshteinDistance(typoLower, correctionLower)
        if (distance > 2) {
            return // Too different, probably context-dependent
        }

        // Track occurrence
        val pending = pendingLearn.getOrPut(typoLower) {
            PendingLearn(typoLower, correctionLower)
        }
        pending.count++

        // Check if ready to promote
        if (pending.count >= LEARN_THRESHOLD) {
            learnedTypos[typoLower] = correctionLower
            pendingLearn.remove(typoLower)
            DebugLog.i("$TAG: Learned correction: $typoLower -> $correctionLower")
        }

        savePersistedData()
    }

    /**
     * Reset all learned data (corrections and skip list).
     */
    fun resetLearnedData() {
        learnedTypos.clear()
        pendingLearn.clear()
        // Don't clear skip list - that's user-added words

        val prefs = context.getSharedPreferences(LEARNED_PREFS, Context.MODE_PRIVATE)
        prefs.edit()
            .remove(KEY_LEARNED_CORRECTIONS)
            .remove(KEY_PENDING_LEARN)
            .apply()

        DebugLog.i("$TAG: Reset learned data")
    }

    /**
     * Load the base dictionary from assets.
     */
    private fun loadBaseDictionary() {
        try {
            // First try loading from assets
            val inputStream = context.assets.open("dictionary.txt")
            BufferedReader(InputStreamReader(inputStream)).use { reader ->
                reader.forEachLine { line ->
                    val word = line.trim().lowercase()
                    if (word.isNotEmpty() && word.length >= 2) {
                        wordSet.add(word)
                        wordTrie.insert(word)
                    }
                }
            }
            DebugLog.d("$TAG: Loaded ${wordSet.size} words from assets")
        } catch (e: Exception) {
            // Fallback to embedded common words
            DebugLog.w("$TAG: No dictionary.txt in assets, using embedded words")
            loadEmbeddedDictionary()
        }
    }

    /**
     * Fallback: Load a small set of common words if assets aren't available.
     */
    private fun loadEmbeddedDictionary() {
        COMMON_WORDS.forEach { word ->
            wordSet.add(word)
            wordTrie.insert(word)
        }
    }

    /**
     * Load static typo corrections from assets or embedded map.
     */
    private fun loadStaticTypos() {
        try {
            val inputStream = context.assets.open("typos.json")
            val json = inputStream.bufferedReader().use { it.readText() }
            val jsonObject = JSONObject(json)

            jsonObject.keys().forEach { key ->
                staticTypos[key.lowercase()] = jsonObject.getString(key).lowercase()
            }
            DebugLog.d("$TAG: Loaded ${staticTypos.size} typo corrections from assets")
        } catch (e: Exception) {
            // Fallback to embedded typos
            DebugLog.w("$TAG: No typos.json in assets, using embedded typos")
            loadEmbeddedTypos()
        }
    }

    /**
     * Fallback: Load common typo corrections.
     */
    private fun loadEmbeddedTypos() {
        COMMON_TYPOS.forEach { (typo, correction) ->
            staticTypos[typo] = correction
        }
    }

    /**
     * Load persisted data (skip list, learned corrections).
     */
    private fun loadPersistedData() {
        try {
            val prefs = context.getSharedPreferences(LEARNED_PREFS, Context.MODE_PRIVATE)

            // Load skip list
            prefs.getStringSet(KEY_SKIP_LIST, emptySet())?.forEach {
                skipList.add(it)
            }

            // Load learned corrections
            val learnedJson = prefs.getString(KEY_LEARNED_CORRECTIONS, null)
            if (learnedJson != null) {
                val jsonObject = JSONObject(learnedJson)
                jsonObject.keys().forEach { key ->
                    learnedTypos[key] = jsonObject.getString(key)
                }
            }

            // Load pending learn
            val pendingJson = prefs.getString(KEY_PENDING_LEARN, null)
            if (pendingJson != null) {
                val jsonArray = JSONArray(pendingJson)
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    val typo = obj.getString("typo")
                    pendingLearn[typo] = PendingLearn(
                        typo = typo,
                        correction = obj.getString("correction"),
                        count = obj.getInt("count"),
                        firstSeen = obj.getLong("firstSeen")
                    )
                }
            }

            // Load rejected corrections
            // Format: {"vwo": ["two", "tow"], "ai": ["a i"]}
            val rejectedJson = prefs.getString(KEY_REJECTED_CORRECTIONS, null)
            if (rejectedJson != null) {
                val jsonObject = JSONObject(rejectedJson)
                jsonObject.keys().forEach { original ->
                    val corrections = jsonObject.getJSONArray(original)
                    val rejectedSet = mutableSetOf<String>()
                    for (i in 0 until corrections.length()) {
                        rejectedSet.add(corrections.getString(i))
                    }
                    rejectedCorrections[original] = rejectedSet
                }
            }

            DebugLog.d("$TAG: Loaded persisted data - ${skipList.size} skip, ${learnedTypos.size} learned, ${pendingLearn.size} pending, ${rejectedCorrections.size} rejected")
        } catch (e: Exception) {
            DebugLog.e("$TAG: Error loading persisted data - ${e.message}")
        }
    }

    /**
     * Save persisted data to SharedPreferences.
     */
    private fun savePersistedData() {
        try {
            val prefs = context.getSharedPreferences(LEARNED_PREFS, Context.MODE_PRIVATE)

            // Save skip list
            prefs.edit().putStringSet(KEY_SKIP_LIST, skipList).apply()

            // Save learned corrections
            val learnedJson = JSONObject()
            learnedTypos.forEach { (typo, correction) ->
                learnedJson.put(typo, correction)
            }
            prefs.edit().putString(KEY_LEARNED_CORRECTIONS, learnedJson.toString()).apply()

            // Save pending learn
            val pendingArray = JSONArray()
            pendingLearn.values.forEach { pending ->
                pendingArray.put(JSONObject().apply {
                    put("typo", pending.typo)
                    put("correction", pending.correction)
                    put("count", pending.count)
                    put("firstSeen", pending.firstSeen)
                })
            }
            prefs.edit().putString(KEY_PENDING_LEARN, pendingArray.toString()).apply()

            // Save rejected corrections
            // Format: {"vwo": ["two", "tow"], "ai": ["a i"]}
            val rejectedJson = JSONObject()
            rejectedCorrections.forEach { (original, corrections) ->
                rejectedJson.put(original, JSONArray(corrections.toList()))
            }
            prefs.edit().putString(KEY_REJECTED_CORRECTIONS, rejectedJson.toString()).apply()
        } catch (e: Exception) {
            DebugLog.e("$TAG: Error saving persisted data - ${e.message}")
        }
    }

    /**
     * QWERTY keyboard adjacency map. Each key maps to the set of keys
     * physically adjacent to it on a standard QWERTY layout.
     */
    private val qwertyAdjacency: Map<Char, Set<Char>> = mapOf(
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

    /**
     * Check if two characters are adjacent on the QWERTY keyboard.
     */
    private fun areAdjacentKeys(a: Char, b: Char): Boolean {
        return qwertyAdjacency[a]?.contains(b) == true
    }

    /**
     * QWERTY-weighted Levenshtein distance. Uses scaled integer costs (x2):
     * - Adjacent key substitution: cost 1 (half of normal)
     * - Non-adjacent substitution: cost 2 (normal)
     * - Insertion/deletion: cost 2 (normal)
     *
     * This makes fat-finger errors on adjacent keys cheaper to correct,
     * so the dictionary prefers nearby-key corrections.
     *
     * @param s1 First string (lowercase)
     * @param s2 Second string (lowercase)
     * @param maxScaledDistance Early termination threshold (scaled x2)
     * @return Scaled distance (divide by 2 for effective edit distance)
     */
    private fun qwertyWeightedDistance(s1: String, s2: String, maxScaledDistance: Int): Int {
        val m = s1.length
        val n = s2.length
        if (m == 0) return n * 2
        if (n == 0) return m * 2
        if (s1 == s2) return 0

        var prevRow = IntArray(n + 1) { it * 2 }
        var currRow = IntArray(n + 1)

        for (i in 1..m) {
            currRow[0] = i * 2
            var rowMin = currRow[0]
            for (j in 1..n) {
                val c1 = s1[i - 1]
                val c2 = s2[j - 1]
                val substCost = if (c1 == c2) {
                    0
                } else if (areAdjacentKeys(c1, c2)) {
                    1 // Half cost for adjacent key fat-finger
                } else {
                    2 // Full cost for non-adjacent substitution
                }
                currRow[j] = minOf(
                    prevRow[j] + 2,          // deletion (cost 2)
                    currRow[j - 1] + 2,      // insertion (cost 2)
                    prevRow[j - 1] + substCost // substitution
                )
                if (currRow[j] < rowMin) rowMin = currRow[j]
            }
            // Early termination if entire row exceeds max
            if (rowMin > maxScaledDistance) return maxScaledDistance + 1
            val temp = prevRow
            prevRow = currRow
            currRow = temp
        }
        return prevRow[n]
    }

    /**
     * Calculate Levenshtein edit distance between two strings.
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        if (m == 0) return n
        if (n == 0) return m
        if (s1 == s2) return 0

        var prevRow = IntArray(n + 1) { it }
        var currRow = IntArray(n + 1)

        for (i in 1..m) {
            currRow[0] = i
            for (j in 1..n) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                currRow[j] = minOf(
                    prevRow[j] + 1,
                    currRow[j - 1] + 1,
                    prevRow[j - 1] + cost
                )
            }
            val temp = prevRow
            prevRow = currRow
            currRow = temp
        }
        return prevRow[n]
    }

    /**
     * Simple Trie for fast prefix matching.
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

        fun contains(word: String): Boolean {
            var node = root
            for (char in word.lowercase()) {
                node = node.children[char] ?: return false
            }
            return node.isWord
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
            val children = sortedMapOf<Char, TrieNode>()
            var isWord = false
            var word: String? = null
        }
    }
}
