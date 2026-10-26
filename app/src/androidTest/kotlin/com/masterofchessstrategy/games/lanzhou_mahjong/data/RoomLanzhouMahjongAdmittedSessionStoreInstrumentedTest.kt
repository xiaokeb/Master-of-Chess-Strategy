package com.masterofchessstrategy.games.lanzhou_mahjong.data

import androidx.room.withTransaction
import com.masterofchessstrategy.data.ActiveGameDao
import com.masterofchessstrategy.data.ActiveGameEntity
import com.masterofchessstrategy.engine.ActionResult
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.LanzhouMahjongAction
import com.masterofchessstrategy.engine.LanzhouMahjongActionKind
import com.masterofchessstrategy.engine.NativeLanzhouMahjongSession
import com.masterofchessstrategy.engine.RestoreResult
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongSessionRecord
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongSessionRecordCodec
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Real Native and UUID disk Room. These methods are not device evidence until Root executes them. */
class RoomLanzhouMahjongAdmittedSessionStoreInstrumentedTest {
    @Test fun productionConstructorRejectsAGateFromAnotherDatabaseOwner() = withOwnedLanzhouDatabase { f ->
        try {
            RoomLanzhouMahjongAdmittedSessionStore(f.db, f.db.activeGameDao(), LanzhouMahjongStorageEpochGate())
            fail("A foreign gate must not bypass shared replacement ownership")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun bothOuterAndNativeVersionsSurviveDiskReopenWithoutRefillingAssistanceOrChangingIds() =
        withOwnedLanzhouDatabase { f ->
            for (rule in 1..2) for (outer in 1..2) {
                val source = LanzhouMahjongNativeFixtures.snapshot(rule = rule, usage = if (outer == 1) 0 else 2,
                    timestamp = 100L + rule * 10 + outer, decisions = outer == 2)
                val canonical = LanzhouMahjongSessionAdmission().createRow(source.record, source.updatedAtEpochMillis)
                val row = if (outer == 1) LanzhouMahjongNativeFixtures.legacyOuter(canonical, source.record) else canonical
                f.db.activeGameDao().upsert(row)
                f.reopen()
                val loaded = requireNotNull(f.store().load())
                assertEquals(row, f.row()) // Merely loading must not rewrite the legacy envelope.
                assertEquals(source.updatedAtEpochMillis, loaded.updatedAtEpochMillis)
                assertArrayEquals(source.record.nativeState, loaded.record.nativeState)
                assertEquals(source.record.assistance, loaded.record.assistance)
                assertEquals(source.record.decisions, loaded.record.decisions)
                assertEquals(source.record.nextChoiceSeed, loaded.record.nextChoiceSeed)
                assertEquals(69, row.sessionId.length)
                f.store().save(loaded)
                val rewritten = requireNotNull(f.row())
                assertEquals(row.sessionId, rewritten.sessionId)
                assertEquals(2, rewritten.engineFormatVersion)
                assertEquals(0, rewritten.undoUseCount)
                assertEquals(0, rewritten.hintUseCount)
                f.reopen()
                val reopened = requireNotNull(f.store().load())
                assertEquals(rule, reopened.record.nativeState[4].toInt())
                assertEquals(loaded.record.assistance, reopened.record.assistance)
                assertArrayEquals(loaded.record.nativeState, reopened.record.nativeState)
            }
        }

    @Test fun validChecksumsCannotAdmitAnIllegalDrawAndBadRowsRemainStored() = withOwnedLanzhouDatabase { f ->
        val good = LanzhouMahjongNativeFixtures.snapshot()
        val store = f.store()
        store.save(good)
        val before = requireNotNull(f.row())
        val bad = LanzhouMahjongNativeFixtures.illegalDrawSnapshot()
        assertNotNull(LanzhouMahjongSessionRecordCodec.decode(LanzhouMahjongSessionRecordCodec.encode(bad.record)))
        expectIOException { store.save(bad) }
        assertEquals(before, f.row())
        // Deliberately insert an invalid historical row to verify load reports and preserves it.
        val badRow = before.copy(engineState = LanzhouMahjongSessionRecordCodec.encode(bad.record))
        f.db.activeGameDao().upsert(badRow)
        expectIOException { store.load() }
        assertEquals(badRow, f.row())
    }

    @Test fun actualRoomRollsBackWhenTheReadBackFailsAfterTheRealUpsert() = withOwnedLanzhouDatabase { f ->
        f.store().save(LanzhouMahjongNativeFixtures.snapshot())
        val before = f.row()
        val dao = f.db.activeGameDao()
        val readFailure = object : ActiveGameDao by dao {
            override suspend fun find(gameTypeCode: Int): ActiveGameEntity? = throw IOException("owned read-back fault")
        }
        expectIOException {
            faultStore(f, readFailure).save(LanzhouMahjongNativeFixtures.snapshot(discard = false, usage = 0, timestamp = 900))
        }
        assertEquals(before, f.row())
        f.reopen()
        assertEquals(before, f.row())
    }

    @Test fun actualRoomRollsBackCancellationAfterTheRealUpsert() = withOwnedLanzhouDatabase { f ->
        f.store().save(LanzhouMahjongNativeFixtures.snapshot())
        val before = f.row()
        val entered = CompletableDeferred<Unit>()
        val dao = f.db.activeGameDao()
        val waitingReadBack = object : ActiveGameDao by dao {
            override suspend fun find(gameTypeCode: Int): ActiveGameEntity? {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        coroutineScope {
            val writer = launch {
                faultStore(f, waitingReadBack).save(LanzhouMahjongNativeFixtures.snapshot(discard = false, usage = 0, timestamp = 901))
            }
            withTimeout(15_000) { entered.await() }
            writer.cancelAndJoin()
        }
        assertEquals(before, f.row())
        f.reopen()
        assertEquals(before, f.row())
    }

    @Test fun actualNativeFalseWinUses24OrLegacy8PerSeatAndPersistsItsTerminalHistory() = withOwnedLanzhouDatabase { f ->
        for (rule in 1..2) {
            val initial = LanzhouMahjongNativeFixtures.initial(rule, seed = 5, base = 2)
            NativeLanzhouMahjongSession(5, 0, 2, 0).use { native ->
                assertEquals(RestoreResult.Restored, native.restore(initial))
                assertTrue(native.legalActions().none { it.kind == LanzhouMahjongActionKind.WIN })
                val claim = LanzhouMahjongAction(LanzhouMahjongActionKind.WIN)
                assertEquals(ActionResult.Accepted, native.apply(claim))
                assertTrue(native.snapshot().outcome.falseWin)
                val single = if (rule == 1) 16L else 48L // Base 2 × (8 or 24), each of the other three seats.
                val scores = listOf(-3 * single, single, single, single)
                assertEquals(scores, native.snapshot().netScores)
                val terminal = native.serialize()
                assertTrue(native.undo())
                assertArrayEquals(initial, native.serialize())
                assertEquals(ActionResult.Accepted, native.apply(claim))
                assertArrayEquals(terminal, native.serialize())
                val record = LanzhouMahjongSessionRecord(Difficulty.MEDIUM, 0, 5, 0, 2, 5, terminal)
                f.store().save(LanzhouMahjongStoredSession(record, 1000L + rule))
                f.reopen()
                val loaded = requireNotNull(f.store().load())
                assertArrayEquals(terminal, loaded.record.nativeState)
                NativeLanzhouMahjongSession(999, 0, 2, 0).use { replay ->
                    assertEquals(RestoreResult.Restored, replay.restore(loaded.record.nativeState))
                    assertEquals(scores, replay.snapshot().netScores)
                    assertTrue(replay.snapshot().outcome.falseWin)
                }
            }
        }
    }

    private fun faultStore(f: LanzhouMahjongOwnedDatabaseFixture, dao: ActiveGameDao): RoomLanzhouMahjongAdmittedSessionStore {
        val gate = LanzhouMahjongStorageEpochGate.forDatabase(f.db)
        return RoomLanzhouMahjongAdmittedSessionStore(dao, gate, gate.captureOwner(),
            LanzhouMahjongSessionAdmission(), Dispatchers.IO, { block -> f.db.withTransaction { block() } })
    }

    private suspend fun expectIOException(block: suspend () -> Any?) {
        try { block(); fail("Expected actual storage/Native admission failure") } catch (_: IOException) { }
    }
}
