package com.masterofchessstrategy.games.lanzhou_fangqi.data

import com.masterofchessstrategy.data.BackupStorageInventory
import com.masterofchessstrategy.data.BackupStorageSlot
import com.masterofchessstrategy.data.BackupStorageTable
import com.masterofchessstrategy.data.GameBackupRecord
import com.masterofchessstrategy.data.GameBackupRegistry
import com.masterofchessstrategy.engine.GameType
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FangqiLearningBackupAdapterTest {
    private val adapter = FangqiLearningBackupAdapter()
    private fun row(key: String, time: Long = 10) = FangqiLearningProgressEntity(key, 1, time)
    private fun record(rows: List<FangqiLearningProgressEntity>) = adapter.encode(FangqiLearningSnapshot(rows))

    @Test fun allNineContentKeysRoundTripWithStableOrderAndFirstTimes() {
        val rows = FangqiLearningCatalog.keys.reversed().mapIndexed { index, key -> row(key, index.toLong()) }
        val encoded = record(rows)
        adapter.validate(encoded, 5)
        val decoded = adapter.decode(encoded)
        assertEquals(rows.sortedBy { it.contentKey }, decoded.rows)
        assertTrue(decoded.tutorialComplete)
        assertEquals(3, decoded.completedPractices.size)
        assertEquals(30, encoded.fields.size)
        assertEquals(encoded, adapter.encode(decoded))
    }

    @Test fun emptyStorageEmitsNoRecordAndIncompatibleRowsArePreserved() = runBlocking {
        val dao = MemoryDao()
        assertTrue(adapter.read(dao).isEmpty())
        dao.rows += row("lesson:unknown")
        try { adapter.read(dao); fail("invalid row must not be filtered") } catch (_: IOException) { }
        assertEquals(listOf(row("lesson:unknown")), dao.rows)
        assertEquals(0, dao.clears)
    }

    @Test fun preparedCollectionOwnsInputAndOnlyInsertsWithoutClearing() = runBlocking {
        val fields = record(listOf(row("lesson:opening"))).fields.toMutableList()
        val input = GameBackupRecord(FangqiLearningBackupAdapter.KIND, fields)
        val prepared = adapter.prepareRestore(input)
        fields[3] = "lesson:unknown"
        val dao = MemoryDao()
        prepared.write(dao)
        assertEquals(listOf(row("lesson:opening")), dao.rows)
        assertEquals(0, dao.clears)
        assertEquals(FangqiLearningBackupAdapter.SLOT, prepared.storageSlot)
    }

    @Test fun foreignGameAndUnknownWireOrContentVersionsAreRejected() {
        val valid = record(listOf(row("lesson:opening")))
        for ((index, value) in listOf(0 to "0", 1 to "2", 4 to "2")) {
            val fields = valid.fields.toMutableList().also { it[index] = value }
            assertThrows(Exception::class.java) { adapter.decode(GameBackupRecord(valid.kind, fields)) }
        }
        assertThrows(IllegalArgumentException::class.java) { adapter.validate(valid, 4) }
        assertThrows(IllegalArgumentException::class.java) { adapter.validate(valid, 6) }
    }

    @Test fun duplicateUnsortedUnknownAndExcessContentCannotBeImported() {
        val valid = record(listOf(row("lesson:opening"), row("lesson:step")))
        val duplicate = valid.fields.toMutableList().also { it[6] = it[3] }
        val unsorted = valid.fields.take(3) + valid.fields.drop(6) + valid.fields.subList(3, 6)
        val unknown = valid.fields.toMutableList().also { it[3] = "practice:foreign" }
        val tooMany = valid.fields.toMutableList().also { it[2] = "10" }
        for (fields in listOf(duplicate, unsorted, unknown, tooMany)) {
            assertThrows(Exception::class.java) { adapter.decode(GameBackupRecord(valid.kind, fields)) }
        }
    }

    @Test fun malformedNumbersAndTruncatedOrEmptyCollectionsAreRejected() {
        val valid = record(listOf(row("lesson:opening")))
        for ((index, value) in listOf(2 to "0", 2 to "01", 4 to "01", 5 to "-1", 5 to "+1",
            5 to "9223372036854775808", 5 to "1.0")) {
            val fields = valid.fields.toMutableList().also { it[index] = value }
            assertThrows(Exception::class.java) { adapter.decode(GameBackupRecord(valid.kind, fields)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            adapter.decode(GameBackupRecord(valid.kind, valid.fields.dropLast(1)))
        }
    }

    @Test fun newInventoryRequiresItsAdapterAndRejectsForeignGameCodes() {
        val slots = BackupStorageInventory.fromCodes(emptyList(), emptyList(), fangqiLearningCodes = listOf(4, 4))
        assertEquals(setOf(FangqiLearningBackupAdapter.SLOT), slots)
        assertThrows(IllegalArgumentException::class.java) { GameBackupRegistry(emptyList()).requireSupportedSlots(slots) }
        GameBackupRegistry(listOf(adapter)).requireSupportedSlots(slots)
        assertThrows(IllegalArgumentException::class.java) {
            BackupStorageSlot(BackupStorageTable.FANGQI_LEARNING, GameType.GO)
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupStorageInventory.fromCodes(emptyList(), emptyList(), fangqiLearningCodes = listOf(999))
        }
    }

    @Test fun registryRejectsDuplicateCompleteCollectionsBeforeAnyRestoreWrite() {
        val valid = record(listOf(row("lesson:opening")))
        val registry = GameBackupRegistry(listOf(adapter))
        assertThrows(IllegalArgumentException::class.java) { registry.prepareRestore(listOf(valid, valid), 5) }
    }

    /** Explicit in-memory DAO; SQLite/transaction behavior belongs to device integration. */
    private class MemoryDao : FangqiLearningProgressDao {
        val rows = mutableListOf<FangqiLearningProgressEntity>()
        var clears = 0
        override suspend fun readAll() = rows.toList()
        override suspend fun insert(row: FangqiLearningProgressEntity) {
            check(rows.none { it.contentKey == row.contentKey })
            rows += row
        }
        override suspend fun clearAll() { clears++; rows.clear() }
    }
}
