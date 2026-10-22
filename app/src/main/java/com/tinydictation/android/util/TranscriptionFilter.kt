package com.tinydictation.android.util

object TranscriptionFilter {

    private val SOUND_DESCRIPTION_PATTERN = Regex(
        """[\(\[\*][\w\s]+(?:ing|ling|ning|ping|king|ting|shot|fire|rumbl|clos|open|creak|clang|rustle|playing)[\w\s]*[\)\]\*]""",
        RegexOption.IGNORE_CASE
    )

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

    private val REPEATED_WORD_PATTERN = Regex(
        """\b(\w+)\s+(?:\1\s*){2,}""",
        RegexOption.IGNORE_CASE
    )

    fun filter(text: String): String? {
        if (text.isBlank()) return null

        var filtered = text

        filtered = SOUND_DESCRIPTION_PATTERN.replace(filtered, "")

        for (phrase in HALLUCINATION_PHRASES) {
            filtered = filtered.replace(phrase, "", ignoreCase = true)
        }

        filtered = REPEATED_WORD_PATTERN.replace(filtered, "$1")

        filtered = filtered.replace(Regex("""\s{2,}"""), " ").trim()

        if (filtered.isBlank() || filtered.all { it.isWhitespace() || it in ".,!?;:-()[]" }) {
            return null
        }

        return filtered
    }

    fun isLikelyHallucination(text: String): Boolean {
        if (text.isBlank()) return true

        val lower = text.lowercase()

        if (HALLUCINATION_PHRASES.any { lower.contains(it) }) {
            return true
        }

        val withoutSounds = SOUND_DESCRIPTION_PATTERN.replace(text, "").trim()
        if (withoutSounds.length < text.length * 0.3) {
            return true
        }

        val words = text.split(Regex("""\s+"""))
        if (words.size >= 3) {
            val uniqueWords = words.map { it.lowercase() }.toSet()
            if (uniqueWords.size <= words.size * 0.3) {
                return true
            }
        }

        return false
    }
}
