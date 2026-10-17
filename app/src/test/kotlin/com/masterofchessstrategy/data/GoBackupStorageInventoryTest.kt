package com.masterofchessstrategy.data

import com.masterofchessstrategy.engine.GameType
import org.junit.Assert.*
import org.junit.Test

class GoBackupStorageInventoryTest {
    @Test fun actualGoRowsRequireTheirOwnRegisteredSlots() {
        val slots = BackupStorageInventory.fromCodes(listOf(GameType.CHINESE_CHESS.code), emptyList(),
            listOf(GameType.GO.code), listOf(GameType.GO.code, GameType.GO.code))
        assertEquals(3, slots.size)
        assertTrue(BackupStorageSlot(BackupStorageTable.GO_LOCAL_SESSION, GameType.GO) in slots)
        assertTrue(BackupStorageSlot(BackupStorageTable.GO_RECORD_SOURCE, GameType.GO) in slots)
        assertThrows(IllegalArgumentException::class.java) { GameBackupRegistry(emptyList()).requireSupportedSlots(slots) }
    }

    @Test fun foreignOrUnknownOwnersAndDuplicateActiveGoRowsAreRejected() {
        for (code in listOf(GameType.CHINESE_CHESS.code, 999)) {
            assertThrows(IllegalArgumentException::class.java) {
                BackupStorageInventory.fromCodes(emptyList(), emptyList(), listOf(code))
            }
            assertThrows(IllegalArgumentException::class.java) {
                BackupStorageInventory.fromCodes(emptyList(), emptyList(), emptyList(), listOf(code))
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupStorageInventory.fromCodes(emptyList(), emptyList(), listOf(GameType.GO.code, GameType.GO.code))
        }
    }

    @Test fun existingChineseCompetitionAndTigerSlotsKeepTheirOwnership() {
        val slots = BackupStorageInventory.fromCodes(listOf(GameType.CHINESE_CHESS.code, GameType.TIGER_AND_GOAT.code),
            listOf(GameType.STANDARD_MAHJONG.code))
        assertEquals(3, slots.size)
        assertFalse(slots.any { it.table in setOf(BackupStorageTable.GO_LOCAL_SESSION, BackupStorageTable.GO_RECORD_SOURCE) })
    }

    @Test fun standaloneGoRecordsNeedAnAdapterEvenWithoutSessionOrSourceRows() {
        // The DAO's source inventory unions standalone game_records with provenance rows.
        val slots = BackupStorageInventory.fromCodes(emptyList(), emptyList(), emptyList(), listOf(GameType.GO.code))
        assertEquals(setOf(BackupStorageSlot(BackupStorageTable.GO_RECORD_SOURCE, GameType.GO)), slots)
        assertThrows(IllegalArgumentException::class.java) { GameBackupRegistry(emptyList()).requireSupportedSlots(slots) }
    }

    @Test fun detailedGoProgressRowsAreProtectedEvenWithoutAnActiveGame() {
        val slots = BackupStorageInventory.fromCodes(emptyList(), emptyList(), emptyList(), emptyList(),
            listOf(GameType.GO.code, GameType.GO.code))
        assertEquals(setOf(BackupStorageSlot(BackupStorageTable.GO_PROGRESS, GameType.GO)), slots)
        assertThrows(IllegalArgumentException::class.java) { GameBackupRegistry(emptyList()).requireSupportedSlots(slots) }
        assertThrows(IllegalArgumentException::class.java) {
            BackupStorageInventory.fromCodes(emptyList(), emptyList(), emptyList(), emptyList(), listOf(999))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupStorageInventory.fromCodes(emptyList(), emptyList(), emptyList(), emptyList(), listOf(GameType.CHINESE_CHESS.code))
        }
    }
}
