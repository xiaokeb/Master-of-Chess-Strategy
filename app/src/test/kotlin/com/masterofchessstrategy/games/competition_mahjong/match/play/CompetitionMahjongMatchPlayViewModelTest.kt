package com.masterofchessstrategy.games.competition_mahjong.match.play

import androidx.lifecycle.ViewModelStore
import com.masterofchessstrategy.engine.CompetitionMahjongAction
import com.masterofchessstrategy.engine.CompetitionMahjongApplyResult
import com.masterofchessstrategy.engine.CompetitionMahjongFanEntry
import com.masterofchessstrategy.engine.CompetitionMahjongFanView
import com.masterofchessstrategy.engine.CompetitionMahjongMeldKind
import com.masterofchessstrategy.engine.CompetitionMahjongPublicMeld
import com.masterofchessstrategy.engine.CompetitionMahjongPublicView
import com.masterofchessstrategy.engine.CompetitionMahjongRoundActionKind
import com.masterofchessstrategy.engine.CompetitionMahjongRoundError
import com.masterofchessstrategy.engine.CompetitionMahjongRoundPhase
import com.masterofchessstrategy.engine.CompetitionMahjongRuleSession
import com.masterofchessstrategy.engine.CompetitionMahjongSeatView
import com.masterofchessstrategy.engine.EngineError
import com.masterofchessstrategy.engine.RestoreResult
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.game.ChineseChessSoundCue
import com.masterofchessstrategy.games.competition_mahjong.CompetitionMahjongMatchConfig
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongCompetitionConfig
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchCheckpoint
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchCheckpointCodec
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchStore
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongAssistanceUsage
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongPlayPolicy
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongUnlockProgress
import com.masterofchessstrategy.games.competition_mahjong.match.hint.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

