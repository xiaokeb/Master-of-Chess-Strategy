package com.masterofchessstrategy.games.competition_mahjong.auto.replay

import com.masterofchessstrategy.engine.CompetitionMahjongAutoConfig
import com.masterofchessstrategy.games.competition_mahjong.auto.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CompetitionMahjongAutoReplayControllerTest {
    private class Harness(
        checkpoint: CompetitionMahjongAutoCheckpoint = autoCheckpoint(hand = 15, completed = true),
    ) {
        val archive = CompetitionMahjongAutoArchive(checkpoint)
        val sessions = mutableListOf<AutoSessionDouble>()
        var rejectRestore: (Int) -> Boolean = { false }
        var wrongConfig = false
        val controller = CompetitionMahjongAutoReplayController(archive, sessionFactory = { expected ->
            val config = if (wrongConfig) CompetitionMahjongAutoConfig(expected.seed, expected.dealerSeat,
                expected.roundWind, (expected.viewerSeat + 1) % 4, expected.botLevels, expected.decisionSeed) else expected
            AutoSessionDouble(config).also { session ->
                session.rejectRestore = rejectRestore
                sessions += session
            }
        }, dispatcher = Dispatchers.Unconfined)
        val active: AutoSessionDouble get() = sessions.last { !it.closed }
    }

    @Test fun opaquePrefixesMatchEndpointsAndRemainValidAtEveryPosition() {
        val config = autoConfig().handConfig(0, 0, true)
        val baseline = autoRecord(config, 1)
        val terminal = autoRecord(config, 3)
        assertArrayEquals(baseline, CompetitionMahjongAutoReplayRecord.prefix(baseline, terminal, 0, config))
        assertArrayEquals(terminal, CompetitionMahjongAutoReplayRecord.prefix(baseline, terminal, 2, config))
        val middle = CompetitionMahjongAutoReplayRecord.prefix(baseline, terminal, 1, config)
        assertEquals(2, CompetitionMahjongAutoRecord.eventCount(middle, config))
        assertEquals(1, CompetitionMahjongAutoRecord.eventsAfter(baseline, middle, config))
        assertThrows(IllegalArgumentException::class.java) {
            CompetitionMahjongAutoReplayRecord.prefix(baseline, terminal, 3, config)
        }
    }

    @Test fun prefixRejectsDifferentOriginOrEarlierTerminalWithoutChangingInputs() {
        val config = autoConfig().handConfig(0, 0, false)
        val baseline = autoRecord(config, 1)
        val terminal = autoRecord(config, 3, origin = 999)
        val original = baseline.copyOf()
        assertThrows(IllegalArgumentException::class.java) {
            CompetitionMahjongAutoReplayRecord.prefix(baseline, terminal, 1, config)
        }
        assertArrayEquals(original, baseline)
        assertThrows(IllegalArgumentException::class.java) {
            CompetitionMahjongAutoReplayRecord.prefix(autoRecord(config, 2), autoRecord(config, 1), 0, config)
        }
    }

    @Test fun openValidatesAllSixteenHandsAndStartsAtFirstBaseline() = runBlocking {
        val harness = Harness()
        assertTrue(harness.controller.open())
        val state = harness.controller.state.value
        assertTrue(state.ready)
        assertEquals(harness.archive.key, state.archiveKey)
        assertEquals(0, state.handIndex)
        assertEquals(0, state.eventIndex)
        assertEquals(3, state.eventCount)
        assertFalse(state.frame!!.isAutoTerminal())
        assertEquals(17, harness.sessions.size)
        assertTrue(harness.sessions.take(16).all { it.closed })
        assertFalse(harness.active.closed)
    }

    @Test fun forwardBackwardAndHandBoundaryUseExactRecordedPositions() = runBlocking {
        val harness = Harness()
        assertTrue(harness.controller.open())
        repeat(3) { index ->
            assertTrue(harness.controller.forward(0, index))
            assertEquals(index + 1, harness.controller.state.value.eventIndex)
        }
        assertTrue(harness.controller.state.value.frame!!.isAutoTerminal())
        assertTrue(harness.controller.forward(0, 3))
        assertEquals(1, harness.controller.state.value.handIndex)
        assertEquals(0, harness.controller.state.value.eventIndex)
        assertFalse(harness.controller.state.value.frame!!.isAutoTerminal())
        assertTrue(harness.controller.backward(1, 0))
        assertEquals(0, harness.controller.state.value.handIndex)
        assertEquals(3, harness.controller.state.value.eventIndex)
        assertTrue(harness.controller.backward(0, 3))
        assertEquals(2, harness.controller.state.value.eventIndex)
        assertFalse(harness.controller.state.value.frame!!.isAutoTerminal())
    }

    @Test fun staleAndOutOfRangeCommandsDoNotCreateOrMoveSessions() = runBlocking {
        val harness = Harness()
        assertTrue(harness.controller.open())
        val count = harness.sessions.size
        assertFalse(harness.controller.forward(1, 0))
        assertFalse(harness.controller.backward(0, 1))
        assertFalse(harness.controller.seek(0, 4))
        assertFalse(harness.controller.seek(16, 0))
        assertFalse(harness.controller.selectHand(-1))
        assertEquals(count, harness.sessions.size)
        assertEquals(0, harness.controller.state.value.eventIndex)
    }

    @Test fun directHandAndEventSelectionReachesFinalEndAndStops() = runBlocking {
        val harness = Harness()
        assertTrue(harness.controller.open())
        assertTrue(harness.controller.selectHand(15))
        assertEquals(15, harness.controller.state.value.handIndex)
        assertEquals(0, harness.controller.state.value.eventIndex)
        assertTrue(harness.controller.seek(15, 3))
        assertTrue(harness.controller.state.value.atEnd)
        assertFalse(harness.controller.forward(15, 3))
        assertTrue(harness.controller.backward(15, 3))
        assertEquals(2, harness.controller.state.value.eventIndex)
    }

    @Test fun specifiedOpeningCountsOnlyEventsAfterImmutableBaseline() = runBlocking {
        val config = autoConfig()
        val opening = autoRecord(config.handConfig(0, 0, true), 1)
        val harness = Harness(autoCheckpoint(config, hand = 15, completed = true, opening = opening))
        assertTrue(harness.controller.open())
        assertEquals(2, harness.controller.state.value.eventCount)
        assertEquals(1, harness.active.count)
        assertFalse(harness.controller.backward(0, 0))
        assertTrue(harness.controller.forward(0, 0))
        assertEquals(2, harness.active.count)
    }

    @Test fun rejectedCandidatePreservesCurrentSessionAndPosition() = runBlocking {
        val harness = Harness()
        assertTrue(harness.controller.open())
        val old = harness.active
        harness.rejectRestore = { it == 1 }
        assertFalse(harness.controller.forward(0, 0))
        assertSame(old, harness.active)
        assertFalse(old.closed)
        assertTrue(harness.sessions.last().closed)
        assertEquals(0, harness.controller.state.value.eventIndex)
        assertNotNull(harness.controller.state.value.error)
    }

    @Test fun invalidArchivedResultIsRejectedAndAllCandidatesClose() = runBlocking {
        val harness = Harness(autoCheckpoint(hand = 15, completed = true, winnerPlayer = { 0 }))
        assertFalse(harness.controller.open())
        assertTrue(harness.sessions.all { it.closed })
        assertNull(harness.controller.state.value.archiveKey)
        assertNotNull(harness.controller.state.value.error)
    }

    @Test fun factoryMismatchClosesCandidateBeforePublishingFrame() = runBlocking {
        val harness = Harness()
        harness.wrongConfig = true
        assertFalse(harness.controller.open())
        assertTrue(harness.sessions.single().closed)
        assertNull(harness.controller.state.value.frame)
    }

    @Test fun closeIsIdempotentAndRejectsFurtherMovement() = runBlocking {
        val harness = Harness()
        assertTrue(harness.controller.open())
        harness.controller.close()
        harness.controller.close()
        assertTrue(harness.controller.state.value.closed)
        assertNull(harness.controller.state.value.frame)
        assertTrue(harness.sessions.all { it.closed })
        assertFalse(harness.controller.forward(0, 0))
        assertFalse(harness.controller.selectHand(1))
    }
}
