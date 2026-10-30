package com.masterofchessstrategy.games.lanzhou_fangqi.data

import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.games.lanzhou_fangqi.*
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Explicit verified-snapshot/replay doubles: protocol tests, not Native or SQLite evidence. */
class RankedFangqiSessionStoreTest {
    @Test fun missingWrongOrUnrankedActiveNeverCreatesAReplacement() = runBlocking {
        for (variant in 0..2) {
            val f = Fixture()
            when (variant) { 0 -> f.active = null; 1 -> f.active = saved("other"); 2 -> f.hasSource = false }
            rejected { f.store().load() }
            assertEquals(0, f.checkpoints)
            assertEquals(0, f.settleCalls)
        }
    }

    @Test fun saveRequiresAnAcknowledgedLoadAndCannotChangeBoundId() = runBlocking {
        val f = Fixture(); val store = f.store()
        rejected { store.save(saved()) }
        store.load()
        rejected { store.save(saved("other", time = 20)) }
        assertEquals(0, f.checkpoints)
    }

    @Test fun checkpointFailureRetriesIdenticalCandidateAndCannotReplacePending() = runBlocking {
        val f = Fixture(); val store = f.store(); store.load()
        val next = saved(time = 20)
        f.failCheckpoint = true
        rejected { store.save(next) }
        rejected { store.save(saved(time = 21)) }
        assertEquals(1, f.checkpoints)
        f.failCheckpoint = false
        store.save(next)
        assertEquals(2, f.checkpoints)
        assertEquals(20L, f.active!!.updatedAtEpochMillis)
        assertEquals(0, f.settleCalls)
    }

    @Test fun committedCheckpointWithLostAcknowledgementIsConfirmedByExactRetry() = runBlocking {
        val f = Fixture(); val store = f.store(); store.load()
        f.loseCheckpointAck = true
        val next = saved(time = 20)
        rejected { store.save(next) }
        assertEquals(20L, f.active!!.updatedAtEpochMillis)
        f.loseCheckpointAck = false
        store.save(next)
        assertEquals(2, f.checkpoints)
    }

    @Test fun terminalWaitsForSettlementAndRetainsOriginalRetryTime() = runBlocking {
        val f = Fixture(); val store = f.store(); store.load()
        val terminal = saved(count = 1, time = 20)
        f.failSettlement = true
        rejected { store.save(terminal) }
        assertNull(f.receipt)
        assertEquals(1, f.active!!.acceptedActionCount)
        f.clock = 900
        f.failSettlement = false
        store.save(terminal)
        assertEquals(listOf(100L, 100L), f.settleTimes)
        assertEquals(100L, f.receipt!!.settledAtEpochMillis)
    }

    @Test fun committedSettlementWithLostAckSkipsFurtherCheckpointAndNeverResurrectsDeletedRecord() = runBlocking {
        val f = Fixture(); val store = f.store(); store.load()
        val terminal = saved(count = 1, time = 20)
        f.loseSettlementAck = true
        rejected { store.save(terminal) }
        val first = f.receipt
        f.deletedAt = 150
        f.loseSettlementAck = false
        f.clock = 999
        store.save(terminal)
        assertEquals(1, f.checkpoints)
        assertEquals(2, f.settleCalls)
        assertEquals(first, f.receipt)
        assertEquals(150L, f.deletedAt)
    }

    @Test fun explicitResumeCompletesPersistedTerminalAndNextLoadDoesNotSettleAgain() = runBlocking {
        val f = Fixture().apply { active = saved(count = 1, time = 20) }
        val store = f.store()
        assertEquals(1, store.load().acceptedActionCount)
        assertEquals(0, f.checkpoints)
        assertEquals(1, f.settleCalls)
        f.clock = 700
        store.load()
        assertEquals(1, f.settleCalls)
        assertEquals(100L, f.receipt!!.settledAtEpochMillis)
    }