@OptIn(ExperimentalCoroutinesApi::class)
class CompetitionMahjongMatchPlayViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val owners = mutableListOf<ViewModelStore>()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() {
        owners.forEach(ViewModelStore::clear)
        dispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }

    @Test fun liveSoundWaitsForAcceptedSaveAndNeverReplaysOnRestore() = runTest(dispatcher) {
        val harness = Harness()
        val game = harness.game()
        val heard = mutableListOf<ChineseChessSoundCue>()
        val listening = backgroundScope.launch { game.sounds.collect { heard += it } }
        runCurrent()
        advanceUntilIdle()
        assertTrue(heard.isEmpty())
        harness.store.failWrite = true
        game.performAction(DISCARD_ID, 0)
        advanceUntilIdle()
        assertTrue(game.state.value.series.needsSave)
        assertTrue(heard.isEmpty())
        harness.store.failWrite = false
        game.retrySave()
        advanceUntilIdle()
        runCurrent()
        assertEquals(listOf(ChineseChessSoundCue.MOVE), heard)
        game.retrySave()
        advanceUntilIdle()
        assertEquals(1, heard.size)
        listening.cancel()
        harness.owner.clear()
        advanceUntilIdle()

        val reopened = harness.game()
        backgroundScope.launch { reopened.sounds.collect { heard += it } }
        runCurrent()
        advanceUntilIdle()
        assertFalse(reopened.state.value.loading)
        assertEquals(1, heard.size)
        reopened.performAction(WIN_ID, 0)
        advanceUntilIdle()
        runCurrent()
        assertEquals(listOf(ChineseChessSoundCue.MOVE, ChineseChessSoundCue.VICTORY), heard)
    }

    @Test fun botBatchSoundUsesPublicMeldsAndPrioritizesTerminalResults() {
        val before = SessionDouble(CompetitionMahjongCompetitionConfig(ID, 10).handConfig(0)).snapshot()
        val seats = before.seats.toMutableList()
        seats[1] = seats[1].copy(melds = listOf(CompetitionMahjongPublicMeld(CompetitionMahjongMeldKind.PUNG,
            tile = 0, fromSeat = 0, claimedPosition = 0, addedKong = false)))
        val claimed = before.copy(seats = seats)
        assertEquals(ChineseChessSoundCue.CAPTURE, competitionMahjongLiveSound(before, claimed))
        assertEquals(ChineseChessSoundCue.DEFEAT, competitionMahjongLiveSound(before,
            claimed.copy(phase = CompetitionMahjongRoundPhase.WON, winnerSeat = 1)))
        assertEquals(ChineseChessSoundCue.DRAW, competitionMahjongLiveSound(before,
            before.copy(phase = CompetitionMahjongRoundPhase.EXHAUSTIVE_DRAW)))
        assertEquals(ChineseChessSoundCue.DEFEAT, competitionMahjongLiveSound(before, before, falseWin = true))
        assertNull(competitionMahjongLiveSound(before, before, CompetitionMahjongAction(CompetitionMahjongRoundActionKind.PASS)))
    }

    @Test fun retirementDrainsAcceptedSaveThenClosesExactlyOnce() = runTest(dispatcher) {
        val harness = Harness()
        val gate = CompletableDeferred<Unit>()
        harness.store.gate = gate
        val game = harness.game()
        runCurrent()
        val done = game.beginRetirement()
        assertTrue(done === game.beginRetirement())
        assertFalse(done.isCompleted)
        game.newMatch(1); game.retryLoad(); game.setSpeed(2f)
        gate.complete(Unit)
        advanceUntilIdle()
        done.await()
        assertEquals(1, harness.store.attempts)
        assertEquals(1, harness.sessions.single().closes)
        assertTrue(game.state.value.retired)
        assertFalse(game.state.value.canOperate)
    }

    @Test fun clearBeforeAcceptedLoadStartsStillDrainsAndCloses() = runTest(dispatcher) {
        val harness = Harness()
        val game = harness.game()
        harness.owner.clear()
        advanceUntilIdle()
        game.retirementCompletion.await()
        assertEquals(1, harness.sessions.single().closes)
    }

    @Test fun expiredOwnerNeverReloadsOrPublishesTheLateSave() = runTest(dispatcher) {
        val harness = Harness()
        val gate = CompletableDeferred<Unit>()
        harness.store.gate = gate
        val game = harness.game()
        runCurrent()
        harness.currentOwner = false
        gate.complete(Unit)
        advanceUntilIdle()
        game.retirementCompletion.await()
        game.retryLoad(); game.newMatch(1)
        advanceUntilIdle()
        assertTrue(game.state.value.retired)
        assertNull(game.state.value.table)
        assertEquals(1, harness.store.attempts)
        assertEquals(1, harness.sessions.single().closes)
    }

    @Test fun closeFailureIsStableAndNeverRetriedOrSwallowed() = runTest(dispatcher) {
        val harness = Harness().apply { configureSession = { it.closeFailure = IOException("native close") } }
        val game = harness.game()
        advanceUntilIdle()
        val done = game.beginRetirement()
        advanceUntilIdle()
        repeat(2) {
            try { game.beginRetirement().await(); error("Expected cleanup failure") }
            catch (failure: IOException) { assertEquals("native close", failure.message) }
        }
        assertTrue(done.isCompleted)
        assertEquals(1, harness.sessions.single().closes)
    }

    @Test fun missingOwnerContractNeverLoadsOrCreatesNativeSession() = runTest(dispatcher) {
        val harness = Harness().apply { ownerProvided = false }
        val game = harness.game()
        advanceUntilIdle()
        game.retirementCompletion.await()
        assertTrue(game.state.value.retired)
        assertTrue(harness.sessions.isEmpty())
        assertEquals(0, harness.store.attempts)
    }

    @Test fun retirementWaitsForTheAcceptedHintWithoutChargingOrPublishingIt() = runTest(dispatcher) {
        val advisor = HintDouble().apply { gate = CompletableDeferred() }
        val harness = Harness().apply { hintAdvisor = advisor }
        val game = harness.game()
        advanceUntilIdle()
        val saved = harness.store.bytes!!.copyOf()
        game.requestHint(0)
        runCurrent()
        val done = game.beginRetirement()
        assertFalse(done.isCompleted)
        assertEquals(0, harness.sessions.single().closes)
        game.requestHint(0)
        advisor.gate!!.complete(Unit)
        advanceUntilIdle()
        done.await()
        assertEquals(1, advisor.calls)
        assertEquals(1, harness.sessions.single().closes)
        assertArrayEquals(saved, harness.store.bytes)
        assertNull(game.state.value.hint)
    }

    @Test fun firstSaveMustCompleteBeforeTheHumanCanAct() = runTest(dispatcher) {
        val harness = Harness()
        val gate = CompletableDeferred<Unit>()
        harness.store.gate = gate
        val game = harness.game()
        runCurrent()
        assertFalse(game.state.value.canOperate)
        game.performAction(DISCARD_ID, 0)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(game.state.value.canOperate)
        assertEquals(0, harness.sessions.single().events.size)
        assertEquals(1, harness.store.records.size)
        assertFalse(game.state.value.table!!.canUndo)
    }

    @Test fun failedReadDoesNotConstructNativeOrOverwriteEvidenceAndRetryIsAvailable() = runTest(dispatcher) {
        val harness = Harness()
        harness.store.failRead = true
        val game = harness.game()
        advanceUntilIdle()
        assertTrue(harness.sessions.isEmpty())
        assertNull(game.state.value.table)
        assertNotNull(game.state.value.error)
        assertTrue(harness.store.records.isEmpty())
        harness.store.failRead = false
        game.retryLoad()
        advanceUntilIdle()
        assertTrue(game.state.value.canOperate)
        assertEquals(1, harness.sessions.size)
    }

    @Test fun humanChangeCommitsBeforeSchedulingAnyOtherSeat() = runTest(dispatcher) {
        val harness = Harness()
        val game = harness.game()
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        harness.store.gate = gate
        game.performAction(DISCARD_ID, 0)
        runCurrent()
        assertEquals(listOf(1), harness.sessions.single().events)
        assertEquals(0, harness.sessions.single().botCalls)
        assertTrue(game.state.value.busy)
        game.performAction(DISCARD_ID, 0)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(listOf(1, 2), harness.sessions.single().events)
        assertTrue(game.state.value.canOperate)
        assertEquals(3, harness.store.records.size)
    }

    @Test fun failedAcceptedWriteBlocksInputUndoAndReplacementUntilRetryFinishes() = runTest(dispatcher) {
        val harness = Harness()
        val game = harness.game()
        advanceUntilIdle()
        val oldBytes = harness.store.bytes!!.copyOf()
        harness.store.failWrite = true
        game.performAction(DISCARD_ID, 0)
        advanceUntilIdle()
        assertTrue(game.state.value.series.needsSave)
        assertFalse(game.state.value.canOperate)
        game.performAction(DISCARD_ID, 0)
        game.undo(0)
        game.newMatch(3)
        advanceUntilIdle()
        assertEquals(listOf(1), harness.sessions.single().events)
        assertEquals(0, harness.sessions.single().botCalls)
        assertArrayEquals(oldBytes, harness.store.bytes)
        harness.store.failWrite = false
        game.retrySave()
        advanceUntilIdle()
        assertFalse(game.state.value.series.needsSave)
        assertEquals(listOf(1, 2), harness.sessions.single().events)
        assertTrue(game.state.value.canOperate)
    }

    @Test fun explicitFalseWinDisplaysPenaltyDisablesFurtherDeclarationAndPersistsIt() = runTest(dispatcher) {
        val harness = Harness()
        val game = harness.game()
        advanceUntilIdle()
        assertTrue(game.state.value.canDeclareWin)
        game.declareWin(0, 0)
        advanceUntilIdle()
        assertFalse(game.state.value.canDeclareWin)
        assertTrue(game.state.value.declarationTiles.isEmpty())
        assertTrue(game.state.value.table!!.feedback!!.contains("各付20分"))
        assertTrue(game.state.value.table!!.feedback!!.contains("本盘停和"))
        assertEquals(listOf(-60, 20, 20, 20), game.state.value.series.table!!.netScores)
        assertEquals(0, game.state.value.series.completedHandCount)
        assertEquals(listOf(4), harness.sessions.single().events)
        game.declareWin(0, 0)
        advanceUntilIdle()
        assertEquals(listOf(4), harness.sessions.single().events)
        assertEquals(2, harness.store.records.size)
    }

    @Test fun unknownActionAndOutOfContextDeclarationNeverReachNative() = runTest(dispatcher) {
        val harness = Harness()
        val game = harness.game()
        advanceUntilIdle()
        game.performAction("obsolete", 0)
        advanceUntilIdle()
        game.declareWin(33, 0)
        advanceUntilIdle()
        assertTrue(harness.sessions.single().events.isEmpty())
        assertEquals(1, harness.store.records.size)
        assertTrue(game.state.value.canOperate)
    }

    @Test fun undoRewindsAiEventsAndStopsAtThePreviousLocalDecision() = runTest(dispatcher) {
        val harness = Harness()
        harness.configureSession = { it.botsUntilChoice = 2 }
        val game = harness.game()
        advanceUntilIdle()
        game.performAction(DISCARD_ID, 0)
        advanceUntilIdle()
        assertEquals(listOf(1, 2, 2), harness.sessions.single().events)
        game.undo(0)
        advanceUntilIdle()
        assertTrue(harness.sessions.single().events.isEmpty())
        assertEquals(3, harness.sessions.single().undoCalls)
        assertTrue(game.state.value.table!!.feedback!!.contains("上一处本席选择"))
        assertTrue(game.state.value.canOperate)
        game.undo(0)
        advanceUntilIdle()
        assertFalse(game.state.value.table!!.canUndo)
        assertTrue(game.state.value.table!!.feedback!!.contains("没有可撤销"))
    }

    @Test fun failedUndoWriteKeepsTheNativeUndoButBlocksAndRestoresItOnRetry() = runTest(dispatcher) {
        val harness = Harness()
        val game = harness.game()
        advanceUntilIdle()
        game.performAction(WIN_ID, 0)
        advanceUntilIdle()
        assertEquals(1, game.state.value.series.completedHandCount)
        harness.store.failWrite = true
        game.undo(0)
        advanceUntilIdle()
        assertTrue(game.state.value.series.needsSave)
        assertEquals(1, game.state.value.series.completedHandCount)
        assertTrue(harness.sessions.single().events.isEmpty())
        harness.store.failWrite = false
        game.retrySave()
        advanceUntilIdle()
        assertEquals(0, game.state.value.series.completedHandCount)
        assertTrue(game.state.value.table!!.canUndo)
        assertEquals(0L, game.state.value.series.cumulativeScores[0])
    }

    @Test fun nextHandRejectsAllOldHandEventsAndAdoptsTheNewSeat() = runTest(dispatcher) {
        val harness = Harness()
        val game = harness.game()
        advanceUntilIdle()
        repeat(4) { index ->
            game.performAction(WIN_ID, index)
            advanceUntilIdle()
            game.nextHand(index)
            advanceUntilIdle()
        }
        assertEquals(4, game.state.value.series.activeHandIndex)
        assertEquals(1, game.state.value.series.table!!.viewerSeat)
        val before = harness.sessions.last().events.toList()
        game.performAction(DISCARD_ID, 3)
        game.declareWin(0, 3)
        game.undo(3)
        game.nextHand(3)
        advanceUntilIdle()
        assertEquals(before, harness.sessions.last().events)
        assertEquals(4, game.state.value.series.completedHandCount)
    }

    @Test fun failedNextHandPreservesOldSessionAndCanRetryTheSameHand() = runTest(dispatcher) {
        val harness = Harness()
        val game = harness.game()
        advanceUntilIdle()
        game.performAction(WIN_ID, 0)
        advanceUntilIdle()
        val old = harness.sessions.first()
        harness.store.failWrite = true
        game.nextHand(0)
        advanceUntilIdle()
        assertEquals(0, old.closes)
        assertEquals(1, harness.sessions.last().closes)
        assertEquals(0, game.state.value.series.activeHandIndex)
        assertNotNull(game.state.value.error)
        harness.store.failWrite = false
        game.nextHand(0)
        advanceUntilIdle()
        assertEquals(1, old.closes)
        assertEquals(1, game.state.value.series.activeHandIndex)
        assertEquals(harness.sessions[1].config, harness.sessions[2].config)
    }

    @Test fun explicitNewSeriesSavesCandidateBeforeClosingTheOldOne() = runTest(dispatcher) {
        val harness = Harness()
        val game = harness.game()
        advanceUntilIdle()
        game.performAction(WIN_ID, 0)
        advanceUntilIdle()
        val before = harness.store.bytes!!.copyOf()
        harness.store.failWrite = true
        game.newMatch(3)
        advanceUntilIdle()
        assertEquals(0, harness.sessions.first().closes)
        assertEquals(1, harness.sessions.last().closes)
        assertArrayEquals(before, harness.store.bytes)
        assertEquals(1, game.state.value.series.completedHandCount)
        harness.store.failWrite = false
        game.newMatch(2)
        advanceUntilIdle()
        assertEquals(1, harness.sessions.first().closes)
        assertEquals(0, game.state.value.series.completedHandCount)
        assertEquals(0, game.state.value.series.activeHandIndex)
        assertEquals(2, game.state.value.botLevel)
        assertEquals(0L, game.state.value.series.cumulativeScores[0])
    }

    @Test fun sixteenHandPlayStopsAndCurrentFinalUndoWithdrawsOnlyItsProvisionalResult() = runTest(dispatcher) {
        val harness = Harness()
        val game = harness.game()
        advanceUntilIdle()
        repeat(16) { index ->
            game.performAction(WIN_ID, index)
            advanceUntilIdle()
            if (index < 15) { game.nextHand(index); advanceUntilIdle() }
        }
        assertEquals(16, game.state.value.series.completedHandCount)
        assertEquals(512L, game.state.value.series.cumulativeScores[0])
        assertEquals(4, game.state.value.series.standings.size)
        game.nextHand(15)
        advanceUntilIdle()
        assertEquals(16, harness.sessions.size)
        game.undo(15)
        advanceUntilIdle()
        assertEquals(15, game.state.value.series.completedHandCount)
        assertEquals(480L, game.state.value.series.cumulativeScores[0])
        assertTrue(game.state.value.series.standings.isEmpty())
    }

    @Test fun pauseDuringBotWriteFinishesThatBatchAndStopsBeforeTheNext() = runTest(dispatcher) {
        val harness = Harness()
        harness.configureSession = { it.botsUntilChoice = 2 }
        val game = harness.game()
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        harness.store.blockWriteNumber = harness.store.attempts + 2
        harness.store.gate = gate
        game.performAction(DISCARD_ID, 0)
        runCurrent()
        assertEquals(1, harness.sessions.single().botCalls)
        game.toggleBots()
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(game.state.value.paused)
        assertEquals(1, harness.sessions.single().botCalls)
        assertEquals(listOf(1, 2), harness.sessions.single().events)
        game.toggleBots()
        advanceUntilIdle()
        assertFalse(game.state.value.paused)
        assertEquals(2, harness.sessions.single().botCalls)
        assertTrue(game.state.value.canOperate)
    }

    @Test fun speedChangeControlsTheNextDelayAndInvalidSpeedIsIgnored() = runTest(dispatcher) {
        val harness = Harness()
        harness.configureSession = { it.botsUntilChoice = 2 }
        val game = harness.game()
        advanceUntilIdle()
        game.setSpeed(4f)
        game.setSpeed(Float.NaN)
        game.setSpeed(3f)
        assertEquals(4f, game.state.value.speed)
        advanceUntilIdle() // Playback metadata commits before another board operation is accepted.
        val start = dispatcher.scheduler.currentTime
        game.performAction(DISCARD_ID, 0)
        advanceUntilIdle()
        assertEquals(100L, dispatcher.scheduler.currentTime - start)
        assertEquals(4f, game.state.value.table!!.autoPlay.speed)
    }

    @Test fun backgroundStopsBetweenBatchesAndForegroundResumesTheSameSavedHand() = runTest(dispatcher) {
        val harness = Harness()
        harness.configureSession = { it.botsUntilChoice = 2 }
        val game = harness.game()
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        harness.store.blockWriteNumber = harness.store.attempts + 2
        harness.store.gate = gate
        game.performAction(DISCARD_ID, 0)
        runCurrent()
        game.setForeground(false)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, harness.sessions.single().botCalls)
        assertTrue(game.state.value.paused)
        game.setForeground(true)
        advanceUntilIdle()
        assertEquals(2, harness.sessions.single().botCalls)
        assertFalse(game.state.value.paused)
        assertTrue(game.state.value.canOperate)
    }

    @Test fun boundedAiLoopPausesAndZeroProgressGivesAnActionableError() = runTest(dispatcher) {
        val harness = Harness()
        harness.configureSession = { it.botsUntilChoice = Int.MAX_VALUE }
        val game = harness.game()
        advanceUntilIdle()
        game.performAction(DISCARD_ID, 0)
        advanceUntilIdle()
        assertEquals(64, harness.sessions.single().botCalls)
        assertTrue(game.state.value.paused)
        assertTrue(game.state.value.table!!.feedback!!.contains("推进上限"))
        harness.sessions.single().zeroProgress = true
        game.toggleBots()
        advanceUntilIdle()
        assertTrue(game.state.value.paused)
        assertNotNull(game.state.value.error)
        assertFalse(game.state.value.series.needsSave)
    }

    @Test fun disposalDuringAcceptedWriteCommitsThenClosesWithoutSchedulingAi() = runTest(dispatcher) {
        val harness = Harness()
        val game = harness.game()
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        harness.store.gate = gate
        game.performAction(DISCARD_ID, 0)
        runCurrent()
        harness.owner.clear()
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, harness.store.records.size)
        assertEquals(1, harness.sessions.single().closes)
        assertEquals(0, harness.sessions.single().botCalls)
        assertEquals(listOf(1), harness.sessions.single().events)
    }

    @Test fun restartFromSavedSeriesLoadsCorrectLevelAndViewerWithoutAwardingAgain() = runTest(dispatcher) {
        val harness = Harness()
        val game = harness.game()
        advanceUntilIdle()
        game.performAction(WIN_ID, 0)
        advanceUntilIdle()
        harness.owner.clear()
        advanceUntilIdle()
        val oldRecordCount = harness.store.records.size
        val resumed = harness.game()
        advanceUntilIdle()
        assertEquals(1, resumed.state.value.series.completedHandCount)
        assertEquals(32L, resumed.state.value.series.cumulativeScores[0])
        assertEquals(1, resumed.state.value.botLevel)
        assertEquals(oldRecordCount, harness.store.records.size)
    }

    @Test fun explicitV2DenialDoesNotAllowSeriesDeclarationFromTheDiscardEnum() = runTest(dispatcher) {
        val harness = Harness().apply { configureSession = { it.allowDeclaration = false } }
        val game = harness.game()
        advanceUntilIdle()
        assertTrue(game.state.value.canOperate)
        assertFalse(game.state.value.canDeclareWin)
        game.declareWin(0, 0)
        advanceUntilIdle()
        assertTrue(harness.sessions.single().events.isEmpty())
    }

    @Test fun legacySeriesFramePreservesLegalActionsWithoutInventingDeclarationOrDraw() = runTest(dispatcher) {
        val harness = Harness().apply { configureSession = { it.publicFrameVersion = 1 } }
        val game = harness.game()
        advanceUntilIdle()
        assertFalse(game.state.value.canDeclareWin)
        assertNull(game.state.value.table!!.drawnTile)
        game.performAction(DISCARD_ID, 0)
        advanceUntilIdle()
        assertEquals(listOf(1, 2), harness.sessions.single().events)
    }

    @Test fun absentPolicyCannotCreateNativeOrWriteAFreshSeries() = runTest(dispatcher) {
        val harness = Harness().apply { policy = null }
        val game = harness.game()
        advanceUntilIdle()
        assertNull(game.state.value.table)
        assertTrue(harness.sessions.isEmpty())
        assertTrue(harness.store.records.isEmpty())
        game.newMatch(0)
        advanceUntilIdle()
        assertTrue(harness.sessions.isEmpty())
        assertNotNull(game.state.value.error)
    }

    @Test fun lockedInitialAndRestartLevelsAreRejectedBeforeNativeAndExplicitAllowedRestartWorks() = runTest(dispatcher) {
        val harness = Harness().apply { policy = onlyEasyPolicy() }
        val game = harness.game()
        advanceUntilIdle()
        assertTrue(harness.sessions.isEmpty())
        game.newMatch(3)
        advanceUntilIdle()
        assertTrue(harness.sessions.isEmpty())
        assertTrue(harness.store.records.isEmpty())
        game.newMatch(0)
        advanceUntilIdle()
        assertTrue(game.state.value.canOperate)
        assertEquals(1, harness.sessions.size)
        assertEquals(Difficulty.EASY, harness.store.load(0)!!.assistance!!.difficulty)
        assertEquals(listOf(0), game.state.value.difficultyOptions.filter { it.unlocked }.map { it.botLevel })
    }

    @Test fun restoredLockedDifficultyIsPreservedWithoutSilentlyDowngradingOrCreatingNative() = runTest(dispatcher) {
        val harness = Harness()
        harness.game(3)
        advanceUntilIdle()
        harness.owner.clear()
        advanceUntilIdle()
        val saved = harness.store.bytes!!.copyOf()
        val writes = harness.store.records.size
        harness.policy = onlyEasyPolicy()
        val resumed = harness.game(0)
        advanceUntilIdle()
        assertNull(resumed.state.value.table)
        assertNotNull(resumed.state.value.error)
        assertEquals(1, harness.sessions.size)
        assertEquals(writes, harness.store.records.size)
        assertArrayEquals(saved, harness.store.bytes)
    }

    @Test fun legacySeriesWithoutUsageIsNotAutomaticallyGivenFreshAllowances() = runTest(dispatcher) {
        val harness = Harness()
        harness.game()
        advanceUntilIdle()
        harness.owner.clear()
        advanceUntilIdle()
        val saved = harness.store.load(0)!!
        val legacy = CompetitionMahjongMatchCheckpointCodec.encode(CompetitionMahjongMatchCheckpoint(
            saved.config, saved.ledger, saved.activeHandIndex, saved.engineRecord))
        harness.store.bytes = legacy
        val writes = harness.store.records.size
        val game = harness.game()
        advanceUntilIdle()
        assertNull(game.state.value.table)
        assertEquals(1, harness.sessions.size)
        assertEquals(writes, harness.store.records.size)
        assertArrayEquals(legacy, harness.store.bytes)
    }

    @Test fun threeHumanUndoRequestsChargeOnceEachAndQuotaSurvivesRecoveryAndNextHand() = runTest(dispatcher) {
        val harness = Harness()
        val game = harness.game()
        advanceUntilIdle()
        repeat(3) { used ->
            game.performAction(DISCARD_ID, 0)
            advanceUntilIdle()
            game.undo(0)
            advanceUntilIdle()
            assertEquals(used + 1, harness.store.load(0)!!.assistance!!.undoUsed)
        }
        game.performAction(DISCARD_ID, 0)
        advanceUntilIdle()
        val calls = harness.sessions.single().undoCalls
        game.undo(0)
        advanceUntilIdle()
        assertEquals(calls, harness.sessions.single().undoCalls)
        assertFalse(game.state.value.table!!.canUndo)
        harness.owner.clear()
        advanceUntilIdle()
        val resumed = harness.game()
        advanceUntilIdle()
        assertEquals(0, resumed.state.value.assistance!!.undoRemaining)
        resumed.performAction(WIN_ID, 0)
        advanceUntilIdle()
        resumed.nextHand(0)
        advanceUntilIdle()
        assertEquals(1, resumed.state.value.series.activeHandIndex)
        assertEquals(3, harness.store.load(0)!!.assistance!!.undoUsed)
    }

    @Test fun acceptedUndoWithFailedSaveBlocksAndRetriesTheSameSingleCharge() = runTest(dispatcher) {
        val harness = Harness()
        val game = harness.game()
        advanceUntilIdle()
        game.performAction(DISCARD_ID, 0)
        advanceUntilIdle()
        harness.store.failWrite = true
        game.undo(0)
        advanceUntilIdle()
        assertTrue(game.state.value.series.needsSave)
        assertEquals(1, game.state.value.assistance!!.undoUsed)
        assertEquals(0, harness.store.load(0)!!.assistance!!.undoUsed)
        val calls = harness.sessions.single().undoCalls
        game.undo(0)
        advanceUntilIdle()
        assertEquals(calls, harness.sessions.single().undoCalls)
        harness.store.failWrite = false
        game.retrySave()
        advanceUntilIdle()
        assertFalse(game.state.value.series.needsSave)
        assertEquals(1, harness.store.load(0)!!.assistance!!.undoUsed)
        assertFalse(harness.store.load(0)!!.assistance!!.undoInProgress)
        assertTrue(harness.sessions.single().events.isEmpty())
        assertEquals(1, harness.sessions.single().botCalls)
    }

    @Test fun savedIntermediateUndoRestoresAndCompletesWithoutASecondChargeOrBotReplay() = runTest(dispatcher) {
        val harness = Harness().apply { configureSession = { it.botsUntilChoice = 2 } }
        val game = harness.game(2)
        advanceUntilIdle()
        game.performAction(DISCARD_ID, 0)
        advanceUntilIdle()
        val beforeUndoWrites = harness.store.records.size
        game.undo(0)
        advanceUntilIdle()
        val intermediate = harness.store.records[beforeUndoWrites].copyOf()
        val pending = CompetitionMahjongMatchCheckpointCodec.decode(intermediate, 0)!!
        assertTrue(pending.assistance!!.undoInProgress)
        assertEquals(1, pending.assistance!!.undoUsed)
        harness.owner.clear()
        advanceUntilIdle()
        harness.store.bytes = intermediate
        val resumed = harness.game(2)
        advanceUntilIdle()
        assertTrue(resumed.state.value.canOperate)
        assertTrue(harness.sessions.last().events.isEmpty())
        assertEquals(0, harness.sessions.last().botCalls)
        assertEquals(1, harness.store.load(0)!!.assistance!!.undoUsed)
        assertFalse(harness.store.load(0)!!.assistance!!.undoInProgress)
    }

    @Test fun masterBlocksUndoCommandsAndAZeroEventUndoDoesNotConsumeQuota() = runTest(dispatcher) {
        val master = Harness()
        val game = master.game(3)
        advanceUntilIdle()
        game.performAction(DISCARD_ID, 0)
        advanceUntilIdle()
        game.undo(0)
        advanceUntilIdle()
        assertEquals(0, master.sessions.single().undoCalls)
        assertFalse(game.state.value.table!!.canUndo)
        val medium = Harness()
        val fresh = medium.game()
        advanceUntilIdle()
        val writes = medium.store.records.size
        fresh.undo(0)
        advanceUntilIdle()
        assertEquals(0, fresh.state.value.assistance!!.undoUsed)
        assertEquals(writes, medium.store.records.size)
    }

    @Test fun failedNewSeriesRetainsSpentAllowanceAndSuccessfulReplacementAloneResetsIt() = runTest(dispatcher) {
        val harness = Harness()
        val game = harness.game(2)
        advanceUntilIdle()
        game.performAction(DISCARD_ID, 0)
        advanceUntilIdle()
        game.undo(0)
        advanceUntilIdle()
        assertEquals(0, game.state.value.assistance!!.undoRemaining)
        val oldId = harness.store.load(0)!!.config.matchId
        harness.store.failWrite = true
        game.newMatch(2)
        advanceUntilIdle()
        assertEquals(oldId, harness.store.load(0)!!.config.matchId)
        assertEquals(1, game.state.value.assistance!!.undoUsed)
        assertEquals(0, harness.sessions.first().closes)
        harness.store.failWrite = false
        game.newMatch(2)
        advanceUntilIdle()
        assertFalse(oldId == harness.store.load(0)!!.config.matchId)
        assertEquals(0, harness.store.load(0)!!.assistance!!.undoUsed)
        assertEquals(1, game.state.value.assistance!!.undoRemaining)
    }

    @Test fun savedUserPauseAndSpeedRestoreWithoutAnyExtraNativeEvent() = runTest(dispatcher) {
        val harness = Harness()
        val game = harness.game()
        advanceUntilIdle()
        game.toggleBots()
        game.setSpeed(4f)
        advanceUntilIdle()
        assertEquals(true, harness.store.load(0)!!.playback!!.paused)
        assertEquals(4f, harness.store.load(0)!!.playback!!.speed)
        val before = harness.store.bytes!!.copyOf()
        harness.owner.clear()
        advanceUntilIdle()
        val reopened = harness.game()
        advanceUntilIdle()
        assertTrue(reopened.state.value.paused)
        assertEquals(4f, reopened.state.value.speed)
        assertEquals(0, harness.sessions.last().botCalls)
        assertTrue(harness.sessions.last().events.isEmpty())
        assertArrayEquals(before, harness.store.bytes)
    }

    @Test fun failedPlaybackSaveBlocksBoardAndPreferenceChangesUntilSameRetryCommits() = runTest(dispatcher) {
        val harness = Harness()
        val game = harness.game()
        advanceUntilIdle()
        harness.store.failWrite = true
        game.toggleBots()
        advanceUntilIdle()
        assertTrue(game.state.value.series.needsSave)
        assertTrue(game.state.value.paused)
        assertFalse(harness.store.load(0)!!.playback!!.paused)
        game.setSpeed(4f)
        game.toggleBots()
        game.performAction(DISCARD_ID, 0)
        advanceUntilIdle()
        assertEquals(1f, game.state.value.speed)
        assertTrue(harness.sessions.single().events.isEmpty())
        harness.store.failWrite = false
        game.retrySave()
        advanceUntilIdle()
        assertFalse(game.state.value.series.needsSave)
        assertTrue(harness.store.load(0)!!.playback!!.paused)
        assertEquals(0, harness.sessions.single().botCalls)
    }

    @Test fun backgroundExecutionStopDoesNotRewriteUserPausePreference() = runTest(dispatcher) {
        val harness = Harness()
        val game = harness.game()
        advanceUntilIdle()
        val before = harness.store.bytes!!.copyOf()
        game.setForeground(false)
        advanceUntilIdle()
        assertTrue(game.state.value.paused)
        assertFalse(harness.store.load(0)!!.playback!!.paused)
        assertArrayEquals(before, harness.store.bytes)
        game.setForeground(true)
        advanceUntilIdle()
        assertFalse(game.state.value.paused)
    }

    @Test fun nextHandPreservesUserPauseAndSpeed() = runTest(dispatcher) {
        val harness = Harness()
        val game = harness.game()
        advanceUntilIdle()
        game.toggleBots()
        game.setSpeed(2f)
        advanceUntilIdle()
        game.performAction(WIN_ID, 0)
        advanceUntilIdle()
        game.nextHand(0)
        advanceUntilIdle()
        assertEquals(1, game.state.value.series.activeHandIndex)
        assertTrue(game.state.value.paused)
        assertEquals(2f, harness.store.load(0)!!.playback!!.speed)
        assertTrue(harness.store.load(0)!!.playback!!.paused)
        assertEquals(0, harness.sessions.last().botCalls)
    }

    @Test fun oldVersionTwoUsesRuntimeDefaultsAndUpgradesOnlyAtNextSuccessfulSave() = runTest(dispatcher) {
        val harness = Harness()
        val game = harness.game()
        advanceUntilIdle()
        val saved = harness.store.load(0)!!
        val old = CompetitionMahjongMatchCheckpoint(saved.config, saved.ledger, saved.activeHandIndex,
            saved.engineRecord, saved.assistance)
        val bytes = CompetitionMahjongMatchCheckpointCodec.encode(old)
        harness.owner.clear()
        advanceUntilIdle()
        harness.store.bytes = bytes
        val reopened = harness.game()
        advanceUntilIdle()
        assertFalse(reopened.state.value.paused)
        assertEquals(1f, reopened.state.value.speed)
        assertArrayEquals(bytes, harness.store.bytes)
        reopened.setSpeed(0.5f)
        advanceUntilIdle()
        assertEquals(3, harness.store.bytes!![4].toInt())
        assertEquals(0.5f, harness.store.load(0)!!.playback!!.speed)
    }

    @Test fun queuedPauseWriteKeepsInputBusyAfterTheCurrentBotBatchCommits() = runTest(dispatcher) {
        val harness = Harness().apply { configureSession = { it.botsUntilChoice = 2 } }
        val game = harness.game()
        advanceUntilIdle()
        val botWrite = CompletableDeferred<Unit>()
        harness.store.blockWriteNumber = harness.store.attempts + 2
        harness.store.gate = botWrite
        game.performAction(DISCARD_ID, 0)
        runCurrent()
        game.toggleBots()
        val preferenceWrite = CompletableDeferred<Unit>()
        harness.store.blockWriteNumber = harness.store.attempts + 1
        harness.store.gate = preferenceWrite
        botWrite.complete(Unit)
        runCurrent()
        assertTrue(game.state.value.busy)
        assertFalse(game.state.value.canOperate)
        game.performAction(DISCARD_ID, 0)
        assertEquals(listOf(1, 2), harness.sessions.single().events)
        preferenceWrite.complete(Unit)
        advanceUntilIdle()
        assertFalse(game.state.value.busy)
        assertTrue(game.state.value.paused)
        assertTrue(harness.store.load(0)!!.playback!!.paused)
        assertEquals(1, harness.sessions.single().botCalls)
    }

    @Test fun paidHintSavesBeforeDisplayAndRepeatedViewDoesNotChargeAgain() = runTest(dispatcher) {
        val advisor = HintDouble()
        val harness = Harness().apply { hintAdvisor = advisor }
        val game = harness.game(2)
        advanceUntilIdle()
        val gate = CompletableDeferred<Unit>()
        harness.store.gate = gate
        game.requestHint(0)
        runCurrent()
        assertTrue(game.state.value.busy)
        assertNull(game.state.value.hint)
        assertEquals(0, harness.store.load(0)!!.assistance!!.hintUsed)
        gate.complete(Unit)
        advanceUntilIdle()
        assertNotNull(game.state.value.hint)
        assertEquals(1, harness.store.load(0)!!.assistance!!.hintUsed)
        assertEquals(4, harness.store.bytes!![4].toInt())
        val writes = harness.store.records.size
        game.requestHint(0)
        advanceUntilIdle()
        assertEquals(1, advisor.calls)
        assertEquals(writes, harness.store.records.size)
        assertTrue(harness.sessions.single().events.isEmpty())
    }

    @Test fun failedHintSaveRetriesOneChargeAndReopensPaidReceiptWithoutAnAdvisor() = runTest(dispatcher) {
        val advisor = HintDouble()
        val harness = Harness().apply { hintAdvisor = advisor }
        val game = harness.game(2)
        advanceUntilIdle()
        harness.store.failWrite = true
        game.requestHint(0)
        advanceUntilIdle()
        assertTrue(game.state.value.series.needsSave)
        assertNull(game.state.value.hint)
        assertEquals(1, game.state.value.assistance!!.hintUsed)
        game.requestHint(0)
        assertEquals(1, advisor.calls)
        harness.store.failWrite = false
        game.retrySave()
        advanceUntilIdle()
        val receipt = game.state.value.hint
        assertNotNull(receipt)
        harness.owner.clear()
        advanceUntilIdle()
        harness.hintAdvisor = null
        val restored = harness.game(2)
        advanceUntilIdle()
        assertEquals(receipt, restored.state.value.hint)
        assertTrue(restored.state.value.canHint)
        restored.requestHint(0)
        advanceUntilIdle()
        assertEquals(1, harness.store.load(0)!!.assistance!!.hintUsed)
        assertEquals(1, advisor.calls)
    }

    @Test fun rejectedEmptyOrThrowingAdviceNeverConsumesQuota() = runTest(dispatcher) {
        val advisor = HintDouble()
        val harness = Harness().apply { hintAdvisor = advisor }
        val game = harness.game()
        advanceUntilIdle()
        val before = harness.store.bytes!!.copyOf()
        advisor.action = CompetitionMahjongAction(CompetitionMahjongRoundActionKind.DISCARD, 33)
        game.requestHint(0)
        advanceUntilIdle()
        assertNotNull(game.state.value.error)
        advisor.action = null
        game.requestHint(0)
        advanceUntilIdle()
        advisor.failure = true
        game.requestHint(0)
        advanceUntilIdle()
        assertEquals(3, advisor.calls)
        assertEquals(0, game.state.value.assistance!!.hintUsed)
        assertArrayEquals(before, harness.store.bytes)
    }

    @Test fun masterAndMissingAdvisorDoNotOfferOrExecuteNewHints() = runTest(dispatcher) {
        val advisor = HintDouble()
        val master = Harness().apply { hintAdvisor = advisor }
        val game = master.game(3)
        advanceUntilIdle()
        assertFalse(game.state.value.canHint)
        game.requestHint(0)
        advanceUntilIdle()
        assertEquals(0, advisor.calls)
        val missing = Harness().game()
        advanceUntilIdle()
        assertFalse(missing.state.value.hintAdvisorAvailable)
        assertFalse(missing.state.value.canHint)
    }

    @Test fun acceptedActionClearsReceiptButNeverRefundsHintAllowance() = runTest(dispatcher) {
        val harness = Harness().apply { hintAdvisor = HintDouble() }
        val game = harness.game(2)
        advanceUntilIdle()
        game.requestHint(0)
        advanceUntilIdle()
        game.performAction(DISCARD_ID, 0)
        advanceUntilIdle()
        assertNull(game.state.value.hint)
        assertNull(harness.store.load(0)!!.hintReceipt)
        assertEquals(1, game.state.value.assistance!!.hintUsed)
        assertFalse(game.state.value.canHint)
        game.undo(0)
        advanceUntilIdle()
        assertEquals(1, game.state.value.assistance!!.hintUsed)
        assertNull(harness.store.load(0)!!.hintReceipt)
    }

    @Test fun backgroundDuringSuggestionDiscardsUnpaidResultAndDuplicateClickIsIgnored() = runTest(dispatcher) {
        val advisor = HintDouble().apply { gate = CompletableDeferred() }
        val harness = Harness().apply { hintAdvisor = advisor }
        val game = harness.game()
        advanceUntilIdle()
        game.requestHint(0)
        runCurrent()
        game.requestHint(0)
        game.setForeground(false)
        advisor.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, advisor.calls)
        assertEquals(0, harness.store.load(0)!!.assistance!!.hintUsed)
        assertNull(game.state.value.hint)
    }

    @Test fun zeroEventUndoKeepsPaidReceiptAndFailedNewSeriesRestoresIt() = runTest(dispatcher) {
        val advisor = HintDouble()
        val harness = Harness().apply { hintAdvisor = advisor }
        val game = harness.game()
        advanceUntilIdle()
        game.requestHint(0)
        advanceUntilIdle()
        val receipt = game.state.value.hint
        game.undo(0)
        advanceUntilIdle()
        assertEquals(receipt, game.state.value.hint)
        harness.store.failWrite = true
        game.newMatch(1)
        advanceUntilIdle()
        assertEquals(receipt, game.state.value.hint)
        assertEquals(1, game.state.value.assistance!!.hintUsed)
    }

    @Test fun timedOutAdvisorLeavesQuotaAndSavedNativeRecordUnchanged() = runTest(dispatcher) {
        val advisor = HintDouble().apply { gate = CompletableDeferred() }
        val harness = Harness().apply { hintAdvisor = advisor }
        val game = harness.game()
        advanceUntilIdle()
        val before = harness.store.bytes!!.copyOf()
        game.requestHint(0)
        advanceUntilIdle()
        assertEquals(1, advisor.calls)
        assertFalse(game.state.value.busy)
        assertEquals(0, game.state.value.assistance!!.hintUsed)
        assertNull(game.state.value.hint)
        assertNotNull(game.state.value.error)
        assertArrayEquals(before, harness.store.bytes)
    }

    @Test fun productionPublicAdvisorUsesVersionThreeSaveThenPersistsVersionFourWithoutNativeEvents() = runTest(dispatcher) {
        val harness = Harness().apply { hintAdvisor = CompetitionMahjongPublicShapeHintAdvisor }
        val game = harness.game(2)
        advanceUntilIdle()
        val before = harness.store.load(0)!!.engineRecord
        assertEquals(3, harness.store.bytes!![4].toInt())
        harness.store.failWrite = true
        game.requestHint(0)
        advanceUntilIdle()
        assertTrue(game.state.value.series.needsSave)
        assertNull(game.state.value.hint)
        assertEquals(3, harness.store.bytes!![4].toInt())
        harness.store.failWrite = false
        game.retrySave()
        advanceUntilIdle()
        assertEquals(4, harness.store.bytes!![4].toInt())
        assertEquals(CompetitionMahjongPublicShapeHintAdvisor.identity, game.state.value.hint!!.advisorIdentity)
        assertTrue(game.state.value.hint!!.action in harness.sessions.single().legalActions())
        assertEquals(1, harness.store.load(0)!!.assistance!!.hintUsed)
        assertArrayEquals(before, harness.store.load(0)!!.engineRecord)
        assertTrue(harness.sessions.single().events.isEmpty())
    }

    @Test fun committedHintWithLostAcknowledgementRestoresExactlyOneCharge() = runTest(dispatcher) {
        val harness = Harness().apply { hintAdvisor = CompetitionMahjongPublicShapeHintAdvisor }
        val first = harness.game(2)
        advanceUntilIdle()
        harness.store.loseAcknowledgement = true
        first.requestHint(0)
        advanceUntilIdle()
        assertTrue(first.state.value.series.needsSave)
        assertNull(first.state.value.hint)
        assertEquals(1, harness.store.load(0)!!.assistance!!.hintUsed)
        harness.owner.clear()
        advanceUntilIdle()
        harness.store.loseAcknowledgement = false
        harness.hintAdvisor = null
        val restored = harness.game(2)
        advanceUntilIdle()
        assertNotNull(restored.state.value.hint)
        assertEquals(1, restored.state.value.assistance!!.hintUsed)
        restored.requestHint(0)
        advanceUntilIdle()
        assertEquals(1, harness.store.load(0)!!.assistance!!.hintUsed)
    }

    private class HintDouble : CompetitionMahjongHintAdvisor {
        override val identity = "test-advisor-v1"
        var calls = 0
        var action: CompetitionMahjongAction? = CompetitionMahjongAction(CompetitionMahjongRoundActionKind.DISCARD, 0)
        var failure = false
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun recommend(input: CompetitionMahjongHintInput, decisionSeed: Long): CompetitionMahjongAction? {
            calls++
            gate?.await()
            if (failure) throw IOException("advisor")
            return action
        }
    }

    private fun onlyEasyPolicy() = CompetitionMahjongPlayPolicy.fromProgress(
        CompetitionMahjongUnlockProgress(true), Difficulty.entries.associateWith { it.code })

    private inner class Harness {
        var currentOwner = true
        var ownerProvided = true
        val store = MemoryStore()
        val sessions = mutableListOf<SessionDouble>()
        var configureSession: (SessionDouble) -> Unit = {}
        var owner = ViewModelStore()
        private var idIndex = 1
        var policy: CompetitionMahjongPlayPolicy? = CompetitionMahjongPlayPolicy.fromProgress(
            CompetitionMahjongUnlockProgress(true, 10, 15, 20), Difficulty.entries.associateWith { it.code })
        var hintAdvisor: CompetitionMahjongHintAdvisor? = null
        fun game(botLevel: Int = 1): CompetitionMahjongMatchPlayViewModel {
            val game = CompetitionMahjongMatchPlayViewModel(store, CompetitionMahjongCompetitionConfig(ID, 10, botLevel = botLevel),
                { config -> SessionDouble(config).also { configureSession(it); sessions += it } }, dispatcher,
                seedSource = { 20 }, idSource = { "80000000-0000-4000-8000-00000000000${++idIndex}" },
                playPolicy = policy, hintAdvisor = hintAdvisor, ownerIsCurrent = if (ownerProvided) ({ currentOwner }) else null)
            owner = ViewModelStore().also { it.put("match-play", game); owners += it }
            return game
        }
    }

    private class MemoryStore : CompetitionMahjongMatchStore {
        var bytes: ByteArray? = null
        val records = mutableListOf<ByteArray>()
        var failRead = false
        var failWrite = false
        var loseAcknowledgement = false
        var gate: CompletableDeferred<Unit>? = null
        var blockWriteNumber: Int? = null
        var attempts = 0
        override suspend fun load(viewerPlayerId: Int): CompetitionMahjongMatchCheckpoint? {
            if (failRead) throw IOException("读取失败，保留证据")
            return bytes?.let { CompetitionMahjongMatchCheckpointCodec.decode(it, viewerPlayerId) ?: throw IOException("测试存档损坏") }
        }
        override suspend fun save(checkpoint: CompetitionMahjongMatchCheckpoint) {
            attempts++
            if (blockWriteNumber == null || blockWriteNumber == attempts) gate?.let { gate = null; it.await() }
            if (failWrite) throw IOException("注入写失败")
            bytes = CompetitionMahjongMatchCheckpointCodec.encode(checkpoint)
            records += bytes!!.copyOf()
            if (loseAcknowledgement) throw IOException("写入已完成但确认丢失")
        }
    }

    private class SessionDouble(val config: CompetitionMahjongMatchConfig) : CompetitionMahjongRuleSession {
        val events = mutableListOf<Int>()
        var botsUntilChoice = 1
        var zeroProgress = false
        var botCalls = 0
        var undoCalls = 0
        var closes = 0
        var closeFailure: IOException? = null
        var publicFrameVersion = 2
        var allowDeclaration = true
        override val viewerSeat get() = config.viewerSeat
        private val won get() = events.lastOrNull() == 3
        private val disabled get() = 4 in events
        private val localChoice: Boolean get() = when (events.lastOrNull()) {
            1 -> false
            2 -> events.asReversed().takeWhile { it != 1 }.count { it == 2 } >= botsUntilChoice
            3, 4 -> true
            else -> config.dealerSeat == viewerSeat
        }
        override fun snapshot(): CompetitionMahjongPublicView {
            val scores = MutableList(4) { if (disabled) 20 else 0 }
            if (disabled) scores[viewerSeat] = -60
            val payer = (viewerSeat + 1) % 4
            if (won) { repeat(4) { scores[it] -= 8 }; scores[viewerSeat] += 40; scores[payer] -= 8 }
            val hand = listOf(0, 0, 1, 2, 3, 5, 6, 9, 10, 12, 18, 20, 27, 28)
            return CompetitionMahjongPublicView(viewerSeat,
                if (won) CompetitionMahjongRoundPhase.WON else if (localChoice) CompetitionMahjongRoundPhase.DISCARD else CompetitionMahjongRoundPhase.DRAW,
                config.dealerSeat, config.roundWind, if (localChoice) viewerSeat else (viewerSeat + 1) % 4,
                90 - events.size.coerceAtMost(90), null, null, if (won) viewerSeat else null, if (won) payer else null,
                (config.dealerSeat + 1) % 4, scores,
                CompetitionMahjongFanView(0, won, if (won) 8 else 0, if (won) 8 else 0, won,
                    if (won) listOf(CompetitionMahjongFanEntry(48, "双暗杠", 8, 1)) else emptyList()),
                (0..3).map { CompetitionMahjongSeatView(it, if (it == viewerSeat) 14 else 13,
                    disabled && it == viewerSeat, emptyList(), emptyList(), emptyList(), if (it == viewerSeat) hand else emptyList()) },
                canDeclareWin = allowDeclaration && localChoice && !won && !disabled,
                drawnTile = 28.takeIf { localChoice && !won },
                winningTile = 6.takeIf { won }, winningTiles = if (won) hand else emptyList(),
                publicFrameVersion = publicFrameVersion)
        }
        override fun legalActions(): List<CompetitionMahjongAction> = if (!localChoice || won) emptyList() else
            listOf(CompetitionMahjongAction(CompetitionMahjongRoundActionKind.DISCARD, 0)) +
                if (disabled) emptyList() else listOf(CompetitionMahjongAction(CompetitionMahjongRoundActionKind.WIN, 6))
        override fun apply(action: CompetitionMahjongAction): CompetitionMahjongApplyResult {
            if (!localChoice || won || (disabled && action.kind == CompetitionMahjongRoundActionKind.WIN))
                return CompetitionMahjongApplyResult(false, CompetitionMahjongRoundError.ILLEGAL_ACTION, false)
            val falseWin = action.kind == CompetitionMahjongRoundActionKind.WIN && action.tile != 6
            events += if (falseWin) 4 else if (action.kind == CompetitionMahjongRoundActionKind.WIN) 3 else 1
            return CompetitionMahjongApplyResult(true, CompetitionMahjongRoundError.NONE, falseWin)
        }
        override fun advanceBots(level: Int, seed: Long, maxActions: Int): Int {
            botCalls++
            if (zeroProgress || won || localChoice) return 0
            events += 2
            return 1
        }
        override fun undo(): Boolean { undoCalls++; if (events.isEmpty()) return false; events.removeAt(events.lastIndex); return true }
        override fun serialize(): ByteArray {
            val frame = ByteBuffer.allocate(17 + events.size).order(ByteOrder.LITTLE_ENDIAN)
            frame.putInt(0x534d434d).put(1.toByte()).put(viewerSeat.toByte()).putShort(0).putInt(events.size + 1).put(0.toByte())
            events.forEach { frame.put(it.toByte()) }
            frame.putInt(CRC32().apply { update(frame.array(), 0, frame.position()) }.value.toInt())
            return frame.array()
        }
        override fun restore(data: ByteArray): RestoreResult {
            if (data.size < 17) return RestoreResult.Rejected(EngineError.CORRUPTED_DATA)
            events.clear()
            for (index in 13 until data.size - 4) events += data[index].toInt()
            return RestoreResult.Restored
        }
        override fun close() { closes++; closeFailure?.let { throw it } }
    }

    companion object {
        private const val ID = "80000000-0000-4000-8000-000000000001"
        private const val DISCARD_ID = "1-0-0"
        private const val WIN_ID = "8-6-0"
    }
}
