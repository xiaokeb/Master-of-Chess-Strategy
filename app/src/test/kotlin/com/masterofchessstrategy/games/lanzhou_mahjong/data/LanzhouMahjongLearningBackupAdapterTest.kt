package com.masterofchessstrategy.games.lanzhou_mahjong.data

import com.masterofchessstrategy.data.BackupStorageInventory
import com.masterofchessstrategy.data.BackupStorageSlot
import com.masterofchessstrategy.data.BackupStorageTable
import com.masterofchessstrategy.data.GameBackupRecord
import com.masterofchessstrategy.data.GameBackupRegistry
import com.masterofchessstrategy.engine.GameType
import org.junit.Assert.*
import org.junit.Test

class LanzhouMahjongLearningBackupAdapterTest {
    private val adapter = LanzhouMahjongLearningBackupAdapter()

    @Test fun allTenPrefixesAndMaximumTimestampRoundTripWithoutRewards() {
        for (count in 0..10) {
            val snapshot = LanzhouMahjongLearningSnapshot(2, count, Long.MAX_VALUE)
            val wire = adapter.encode(snapshot)
            adapter.validate(wire, 5)
            assertEquals(snapshot, adapter.decode(wire))
            assertTrue(adapter.prepareRestore(wire).completedRecordIds.isEmpty())
        }
    }

    @Test fun legacyFourStepVersionIsNotNewTutorialCompletion() {
        assertThrows(IllegalArgumentException::class.java) { adapter.validate(record("3", "1", "1", "4", "42"), 5) }
        assertFalse(adapter.decode(record("3", "1", "2", "4", "42")).isCompleted)
        assertThrows(IllegalArgumentException::class.java) { adapter.validate(record("3", "1", "2", "10", "42"), 4) }
    }

    @Test fun foreignOwnersUnknownFormatsAndMalformedIntegersAreRejected() {
        val good = listOf("3", "1", "2", "10", "42")
        val changes = listOf(0 to "4", 1 to "2", 2 to "3", 3 to "11", 3 to "-1", 3 to "01",
            3 to "true", 4 to "-1", 4 to "+42", 4 to "9223372036854775808")
        for ((index, value) in changes) {
            val bad = good.toMutableList().also { it[index] = value }
            assertThrows(IllegalArgumentException::class.java) { adapter.decode(GameBackupRecord(adapter.descriptor.kind, bad)) }
        }
        assertThrows(IllegalArgumentException::class.java) { adapter.decode(record(*good.dropLast(1).toTypedArray())) }
        assertThrows(IllegalArgumentException::class.java) { adapter.decode(record(*(good + "extra").toTypedArray())) }
    }

    @Test fun registryCannotAcceptDuplicateProgressOrOmitItsAdapter() {
        val wire = record("3", "1", "2", "10", "42")
        val registry = GameBackupRegistry(listOf(adapter))
        assertThrows(IllegalArgumentException::class.java) { registry.validate(listOf(wire, wire), 5) }
        assertThrows(IllegalArgumentException::class.java) { GameBackupRegistry(emptyList()).validate(listOf(wire), 5) }
    }

    @Test fun newInventorySlotHasOnlyLanzhouOwnership() {
        assertEquals(setOf(LanzhouMahjongLearningBackupAdapter.SLOT), BackupStorageInventory.fromCodes(
            emptyList(), emptyList(), lanzhouMahjongLearningCodes = listOf(3)))
        assertThrows(IllegalArgumentException::class.java) {
            BackupStorageSlot(BackupStorageTable.LANZHOU_MAHJONG_LEARNING, GameType.CHINESE_CHESS)
        }
    }

    private fun record(vararg fields: String) = GameBackupRecord(adapter.descriptor.kind, fields.toList())
}
