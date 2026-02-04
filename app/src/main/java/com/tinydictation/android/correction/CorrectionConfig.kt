package com.tinydictation.android.correction

/**
 * Configuration for the two-stage text correction system.
 */
data class CorrectionConfig(
    val enabled: Boolean = true,

    // Stage 1: Word-level local correction
    val wordLevelEnabled: Boolean = true,
    val splitStuckWords: Boolean = true,

    // Stage 2: Sentence-level AI correction
    val sentenceLevelEnabled: Boolean = true,
    val sentenceProvider: SentenceProvider = SentenceProvider.CLAUDE_HAIKU,
    val triggerOnPunctuation: Boolean = true,
    val triggerOnPause: Boolean = true,
    val pauseDurationMs: Long = 2000L,
    val triggerOnDoubleSpace: Boolean = false,

    // UI
    val highlightCorrections: Boolean = true,
    val showMajorChangeToast: Boolean = true,

    // Learning
    val importContacts: Boolean = true,
    val learnFromCorrections: Boolean = true
)

/**
 * AI providers for sentence-level correction.
 */
enum class SentenceProvider(
    val displayName: String,
    val modelId: String,
    val requiresInternet: Boolean = true
) {
    GROQ_FAST("Groq Fast", "llama-3.1-8b-instant"),
    GROQ_SMART("Groq Smart", "llama-3.3-70b-versatile"),
    CLAUDE_HAIKU("Claude Haiku", "claude-haiku-4-5-20251101"),
    CLAUDE_OPUS("Claude Opus", "claude-opus-4-5-20251101");

    companion object {
        fun fromId(id: String): SentenceProvider {
            return entries.find { it.name == id } ?: GROQ_FAST
        }
    }
}

/**
 * Represents a correction made by either stage.
 */
data class Correction(
    val original: String,
    val corrected: String,
    val stage: CorrectionStage,
    val isMajorChange: Boolean,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * Calculate the change percentage between original and corrected text.
     */
    val changePercentage: Float
        get() {
            if (original.isEmpty()) return 0f
            val distance = levenshteinDistance(original, corrected)
            return distance.toFloat() / original.length.toFloat()
        }

    private fun levenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        if (m == 0) return n
        if (n == 0) return m

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
}

/**
 * Which stage made the correction.
 */
enum class CorrectionStage {
    WORD,     // Stage 1: Local word-level
    SENTENCE  // Stage 2: AI sentence-level
}

/**
 * Learning data for pending corrections that haven't hit the threshold yet.
 */
data class PendingLearn(
    val typo: String,
    val correction: String,
    var count: Int = 1,
    val firstSeen: Long = System.currentTimeMillis()
)
