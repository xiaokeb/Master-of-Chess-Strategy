package com.masterofchessstrategy.games.tiger_goat

import com.masterofchessstrategy.data.ActiveGameDao
import com.masterofchessstrategy.data.ActiveGameEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TigerGoatSessionStoreTest {
    @Test fun acceptsBothVersionsAndRecordsMatchingVersionAndOwnedBytes() = runTest {
        val dao = FakeDao()
        val store = RoomTigerGoatSessionStore(dao) { true }
        for (version in 1..2) {
            val bytes = envelope(version)
            store.save(TigerGoatSavedSession("saved", bytes, 0, 7))
            bytes[10] = 99
            assertEquals(version, dao.row!!.engineFormatVersion)
            assertEquals(0, dao.row!!.engineState[10].toInt())
            val loaded = store.load() as TigerGoatLoadResult.Loaded
            loaded.saved.bytes[10] = 98
            assertEquals(0, dao.row!!.engineState[10].toInt())
        }
    }

    @Test fun nativeRejectionCannotReplaceCurrentRowAndMismatchCannotLoad() = runTest {
        val dao = FakeDao()
        val store = RoomTigerGoatSessionStore(dao) { it[10] == 0.toByte() }
        store.save(TigerGoatSavedSession("saved", envelope(2), 0, 7))
        val old = dao.row
        val invalid = envelope(2).also { it[10] = 3 }
        try { store.save(TigerGoatSavedSession("saved", invalid, 0, 7)); fail("Expected rejection") }
        catch (_: IllegalArgumentException) { }
        assertSame(old, dao.row)
        dao.row = old!!.copy(engineFormatVersion = 1)
        assertEquals(TigerGoatLoadResult.Incompatible, store.load())
        dao.row = old.copy(engineState = invalid)
        assertEquals(TigerGoatLoadResult.Incompatible, store.load())
    }

    @Test fun unknownVersionsAndTruncationRejectBeforeNativeAdmission() = runTest {
        val dao = FakeDao()
        var admissions = 0
        val store = RoomTigerGoatSessionStore(dao) { admissions++; true }
        for (bytes in listOf(envelope(3), byteArrayOf(77), envelope(1).copyOf(52))) {
            try { store.save(TigerGoatSavedSession("saved", bytes, 0, 7)); fail("Expected rejection") }
            catch (_: IllegalArgumentException) { }
        }
        assertEquals(0, admissions)
        assertNull(dao.row)
    }

    private fun envelope(version: Int) = ByteArray(53).also {
        byteArrayOf(77, 79, 67, 84, version.toByte(), 1).copyInto(it)
    }

    private class FakeDao : ActiveGameDao {
        var row: ActiveGameEntity? = null
        override suspend fun find(gameTypeCode: Int) = row?.takeIf { it.gameTypeCode == gameTypeCode }
        override suspend fun upsert(entity: ActiveGameEntity) { row = entity }
        override suspend fun delete(gameTypeCode: Int) { if (row?.gameTypeCode == gameTypeCode) row = null }
        override suspend fun deleteAll() { row = null }
    }
}
