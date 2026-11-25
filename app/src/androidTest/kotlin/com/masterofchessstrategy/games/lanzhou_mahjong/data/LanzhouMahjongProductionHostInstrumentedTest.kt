package com.masterofchessstrategy.games.lanzhou_mahjong.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.LanzhouMahjongActionKind
import com.masterofchessstrategy.engine.LanzhouMahjongPhase
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongGameHost
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongLaunchConfig
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongOpenMode
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Exact production binding, real Native and a unique disk Room database; no default app data. */
@RunWith(AndroidJUnit4::class)
class LanzhouMahjongProductionHostInstrumentedTest {
    @Test fun firstSaveRetriesAtomicallyAndRealTableCommitsOneTerminalReceipt() = withOwnedLanzhouDatabase { fixture ->
        val db = fixture.db
        db.lanzhouMahjongLearningProgressDao().upsert(LanzhouMahjongLearningProgressEntity(0, 2, 10, 1))
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER block_lanzhou_initial BEFORE INSERT ON active_games " +
                "WHEN NEW.gameTypeCode = 3 BEGIN SELECT RAISE(ABORT, 'injected initial save failure'); END",
        )
        val host = LanzhouMahjongGameHost(
            db.createLanzhouMahjongHostStorage(), LanzhouMahjongLaunchConfig(Difficulty.EASY, 1), now = { 42 },
        )
        try {
            host.open()
            assertTrue(requireNotNull(host.state.value.table).needsSave)
            assertNull(fixture.row())
            assertTrue(db.lanzhouMahjongSettlementDao().readCreations().isEmpty())
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER block_lanzhou_initial")
            host.retrySave()
            val initial = requireNotNull(fixture.row())
            assertEquals(0, initial.acceptedMoveCount)
            assertEquals(42L, db.lanzhouMahjongSettlementDao().creation(initial.sessionId)?.createdAt)

            val terminal = withTimeout(15_000) {
                repeat(240) {
                    val table = requireNotNull(host.state.value.table)
                    assertFalse(table.needsSave)
                    if (table.view.phase in setOf(LanzhouMahjongPhase.WON, LanzhouMahjongPhase.EXHAUSTIVE_DRAW)) {
                        return@withTimeout table.view
                    }
                    val action = table.actions.firstOrNull { it.kind == LanzhouMahjongActionKind.WIN }
                        ?: table.actions.first {
                            it.kind in setOf(LanzhouMahjongActionKind.DRAW, LanzhouMahjongActionKind.DISCARD,
                                LanzhouMahjongActionKind.PASS)
                        }
                    host.apply(action)
                }
                error("Seeded round did not finish")
            }
            val receipt = db.lanzhouMahjongSettlementDao().readReceipts().single()
            assertEquals(initial.sessionId, receipt.sessionId)
            assertEquals(terminal.outcome.winnerSeat, receipt.winnerSeat)
            assertEquals(terminal.netScores,
                listOf(receipt.seat0Score, receipt.seat1Score, receipt.seat2Score, receipt.seat3Score))
            assertArrayEquals(fixture.row()?.engineState, receipt.terminalRecord)
            assertTrue(host.leave())
        } finally {
            withTimeout(15_000) { host.closeAndJoin() }
        }
    }

    @Test fun replacingDataRetiresRealHostAndNewOwnerCanContinueSavedHistory() = withOwnedLanzhouDatabase { fixture ->
        fixture.db.lanzhouMahjongLearningProgressDao().upsert(LanzhouMahjongLearningProgressEntity(0, 2, 10, 1))
        val storage = fixture.db.createLanzhouMahjongHostStorage()
        val config = LanzhouMahjongLaunchConfig(Difficulty.EASY, 321)
        val first = LanzhouMahjongGameHost(storage, config, now = { 42 })
        var next: LanzhouMahjongGameHost? = null
        try {
            withTimeout(15_000) { first.open() }
            assertFalse(first.state.value.isLoading)
            assertNotNull(first.state.value.table)
            val original = requireNotNull(storage.store.load())
            assertEquals(42L, original.updatedAtEpochMillis)
            assertEquals(0, original.record.nativeState[122].toInt())

            val discard = first.state.value.table!!.actions.first { it.kind == LanzhouMahjongActionKind.DISCARD }
            withTimeout(15_000) { first.apply(discard) }
            val saved = requireNotNull(storage.store.load())
            assertFalse(saved.record.nativeState.contentEquals(original.record.nativeState))
            assertFalse(first.state.value.table!!.needsSave)

            val retainedRow = requireNotNull(fixture.row()).copy(updatedAtEpochMillis = 100)
            storage.gate.exclusive(replacing = true) { fixture.db.activeGameDao().upsert(retainedRow) }
            withTimeout(15_000) { first.retirementCompletion.await() }
            assertTrue(first.state.value.retired)
            try { storage.store.save(saved); fail("Retired owner wrote after replacement") }
            catch (_: IOException) { /* Actual strict store rejects before touching Room. */ }
            assertEquals(retainedRow, fixture.row())

            val newStorage = fixture.db.createLanzhouMahjongHostStorage()
            val resumed = LanzhouMahjongGameHost(newStorage, config, now = { 200 }, openMode = LanzhouMahjongOpenMode.RESUME)
            next = resumed
            withTimeout(15_000) { resumed.open() }
            assertFalse(resumed.state.value.retired)
            assertFalse(resumed.state.value.restoreFailed)
            assertNotNull(resumed.state.value.table)
            val recovered = requireNotNull(newStorage.store.load())
            assertTrue(saved.record.nativeState.contentEquals(recovered.record.nativeState))
            assertEquals(saved.record.nextChoiceSeed, recovered.record.nextChoiceSeed)
            assertEquals(saved.record.assistance, recovered.record.assistance)
            assertTrue(withTimeout(15_000) { resumed.leave() })
        } finally {
            withTimeout(15_000) { next?.closeAndJoin(); first.closeAndJoin() }
        }
    }

    @Test fun malformedRealRoomRowRemainsIntactAndCannotBeReplacedByRestart() = withOwnedLanzhouDatabase { fixture ->
        val initial = withContext(Dispatchers.Default) {
            LanzhouMahjongNativeFixtures.snapshot(discard = false, usage = 0, timestamp = 50, decisions = false)
        }
        fixture.store().save(initial)
        val row = requireNotNull(fixture.row())
        val bad = row.copy(engineState = row.engineState.copyOf().also { it[0] = 0 })
        fixture.db.activeGameDao().upsert(bad)
        val host = LanzhouMahjongGameHost(fixture.db.createLanzhouMahjongHostStorage(),
            LanzhouMahjongLaunchConfig(Difficulty.EASY, 999))
        try {
            withTimeout(15_000) { host.open() }
            assertTrue(host.state.value.restoreFailed)
            assertNull(host.state.value.table)
            assertNotNull(host.state.value.error)
            assertFalse(host.state.value.error!!.contains("SQLite"))
            host.restart()
            assertEquals(bad, fixture.row())
            assertTrue(host.leave())
        } finally { withTimeout(15_000) { host.closeAndJoin() } }
    }
}
