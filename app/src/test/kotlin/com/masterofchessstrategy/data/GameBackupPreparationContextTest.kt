package com.masterofchessstrategy.data

import com.masterofchessstrategy.engine.GameType
import org.junit.Assert.*
import org.junit.Test

class GameBackupPreparationContextTest {
    @Test fun eachIdentityKeyHasOneOwnedValuePerContext() {
        var created = 0
        val key = GameBackupPreparationKey { Any().also { created++ } }
        val first = GameBackupPreparationContext()
        assertSame(first.get(key), first.get(key))
        assertNotSame(first.get(key), GameBackupPreparationContext().get(key))
        assertEquals(2, created)
    }

    @Test fun differentKeysNeverAliasEvenWhenTheirFactoriesAreTheSame() {
        val factory = { Any() }
        val context = GameBackupPreparationContext()
        assertNotSame(context.get(GameBackupPreparationKey(factory)), context.get(GameBackupPreparationKey(factory)))
    }

    @Test fun failedCreationIsNotCachedAsSuccessfulAdmission() {
        var calls = 0
        val key = GameBackupPreparationKey { check(++calls > 1); Any() }
        val context = GameBackupPreparationContext()
        assertThrows(IllegalStateException::class.java) { context.get(key) }
        assertSame(context.get(key), context.get(key))
        assertEquals(2, calls)
    }

    @Test fun aRegistryInvocationSharesOnlyItsOwnBatchContextAcrossRelatedAdapters() {
        var calls = 0
        val key = GameBackupPreparationKey { ++calls }
        val observations = mutableListOf<Int>()
        fun adapter(kind: String, slot: BackupStorageSlot, order: Int) = object : GameBackupAdapter,
            GameBackupContextualPreparation {
            override val descriptor = GameBackupDescriptor(kind, 5, slot, 1, order, 100)
            override fun validate(record: GameBackupRecord, containerVersion: Int) = Unit
            override suspend fun read(database: MocsDatabase) = error("Not used")
            override fun prepareRestore(record: GameBackupRecord): PreparedGameBackup = error("Use batch-local preparation")
            override fun prepareRestore(record: GameBackupRecord, context: GameBackupPreparationContext): PreparedGameBackup {
                observations += context.get(key)
                return object : PreparedGameBackup {
                    override val storageSlot = slot
                    override suspend fun write(database: MocsDatabase) = error("No writes in this test")
                }
            }
        }
        val tiger = adapter("TEST_TIGER_CONTEXT", BackupStorageSlot(BackupStorageTable.ACTIVE_GAME, GameType.TIGER_AND_GOAT), 0)
        val mahjong = adapter("TEST_MAHJONG_CONTEXT", BackupStorageSlot(BackupStorageTable.COMPETITION_MATCH, GameType.STANDARD_MAHJONG), 1)
        val registry = GameBackupRegistry(listOf(tiger, mahjong))
        val records = listOf(GameBackupRecord(tiger.descriptor.kind, listOf(GameType.TIGER_AND_GOAT.code.toString())),
            GameBackupRecord(mahjong.descriptor.kind, listOf(GameType.STANDARD_MAHJONG.code.toString())))
        repeat(2) { assertEquals(2, registry.prepareRestore(records, 5).size) }
        assertEquals(listOf(1, 1, 2, 2), observations)
        assertEquals(2, calls)
    }
}
