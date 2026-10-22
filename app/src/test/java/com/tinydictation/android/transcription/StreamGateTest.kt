package com.tinydictation.android.transcription

import com.tinydictation.android.transcription.StreamGate.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamGateTest {
    private class Clock {
        val gate = StreamGate().apply { start() }
        var nowMs = 0L

        fun chunk(rms: Int): Action {
            val action = gate.process(rms, nowMs)
            nowMs += 20
            return action
        }

        fun chunks(count: Int, rms: Int): List<Action> = List(count) { chunk(rms) }

        fun open() {
            assertEquals(listOf(Action.NONE, Action.NONE, Action.OPEN), chunks(3, 2000))
        }
    }

    @Test
    fun silenceOnlyNeverOpens() {
        val clock = Clock()
        assertTrue(clock.chunks(3000, 100).all { it == Action.NONE })
    }

    @Test
    fun threeConsecutiveLoudChunksOpen() {
        Clock().open()
    }

    @Test
    fun interruptedOnsetDoesNotOpen() {
        val clock = Clock()
        clock.chunks(2, 2000)
        assertEquals(Action.NONE, clock.chunk(0))
        assertEquals(listOf(Action.NONE, Action.NONE, Action.OPEN), clock.chunks(3, 2000))
    }

    @Test
    fun thresholdIsStrictAndDoesNotOpenAtEightHundred() {
        assertTrue(Clock().chunks(50, 800).all { it == Action.NONE })
    }

    @Test
    fun mostlySilentMinuteSendsAtMostTwoAndAHalfSeconds() {
        val clock = Clock()
        val actions = clock.chunks(500, 0) + clock.chunks(100, 2000) + clock.chunks(2400, 0)
        assertEquals(3000, actions.size)
        assertEquals(1, actions.count { it == Action.OPEN })
        assertTrue(actions.count { it == Action.SEND } * 20 <= 2500)
        assertEquals(1, actions.count { it == Action.CLOSE })
    }

    @Test
    fun twoSecondPauseKeepsSocketAliveWithoutStreamingAfterHangover() {
        val clock = Clock()
        clock.open()
        val pause = clock.chunks(100, 0)
        assertEquals(20, pause.count { it == Action.SEND })
        assertEquals(Action.KEEPALIVE, pause[20])
        assertFalse(pause.drop(20).contains(Action.SEND))
        assertFalse(pause.contains(Action.CLOSE))
        assertFalse(clock.gate.isSpeech)
    }

    @Test
    fun fourSecondsOfSilenceCloseWithKeepAlivesThreeSecondsApart() {
        val clock = Clock()
        clock.open()
        val silence = clock.chunks(201, 0)
        assertEquals(listOf(20, 170), silence.indices.filter { silence[it] == Action.KEEPALIVE })
        assertEquals(Action.CLOSE, silence.last())
        assertEquals(1, silence.count { it == Action.CLOSE })
        assertEquals(Action.NONE, clock.chunk(0))
    }

    @Test
    fun resumeSendsAllThreeOnsetChunksWithoutReopening() {
        val clock = Clock()
        clock.open()
        clock.chunks(100, 0)
        assertEquals(Action.NONE, clock.chunk(2000))
        assertEquals(Action.NONE, clock.chunk(2000))
        assertEquals(Action.SEND, clock.chunk(2000))
        assertEquals(3, clock.gate.resumeChunks)
        assertTrue(clock.gate.isSpeech)
        assertEquals(Action.SEND, clock.chunk(2000))
        assertEquals(0, clock.gate.resumeChunks)
        assertFalse(clock.chunks(150, 0).contains(Action.CLOSE))
    }

    @Test
    fun hangoverDoesNotCountAsSpeech() {
        val clock = Clock()
        clock.open()
        assertEquals(Action.SEND, clock.chunk(0))
        assertFalse(clock.gate.isSpeech)
    }

    @Test
    fun activeSpeechUsesLowerHysteresisThreshold() {
        val clock = Clock()
        clock.open()
        assertEquals(Action.SEND, clock.chunk(500))
        assertTrue(clock.gate.isSpeech)
    }

    @Test
    fun constantLoudNoiseAdaptsAndStopsSendingWellBeforeCap() {
        val clock = Clock()
        val actions = clock.chunks(3000, 2000)
        assertEquals(1, actions.count { it == Action.OPEN })
        assertTrue(actions.count { it == Action.SEND } * 20 < 20_000)
        assertTrue(actions.contains(Action.CLOSE))
        assertTrue(actions.takeLast(1000).all { it == Action.NONE })
    }

    @Test
    fun learnedNoiseFloorRejectsSmallIncreaseButAllowsRealOnset() {
        val clock = Clock()
        assertTrue(clock.chunks(500, 250).all { it == Action.NONE })
        assertTrue(clock.chunks(500, 700).all { it == Action.NONE })
        assertEquals(listOf(Action.NONE, Action.NONE, Action.OPEN), clock.chunks(3, 3000))
    }

    @Test
    fun stopMidSpeechClosesAndIgnoresChunksUntilRestart() {
        val clock = Clock()
        clock.open()
        assertEquals(Action.CLOSE, clock.gate.stop())
        assertTrue(clock.chunks(100, 2000).all { it == Action.NONE })
        assertEquals(Action.NONE, clock.gate.stop())
        clock.gate.start()
        clock.open()
    }

    @Test
    fun stoppedByDefault() {
        assertEquals(Action.NONE, StreamGate().process(2000, 0))
    }

    @Test
    fun continuousUtteranceWithBriefQuietChunksHitsHardCap() {
        val clock = Clock()
        clock.open()
        val capAt = clock.nowMs - 20 + StreamGate.MAX_OPEN_MS
        var index = 0
        while (clock.nowMs < capAt) {
            val action = clock.chunk(if (index++ % 15 < 10) 2000 else 0)
            assertFalse(action == Action.CLOSE || action == Action.OPEN)
        }
        assertEquals(Action.CLOSE, clock.chunk(2000))
        assertTrue(clock.chunks(10, 2000).all { it == Action.NONE })
        assertEquals(Action.NONE, clock.chunk(0))
        clock.open()
    }

    @Test
    fun aNewUtteranceAfterSilenceCloseCanReopen() {
        val clock = Clock()
        clock.open()
        clock.chunks(201, 0)
        clock.open()
    }
    @Test
    fun playbackPauseDisablesGateUntilRestartAndRequiresFreshSpeech() {
        val clock = Clock()
        clock.open()
        clock.chunks(100, 0)
        assertEquals(Action.CLOSE, clock.gate.stop())
        assertTrue(clock.chunks(500, 2000).all { it == Action.NONE })
        clock.gate.start()
        assertTrue(clock.chunks(100, 0).all { it == Action.NONE })
        clock.open()
        assertEquals(Action.SEND, clock.chunk(2000))
    }
}
