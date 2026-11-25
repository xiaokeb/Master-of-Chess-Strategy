package com.masterofchessstrategy.games.lanzhou_mahjong.settlement

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.engine.ActionResult
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.LanzhouMahjongAction
import com.masterofchessstrategy.engine.LanzhouMahjongActionKind
import com.masterofchessstrategy.engine.NativeLanzhouMahjongSession
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongSessionRecord
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongSessionAdmission
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongStorageEpochGate
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongStoredSession
import com.masterofchessstrategy.games.lanzhou_mahjong.data.withOwnedLanzhouDatabase
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test

class RoomLanzhouMahjongSettlementInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    @get:Rule val helper = MigrationTestHelper(instrumentation, MocsDatabase::class.java)

    @Test fun migrationPreservesExistingRowsAndStartsWithAnEmptyLedger() {
        val name = "lanzhou-ledger-${UUID.randomUUID()}.db"
        try {
            helper.createDatabase(name, 25).use { db ->
                db.execSQL("INSERT INTO competition_mahjong_learning_proof (singletonId,proof) VALUES (0,X'01')")
            }
            helper.runMigrationsAndValidate(name, 26, true, MocsDatabase.MIGRATION_25_26).use { db ->
                for (table in listOf("lanzhou_mahjong_formal_creations", "lanzhou_mahjong_terminal_receipts")) {
                    db.query("SELECT count(*) FROM $table").use { rows ->
                        assertTrue(rows.moveToFirst())
                        assertEquals(0, rows.getInt(0))
                    }
                }
                db.query("SELECT count(*) FROM competition_mahjong_learning_proof").use { rows ->
                    assertTrue(rows.moveToFirst())
                    assertEquals(1, rows.getInt(0))
                }
            }
        } finally {
            instrumentation.targetContext.deleteDatabase(name)
        }
    }

    @Test fun concurrentFirstSettlementKeepsTheFirstTimestampAndAllFourAmounts() =
        withOwnedLanzhouDatabase { fixture ->
            val db = fixture.db
            val gate = LanzhouMahjongStorageEpochGate.forDatabase(db)
            val store = RoomLanzhouMahjongSettlement(db, gate.captureOwner())
            val (initial, terminal) = round()
            val active = store.create(null, initial) { assertEquals(Difficulty.EASY, it) }
            assertEquals(active, store.create(null, initial) { fail("An exact creation retry must not reauthorize") })
            val results = coroutineScope {
                val first = async { store.complete(active, terminal) }
                val second = async { store.complete(active, terminal) }
                listOf(first.await(), second.await())
            }
            assertEquals(listOf(200L, 200L), results.map { it.terminal.capturedAt })
            assertEquals(listOf(-72L, 24L, 24L, 24L), results.first().netScores)
            fixture.reopen()
            val stored = requireNotNull(fixture.db.lanzhouMahjongSettlementDao().receipt(active.sessionId))
            assertEquals(200L, stored.capturedAt)
            assertEquals(listOf(-72L, 24L, 24L, 24L),
                listOf(stored.seat0Score, stored.seat1Score, stored.seat2Score, stored.seat3Score))
            assertEquals(1, fixture.row()?.acceptedMoveCount)
        }

    @Test fun failedActiveWriteRollsBackTheFirstSettlementAndPreservesThePriorCheckpoint() =
        withOwnedLanzhouDatabase { fixture ->
            val db = fixture.db
            val store = RoomLanzhouMahjongSettlement(db, LanzhouMahjongStorageEpochGate.forDatabase(db).captureOwner())
            val (initial, terminal) = round()
            val active = store.create(null, initial) {}
            db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER block_lanzhou_terminal BEFORE INSERT ON active_games " +
                    "WHEN NEW.gameTypeCode = ${GameType.LANZHOU_MAHJONG.code} AND NEW.acceptedMoveCount > 0 " +
                    "BEGIN SELECT RAISE(ABORT, 'injected active row failure'); END",
            )
            try {
                store.complete(active, terminal)
                fail("Expected injected active write failure")
            } catch (_: android.database.sqlite.SQLiteConstraintException) {
                assertNull(db.lanzhouMahjongSettlementDao().receipt(active.sessionId))
                assertEquals(active, fixture.row())
            }
            db.openHelper.writableDatabase.execSQL("DROP TRIGGER block_lanzhou_terminal")
            assertEquals(LanzhouMahjongQualifiedOutcome.FALSE_DECLARATION, store.complete(active, terminal).outcome)
        }

    @Test fun rejectsLegacyOrStaleActivityWithoutAddingCreationOrSettlement() =
        withOwnedLanzhouDatabase { fixture ->
            val db = fixture.db
            val store = RoomLanzhouMahjongSettlement(db, LanzhouMahjongStorageEpochGate.forDatabase(db).captureOwner())
            val (initial, terminal) = round()
            val active = LanzhouMahjongSessionAdmission().createRow(initial.record, initial.updatedAtEpochMillis)
            db.activeGameDao().upsert(active)
            expectIOException { store.create(null, initial) { fail("Stale start must not check eligibility") } }
            expectIOException { store.complete(active, terminal) }
            assertNull(db.lanzhouMahjongSettlementDao().creation(active.sessionId))
            assertNull(db.lanzhouMahjongSettlementDao().receipt(active.sessionId))
            assertEquals(active, fixture.row())
        }

    private suspend fun expectIOException(block: suspend () -> Unit) {
        try {
            block()
            fail("Expected rejection")
        } catch (_: IOException) { }
    }

    private fun round(): Pair<LanzhouMahjongStoredSession, LanzhouMahjongStoredSession> =
        NativeLanzhouMahjongSession(5, 0, 1, 0).use { session ->
            fun snapshot(time: Long) = LanzhouMahjongStoredSession(
                LanzhouMahjongSessionRecord(Difficulty.EASY, 0, 5, 0, 1, 5, session.serialize()), time,
            )
            val initial = snapshot(100)
            assertEquals(ActionResult.Accepted, session.apply(LanzhouMahjongAction(LanzhouMahjongActionKind.WIN)))
            initial to snapshot(200)
        }
}
