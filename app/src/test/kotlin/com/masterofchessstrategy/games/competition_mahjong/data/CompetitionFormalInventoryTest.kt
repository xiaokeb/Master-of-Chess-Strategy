package com.masterofchessstrategy.games.competition_mahjong.data

import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.engine.GameType
import org.junit.Assert.*
import org.junit.Test

class CompetitionFormalInventoryTest {
    private val slot = BackupStorageSlot(BackupStorageTable.COMPETITION_FORMAL, GameType.STANDARD_MAHJONG)

    @Test fun newSlotBelongsOnlyToCompetitionMahjong() {
        for (game in GameType.entries.filter { it != GameType.STANDARD_MAHJONG }) {
            assertThrows(IllegalArgumentException::class.java) { BackupStorageSlot(BackupStorageTable.COMPETITION_FORMAL, game) }
        }
        assertEquals(GameType.STANDARD_MAHJONG, slot.gameType)
    }

    @Test fun multipleViewerRowsIdentifyOneRequiredStorageSlot() {
        assertEquals(setOf(slot), BackupStorageInventory.fromCodes(emptyList(), emptyList(),
            competitionFormalCodes = listOf(2, 2, 2, 2)))
    }

    @Test fun emptyFormalTableAddsNoRequirement() {
        assertTrue(BackupStorageInventory.fromCodes(emptyList(), emptyList()).isEmpty())
    }

    @Test fun legacyMatchRegistrationDoesNotCoverCompleteFormalData() {
        val legacy = object : GameBackupAdapter {
            override val descriptor = GameBackupDescriptor("COMPETITION_MATCH", 4,
                BackupStorageSlot(BackupStorageTable.COMPETITION_MATCH, GameType.STANDARD_MAHJONG), 1, 0, 1000)
            override fun validate(record: GameBackupRecord, containerVersion: Int) = Unit
            override suspend fun read(database: MocsDatabase): List<GameBackupRecord> = error("Not used")
            override fun prepareRestore(record: GameBackupRecord): PreparedGameBackup = error("Not used")
        }
        assertThrows(IllegalArgumentException::class.java) { GameBackupRegistry(listOf(legacy)).requireSupportedSlots(setOf(slot)) }
    }

    @Test fun unsupportedFormalDataCannotPassAnEmptyRegistry() {
        assertThrows(IllegalArgumentException::class.java) { GameBackupRegistry(emptyList()).requireSupportedSlots(setOf(slot)) }
    }

    @Test fun foreignOrUnknownInventoryCodeIsRejected() {
        for (code in listOf(0, 1, 3, 4, 5, 999)) {
            assertThrows(IllegalArgumentException::class.java) {
                BackupStorageInventory.fromCodes(emptyList(), emptyList(), competitionFormalCodes = listOf(code))
            }
        }
    }

    @Test fun oldMatchAndOtherGamesRemainDistinctFromFormalStorage() {
        val slots = BackupStorageInventory.fromCodes(listOf(0, 5), listOf(2), competitionFormalCodes = listOf(2))
        assertEquals(setOf(slot, BackupStorageSlot(BackupStorageTable.COMPETITION_MATCH, GameType.STANDARD_MAHJONG),
            BackupStorageSlot(BackupStorageTable.ACTIVE_GAME, GameType.CHINESE_CHESS),
            BackupStorageSlot(BackupStorageTable.ACTIVE_GAME, GameType.TIGER_AND_GOAT)), slots)
    }
}
