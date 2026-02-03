package com.tinydictation.android.correction

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.tinydictation.android.util.DebugLog
import com.tinydictation.android.util.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.ArrayDeque

/**
 * Main orchestrator for the two-stage text correction system.
 *
 * Manages:
 * - Stage 1: Word-level local corrections (after each word)
 * - Stage 2: Sentence-level AI corrections (after punctuation/pause)
 * - Correction triggers (punctuation, pause, double-space)
 * - Undo stack for reverting corrections
 * - Learning from corrections
 */
class CorrectionManager(private val context: Context) {

    companion object {
        private const val TAG = "CorrectionManager"
        private const val MAX_UNDO_STACK = 10
        private val PUNCTUATION = charArrayOf('.', ',', '!', '?', ';', ':', '-', '"', '\'', ')', ']', '}')
        private val SENTENCE_ENDERS = charArrayOf('.', '!', '?')
    }

    // Processors
    private val wordProcessor = WordProcessor(context)
    private val sentenceProcessor = SentenceProcessor(context)

    // Configuration
    private val prefs = PreferenceManager(context)
    private var config = loadConfig()

    // State
    private var isInitialized = false
    private var sentenceBuffer = StringBuilder()
    private var lastKeyTime = 0L
    private var pauseJob: Job? = null

    // Undo stack
    private val undoStack = ArrayDeque<Correction>(MAX_UNDO_STACK)

    // Double-space detection
    private var lastSpaceTime = 0L
    private val doubleSpaceThreshold = 300L // ms

    // Track last correction for rejection detection
    private var lastAppliedCorrection: Correction? = null
    private var lastCorrectionTimestamp = 0L
    private val CORRECTION_REJECTION_WINDOW = 5000L // 5 seconds to detect rejection

    // Coroutine scope
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val mainHandler = Handler(Looper.getMainLooper())

    // Listener for correction events
    var correctionListener: CorrectionListener? = null

