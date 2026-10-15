package com.masterofchessstrategy.data

import com.masterofchessstrategy.engine.GameType

internal enum class BackupStorageTable { ACTIVE_GAME, COMPETITION_MATCH }

/** A schema-owned row slot; registration cannot introduce or conditionally remove tables. */
internal data class BackupStorageSlot(val table: BackupStorageTable, val gameType: GameType) {
    init {
        require(table != BackupStorageTable.COMPETITION_MATCH || gameType == GameType.STANDARD_MAHJONG) {
            "The competition match table belongs only to competition mahjong"
        }
    }
}

internal data class GameBackupDescriptor(
    val kind: String,
    val minimumContainerVersion: Int,
    val storageSlot: BackupStorageSlot,
    val maximumRecords: Int,
    val canonicalOrder: Int,
    val maximumRecordBytes: Int,
) {
    init {
        require(kind.matches(Regex("[A-Z][A-Z0-9_]{0,63}")))
        require(minimumContainerVersion in 4..5)
        require(maximumRecords in 1..200_000 && canonicalOrder >= 0)
        require(maximumRecordBytes in 1..GameBackupRecord.MAX_BYTES)
    }
}

internal interface GameBackupRecordCodec {
    val descriptor: GameBackupDescriptor

    /** Envelope validation only: no Native session and no database writes. */
    fun validate(record: GameBackupRecord, containerVersion: Int)

    /** Normalize accepted legacy wire spellings before the registry checks canonical ownership. */
    fun normalize(record: GameBackupRecord, containerVersion: Int): GameBackupRecord {
        validate(record, containerVersion)
        return record
    }
}

internal interface GameBackupAdapter : GameBackupRecordCodec {
    /** The public repository owns the surrounding snapshot transaction. */
    suspend fun read(database: MocsDatabase): List<GameBackupRecord>

    /** Decode and finish actual Native admission before any clearing transaction starts. */
    fun prepareRestore(record: GameBackupRecord): PreparedGameBackup
}

internal interface PreparedGameBackup {
    val storageSlot: BackupStorageSlot

    /** Own immutable admitted data; only write it inside the repository's single transaction. */
    suspend fun write(database: MocsDatabase)
}
