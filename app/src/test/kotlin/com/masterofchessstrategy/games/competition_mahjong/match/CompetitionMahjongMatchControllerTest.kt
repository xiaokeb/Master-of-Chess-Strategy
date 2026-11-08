package com.masterofchessstrategy.games.competition_mahjong.match

import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.games.competition_mahjong.CompetitionMahjongMatchConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CompetitionMahjongMatchControllerTest {
    @Test fun sixteenHandsCommitBeforeAdvanceAndAllSessionsCloseExactlyOnce() = runBlocking {
        val harness = Harness(matchConfig(2))
        assertTrue(harness.controller.open())
        repeat(16) { index ->
            assertEquals(index, harness.controller.state.value.activeHandIndex)
            assertEquals(harness.config.handConfig(index).viewerSeat, harness.controller.state.value.table!!.viewerSeat)
            assertTrue(harness.controller.apply(WIN, index)!!.accepted)
            assertEquals(index + 1, harness.controller.state.value.completedHandCount)
            if (index < 15) assertTrue(harness.controller.nextHand(index)) else assertFalse(harness.controller.nextHand(index))
        }
        assertEquals(512L, harness.controller.state.value.cumulativeScores[0])
        assertEquals(1, harness.controller.state.value.standings.first { it.playerId == 0 }.rank)
        assertEquals(16, harness.sessions.size)
        assertTrue(harness.sessions.dropLast(1).all { it.closes == 1 })
        assertEquals(0, harness.sessions.last().closes)
        harness.controller.close()
        harness.controller.close()
        assertTrue(harness.sessions.all { it.closes == 1 })
    }

    @Test fun failedTerminalWriteBlocksEveryMutationAndRetryRecordsTheHandOnlyOnce() = runBlocking {
        val harness = Harness()
        harness.controller.open()
        harness.store.failWrite = true
        assertTrue(harness.controller.apply(WIN, 0)!!.accepted)
        assertTrue(harness.controller.state.value.needsSave)
        assertEquals(0, harness.controller.state.value.completedHandCount)
        assertEquals(0, harness.store.saved()!!.ledger.completedHandCount)
        assertNull(harness.controller.apply(WIN, 0))
        assertFalse(harness.controller.undo(0))
        assertFalse(harness.controller.nextHand(0))
        assertEquals(0, harness.controller.advanceOtherSeats(0, 1))
        assertEquals(1, harness.sessions.single().applies)
        harness.store.failWrite = false
        assertTrue(harness.controller.retrySave())
        assertEquals(1, harness.controller.state.value.completedHandCount)
        assertEquals(32L, harness.controller.state.value.cumulativeScores[0])
        assertFalse(harness.controller.retrySave())
        assertEquals(2, harness.store.commits)
        harness.controller.close()
    }

    @Test fun failedNextHandWriteRetainsOldSessionAndRetryUsesSameCandidateSeed() = runBlocking {
        val harness = Harness()
        harness.controller.open()
        harness.controller.apply(WIN, 0)
        val old = harness.sessions.first()
        harness.store.failWrite = true
        assertFalse(harness.controller.nextHand(0))
        assertEquals(0, old.closes)
        assertEquals(1, harness.sessions.last().closes)
        assertEquals(0, harness.controller.state.value.activeHandIndex)
        assertTrue(harness.controller.state.value.canStartNextHand)
        assertEquals(0, harness.store.saved()!!.activeHandIndex)
        harness.store.failWrite = false
        assertTrue(harness.controller.nextHand(0))
        assertEquals(harness.sessions[1].handConfig, harness.sessions[2].handConfig)
        assertEquals(1, old.closes)
        assertEquals(1, harness.controller.state.value.activeHandIndex)
        harness.controller.close()
    }

    @Test fun resumeAfterSeatChangeRestoresPlayerLedgerAndRejectsOldTableEvents() = runBlocking {
        val first = Harness(matchConfig(2))
        first.controller.open()
        repeat(5) { index ->
            first.controller.apply(WIN, index)
            if (index < 4) first.controller.nextHand(index)
        }
        first.controller.close()
        val resumed = Harness(first.config.copy(seed = 999), first.store)
        assertTrue(resumed.controller.open())
        assertEquals(4, resumed.controller.state.value.activeHandIndex)
        assertEquals(5, resumed.controller.state.value.completedHandCount)
        assertEquals(3, resumed.controller.state.value.table!!.viewerSeat)
        assertEquals(160L, resumed.controller.state.value.cumulativeScores[0])
        assertTrue(resumed.controller.nextHand(4))
        assertNull(resumed.controller.apply(WIN, 4))
        assertFalse(resumed.controller.undo(4))
        assertFalse(resumed.controller.nextHand(4))
        assertEquals(0, resumed.controller.advanceOtherSeats(4, 1))
        assertEquals(0, resumed.sessions.last().applies)
        resumed.controller.close()
    }

    @Test fun restoredLedgerDisagreementAndNativeRestoreFailurePreserveOriginalStore() = runBlocking {
        val config = matchConfig()
        val wrongLedger = CompetitionMahjongMatchLedger.empty().recordTerminal(config, 0, matchFrame(config, 0, winnerPlayer = 1))
        val store = MemoryStore()
        store.save(CompetitionMahjongMatchCheckpoint(config, wrongLedger, 0, matchRecord(0, true)))
        val original = store.bytes!!.copyOf()
        val mismatched = Harness(config, store)
        assertFalse(mismatched.controller.open())
        assertNull(mismatched.controller.state.value.table)
        assertArrayEquals(original, store.bytes)
        assertEquals(1, store.commits)
        assertEquals(1, mismatched.sessions.single().closes)
        val invalidNative = Harness(config, store).apply { rejectRestore = true }
        assertFalse(invalidNative.controller.open())
        assertArrayEquals(original, store.bytes)
        assertEquals(1, invalidNative.sessions.single().closes)
    }

    @Test fun currentTerminalUndoRemovesOnlyItsProvisionalResultAndSaveFailureIsRetryable() = runBlocking {
        val harness = Harness()
        harness.controller.open()
        harness.controller.apply(WIN, 0)
        harness.controller.nextHand(0)
        harness.controller.apply(WIN, 1)
        harness.store.failWrite = true
        assertFalse(harness.controller.undo(1))
        assertTrue(harness.controller.state.value.needsSave)
        assertEquals(2, harness.controller.state.value.completedHandCount)
        harness.store.failWrite = false
        assertTrue(harness.controller.retrySave())
        assertEquals(1, harness.controller.state.value.completedHandCount)
        assertEquals(32L, harness.controller.state.value.cumulativeScores[0])
        assertEquals(1, harness.store.saved()!!.ledger.completedHandCount)
        assertTrue(harness.controller.apply(WIN, 1)!!.accepted)
        assertEquals(2, harness.controller.state.value.completedHandCount)
        assertEquals(64L, harness.controller.state.value.cumulativeScores[0])
        harness.controller.close()
    }

    @Test fun rejectedActionsDoNotWriteAndOtherSeatAdvanceCommitsRealProgressBoundary() = runBlocking {
        val harness = Harness()
        harness.controller.open()
        harness.sessions.single().rejectAction = true
        assertFalse(harness.controller.apply(WIN, 0)!!.accepted)
        assertEquals(1, harness.store.commits)
        assertEquals(1, harness.controller.advanceOtherSeats(0, 41, 8))
        assertEquals(2, harness.store.commits)
        assertEquals(1, harness.controller.state.value.completedHandCount)
        assertEquals(0, harness.controller.advanceOtherSeats(0, 41))
        harness.controller.close()
    }

    @Test fun overlappingNativeCallsWaitForCommitAndDoNotDuplicateTerminalResult() = runBlocking {
        val harness = Harness()
        harness.controller.open()
        val gate = SaveGate()
        harness.store.gate = gate
        val win = async(Dispatchers.Default) { harness.controller.apply(WIN, 0) }
        gate.started.await()
        val other = async(Dispatchers.Default) { harness.controller.advanceOtherSeats(0, 1) }
        assertEquals(1, harness.sessions.single().applies)
        assertEquals(0, harness.sessions.single().botCalls)
        gate.release.complete(Unit)
        assertTrue(win.await()!!.accepted)
        assertEquals(0, other.await())
        assertEquals(2, harness.store.commits)
        assertEquals(1, harness.controller.state.value.completedHandCount)
        harness.controller.close()
    }

    @Test fun callerCancellationAfterNativeAcceptanceStillCommitsBeforePublishing() = runBlocking {
        val harness = Harness()
        harness.controller.open()
        val gate = SaveGate()
        harness.store.gate = gate
        val win = async(Dispatchers.Default) { harness.controller.apply(WIN, 0) }
        gate.started.await()
        win.cancel()
        gate.release.complete(Unit)
        win.join()
        assertEquals(1, harness.store.saved()!!.ledger.completedHandCount)
        assertEquals(1, harness.controller.state.value.completedHandCount)
        assertFalse(harness.controller.state.value.needsSave)
        harness.controller.close()
    }

    @Test fun readFailureDoesNotCreateSessionOrOverwriteEvidence() = runBlocking {
        val harness = Harness()
        harness.store.loadFailure = IOException("保留损坏证据")
        assertFalse(harness.controller.open())
        assertTrue(harness.sessions.isEmpty())
        assertEquals(0, harness.store.commits)
        assertEquals("保留损坏证据", harness.controller.state.value.error)
        harness.store.loadFailure = null
        assertTrue(harness.controller.open())
        harness.controller.close()
    }

    @Test fun inconsistentNewCandidateCannotReplaceTheSavedTerminalHand() = runBlocking {
        val harness = Harness()
        harness.controller.open()
        harness.controller.apply(WIN, 0)
        harness.badCandidate = true
        assertFalse(harness.controller.nextHand(0))
        assertEquals(2, harness.store.commits)
        assertEquals(0, harness.sessions.first().closes)
        assertEquals(1, harness.sessions.last().closes)
        assertEquals(0, harness.controller.state.value.activeHandIndex)
        harness.controller.close()
    }

    private class Harness(val config: CompetitionMahjongCompetitionConfig = matchConfig(), val store: MemoryStore = MemoryStore()) {
        val sessions = mutableListOf<FakeSession>()
        var rejectRestore = false
        var badCandidate = false
        val controller = CompetitionMahjongMatchController(config, store, { hand ->
            FakeSession(config.copy(seed = store.saved()?.config?.seed ?: config.seed), hand).also {
                it.rejectRestore = rejectRestore
                it.badFrame = badCandidate
                sessions += it
            }
        })
    }

    private class SaveGate {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
    }

    private class MemoryStore : CompetitionMahjongMatchStore {
        var bytes: ByteArray? = null
        var failWrite = false
        var loadFailure: IOException? = null
        var commits = 0
        var gate: SaveGate? = null
        private var viewer = 0
        fun saved(): CompetitionMahjongMatchCheckpoint? = bytes?.let { CompetitionMahjongMatchCheckpointCodec.decode(it, viewer)!! }
        override suspend fun load(viewerPlayerId: Int): CompetitionMahjongMatchCheckpoint? {
            loadFailure?.let { throw it }
            return bytes?.let { CompetitionMahjongMatchCheckpointCodec.decode(it, viewerPlayerId) ?: throw IOException("记录校验失败") }
        }
        override suspend fun save(checkpoint: CompetitionMahjongMatchCheckpoint) {
            gate?.let { gate = null; it.started.complete(Unit); it.release.await() }
            if (failWrite) throw IOException("注入写失败")
            val encoded = CompetitionMahjongMatchCheckpointCodec.encode(checkpoint)
            bytes = encoded
            viewer = checkpoint.config.viewerPlayerId
            commits++
        }
    }

    private class FakeSession(val config: CompetitionMahjongCompetitionConfig, val handConfig: CompetitionMahjongMatchConfig) : CompetitionMahjongRuleSession {
        private val index = handConfig.roundWind * 4 + handConfig.dealerSeat
        private var terminal = false
        var applies = 0
        var botCalls = 0
        var closes = 0
        var rejectAction = false
        var rejectRestore = false
        var badFrame = false
        override val viewerSeat get() = handConfig.viewerSeat
        override fun snapshot(): CompetitionMahjongPublicView = matchFrame(config, index, terminal).let {
            if (badFrame) it.copy(roundWind = (handConfig.roundWind + 1) % 4) else it
        }
        override fun legalActions() = if (terminal) emptyList() else listOf(WIN)
        override fun apply(action: CompetitionMahjongAction): CompetitionMahjongApplyResult {
            applies++
            if (rejectAction || terminal) return CompetitionMahjongApplyResult(false, CompetitionMahjongRoundError.ILLEGAL_ACTION, false)
            terminal = true
            return CompetitionMahjongApplyResult(true, CompetitionMahjongRoundError.NONE, false)
        }
        override fun advanceBots(level: Int, seed: Long, maxActions: Int): Int { botCalls++; terminal = true; return 1 }
        override fun undo(): Boolean = terminal.also { terminal = false }
        override fun serialize() = matchRecord(viewerSeat, terminal)
        override fun restore(data: ByteArray): RestoreResult {
            if (rejectRestore) return RestoreResult.Rejected(EngineError.CORRUPTED_DATA)
            terminal = data[12].toInt() == 1
            return RestoreResult.Restored
        }
        override fun close() { closes++ }
    }

    companion object { private val WIN = CompetitionMahjongAction(CompetitionMahjongRoundActionKind.WIN) }
}
