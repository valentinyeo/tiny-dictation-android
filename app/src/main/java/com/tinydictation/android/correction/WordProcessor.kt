package com.tinydictation.android.correction

import android.content.Context
import com.tinydictation.android.util.DebugLog

/**
 * Stage 1: Local word-level correction processor.
 *
 * Performs fast, offline corrections:
 * - Typo fixes using dictionary lookup and edit distance
 * - Common typo pattern matching
 * - Splitting stuck-together words
 * - Basic capitalization
 *
 * Latency target: <50ms per word (must never block typing)
 */
class WordProcessor(private val context: Context) {

    companion object {
        private const val TAG = "WordProcessor"
        private const val MAX_PROCESS_TIME_MS = 50L
        private val PUNCTUATION = charArrayOf('.', ',', '!', '?', ';', ':', '"', '\'', ')', ']', '}')

        // Common stuck-together words
        private val COMMON_STUCK_WORDS = mapOf(
            "iwant" to "i want",
            "iwas" to "i was",
            "iwill" to "i will",
            "iwould" to "i would",
            "ihave" to "i have",
            "ive" to "i've",
            "im" to "i'm",
            "ill" to "i'll",
            "id" to "i'd",
            "goto" to "go to",
            "inthe" to "in the",
            "onthe" to "on the",
            "atthe" to "at the",
            "tothe" to "to the",
            "forthe" to "for the",
            "ofthe" to "of the",
            "withthe" to "with the",
            "fromthe" to "from the",
            "bythe" to "by the",
            "isthe" to "is the",
            "wasthe" to "was the",
            "arethe" to "are the",
            "werethe" to "were the",
            "tobe" to "to be",
            "canbe" to "can be",
            "willbe" to "will be",
            "maybe" to "may be",
            "alot" to "a lot",
            "abit" to "a bit",
            "infact" to "in fact",
            "aswell" to "as well",
            "sofar" to "so far",
            "awhile" to "a while",
            "thankyou" to "thank you",
            "goodmorning" to "good morning",
            "goodnight" to "good night",
            "goodevening" to "good evening",
            "goodafternoon" to "good afternoon"
        )
    }

    private val dictionary = Dictionary(context)
    private var isInitialized = false

