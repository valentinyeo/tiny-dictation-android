package com.tinydictation.android.prediction

import android.content.Context

/**
 * Prediction result containing the suggested word and metadata.
 */
data class Prediction(
    val word: String,
    val confidence: Float,
    val isCompletion: Boolean // True if completing a partial word, false if next word
)

/**
 * Interface for word prediction engines.
 * Implementations can be swapped out to test different approaches (n-gram, neural, cloud).
 */
interface PredictionEngine {

    /** Unique identifier for this engine */
    val engineId: String

    /** Display name for settings UI */
    val displayName: String

    /** Whether this engine requires internet connection */
    val requiresInternet: Boolean

    /**
     * Initialize the engine. Called once when engine is selected.
     * @param context Android context for resource access
     * @return Result indicating success or failure with error message
     */
    suspend fun initialize(context: Context): Result<Unit>

    /**
     * Get word predictions based on context and partial word.
     *
     * @param contextWords Previous words for context (typically last 2-3 words)
     * @param partialWord Current word being typed (can be empty for next-word prediction)
     * @return List of predictions ordered by likelihood, typically top 3
     */
    suspend fun predict(
        contextWords: List<String>,
        partialWord: String
    ): List<Prediction>

    /**
     * Release any resources held by the engine.
     */
    fun release()

    /**
     * Optional: Learn from user's confirmed input to improve predictions.
     * @param words The words the user actually typed/selected
     */
    fun learn(words: List<String>) {
        // Default no-op, engines can override to implement learning
    }
}