    @Test fun failedTerminalResumeRetainsSameRequestUntilConfirmation() = runBlocking {
        val f = Fixture().apply { active = saved(count = 1, time = 20); failSettlement = true }
        val store = f.store()
        rejected { store.load() }
        f.clock = 800; f.failSettlement = false
        store.load()
        assertEquals(listOf(100L, 100L), f.settleTimes)
    }

    @Test fun replayAndOwnerReadFailuresRemainFailuresWithoutCheckpointWrites() = runBlocking {
        val f = Fixture(); val store = f.store(); store.load()
        f.refuseRead = true
        rejected { store.save(saved(time = 20)) }
        assertEquals(0, f.checkpoints)
        val other = Fixture().apply { refuseReplay = true }
        rejected { other.store().load() }
        assertEquals(0, other.checkpoints)
    }

    private class Fixture {
        var active: FangqiStoredSession? = saved()
        var hasSource = true
        var receipt: FangqiRankedTerminalReceipt? = null
        var deletedAt: Long? = null
        var clock = 100L
        var failCheckpoint = false; var loseCheckpointAck = false
        var failSettlement = false; var loseSettlementAck = false
        var refuseRead = false; var refuseReplay = false
        var checkpoints = 0; var settleCalls = 0
        val settleTimes = mutableListOf<Long>()
        private val creation = FangqiRankedCreation(1, FangqiRankedStartOrigin.NEW_STANDARD_HUMAN_GAME,
            saved().toRankedPosition())
        fun store() = RankedFangqiSessionStore(ID, {
            if (refuseRead) throw IOException("explicit old-owner failure")
            FangqiRankedVerifiedSnapshot(if (hasSource) mapOf(ID to creation) else emptyMap(),
                receipt?.let { mapOf(ID to FangqiRankedUnvalidatedSettlement(it, deletedAt)) } ?: emptyMap(),
                active, null, FangqiQualifiedWinFacts.fromVerifiedLedgerCounts(GameType.LANZHOU_SQUARE_CHESS, emptyMap()))
        }, { before, after ->
            checkpoints++
            if (receipt != null) throw IOException("cannot checkpoint settled source")
            if (failCheckpoint) throw IOException("explicit checkpoint failure")
            if (!same(active, after)) {
                if (!same(active, before)) throw IOException("explicit stale CAS")
                validateFangqiCheckpointTransition(before, after)
                active = after
            }
            if (loseCheckpointAck) throw IOException("explicit lost checkpoint acknowledgement")
            after
        }, { terminal, time ->
            settleCalls++; settleTimes += time
            if (failSettlement) throw IOException("explicit settlement failure")
            if (receipt == null) receipt = FangqiRankedTerminalReceipt(1, creation.sha256,
                terminal.toRankedPosition(), GameResult.FIRST_PLAYER_WIN, time)
            if (receipt!!.terminal != terminal.toRankedPosition()) throw IOException("different first terminal")
            if (loseSettlementAck) throw IOException("explicit lost settlement acknowledgement")
            receipt!!
        }, { history ->
            if (refuseReplay) throw IOException("explicit replay refusal")
            FangqiRankedReplayResult(history,
                if (FangqiSessionAdmission.inspectHistory(history.bytes) == 0) GameResult.ONGOING else GameResult.FIRST_PLAYER_WIN, 0)
        }, { clock })
    }

    private suspend fun rejected(action: suspend () -> Unit) {
        try { action(); fail("invalid request accepted") } catch (_: IOException) { }
    }
    companion object {
        private const val ID = "ranked-queue-test"
        private fun saved(id: String = ID, time: Long = 10, count: Int = 0): FangqiStoredSession {
            val root = FangqiRankedLedger.standardInitialHistory().bytes.toString(Charsets.US_ASCII)
            val history = if (count == 0) root else root.removeSuffix("|0|") + "|1|m,1,35,28"
            return FangqiStoredSession(FangqiStoredMode.HUMAN_VS_AI, Difficulty.EASY,
                history.toByteArray(Charsets.US_ASCII), id, time, count)
        }
        private fun same(a: FangqiStoredSession?, b: FangqiStoredSession) = a != null &&
            a.toRankedPosition() == b.toRankedPosition()
    }
}
