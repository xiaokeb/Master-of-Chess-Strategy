package com.masterofchessstrategy.games.go.data

import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.go.GoAiMatchFileCodec

/** Metadata only. GO_LOCAL_RECORD exclusively owns the actual shared GameRecord payload. */
internal class GoAiProjectionBackupAdapter : GameBackupAdapter, GameBackupSnapshotGuard {
    override val descriptor = GameBackupDescriptor(KIND, 5,
        BackupStorageSlot(BackupStorageTable.GO_AI_PROJECTION, GameType.GO), 200_000, 15, 128)

    override suspend fun read(database: MocsDatabase): List<GameBackupRecord> =
        database.goAiProjectionDao().listAll().map {
            require(it.projectionVersion == 1)
            GameBackupRecord(KIND, listOf(GameType.GO.code.toString(), "1", it.recordId)).also { wire -> validate(wire, 5) }
        }

    override fun validate(record: GameBackupRecord, containerVersion: Int) {
        require(containerVersion == 5)
        GoAiBackupWire.header(record, KIND, 3)
        GoAiBackupWire.recordId(record.fields[2])
    }

    override fun prepareRestore(record: GameBackupRecord): PreparedGameBackup {
        validate(record, 5)
        return PreparedGoAiProjectionBackup(record.fields[2])
    }

    override fun validatePreparedSnapshot(records: List<PreparedGameBackup>) {
        val associations = records.filterIsInstance<PreparedGoAiProjectionBackup>()
        require(associations.map { it.recordId }.distinct().size == associations.size) { "Duplicate Go AI projection" }
        val archives = records.filterIsInstance<PreparedGoAiArchiveBackup>().associateBy { it.recordId }
        val shared = records.filterIsInstance<PreparedGoRecordBackup>().associateBy { it.recordId }
        associations.forEach { association ->
            val raw = requireNotNull(archives[association.recordId]) { "Go AI projection lacks raw archive" }
            val record = requireNotNull(shared[association.recordId]) { "Go AI projection lacks shared record" }
            require(record.source == null) { "Go AI projection cannot claim local provenance" }
            val archive = GoAiCheckpointArchive(raw.recordId,
                GoAiCheckpointEntry(GoAiMatchFileCodec.encode(raw.admitted.match()), raw.lineage), raw.completedAtEpochMillis)
            requireSameGoAiRecord(record.record(), projectAdmittedGoAiRecord(archive, raw.admitted))
        }
    }

    companion object { const val KIND = "GO_AI_PROJECTION" }
}

internal class PreparedGoAiProjectionBackup(val recordId: String) : PreparedGameBackup {
    override val storageSlot = BackupStorageSlot(BackupStorageTable.GO_AI_PROJECTION, GameType.GO)
    override suspend fun write(database: MocsDatabase) {
        check(database.goAiProjectionDao().insert(GoAiProjectionEntity(recordId)) != -1L)
    }
}