    /**
     * Initialize the correction system.
     */
    suspend fun initialize(): Result<Unit> {
        return try {
            if (isInitialized) return Result.success(Unit)

            val startTime = System.currentTimeMillis()

            // Initialize word processor (loads dictionary)
            wordProcessor.initialize().getOrThrow()

            config = loadConfig()
            isInitialized = true

            val elapsed = System.currentTimeMillis() - startTime
            DebugLog.i("$TAG: Initialized in ${elapsed}ms")

            Result.success(Unit)
        } catch (e: Exception) {
            DebugLog.e("$TAG: Init failed - ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Release resources.
     */
    fun release() {
        pauseJob?.cancel()
        scope.cancel()
        isInitialized = false
        DebugLog.i("$TAG: Released")
    }

    /**
     * Reload configuration from preferences.
     */
    fun reloadConfig() {
        config = loadConfig()
        DebugLog.d("$TAG: Config reloaded")
    }

    /**
     * Process a character input. Called for each character typed.
     *
     * @param char The character typed
     * @param textBeforeCursor Current text before cursor
     * @return Stage 1 correction if applicable, null otherwise
     */
    fun onCharacterTyped(char: Char, textBeforeCursor: String): Correction? {
        if (!isInitialized || !config.enabled) return null

        lastKeyTime = System.currentTimeMillis()
        cancelPauseTimer()

        // Track for double-space detection
        if (char == ' ') {
            val now = System.currentTimeMillis()
            if (config.triggerOnDoubleSpace && (now - lastSpaceTime) < doubleSpaceThreshold) {
                // Double-space detected - trigger Stage 2
                lastSpaceTime = 0L
                triggerSentenceCorrection(textBeforeCursor)
                return null
            }
            lastSpaceTime = now
        } else {
            lastSpaceTime = 0L
        }

        // Check for word boundary (space or punctuation)
        if (char.isWhitespace() || char in PUNCTUATION) {
            // Stage 1: Word-level correction
            val wordCorrection = processWordBoundary(textBeforeCursor)

            // Check for sentence-ending punctuation
            if (char in SENTENCE_ENDERS && config.triggerOnPunctuation) {
                // Trigger Stage 2 after a short delay to let Stage 1 complete
                triggerSentenceCorrection(textBeforeCursor)
            } else if (config.triggerOnPause) {
                // Start pause timer
                startPauseTimer(textBeforeCursor)
            }

            return wordCorrection
        }

        return null
    }

    /**
     * Manually trigger Stage 2 correction on current text.
     */
    fun triggerManualCorrection(textBeforeCursor: String) {
        triggerSentenceCorrection(textBeforeCursor)
    }

    /**
     * Undo the last correction.
     *
     * @return The correction that was undone, or null if nothing to undo
     */
    fun undo(): Correction? {
        return undoStack.pollLast()?.also {
            DebugLog.i("$TAG: Undid correction: '${it.corrected}' -> '${it.original}'")
        }
    }

    /**
     * Add words to the skip list (won't be corrected).
     */
    fun addToSkipList(words: Collection<String>) {
        wordProcessor.addToSkipList(words)
    }

    /**
     * Reset all learned data.
     */
    fun resetLearnedData() {
        wordProcessor.resetLearnedData()
    }

    /**
     * Get the last correction that was applied, if still within the rejection window.
     * Returns null if no recent correction or if the window has expired.
     */
    fun getLastCorrection(): Correction? {
        if (lastAppliedCorrection == null) return null
        val elapsed = System.currentTimeMillis() - lastCorrectionTimestamp
        return if (elapsed <= CORRECTION_REJECTION_WINDOW) {
            lastAppliedCorrection
        } else {
            null
        }
    }

    /**
     * Reject the last applied correction. The user manually reverted it,
     * so we should never apply this correction pair again.
     *
     * @return The correction that was rejected, or null if no recent correction to reject
     */
    fun rejectLastCorrection(): Correction? {
        val correction = getLastCorrection() ?: return null

        // Add to rejected list (only for word-level corrections)
        if (correction.stage == CorrectionStage.WORD) {
            wordProcessor.addRejectedCorrection(correction.original, correction.corrected)
            DebugLog.i("$TAG: User rejected correction: '${correction.original}' -> '${correction.corrected}'")
        } else {
            // For sentence-level, just add original words to skip list
            correction.original.split(Regex("\\s+")).forEach { word ->
                if (word.length >= 2) {
                    wordProcessor.addToSkipList(word)
                }
            }
            DebugLog.i("$TAG: User rejected sentence correction, added words to skip list")
        }

        // Clear the last correction since it's been handled
        lastAppliedCorrection = null

        return correction
    }

    /**
     * Clear the last correction tracking (e.g., when user types new content).
     */
    fun clearLastCorrection() {
        lastAppliedCorrection = null
    }

    /**
     * Check if correction is enabled.
     */
    fun isEnabled(): Boolean = config.enabled

    /**
     * Get current configuration.
     */
    fun getConfig(): CorrectionConfig = config

    // ==================== Private Methods ====================

    private fun loadConfig(): CorrectionConfig {
        return CorrectionConfig(
            enabled = prefs.getCorrectionEnabled(),
            wordLevelEnabled = prefs.getCorrectionWordLevelEnabled(),
            splitStuckWords = prefs.getCorrectionSplitStuckWords(),
            sentenceLevelEnabled = prefs.getCorrectionSentenceLevelEnabled(),
            sentenceProvider = SentenceProvider.fromId(prefs.getCorrectionProvider()),
            triggerOnPunctuation = prefs.getCorrectionTriggerOnPunctuation(),
            triggerOnPause = prefs.getCorrectionTriggerOnPause(),
            pauseDurationMs = prefs.getCorrectionPauseDuration(),
            triggerOnDoubleSpace = prefs.getCorrectionTriggerOnDoubleSpace(),
            highlightCorrections = prefs.getCorrectionHighlightEnabled(),
            showMajorChangeToast = prefs.getCorrectionShowMajorChangeToast(),
            importContacts = prefs.getCorrectionImportContacts(),
            learnFromCorrections = prefs.getCorrectionLearnEnabled()
        )
    }

    private fun processWordBoundary(textBeforeCursor: String): Correction? {
        if (!config.wordLevelEnabled) return null

        val correction = wordProcessor.processWordBoundary(textBeforeCursor, config)

        if (correction != null) {
            pushUndo(correction)
            correctionListener?.onWordCorrected(correction)
        }

        return correction
    }

    private fun triggerSentenceCorrection(textBeforeCursor: String) {
        if (!config.sentenceLevelEnabled) return

        scope.launch {
            try {
                // Extract the last sentence
                val sentence = sentenceProcessor.extractLastSentence(textBeforeCursor)
                if (sentence == null || sentence.length < 5) return@launch

                val correction = sentenceProcessor.processSentence(sentence, config)

                if (correction != null) {
                    pushUndo(correction)

                    // Learn from this correction if enabled
                    if (config.learnFromCorrections) {
                        learnFromCorrection(correction)
                    }

                    // Notify listener on main thread
                    mainHandler.post {
                        correctionListener?.onSentenceCorrected(correction)
                    }
                }
            } catch (e: Exception) {
                DebugLog.e("$TAG: Sentence correction failed - ${e.message}")
            }
        }
    }

    private fun startPauseTimer(textBeforeCursor: String) {
        pauseJob?.cancel()
        pauseJob = scope.launch {
            kotlinx.coroutines.delay(config.pauseDurationMs)

            // Check if still paused (no new keystrokes)
            val timeSinceLastKey = System.currentTimeMillis() - lastKeyTime
            if (timeSinceLastKey >= config.pauseDurationMs) {
                triggerSentenceCorrection(textBeforeCursor)
            }
        }
    }

    private fun cancelPauseTimer() {
        pauseJob?.cancel()
        pauseJob = null
    }

    private fun pushUndo(correction: Correction) {
        if (undoStack.size >= MAX_UNDO_STACK) {
            undoStack.pollFirst()
        }
        undoStack.addLast(correction)

        // Track this as the last applied correction for rejection detection
        lastAppliedCorrection = correction
        lastCorrectionTimestamp = System.currentTimeMillis()
    }

    private fun learnFromCorrection(correction: Correction) {
        // Find word-level differences and record them
        val originalWords = correction.original.split(Regex("\\s+"))
        val correctedWords = correction.corrected.split(Regex("\\s+"))

        // Only learn from simple single-word corrections
        if (originalWords.size == correctedWords.size) {
            originalWords.zip(correctedWords).forEach { (orig, corrected) ->
                if (orig != corrected && orig.length >= 2 && corrected.length >= 2) {
                    wordProcessor.recordCorrection(orig, corrected)
                }
            }
        }
    }

    /**
     * Listener interface for correction events.
     */
    interface CorrectionListener {
        /**
         * Called when Stage 1 makes a word-level correction.
         */
        fun onWordCorrected(correction: Correction)

        /**
         * Called when Stage 2 makes a sentence-level correction.
         */
        fun onSentenceCorrected(correction: Correction)
    }

}
