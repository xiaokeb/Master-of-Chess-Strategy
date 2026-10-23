package com.masterofchessstrategy.data

import com.masterofchessstrategy.engine.GameType
import org.junit.Assert.*
import org.junit.Test

class GoAiBackupInventoryTest {
    @Test fun formalStartAndSettlementEachRequireTheirOwnRegisteredSlot() {
        val start = BackupStorageInventory.fromCodes(emptyList(), emptyList(), goRankedStartCodes = listOf(1, 1))
        val settlement = BackupStorageInventory.fromCodes(emptyList(), emptyList(), goRankedSettlementCodes = listOf(1))
        assertEquals(setOf(BackupStorageSlot(BackupStorageTable.GO_RANKED_START, GameType.GO)), start)
        assertEquals(setOf(BackupStorageSlot(BackupStorageTable.GO_RANKED_SETTLEMENT, GameType.GO)), settlement)
        for (slots in listOf(start, settlement, start + settlement)) {
            assertThrows(IllegalArgumentException::class.java) { GameBackupRegistry(emptyList()).requireSupportedSlots(slots) }
        }
        for (code in listOf(999, GameType.CHINESE_CHESS.code)) {
            assertThrows(IllegalArgumentException::class.java) {
                BackupStorageInventory.fromCodes(emptyList(), emptyList(), goRankedStartCodes = listOf(code))
            }
            assertThrows(IllegalArgumentException::class.java) {
                BackupStorageInventory.fromCodes(emptyList(), emptyList(), goRankedSettlementCodes = listOf(code))
            }
        }
    }

    @Test fun independentActiveAndArchiveSlotsCannotBeHiddenByMissingDiskFiles() {
        val active = BackupStorageInventory.fromCodes(emptyList(), emptyList(),
            goAiSessionCodes = listOf(GameType.GO.code))
        val archive = BackupStorageInventory.fromCodes(emptyList(), emptyList(),
            goAiArchiveCodes = listOf(GameType.GO.code, GameType.GO.code))
        assertEquals(setOf(BackupStorageSlot(BackupStorageTable.GO_AI_SESSION, GameType.GO)), active)
        assertEquals(setOf(BackupStorageSlot(BackupStorageTable.GO_AI_ARCHIVE, GameType.GO)), archive)
        for (slots in listOf(active, archive, active + archive)) {
            assertThrows(IllegalArgumentException::class.java) {
                GameBackupRegistry(emptyList()).requireSupportedSlots(slots)
            }
        }
    }

    @Test fun foreignOrUnknownOwnersInEitherAiTableAreRejected() {
        for (code in listOf(999, GameType.CHINESE_CHESS.code)) {
            assertThrows(IllegalArgumentException::class.java) {
                BackupStorageInventory.fromCodes(emptyList(), emptyList(), goAiSessionCodes = listOf(code))
            }
            assertThrows(IllegalArgumentException::class.java) {
                BackupStorageInventory.fromCodes(emptyList(), emptyList(), goAiArchiveCodes = listOf(code))
            }
        }
    }

    @Test fun activeSlotsAreUniqueButMultipleArchivesAreExpected() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupStorageInventory.fromCodes(emptyList(), emptyList(),
                goAiSessionCodes = listOf(GameType.GO.code, GameType.GO.code))
        }
        assertEquals(1, BackupStorageInventory.fromCodes(emptyList(), emptyList(),
            goAiArchiveCodes = listOf(1, 1, 1)).size)
    }

    @Test fun anEmptyAiDatabaseAddsNoNewBackupRequirement() {
        val slots = BackupStorageInventory.fromCodes(listOf(GameType.CHINESE_CHESS.code), emptyList())
        GameBackupRegistry(emptyList()).requireSupportedSlots(slots)
        assertEquals(setOf(BackupStorageSlot(BackupStorageTable.ACTIVE_GAME, GameType.CHINESE_CHESS)), slots)
    }

    @Test fun restoreFencesHaveFourDistinctStableSourceNames() {
        assertEquals(listOf("go-legacy-local-session", "go-legacy-learning-preferences",
            "go-legacy-endgame-progress", "go-legacy-ai-match"), GoLegacyImportScopes.ALL)
        assertEquals(4, GoLegacyImportScopes.ALL.distinct().size)
    }
}
