package com.masterofchessstrategy.games.lanzhou_fangqi.data

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.masterofchessstrategy.data.ActiveGameEntity
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiAction
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiPoint
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiSide
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.NativeLanzhouFangqiSession
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Actual Room/JNI contracts. A successful compile does not mean these methods ran on a device. */
class FangqiBackupAdapterInstrumentedTest {
    private lateinit var database: MocsDatabase
    private val adapter = FangqiBackupAdapter()

    @Before fun createDatabase() {
        database = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), MocsDatabase::class.java).build()
    }

    @After fun closeDatabase() { database.close() }

    @Test fun realNativePartialCaptureBackupPreservesUndoAndEveryModeMetadata() = runBlocking {
        val board = MutableList(49) { 0 }.apply {
            listOf(0, 2, 14, 8, 9, 15, 23).forEach { this[it] = 1 }
            listOf(40, 41, 48).forEach { this[it] = 2 }
        }
        val dao = database.activeGameDao()
        NativeLanzhouFangqiSession.fromPosition(board, FangqiSide.RED).use { original ->
            assertTrue(original.apply(move(2, 3, 2, 2)).accepted)
            assertTrue(original.apply(FangqiAction.Capture(FangqiPoint(6, 6))).accepted)
            assertEquals(1, original.snapshot().pendingCaptures)
            val bytes = original.serialize()
            val variants = listOf(0 to null) + listOf(1, 2).flatMap { mode ->
                Difficulty.entries.map { mode to it.code }
            }
            variants.forEach { (mode, difficulty) ->
                val source = entity(bytes).copy(modeCode = mode, difficultyCode = difficulty)
                dao.upsert(source)
                val wire = adapter.read(database).single()
                adapter.validate(wire, 5)
                val prepared = adapter.prepareRestore(wire)
                database.withTransaction { dao.delete(4); prepared.write(database) }
                assertEquals(source, dao.find(4))
                NativeLanzhouFangqiSession().use { restored ->
                    assertTrue(restored.restore(requireNotNull(dao.find(4)).engineState))
                    assertEquals(original.snapshot(), restored.snapshot())
                    assertTrue(restored.undo())
                    assertEquals(board, restored.snapshot().board)
                }
            }
        }
    }

    @Test fun realRoomOuterTransactionRollsBackAfterPreparedWrite() = runBlocking {
        val dao = database.activeGameDao()
        val bytes = NativeLanzhouFangqiSession().use { it.serialize() }
        val before = entity(bytes)
        val other = before.copy(gameTypeCode = 0, engineFormatVersion = 2, engineState = byteArrayOf(3, 1, 4))
        dao.upsert(before)
        dao.upsert(other)
        val prepared = adapter.prepareRestore(adapter.read(database).single().let {
            com.masterofchessstrategy.data.GameBackupRecord(it.kind, it.fields.toMutableList().apply {
                this[5] = "replacement".toByteArray().joinToString("") { b -> "%02x".format(b.toInt() and 255) }
            })
        })
        try {
            database.withTransaction { dao.delete(4); prepared.write(database); throw IOException("after-write fault") }
            fail("Outer restore transaction must roll back")
        } catch (_: IOException) { }
        assertEquals(before, dao.find(4))
        assertEquals(other, dao.find(0))
    }

    @Test fun structurallyValidButRepeatedNativeHistoryCannotBePreparedOrClearData() = runBlocking {
        val dao = database.activeGameDao()
        val seed = NativeLanzhouFangqiSession().use { it.serialize() }
        val before = entity(seed)
        dao.upsert(before)
        val board = MutableList(49) { 0 }.apply { this[0] = 1; this[48] = 2 }
        NativeLanzhouFangqiSession.fromPosition(board, FangqiSide.RED).use { session ->
            listOf(move(0, 0, 1, 0), move(6, 6, 5, 6), move(1, 0, 0, 0)).forEach {
                assertTrue(session.apply(it).accepted)
            }
            val parts = session.serialize().toString(Charsets.US_ASCII).split('|').toMutableList()
            parts[4] = "4"
            parts[5] += ";m,2,47,48"
            val rejected = adapter.read(database).single().let { wire ->
                com.masterofchessstrategy.data.GameBackupRecord(wire.kind, wire.fields.toMutableList().apply {
                    this[10] = parts.joinToString("|").toByteArray(Charsets.US_ASCII)
                        .joinToString("") { "%02x".format(it.toInt() and 255) }
                })
            }
            adapter.validate(rejected, 5)
            try { adapter.prepareRestore(rejected); fail("Native must reject first repetition") }
            catch (_: IOException) { }
        }
        assertEquals(before, dao.find(4))
    }

    @Test fun existingBadMetadataOrHistoryIsNotExportedOrRewritten() = runBlocking {
        val dao = database.activeGameDao()
        val bytes = NativeLanzhouFangqiSession().use { it.serialize() }
        val invalid = listOf(entity(bytes).copy(pendingDrawOfferSideCode = 0),
            entity("broken-history".toByteArray()))
        invalid.forEach { row ->
            dao.upsert(row)
            try { adapter.read(database); fail("Malformed existing data must not be omitted") }
            catch (_: Exception) { }
            assertEquals(row, dao.find(4))
        }
    }

    private fun entity(bytes: ByteArray) = ActiveGameEntity(4, 1, Difficulty.MASTER.code, 5, 1, bytes,
        Long.MAX_VALUE, "方棋-备份", 16_384, Int.MAX_VALUE, Int.MAX_VALUE)
    private fun move(x: Int, y: Int, tx: Int, ty: Int) = FangqiAction.Move(FangqiPoint(x, y), FangqiPoint(tx, ty))
}
