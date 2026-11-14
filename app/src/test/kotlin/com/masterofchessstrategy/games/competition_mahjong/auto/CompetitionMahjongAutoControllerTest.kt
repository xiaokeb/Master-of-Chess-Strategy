package com.masterofchessstrategy.games.competition_mahjong.auto

import com.masterofchessstrategy.game.ChineseChessSoundCue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CompetitionMahjongAutoControllerTest {
    @Test fun terminalCueWaitsForSaveAndDoesNotPlayOnRestoreOrUndo() = runBlocking {
        val harness = AutoHarness()
        val heard = mutableListOf<ChineseChessSoundCue>()
        val listening = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
            harness.controller.sounds.collect { heard += it }
        }
        try {
            assertTrue(harness.controller.open())
            repeat(2) { assertTrue(harness.step()) }
            harness.store.failNext = true
            assertFalse(harness.step())
            assertTrue(heard.isEmpty())
            assertTrue(harness.controller.retrySave())
            assertEquals(listOf(ChineseChessSoundCue.DRAW), heard)
            assertFalse(harness.controller.retrySave())
            harness.controller.close()

            val restored = AutoHarness(store = harness.store)
            val observing = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                restored.controller.sounds.collect { heard += it }
            }
            try {
                assertTrue(restored.controller.open())
                assertEquals(1, heard.size)
                assertTrue(restored.controller.undo(0, 0))
                assertEquals(1, heard.size)
            } finally { observing.cancel(); restored.controller.close() }
        } finally { listening.cancel(); harness.controller.close() }
    }
    @Test fun scheduledTickNeedsCurrentStateExecutionPermissionAndUnpausedSettings() = runBlocking {
        val harness = AutoHarness(settings = CompetitionMahjongAutoSettings(paused = false))
        assertTrue(harness.controller.open())
        assertFalse(harness.controller.scheduledStep(harness.controller.state.value))
        assertTrue(harness.controller.setExecutionActive(true))
        val beforePause = harness.controller.state.value
        assertTrue(harness.controller.updateSettings(beforePause.settings.copy(paused = true)))
        assertFalse(harness.controller.scheduledStep(beforePause))
        assertFalse(harness.controller.scheduledStep(harness.controller.state.value))
        assertTrue(harness.controller.updateSettings(beforePause.settings))
        val current = harness.controller.state.value
        assertTrue(harness.controller.scheduledStep(current))
        assertFalse(harness.controller.scheduledStep(current))
        assertEquals(1, harness.active.advanceCalls)
        val beforeStop = harness.controller.state.value
        assertTrue(harness.controller.setExecutionActive(false))
        assertFalse(harness.controller.scheduledStep(beforeStop))
        assertEquals(1, harness.active.advanceCalls)
    }

    @Test fun failedCandidateSnapshotCannotSaveOrReplaceExistingSession() = runBlocking {
        val harness = AutoHarness()
        assertTrue(harness.controller.open())
        val old = harness.active
        val bytes = harness.store.bytes!!.copyOf()
        harness.failNextSnapshot = true
        assertFalse(harness.controller.newRun(autoConfig(id = "81000000-0000-4000-8000-000000000002"), CompetitionMahjongAutoSettings()))
        assertFalse(old.closed)
        assertTrue(harness.sessions.last().closed)
        assertArrayEquals(bytes, harness.store.bytes)
        assertTrue(harness.step())
        assertEquals(1, old.advanceCalls)
    }

    @Test fun factoryMismatchClosesCandidateBeforeAnySave() = runBlocking {
        val candidate = AutoSessionDouble(autoConfig().handConfig(0, 0, false))
        val store = AutoMemoryStore()
        val target = autoConfig(viewer = 2)
        val controller = CompetitionMahjongAutoController(target, store, autoPolicy(), sessionFactory = { candidate })
        assertFalse(controller.open())
        assertTrue(candidate.closed)
        assertNull(store.bytes)
    }

    @Test fun failedUndoSaveBlocksProgressAndRetrySavesUndoneLedgerOnce() = runBlocking {
        val harness = AutoHarness()
        assertTrue(harness.controller.open())
        repeat(3) { assertTrue(harness.step()) }
        val terminal = harness.store.bytes!!.copyOf()
        harness.store.failNext = true
        assertFalse(harness.controller.undo(0, 0))
        assertArrayEquals(terminal, harness.store.bytes)
        assertFalse(harness.step())
        assertFalse(harness.controller.undo(0, 0))
        assertTrue(harness.controller.retrySave())
        assertEquals(2, harness.active.count)
        assertEquals(0, harness.store.load(0)!!.ledger.completedHandCount)
        assertEquals(3, harness.active.advanceCalls)
    }

    @Test fun newRunCannotUseAnUnauthorizedOpponentAndPreservesCurrentActivity() = runBlocking {
        val harness = AutoHarness(policy = autoPolicy(false))
        assertTrue(harness.controller.open())
        val old = harness.active
        val bytes = harness.store.bytes!!.copyOf()
        assertFalse(harness.controller.newRun(autoConfig(levels = listOf(0, 0, 0, 3),
            id = "81000000-0000-4000-8000-000000000002"), CompetitionMahjongAutoSettings()))
        assertEquals(1, harness.sessions.size)
        assertFalse(old.closed)
        assertArrayEquals(bytes, harness.store.bytes)
    }

    @Test fun openSavesInitialStateWithoutRunningAnySeat() = runBlocking {
        val harness = AutoHarness()
        assertTrue(harness.controller.open())
        assertEquals(1, harness.store.writes)
        assertEquals(0, harness.active.advanceCalls)
        assertEquals(0, harness.controller.state.value.eventsAfterOpening)
        assertTrue(harness.controller.state.value.settings.paused)
        assertFalse(harness.controller.state.value.canUndo)
        harness.controller.close()
        assertTrue(harness.sessions.single().closed)
    }

    @Test fun missingPolicyOrAnyLockedPlayerPreventsNativeAndStorage() = runBlocking {
        val cases = listOf(AutoHarness(policy = null), AutoHarness(config = autoConfig(levels = listOf(0, 0, 1, 0)), policy = autoPolicy(false)))
        cases.forEach { harness ->
            assertFalse(harness.controller.open())
            assertTrue(harness.sessions.isEmpty())
            assertNull(harness.store.bytes)
            assertNotNull(harness.controller.state.value.error)
        }
    }

    @Test fun failedInitialSaveClosesCandidateAndKeepsExistingBytes() = runBlocking {
        val harness = AutoHarness()
        harness.store.failNext = true
        assertFalse(harness.controller.open())
        assertTrue(harness.sessions.single().closed)
        assertNull(harness.controller.state.value.runId)
        assertNull(harness.store.bytes)
        assertTrue(harness.controller.open())
    }

    @Test fun everyStepCommitsBeforeMoreProgressAndFailedSaveBlocksUntilRetry() = runBlocking {
        val harness = AutoHarness()
        assertTrue(harness.controller.open())
        val initial = harness.store.bytes!!.copyOf()
        harness.store.failNext = true
        assertFalse(harness.step())
        assertEquals(1, harness.active.advanceCalls)
        assertTrue(harness.controller.state.value.needsSave)
        assertFalse(harness.step())
        assertEquals(1, harness.active.advanceCalls)
        assertArrayEquals(initial, harness.store.bytes)
        assertFalse(harness.controller.updateSettings(harness.controller.state.value.settings.copy(speed = 2f)))
        assertTrue(harness.controller.retrySave())
        assertEquals(1, harness.store.load(0)!!.eventsAfterOpening)
        assertTrue(harness.step())
        assertEquals(2, harness.store.load(0)!!.eventsAfterOpening)
    }

    @Test fun savedStateRestoresWithoutReplayingAiOrUsingNewOpeningArgument() = runBlocking {
        val first = AutoHarness()
        assertTrue(first.controller.open())
        assertTrue(first.step())
        val saved = first.store.bytes!!.copyOf()
        first.controller.close()
        val replacementOpening = autoRecord(first.config.handConfig(0, 0, true), 1, origin = 999)
        val resumed = AutoHarness(store = first.store, opening = replacementOpening)
        assertTrue(resumed.controller.open())
        assertEquals(1, resumed.active.count)
        assertEquals(0, resumed.active.advanceCalls)
        assertNull(first.store.load(0)!!.openingRecord)
        assertArrayEquals(saved, first.store.bytes)
    }

    @Test fun damagedUnknownOrForeignRecordsArePreservedWithoutNewSession() = runBlocking {
        val good = CompetitionMahjongAutoCheckpointCodec.encode(autoCheckpoint())
        val unknown = good.copyOf().also { it[4] = 2; fixAutoCheckpointCrc(it) }
        val foreign = CompetitionMahjongAutoCheckpointCodec.encode(autoCheckpoint(autoConfig(1)))
        listOf(byteArrayOf(0), unknown, foreign).forEach { bytes ->
            val store = AutoMemoryStore().also { it.bytes = bytes.copyOf() }
            val harness = AutoHarness(store = store)
            assertFalse(harness.controller.open())
            assertTrue(harness.sessions.isEmpty())
            assertArrayEquals(bytes, store.bytes)
            assertEquals(0, store.writes)
        }
    }

    @Test fun nativeRestoreRejectsCurrentOrEarlierResultsWithoutOverwriting() = runBlocking {
        val checkpoints = listOf(autoCheckpoint(events = 1), autoCheckpoint(hand = 2, winnerPlayer = { 0 }))
        checkpoints.forEachIndexed { index, checkpoint ->
            val store = AutoMemoryStore().also { it.bytes = CompetitionMahjongAutoCheckpointCodec.encode(checkpoint) }
            val bytes = store.bytes!!.copyOf()
            val harness = AutoHarness(store = store)
            if (index == 0) harness.rejectRestore = { it == 1 }
            assertFalse(harness.controller.open())
            assertTrue(harness.sessions.all { it.closed })
            assertArrayEquals(bytes, store.bytes)
            assertEquals(0, store.writes)
        }
    }

    @Test fun standardActivityCannotRestoreFromAnUnspecifiedWall() = runBlocking {
        val config = autoConfig()
        val hand = config.handConfig(0, 0, false)
        val altered = autoRecord(hand, origin = 250)
        val checkpoint = CompetitionMahjongAutoCheckpoint(
            config, CompetitionMahjongAutoSettings(), 0, 0, emptyList(), altered, altered,
        )
        val saved = CompetitionMahjongAutoCheckpointCodec.encode(checkpoint)
        val harness = AutoHarness(store = AutoMemoryStore().also { it.bytes = saved.copyOf() })

        assertFalse(harness.controller.open())
        assertTrue(harness.sessions.single().closed)
        assertArrayEquals(saved, harness.store.bytes)
        assertEquals(0, harness.store.writes)
    }

    @Test fun fixedOpeningLimitsUndoAndIsReusedAtNextSeriesOnly() = runBlocking {
        val config = autoConfig()
        val opening = autoRecord(config.handConfig(0, 0, true), 1)
        val harness = AutoHarness(config, settings = CompetitionMahjongAutoSettings(autoContinue = true, maxSeries = 2), opening = opening)
        assertTrue(harness.controller.open())
        assertEquals(1, harness.active.count)
        assertFalse(harness.controller.undo(0, 0))
        assertTrue(harness.step())
        assertTrue(harness.controller.undo(0, 0))
        assertEquals(1, harness.active.count)
        assertFalse(harness.controller.undo(0, 0))
        while (!harness.controller.state.value.seriesComplete) assertTrue(harness.step())
        assertEquals(1, harness.store.archives.size)
        assertTrue(harness.step())
        assertEquals(1, harness.controller.state.value.seriesIndex)
        assertEquals(1, harness.active.count)
        assertEquals(0, harness.controller.state.value.eventsAfterOpening)
        assertArrayEquals(opening, harness.store.load(0)!!.baseline)
        assertArrayEquals(opening, harness.store.load(0)!!.openingRecord)
    }

    @Test fun allSixteenHandsThenFiniteSecondSeriesStopAndArchiveExactlyOnce() = runBlocking {
        val harness = AutoHarness(config = autoConfig(2, listOf(0, 1, 2, 3)),
            settings = CompetitionMahjongAutoSettings(autoContinue = true, maxSeries = 2))
        assertTrue(harness.controller.open())
        repeat(127) { assertTrue("step=$it", harness.step()) }
        val state = harness.controller.state.value
        assertEquals(1, state.seriesIndex)
        assertEquals(15, state.handIndex)
        assertEquals(16, state.completedHands)
        assertTrue(state.finished)
        assertFalse(state.canUndo)
        assertFalse(harness.step())
        assertEquals(2, harness.store.archives.size)
        assertEquals(32, harness.sessions.size)
        assertEquals(96, harness.sessions.sumOf { it.advanceCalls })
        assertTrue(harness.sessions.dropLast(1).all { it.closed })
        assertEquals(16, harness.store.load(2)!!.completedHands.size)
        assertTrue(harness.controller.updateSettings(state.settings.copy(speed = 1.875f)))
        assertEquals(2, harness.store.archives.size)
        assertEquals(1.875f, harness.store.load(2)!!.settings.speed)

        val removedKey = "${harness.config.runId}/1"
        assertNotNull(harness.store.archives.remove(removedKey))
        assertTrue(harness.controller.updateSettings(state.settings.copy(speed = 1.5f)))
        assertTrue(harness.controller.setExecutionActive(false))
        assertFalse(harness.store.archives.containsKey(removedKey))
    }

    @Test fun archiveCommitReceiptFailureRetriesIdempotentlyWithoutAnotherAiEvent() = runBlocking {
        val harness = AutoHarness()
        assertTrue(harness.controller.open())
        repeat(62) { assertTrue(harness.step()) }
        harness.store.failAfterCommit = true
        assertFalse(harness.step())
        assertEquals(1, harness.store.archives.size)
        assertTrue(harness.controller.state.value.needsSave)
        val calls = harness.sessions.sumOf { it.advanceCalls }
        assertFalse(harness.step())
        assertTrue(harness.controller.retrySave())
        assertEquals(calls, harness.sessions.sumOf { it.advanceCalls })
        assertEquals(1, harness.store.archives.size)
        assertTrue(harness.controller.state.value.finished)
    }

    @Test fun disabledArchiveConditionsKeepCompletedActivityWithoutCreatingArchive() = runBlocking {
        val harness = AutoHarness(settings = CompetitionMahjongAutoSettings(archiveAll = false, archiveFanThreshold = 8))
        assertTrue(harness.controller.open())
        repeat(63) { assertTrue(harness.step()) }
        assertTrue(harness.controller.state.value.finished)
        assertTrue(harness.store.archives.isEmpty())
        assertEquals(16, harness.store.load(0)!!.ledger.completedHandCount)
    }

    @Test fun failedNextHandKeepsTerminalSessionAndRetryDoesNotRepeatScore() = runBlocking {
        val harness = AutoHarness()
        assertTrue(harness.controller.open())
        repeat(3) { assertTrue(harness.step()) }
        val old = harness.active
        val bytes = harness.store.bytes!!.copyOf()
        harness.store.failNext = true
        assertFalse(harness.step())
        assertFalse(old.closed)
        assertTrue(harness.sessions.last().closed)
        assertEquals(1, harness.controller.state.value.completedHands)
        assertArrayEquals(bytes, harness.store.bytes)
        assertTrue(harness.step())
        assertTrue(old.closed)
        assertEquals(1, harness.controller.state.value.completedHands)
        assertEquals(1, harness.controller.state.value.handIndex)
        assertFalse(harness.controller.step(0, 0))
        assertFalse(harness.controller.undo(0, 0))
    }

    @Test fun pausedUndoRewritesTerminalLedgerButNeverCrossesPreviousHand() = runBlocking {
        val harness = AutoHarness()
        assertTrue(harness.controller.open())
        repeat(3) { assertTrue(harness.step()) }
        assertEquals(1, harness.controller.state.value.completedHands)
        assertTrue(harness.controller.undo(0, 0))
        assertEquals(0, harness.controller.state.value.completedHands)
        assertEquals(0, harness.store.load(0)!!.completedHands.size)
        assertTrue(harness.step())
        assertTrue(harness.step())
        assertEquals(1, harness.controller.state.value.handIndex)
        assertFalse(harness.controller.undo(0, 1))
        assertEquals(1, harness.store.load(0)!!.completedHands.size)
    }

    @Test fun allPlaybackSettingsCommitTogetherAndSurviveFailureRecovery() = runBlocking {
        val harness = AutoHarness()
        assertTrue(harness.controller.open())
        val old = harness.store.bytes!!.copyOf()
        val target = CompetitionMahjongAutoSettings(false, 2.375f, 123, 2345, true, 7, true, 4000, false, 8, 200, true, true)
        harness.store.failNext = true
        assertFalse(harness.controller.updateSettings(target))
        assertArrayEquals(old, harness.store.bytes)
        assertFalse(harness.step())
        assertTrue(harness.controller.retrySave())
        assertEquals(target, harness.store.load(0)!!.settings)
        assertEquals(0, harness.active.advanceCalls)
        harness.controller.close()
        val restored = AutoHarness(store = harness.store)
        assertTrue(restored.controller.open())
        assertEquals(target, restored.controller.state.value.settings)
        assertFalse(restored.controller.undo(0, 0))
    }

    @Test fun executionClockStopsForPauseOrUngrantableBackgroundAndPersistsElapsed() = runBlocking {
        val harness = AutoHarness(settings = CompetitionMahjongAutoSettings(paused = false))
        assertTrue(harness.controller.open())
        assertTrue(harness.controller.setExecutionActive(true))
        harness.time = 125
        assertTrue(harness.step())
        assertEquals(125L, harness.store.load(0)!!.elapsedMillis)
        harness.time = 300
        assertTrue(harness.controller.updateSettings(harness.controller.state.value.settings.copy(paused = true)))
        harness.time = 999
        assertTrue(harness.controller.setExecutionActive(false))
        assertEquals(300L, harness.store.load(0)!!.elapsedMillis)
        assertTrue(harness.controller.updateSettings(harness.controller.state.value.settings.copy(paused = false)))
        harness.time = 1200
        assertTrue(harness.controller.setExecutionActive(true))
        harness.time = 1301
        assertTrue(harness.step())
        assertEquals(401L, harness.store.load(0)!!.elapsedMillis)
    }

    @Test fun confirmedNewRunCommitsBeforeReplacingAndClosesFailedCandidateOnly() = runBlocking {
        val harness = AutoHarness()
        assertTrue(harness.controller.open())
        assertTrue(harness.step())
        val old = harness.active
        val bytes = harness.store.bytes!!.copyOf()
        val target = autoConfig(id = "81000000-0000-4000-8000-000000000002")
        harness.store.failNext = true
        assertFalse(harness.controller.newRun(target, CompetitionMahjongAutoSettings(speed = 2f)))
        assertFalse(old.closed)
        assertTrue(harness.sessions.last().closed)
        assertArrayEquals(bytes, harness.store.bytes)
        assertTrue(harness.controller.newRun(target, CompetitionMahjongAutoSettings(speed = 2f)))
        assertTrue(old.closed)
        assertEquals(target.runId, harness.store.load(0)!!.config.runId)
        assertEquals(0, harness.controller.state.value.completedHands)
        assertEquals(2f, harness.controller.state.value.settings.speed)
    }
}
