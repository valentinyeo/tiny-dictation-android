package com.tinydictation.android.util

/**
 * Filters out Whisper hallucinations and noise from transcription output.
 *
 * Whisper models (especially smaller ones) tend to hallucinate sound descriptions
 * like "(clicking)", "(door closing)", "[GUNFIRE]" when there's silence or
 * background noise. This filter removes these unwanted annotations.
 */
object TranscriptionFilter {

    // Pattern to match sound descriptions in various formats:
    // (clicking), [door closing], *thunder*, etc.
    // Also matches repeated patterns and common hallucinations
    private val SOUND_DESCRIPTION_PATTERN = Regex(
        """[\(\[\*][\w\s]+(?:ing|ling|ning|ping|king|ting|shot|fire|rumbl|clos|open|creak|clang|rustle|playing)[\w\s]*[\)\]\*]""",
        RegexOption.IGNORE_CASE
    )

    // Common Whisper hallucination phrases (case-insensitive)
    private val HALLUCINATION_PHRASES = listOf(
        "thank you for watching",
        "thanks for watching",
        "please subscribe",
        "like and subscribe",
        "see you next time",
        "see you in the next",
        "don't forget to subscribe",
        "hit the bell",
        "click the bell",
        "leave a comment",
        "check out my",
        "link in the description",
        "www.",
        "http",
        ".com",
        ".org",
        "subtitles by",
        "captions by",
        "transcribed by",
        "music playing",
        "music continues",
        "silence",
        "inaudible",
        "unintelligible",
        "foreign language",
        "speaking foreign",
        "speaking in",
        "[music]",
        "[applause]",
        "[laughter]",
        "[silence]"
    )

    // Pattern for repeated short segments (like "clicking clicking clicking")
    private val REPEATED_WORD_PATTERN = Regex(
        """\b(\w+)\s+(?:\1\s*){2,}""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Filters transcription text to remove Whisper hallucinations.
     *
     * @param text The raw transcription text
     * @return Cleaned text with hallucinations removed, or null if entire text was noise
     */
    fun filter(text: String): String? {
        if (text.isBlank()) return null

        var filtered = text

        // Remove sound descriptions in parentheses/brackets
        filtered = SOUND_DESCRIPTION_PATTERN.replace(filtered, "")

        // Remove common hallucination phrases
        for (phrase in HALLUCINATION_PHRASES) {
            filtered = filtered.replace(phrase, "", ignoreCase = true)
        }

        // Remove repeated word patterns (e.g., "clicking clicking clicking")
        filtered = REPEATED_WORD_PATTERN.replace(filtered, "$1")

        // Clean up multiple spaces and trim
        filtered = filtered.replace(Regex("""\s{2,}"""), " ").trim()

        // If the result is empty or just punctuation/whitespace, return null
        if (filtered.isBlank() || filtered.all { it.isWhitespace() || it in ".,!?;:-()[]" }) {
            return null
        }

        return filtered
    }

    /**
     * Checks if text appears to be mostly hallucination/noise.
     * Returns true if the text should be discarded entirely.
     */
    fun isLikelyHallucination(text: String): Boolean {
        if (text.isBlank()) return true

        val lower = text.lowercase()

        // Check for hallucination phrases
        if (HALLUCINATION_PHRASES.any { lower.contains(it) }) {
            return true
        }

        // Check if mostly sound descriptions
        val withoutSounds = SOUND_DESCRIPTION_PATTERN.replace(text, "").trim()
        if (withoutSounds.length < text.length * 0.3) {
            // More than 70% was sound descriptions
            return true
        }

        // Check for excessive repetition
        val words = text.split(Regex("""\s+"""))
        if (words.size >= 3) {
            val uniqueWords = words.map { it.lowercase() }.toSet()
            if (uniqueWords.size <= words.size * 0.3) {
                // More than 70% repetition
                return true
            }
        }

        return false
    }
}
