package com.masterofchessstrategy.games.lanzhou_mahjong.settlement

import com.masterofchessstrategy.data.GameBackupRecord
import com.masterofchessstrategy.data.GameBackupRegistry
import com.masterofchessstrategy.data.LocalDataBackupCodec
import com.masterofchessstrategy.data.LocalDataSnapshot
import com.masterofchessstrategy.engine.ActionResult
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.LanzhouMahjongAction
import com.masterofchessstrategy.engine.LanzhouMahjongActionKind
import com.masterofchessstrategy.engine.NativeLanzhouMahjongSession
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongSessionRecord
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongBackupAdapter
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongOwnedDatabaseFixture
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongStorageEpochGate
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongStoredSession
import com.masterofchessstrategy.games.lanzhou_mahjong.data.withOwnedLanzhouDatabase
import com.masterofchessstrategy.games.lanzhou_mahjong.data.RoomLanzhouMahjongEntryRepository
import java.math.BigInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class LanzhouMahjongSettlementBackupInstrumentedTest {
    private val activeAdapter = LanzhouMahjongBackupAdapter()
    private val ledgerAdapter = LanzhouMahjongSettlementBackupAdapter()
    private val registry = GameBackupRegistry(listOf(activeAdapter, ledgerAdapter))

    @Test fun completeBackupRoundTripsLedgerAndLegacyRestoreClearsIt() = withOwnedLanzhouDatabase { fixture ->
        val repository = fixture.repository()
        val empty = repository.export(300)
        val abandoned = play(fixture, 1, finish = false)
        val session = play(fixture, 5, finish = true)
        val saved = repository.export(400)
        val ledgerBefore = ledgerAdapter.read(fixture.db).map { it.fields }
        val activeBefore = fixture.row()
        val statistics = RoomLanzhouMahjongEntryRepository(fixture.db).loadFacts().statistics
        assertEquals(1, statistics.completedRounds)
        assertEquals(listOf(1, 0, 0, 0), statistics.seats.map { it.falseDeclarations })
        assertEquals(listOf(-72L, 24L, 24L, 24L).map(BigInteger::valueOf), statistics.seats.map { it.netScore })
        val before = requireNotNull(fixture.db.lanzhouMahjongSettlementDao().receipt(session))
        repository.restore(empty)
        assertNull(fixture.db.lanzhouMahjongSettlementDao().creation(session))
        assertNull(fixture.db.lanzhouMahjongSettlementDao().receipt(session))
        repository.restore(saved)
        fixture.reopen()
        val restored = requireNotNull(fixture.db.lanzhouMahjongSettlementDao().receipt(session))
        assertEquals(200L, restored.capturedAt)
        assertEquals(session, fixture.row()?.sessionId)
        assertEquals(100L, fixture.db.lanzhouMahjongSettlementDao().creation(abandoned)?.createdAt)
        assertNull(fixture.db.lanzhouMahjongSettlementDao().receipt(abandoned))
        assertEquals(true, before.sameEvidence(restored))
        assertEquals(activeBefore, fixture.row())
        assertEquals(ledgerBefore, ledgerAdapter.read(fixture.db).map { it.fields })
        assertEquals(statistics, RoomLanzhouMahjongEntryRepository(fixture.db).loadFacts().statistics)
    }

    @Test fun rejectsIncorrectPaymentsAndMissingActiveReceiptBeforeReplacingRows() =
        withOwnedLanzhouDatabase { fixture ->
            val session = play(fixture, 5, finish = true)
            val repository = fixture.repository()
            val saved = repository.export(400)
            val active = activeAdapter.read(fixture.db).single()
            val ledger = ledgerAdapter.read(fixture.db).single()
            val incorrect = ledger.fields.toMutableList().apply {
                this[11] = "-73"
                this[12] = "25"
            }
            for (fields in listOf(incorrect, ledger.fields.take(6))) {
                val bad = LocalDataSnapshot(400, gameExtensions = listOf(active, GameBackupRecord(ledger.kind, fields)))
                try {
                    repository.restore(LocalDataBackupCodec.encode(bad, registry))
                    fail("Expected invalid historical receipt to be rejected")
                } catch (_: IllegalArgumentException) { }
                assertEquals(session, fixture.row()?.sessionId)
                assertArrayEquals(saved, repository.export(400))
            }
        }

    private suspend fun play(fixture: LanzhouMahjongOwnedDatabaseFixture, seed: Long, finish: Boolean): String {
        val db = fixture.db
        val gate = LanzhouMahjongStorageEpochGate.forDatabase(db)
        val writer = RoomLanzhouMahjongSettlement(db, gate.captureOwner())
        return NativeLanzhouMahjongSession(seed, 0, 1, 0).use { session ->
            fun snapshot(at: Long) = LanzhouMahjongStoredSession(
                LanzhouMahjongSessionRecord(Difficulty.EASY, 0, seed, 0, 1, seed, session.serialize()), at,
            )
            val active = writer.create(fixture.row(), snapshot(100)) {}
            if (finish) {
                assertEquals(ActionResult.Accepted, session.apply(LanzhouMahjongAction(LanzhouMahjongActionKind.WIN)))
                writer.complete(active, snapshot(200))
            }
            active.sessionId
        }
    }
}
