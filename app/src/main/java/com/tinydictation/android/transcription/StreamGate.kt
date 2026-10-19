package com.tinydictation.android.transcription

class StreamGate {
    enum class Action { NONE, OPEN, SEND, KEEPALIVE, CLOSE }

    companion object {
        const val MAX_OPEN_MS = 120_000L
        private const val ONSET_CHUNKS = 3
        private const val HANGOVER_MS = 400L
        private const val SILENCE_CLOSE_MS = 4_000L
        private const val KEEPALIVE_MS = 3_000L
    }

    var isSpeech = false
        private set
    var resumeChunks = 0
        private set

    private var enabled = false
    private var open = false
    private var openedAt = 0L
    private var noiseFloor = 0.0
    private var onsetChunks = 0
    private var silentSince: Long? = null
    private var lastKeepAlive: Long? = null
    private var sending = false
    private var requireFreshOnset = false

    @Synchronized
    fun start() {
        stop()
        enabled = true
    }

    @Synchronized
    fun stop(): Action {
        val action = if (open) Action.CLOSE else Action.NONE
        enabled = false
        open = false
        sending = false
        isSpeech = false
        resumeChunks = 0
        noiseFloor = 0.0
        onsetChunks = 0
        silentSince = null
        lastKeepAlive = null
        requireFreshOnset = false
        return action
    }

    @Synchronized
    fun process(rms: Int, nowMs: Long): Action {
        isSpeech = false
        resumeChunks = 0
        if (!enabled) return Action.NONE

        if (open && nowMs - openedAt >= MAX_OPEN_MS) {
            open = false
            sending = false
            onsetChunks = 0
            requireFreshOnset = true
            silentSince = null
            return Action.CLOSE
        }

        val level = rms.coerceAtLeast(0).toDouble()
        val aboveOnset = level > maxOf(800.0, 3.0 * noiseFloor)
        val aboveSilence = level > maxOf(300.0, 1.5 * noiseFloor)
        val speech = if (open && sending && silentSince == null) aboveSilence else aboveOnset
        noiseFloor += (level - noiseFloor) * if (speech) 0.002 else 0.05

        if (requireFreshOnset) {
            if (!aboveOnset) requireFreshOnset = false
            return Action.NONE
        }

        if (!open) {
            onsetChunks = if (aboveOnset) onsetChunks + 1 else 0
            isSpeech = aboveOnset
            if (onsetChunks < ONSET_CHUNKS) return Action.NONE
            open = true
            sending = true
            openedAt = nowMs
            silentSince = null
            lastKeepAlive = null
            onsetChunks = 0
            return Action.OPEN
        }

        if (speech && sending && silentSince == null) {
            isSpeech = true
            return Action.SEND
        }

        onsetChunks = if (aboveOnset) onsetChunks + 1 else 0
        if (onsetChunks >= ONSET_CHUNKS) {
            isSpeech = true
            resumeChunks = if (sending) 0 else ONSET_CHUNKS
            sending = true
            silentSince = null
            lastKeepAlive = null
            onsetChunks = 0
            return Action.SEND
        }

        val silenceStart = silentSince ?: nowMs.also { silentSince = it }
        val silenceMs = nowMs - silenceStart
        if (silenceMs >= SILENCE_CLOSE_MS) {
            open = false
            sending = false
            onsetChunks = 0
            return Action.CLOSE
        }
        if (silenceMs < HANGOVER_MS) return Action.SEND

        sending = false
        val previousKeepAlive = lastKeepAlive
        if (previousKeepAlive == null || nowMs - previousKeepAlive >= KEEPALIVE_MS) {
            lastKeepAlive = nowMs
            return Action.KEEPALIVE
        }
        return Action.NONE
    }
}
