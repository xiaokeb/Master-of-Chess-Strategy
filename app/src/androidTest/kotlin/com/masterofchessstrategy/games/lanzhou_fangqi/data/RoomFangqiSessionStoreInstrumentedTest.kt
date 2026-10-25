package com.masterofchessstrategy.games.lanzhou_fangqi.data

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.masterofchessstrategy.data.ActiveGameDao
import com.masterofchessstrategy.data.ActiveGameEntity
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiAction
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiPoint
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiSide
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.NativeLanzhouFangqiSession
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiStoredMode
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiStoredSession
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real Room and JNI admission. Compiling this suite is not evidence of device execution. */
class RoomFangqiSessionStoreInstrumentedTest {
    private lateinit var database: MocsDatabase

    @Before fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MocsDatabase::class.java).build()
    }

    @After fun closeDatabase() { database.close() }

    @Test fun productionConstructorRejectsTheWrongDatabaseGate() {
        try {
            RoomFangqiSessionStore(database, FangqiStorageEpochGate())
            fail("A different gate would allow writes to race with restore")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun realNativePartialCaptureRoundTripsWithWholeTurnUndo() = runBlocking {
        val board = MutableList(49) { 0 }.apply {
            listOf(0, 2, 14, 8, 9, 15, 23).forEach { this[it] = 1 }
            listOf(40, 41, 48).forEach { this[it] = 2 }
        }
        NativeLanzhouFangqiSession.fromPosition(board, FangqiSide.RED).use { original ->
            assertTrue(original.apply(move(2, 3, 2, 2)).accepted)
            assertTrue(original.apply(FangqiAction.Capture(FangqiPoint(6, 6))).accepted)
            assertEquals(1, original.snapshot().pendingCaptures)
            val bytes = original.serialize()
            val store = RoomFangqiSessionStore(database)
            store.save(snapshot(bytes))
            val restored = requireNotNull(store.load())
            assertArrayEquals(bytes, restored.engineState)
            assertEquals(Difficulty.MASTER, restored.difficulty)
            assertEquals(16_384, restored.acceptedActionCount)
            assertEquals(Int.MAX_VALUE, restored.undoUseCount)
            assertEquals(Int.MAX_VALUE, restored.hintUseCount)
            NativeLanzhouFangqiSession().use { replay ->
                assertTrue(replay.restore(restored.engineState))
                assertEquals(original.snapshot(), replay.snapshot())
                assertTrue(replay.undo())
                assertEquals(board, replay.snapshot().board)
            }
        }
    }

    @Test fun nativeRejectsIllegalRepeatedHistoryAndLeavesPriorBytesUntouched() = runBlocking {
        val store = RoomFangqiSessionStore(database)
        val seed = NativeLanzhouFangqiSession().use { it.serialize() }
        store.save(snapshot(seed))
        val board = MutableList(49) { 0 }.apply { this[0] = 1; this[48] = 2 }
        NativeLanzhouFangqiSession.fromPosition(board, FangqiSide.RED).use { session ->
            listOf(move(0, 0, 1, 0), move(6, 6, 5, 6), move(1, 0, 0, 0)).forEach {
                assertTrue(session.apply(it).accepted)
            }
            val parts = session.serialize().toString(Charsets.US_ASCII).split('|').toMutableList()
            parts[4] = "4"
            parts[5] += ";m,2,47,48"
            expectFailure { store.save(snapshot(parts.joinToString("|").toByteArray(Charsets.US_ASCII))) }
        }
        assertArrayEquals(seed, requireNotNull(store.load()).engineState)
    }

    @Test fun malformedExistingRowIsNotMissingAndIsNeverRewrittenByLoad() = runBlocking {
        val dao = database.activeGameDao()
        val corrupt = ActiveGameEntity(4, 0, null, 5, 1, "broken-history".toByteArray(), 123, "retained")
        dao.upsert(corrupt)
        expectFailure { RoomFangqiSessionStore(database).load() }
        assertEquals(corrupt, dao.find(4))
    }

    @Test fun realRoomRollsBackLateReadbackException() = runBlocking {
        val dao = database.activeGameDao()
        val bytes = NativeLanzhouFangqiSession().use { it.serialize() }
        RoomFangqiSessionStore(database).save(snapshot(bytes))
        val before = dao.find(4)
        val faulty = object : ActiveGameDao by dao {
            override suspend fun find(gameTypeCode: Int): ActiveGameEntity? = throw IOException("late readback failure")
        }
        expectFailure { store(faulty).save(snapshot(bytes, "replacement")) }
        assertEquals(before, dao.find(4))
    }

    @Test fun realRoomRollsBackCancellationAfterUpsert() = runBlocking {
        val dao = database.activeGameDao()
        val bytes = NativeLanzhouFangqiSession().use { it.serialize() }
        RoomFangqiSessionStore(database).save(snapshot(bytes))
        val before = dao.find(4)
        val entered = CompletableDeferred<Unit>()
        val suspended = object : ActiveGameDao by dao {
            override suspend fun find(gameTypeCode: Int): ActiveGameEntity? {
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        val writer = launch { store(suspended).save(snapshot(bytes, "cancelled-replacement")) }
        entered.await()
        writer.cancelAndJoin()
        assertEquals(before, dao.find(4))
    }

    @Test fun restoredEpochRejectsOldWriterWithoutTouchingOtherGameRows() = runBlocking {
        val dao = database.activeGameDao()
        val other = ActiveGameEntity(0, 0, null, 5, 2, byteArrayOf(7, 8, 9), 123, "other-game")
        dao.upsert(other)
        val bytes = NativeLanzhouFangqiSession().use { it.serialize() }
        val gate = FangqiStorageEpochGate.forDatabase(database)
        val old = RoomFangqiSessionStore(database, gate)
        old.save(snapshot(bytes))
        gate.exclusive(true) { database.withTransaction { dao.delete(4) } }
        expectFailure { old.save(snapshot(bytes)) }
        expectFailure { old.load() }
        assertNull(dao.find(4))
        assertEquals(other, dao.find(0))
        RoomFangqiSessionStore(database, gate).save(snapshot(bytes))
        assertArrayEquals(bytes, requireNotNull(RoomFangqiSessionStore(database, gate).load()).engineState)
    }

    private fun store(dao: ActiveGameDao): RoomFangqiSessionStore {
        val gate = FangqiStorageEpochGate.forDatabase(database)
        return RoomFangqiSessionStore(dao, gate, gate.captureOwner(), FangqiSessionAdmission(), Dispatchers.IO,
            { block -> database.withTransaction { block() } })
    }

    private suspend fun expectFailure(block: suspend () -> Unit) {
        try { block(); fail("Expected IOException") } catch (_: IOException) { }
    }

    private fun snapshot(bytes: ByteArray, id: String = "instrumented-方棋") = FangqiStoredSession(
        FangqiStoredMode.HUMAN_VS_AI, Difficulty.MASTER, bytes, id, 123_456L, 16_384, Int.MAX_VALUE, Int.MAX_VALUE)

    private fun move(x: Int, y: Int, tx: Int, ty: Int) = FangqiAction.Move(FangqiPoint(x, y), FangqiPoint(tx, ty))
}
