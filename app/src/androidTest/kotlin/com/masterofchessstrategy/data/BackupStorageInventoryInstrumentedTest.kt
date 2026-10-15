package com.masterofchessstrategy.data

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.engine.GameType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real SQLite inventory only, in a private in-memory database. Never clears user data. */
class BackupStorageInventoryInstrumentedTest {
    private lateinit var database: MocsDatabase

    @Before fun prepare() {
        database = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,
            MocsDatabase::class.java).build()
    }
    @After fun cleanup() { database.close() }

    @Test fun inventoryReadsBothTablesAndEmptyRegistryRejectsUnregisteredData() = runBlocking {
        database.activeGameDao().upsert(active(GameType.CHINESE_CHESS.code))
        database.activeGameDao().upsert(active(GameType.TIGER_AND_GOAT.code))
        val match = match(GameType.STANDARD_MAHJONG.code)
        database.competitionMahjongMatchDao().upsert(match)
        val slots = database.withTransaction { BackupStorageInventory.read(database) }
        assertEquals(setOf(
            BackupStorageSlot(BackupStorageTable.ACTIVE_GAME, GameType.CHINESE_CHESS),
            BackupStorageSlot(BackupStorageTable.ACTIVE_GAME, GameType.TIGER_AND_GOAT),
            BackupStorageSlot(BackupStorageTable.COMPETITION_MATCH, GameType.STANDARD_MAHJONG),
        ), slots)
        assertThrows(IllegalArgumentException::class.java) { GameBackupRegistry(emptyList()).requireSupportedSlots(slots) }
        assertArrayEquals(byteArrayOf(1, 2, 3), database.activeGameDao().find(GameType.TIGER_AND_GOAT.code)!!.engineState)
        assertArrayEquals(match.checkpoint, database.competitionMahjongMatchDao().find(match.gameTypeCode)!!.checkpoint)
    }

    @Test fun actualUnknownActivePrimaryKeyIsRejectedAndItsBytesRemain() = runBlocking {
        val row = active(999)
        database.activeGameDao().upsert(row)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { database.withTransaction { BackupStorageInventory.read(database) } }
        }
        assertArrayEquals(row.engineState, database.activeGameDao().find(999)!!.engineState)
    }

    @Test fun foreignCompetitionPrimaryKeyIsRejectedAndItsBytesRemain() = runBlocking {
        val row = match(GameType.TIGER_AND_GOAT.code)
        database.competitionMahjongMatchDao().upsert(row)
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { database.withTransaction { BackupStorageInventory.read(database) } }
        }
        assertArrayEquals(row.checkpoint, database.competitionMahjongMatchDao().find(row.gameTypeCode)!!.checkpoint)
    }

    @Test fun coreOnlyInventoryDoesNotRequireAGameExtension() = runBlocking {
        database.activeGameDao().upsert(active(GameType.CHINESE_CHESS.code))
        val slots = database.withTransaction { BackupStorageInventory.read(database) }
        GameBackupRegistry(emptyList()).requireSupportedSlots(slots)
        assertEquals(1, slots.size)
    }

    @Test fun registryOwnsReadOrderAndRejectsForeignReturnedKinds(): Unit = runBlocking {
        val tiger = reader("TIGER_GOAT_ACTIVE", BackupStorageSlot(BackupStorageTable.ACTIVE_GAME, GameType.TIGER_AND_GOAT), 5, 0)
        val match = reader("COMPETITION_MATCH", BackupStorageSlot(BackupStorageTable.COMPETITION_MATCH, GameType.STANDARD_MAHJONG), 4, 1)
        val registry = GameBackupRegistry(listOf(match, tiger))
        assertEquals(listOf("TIGER_GOAT_ACTIVE", "COMPETITION_MATCH"), registry.readRecords(database).map { it.kind })
        tiger.records = match.records
        assertThrows(IllegalArgumentException::class.java) { runBlocking { registry.readRecords(database) } }
    }

    @Test fun descriptorChangesDuringReadAreRejectedBeforeReturningASnapshot() = runBlocking {
        val tiger = reader("TIGER_GOAT_ACTIVE", BackupStorageSlot(BackupStorageTable.ACTIVE_GAME, GameType.TIGER_AND_GOAT), 5, 0)
        val registry = GameBackupRegistry(listOf(tiger))
        tiger.beforeRead = { tiger.descriptor = tiger.descriptor.copy(maximumRecords = 2) }
        assertThrows(IllegalArgumentException::class.java) { runBlocking { registry.readRecords(database) } }
        assertTrue(database.activeGameDao().find(GameType.TIGER_AND_GOAT.code) == null)
    }

    private class Reader(override var descriptor: GameBackupDescriptor) : GameBackupAdapter {
        var records = listOf(GameBackupRecord(descriptor.kind, listOf(descriptor.storageSlot.gameType.code.toString())))
        var beforeRead: () -> Unit = {}
        override fun validate(record: GameBackupRecord, containerVersion: Int) = Unit
        override suspend fun read(database: MocsDatabase): List<GameBackupRecord> {
            beforeRead()
            return records
        }
        override fun prepareRestore(record: GameBackupRecord): PreparedGameBackup = error("This test only reads")
    }

    private fun reader(kind: String, slot: BackupStorageSlot, version: Int, order: Int) =
        Reader(GameBackupDescriptor(kind, version, slot, 1, order, 1_000))

    private fun active(code: Int) = ActiveGameEntity(code, 0, null, 5, 1, byteArrayOf(1, 2, 3), 0)
    private fun match(code: Int) = CompetitionMahjongMatchEntity(code, 0, "inventory-only", 1, byteArrayOf(4, 5), 0)
}
