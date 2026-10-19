package com.tinydictation.android.transcription

data class TranscriptionError(val message: String, val fatal: Boolean = false)

fun mapDeepgramError(
    httpCode: Int? = null,
    dgError: String? = null,
    closeCode: Int? = null,
    causeMessage: String? = null
): TranscriptionError? {
    return when {
        httpCode == 401 -> TranscriptionError(
            "Deepgram rejected the API key. Open Settings > API Keys and paste a new key.",
            fatal = true
        )
        httpCode == 403 -> TranscriptionError(
            "Deepgram key has no permission for live transcription. " +
                "Create a new key with Member role.",
            fatal = true
        )
        httpCode == 402 -> TranscriptionError(
            "Deepgram account is out of credit. Add credit in the Deepgram console.",
            fatal = true
        )
        httpCode != null -> TranscriptionError(
            "Deepgram error $httpCode: ${dgError ?: causeMessage ?: "Unknown error"}"
        )
        closeCode == 1000 -> null
        closeCode != null -> TranscriptionError(
            "Deepgram closed the connection ($closeCode: ${causeMessage ?: "Unknown reason"})",
            fatal = closeCode == 1008
        )
        else -> TranscriptionError("Cannot reach Deepgram. Check your internet connection.")
    }
}
