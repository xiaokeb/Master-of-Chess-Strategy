package com.masterofchessstrategy.data

import com.masterofchessstrategy.engine.GameType

/** Inspect actual primary keys, including unknown codes that enum-based iteration would miss. */
internal object BackupStorageInventory {
    suspend fun read(database: MocsDatabase): Set<BackupStorageSlot> = fromCodes(
        database.backupStorageInventoryDao().activeGameTypeCodes(),
        database.backupStorageInventoryDao().competitionGameTypeCodes(),
    )

    fun fromCodes(activeCodes: List<Int>, competitionCodes: List<Int>): Set<BackupStorageSlot> {
        require(activeCodes.distinct().size == activeCodes.size && competitionCodes.distinct().size == competitionCodes.size)
        fun gameType(code: Int) = requireNotNull(GameType.entries.firstOrNull { it.code == code }) { "Unknown stored game code" }
        return activeCodes.map { BackupStorageSlot(BackupStorageTable.ACTIVE_GAME, gameType(it)) }.toSet() +
            competitionCodes.map { BackupStorageSlot(BackupStorageTable.COMPETITION_MATCH, gameType(it)) }
    }
}
