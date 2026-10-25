package com.masterofchessstrategy.data

import com.masterofchessstrategy.engine.GameType

/** Inspect actual primary keys, including unknown codes that enum-based iteration would miss. */
internal object BackupStorageInventory {
    suspend fun read(database: MocsDatabase): Set<BackupStorageSlot> = fromCodes(
        database.backupStorageInventoryDao().activeGameTypeCodes(),
        database.backupStorageInventoryDao().competitionGameTypeCodes(),
        database.backupStorageInventoryDao().goLocalGameTypeCodes(),
        database.backupStorageInventoryDao().goRecordSourceGameTypeCodes(),
        database.backupStorageInventoryDao().goProgressGameTypeCodes(),
        database.backupStorageInventoryDao().goAiSessionGameTypeCodes(),
        database.backupStorageInventoryDao().goAiArchiveGameTypeCodes(),
        database.backupStorageInventoryDao().goAiProjectionGameTypeCodes(),
        database.backupStorageInventoryDao().goRankedStartGameTypeCodes(),
        database.backupStorageInventoryDao().goRankedSettlementGameTypeCodes(),
        database.backupStorageInventoryDao().fangqiLearningGameTypeCodes(),
    )

    fun fromCodes(
        activeCodes: List<Int>, competitionCodes: List<Int>,
        goLocalCodes: List<Int> = emptyList(), goRecordSourceCodes: List<Int> = emptyList(),
        goProgressCodes: List<Int> = emptyList(),
        goAiSessionCodes: List<Int> = emptyList(), goAiArchiveCodes: List<Int> = emptyList(),
        goAiProjectionCodes: List<Int> = emptyList(),
        goRankedStartCodes: List<Int> = emptyList(), goRankedSettlementCodes: List<Int> = emptyList(),
        fangqiLearningCodes: List<Int> = emptyList(),
    ): Set<BackupStorageSlot> {
        require(activeCodes.distinct().size == activeCodes.size && competitionCodes.distinct().size == competitionCodes.size &&
            goLocalCodes.distinct().size == goLocalCodes.size &&
            goAiSessionCodes.distinct().size == goAiSessionCodes.size)
        fun gameType(code: Int) = requireNotNull(GameType.entries.firstOrNull { it.code == code }) { "Unknown stored game code" }
        return activeCodes.map { BackupStorageSlot(BackupStorageTable.ACTIVE_GAME, gameType(it)) }.toSet() +
            competitionCodes.map { BackupStorageSlot(BackupStorageTable.COMPETITION_MATCH, gameType(it)) } +
            goLocalCodes.map { BackupStorageSlot(BackupStorageTable.GO_LOCAL_SESSION, gameType(it)) } +
            goRecordSourceCodes.map { BackupStorageSlot(BackupStorageTable.GO_RECORD_SOURCE, gameType(it)) } +
            goProgressCodes.map { BackupStorageSlot(BackupStorageTable.GO_PROGRESS, gameType(it)) } +
            goAiSessionCodes.map { BackupStorageSlot(BackupStorageTable.GO_AI_SESSION, gameType(it)) } +
            goAiArchiveCodes.map { BackupStorageSlot(BackupStorageTable.GO_AI_ARCHIVE, gameType(it)) } +
            goAiProjectionCodes.map { BackupStorageSlot(BackupStorageTable.GO_AI_PROJECTION, gameType(it)) } +
            goRankedStartCodes.map { BackupStorageSlot(BackupStorageTable.GO_RANKED_START, gameType(it)) } +
            goRankedSettlementCodes.map { BackupStorageSlot(BackupStorageTable.GO_RANKED_SETTLEMENT, gameType(it)) } +
            fangqiLearningCodes.map { BackupStorageSlot(BackupStorageTable.FANGQI_LEARNING, gameType(it)) }
    }
}