    /**
     * Initialize the word processor and dictionary.
     */
    suspend fun initialize(): Result<Unit> {
        return try {
            if (isInitialized) return Result.success(Unit)

            val result = dictionary.initialize()
            if (result.isSuccess) {
                isInitialized = true
                DebugLog.i("$TAG: Initialized")
            }
            result
        } catch (e: Exception) {
            DebugLog.e("$TAG: Init failed - ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Process a single word and return correction if needed.
     *
     * @param word The word to process
     * @param isStartOfSentence Whether this is at the start of a sentence
     * @param config Correction configuration
     * @return Corrected word, or null if no correction needed
     */
    fun processWord(
        word: String,
        isStartOfSentence: Boolean = false,
        config: CorrectionConfig = CorrectionConfig()
    ): String? {
        if (!isInitialized || !config.wordLevelEnabled) return null
        if (word.isBlank() || word.length < 2) return null

        val startTime = System.currentTimeMillis()

        try {
            // Skip if in skip list (contacts, user-added words)
            if (dictionary.shouldSkip(word)) {
                DebugLog.d("$TAG: Skipping '$word' (in skip list)")
                return null
            }

            // Skip special patterns (URLs, emails, etc.)
            if (shouldSkipPattern(word)) {
                return null
            }

            var corrected: String? = null

            // Helper to check if a correction was rejected by the user
            fun checkRejected(original: String, correction: String): Boolean {
                if (dictionary.isRejectedCorrection(original, correction)) {
                    DebugLog.d("$TAG: Skipping rejected correction: '$original' -> '$correction'")
                    return true
                }
                return false
            }

            // 1. Check for known typo correction
            corrected = dictionary.getTypoCorrection(word)
            if (corrected != null) {
                // Check if user previously rejected this correction
                if (!checkRejected(word, corrected)) {
                    DebugLog.d("$TAG: Typo match: '$word' -> '$corrected'")
                    return applyCasing(word, corrected, isStartOfSentence)
                }
                corrected = null  // Reset since this was rejected
            }

            // 2. Check if it's a valid word
            if (dictionary.isValidWord(word)) {
                // Word is valid, only apply capitalization if needed
                return if (isStartOfSentence && word.first().isLowerCase()) {
                    word.replaceFirstChar { it.uppercase() }
                } else {
                    null
                }
            }

            // 3. Try to split stuck-together words
            if (config.splitStuckWords && word.length >= 4) {
                val split = trySplitWord(word)
                if (split != null) {
                    // Check if user rejected this split
                    if (!checkRejected(word, split)) {
                        DebugLog.d("$TAG: Split word: '$word' -> '$split'")
                        return if (isStartOfSentence) {
                            split.replaceFirstChar { it.uppercase() }
                        } else {
                            split
                        }
                    }
                }
            }

            // 4. Try fuzzy matching for typos
            corrected = dictionary.findSimilarWord(word, maxDistance = 2)
            if (corrected != null) {
                // Check if user rejected this fuzzy match
                if (!checkRejected(word, corrected)) {
                    DebugLog.d("$TAG: Fuzzy match: '$word' -> '$corrected'")
                    return applyCasing(word, corrected, isStartOfSentence)
                }
            }

            // 5. No correction found, apply capitalization only
            if (isStartOfSentence && word.first().isLowerCase()) {
                return word.replaceFirstChar { it.uppercase() }
            }

            return null
        } finally {
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed > MAX_PROCESS_TIME_MS) {
                DebugLog.w("$TAG: Processing '$word' took ${elapsed}ms (target: <${MAX_PROCESS_TIME_MS}ms)")
            }
        }
    }

    /**
     * Process text entered after a word boundary (space/punctuation).
     * This is the main entry point for Stage 1 correction.
     *
     * @param textBeforeCursor Text before the cursor
     * @param config Correction configuration
     * @return Correction result, or null if no correction needed
     */
    fun processWordBoundary(
        textBeforeCursor: String,
        config: CorrectionConfig = CorrectionConfig()
    ): Correction? {
        if (!isInitialized || !config.wordLevelEnabled) return null
        if (textBeforeCursor.isBlank()) return null

        // Extract the last word before the space/punctuation
        val lastWord = extractLastWord(textBeforeCursor)
        if (lastWord == null || lastWord.length < 2) return null

        // Determine if this is start of sentence
        val isStartOfSentence = isAtSentenceStart(textBeforeCursor, lastWord)

        val corrected = processWord(lastWord, isStartOfSentence, config)

        return if (corrected != null && corrected != lastWord) {
            Correction(
                original = lastWord,
                corrected = corrected,
                stage = CorrectionStage.WORD,
                isMajorChange = false // Word-level corrections are never "major"
            )
        } else {
            null
        }
    }

    /**
     * Add a word to the skip list.
     */
    fun addToSkipList(word: String) {
        dictionary.addToSkipList(word)
    }

    /**
     * Add multiple words to the skip list (e.g., contacts).
     */
    fun addToSkipList(words: Collection<String>) {
        dictionary.addToSkipList(words)
    }

    /**
     * Record a correction made by Stage 2 for learning.
     */
    fun recordCorrection(typo: String, correction: String) {
        dictionary.recordCorrection(typo, correction)
    }

    /**
     * Add a rejected correction - user manually reverted this correction,
     * so never apply it again.
     */
    fun addRejectedCorrection(original: String, rejectedCorrection: String) {
        dictionary.addRejectedCorrection(original, rejectedCorrection)
    }

    /**
     * Reset learned data.
     */
    fun resetLearnedData() {
        dictionary.resetLearnedData()
    }

    /**
     * Try to split a stuck-together word into valid words.
     * E.g., "iwant" -> "i want", "goto" -> "go to"
     */
    private fun trySplitWord(word: String): String? {
        val lower = word.lowercase()

        // Check common stuck patterns first
        COMMON_STUCK_WORDS[lower]?.let { return it }

        // Try splitting at each position
        for (i in 2 until lower.length - 1) {
            val first = lower.substring(0, i)
            val second = lower.substring(i)

            if (dictionary.isValidWord(first) && dictionary.isValidWord(second)) {
                // Prefer splits where both parts are common words
                return "$first $second"
            }
        }

        return null
    }

    /**
     * Extract the last word from text (before word boundary).
     */
    private fun extractLastWord(text: String): String? {
        // Trim trailing whitespace/punctuation
        val trimmed = text.trimEnd { it.isWhitespace() || it in PUNCTUATION }
        if (trimmed.isEmpty()) return null

        // Find the last word boundary
        val lastBoundary = trimmed.lastIndexOfAny(charArrayOf(' ', '\n', '\t'))
        return if (lastBoundary >= 0) {
            trimmed.substring(lastBoundary + 1)
        } else {
            trimmed
        }
    }

    /**
     * Determine if the word is at the start of a sentence.
     */
    private fun isAtSentenceStart(text: String, word: String): Boolean {
        // Find where the word starts
        val wordIndex = text.lastIndexOf(word)
        if (wordIndex <= 0) return true // At very beginning

        // Look at what's before the word
        val before = text.substring(0, wordIndex).trimEnd()
        if (before.isEmpty()) return true

        // Check if previous character is sentence-ending punctuation
        val lastChar = before.last()
        return lastChar in ".!?\n"
    }

    /**
     * Apply casing from original word to corrected word.
     */
    private fun applyCasing(original: String, corrected: String, isStartOfSentence: Boolean): String {
        return when {
            // ALL CAPS -> keep all caps
            original.all { !it.isLetter() || it.isUpperCase() } -> corrected.uppercase()
            // Title Case or start of sentence -> capitalize first
            original.first().isUpperCase() || isStartOfSentence -> {
                corrected.replaceFirstChar { it.uppercase() }
            }
            // Lowercase
            else -> corrected.lowercase()
        }
    }

    /**
     * Check if word matches a pattern that should be skipped.
     */
    private fun shouldSkipPattern(word: String): Boolean {
        val lower = word.lowercase()

        // URLs
        if (lower.startsWith("http://") || lower.startsWith("https://") ||
            lower.startsWith("www.") || lower.contains(".com") ||
            lower.contains(".org") || lower.contains(".net")) {
            return true
        }

        // Email addresses
        if (word.contains("@") && word.contains(".")) {
            return true
        }

        // Code-like patterns (camelCase, snake_case)
        if (word.contains("_") || (word.any { it.isUpperCase() } && word.any { it.isLowerCase() } &&
                    word.filterIndexed { i, c -> i > 0 && c.isUpperCase() }.isNotEmpty())) {
            return true
        }

        // Numbers/alphanumeric codes
        if (word.any { it.isDigit() }) {
            return true
        }

        return false
    }

}
