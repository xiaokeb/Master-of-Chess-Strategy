package com.masterofchessstrategy.games.go.data

import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.go.GoAiMatchFileCodec

/** The initial GAI is a local workflow fact, not authentication or anti-cheat data. */
internal class GoRankedStartBackupAdapter : GameBackupAdapter {
    override val descriptor = GameBackupDescriptor(KIND, 5,
        BackupStorageSlot(BackupStorageTable.GO_RANKED_START, GameType.GO), 200_000, 16,
        GoAiBackupWire.MAX_RECORD_BYTES)

    override suspend fun read(database: MocsDatabase) = database.goRankedDao().starts().map {
        val start = it.toStart()
        GameBackupRecord(KIND, listOf("1", "1", start.recordId,
            start.createdAtEpochMillis.toString(), GoAiBackupWire.hex(start.copyBytes())))
            .also { wire -> validate(wire, 5) }
    }

    override fun validate(record: GameBackupRecord, containerVersion: Int) {
        require(containerVersion == 5)
        GoAiBackupWire.header(record, KIND, 5)
        GoAiBackupWire.recordId(record.fields[2])
        GoAiBackupWire.time(record.fields[3])
        GoAiBackupWire.image(record.fields[4])
    }

    override fun prepareRestore(record: GameBackupRecord): PreparedGameBackup {
        validate(record, 5)
        val start = admitGoRankedStart(GoAiBackupWire.canonical(GoAiBackupWire.image(record.fields[4])),
            GoAiBackupWire.time(record.fields[3]))
        require(start.recordId == record.fields[2]) { "Go formal creation identity mismatch" }
        return PreparedGoRankedStart(start)
    }

    companion object { const val KIND = "GO_RANKED_START" }
}

internal class PreparedGoRankedStart(val start: GoRankedStart) : PreparedGameBackup {
    override val storageSlot = BackupStorageSlot(BackupStorageTable.GO_RANKED_START, GameType.GO)
    override suspend fun write(database: MocsDatabase) = database.goRankedDao().insertStart(start.toEntity())
}

internal class GoRankedSettlementBackupAdapter : GameBackupAdapter, GameBackupSnapshotGuard,
    GameBackupCoreSnapshotGuard {
    override val descriptor = GameBackupDescriptor(KIND, 5,
        BackupStorageSlot(BackupStorageTable.GO_RANKED_SETTLEMENT, GameType.GO), 200_000, 17, 128)

    override suspend fun read(database: MocsDatabase) = database.goRankedDao().settlements().map {
        require(it.formatVersion == 1)
        GameBackupRecord(KIND, listOf("1", "1", it.recordId)).also { wire -> validate(wire, 5) }
    }

    override fun validate(record: GameBackupRecord, containerVersion: Int) {
        require(containerVersion == 5)
        GoAiBackupWire.header(record, KIND, 3)
        GoAiBackupWire.recordId(record.fields[2])
    }

    override fun prepareRestore(record: GameBackupRecord): PreparedGameBackup {
        validate(record, 5)
        return PreparedGoRankedSettlement(record.fields[2])
    }

    override fun validatePreparedSnapshot(records: List<PreparedGameBackup>) {
        val starts = records.filterIsInstance<PreparedGoRankedStart>()
        val settlements = records.filterIsInstance<PreparedGoRankedSettlement>()
        require(starts.map { it.start.recordId }.distinct().size == starts.size) { "Duplicate Go formal creation" }
        require(settlements.map { it.recordId }.distinct().size == settlements.size) { "Duplicate Go settlement" }
        // The core guard below also verifies Native terminal facts and the shared outcome payload.
    }

    override fun validateCoreSnapshot(records: List<PreparedGameBackup>, outcomes: List<MatchOutcome>) {
        val starts = records.filterIsInstance<PreparedGoRankedStart>().associateBy { it.start.recordId }
        val archives = records.filterIsInstance<PreparedGoAiArchiveBackup>().associateBy { it.recordId }
        val projections = records.filterIsInstance<PreparedGoAiProjectionBackup>().map { it.recordId }.toSet()
        val ledger = outcomes.associateBy { it.matchId }
        records.filterIsInstance<PreparedGoRankedSettlement>().forEach {
            val start = requireNotNull(starts[it.recordId]) { "Go settlement lacks formal creation" }.start
            val raw = requireNotNull(archives[it.recordId]) { "Go settlement lacks first raw archive" }
            require(it.recordId in projections) { "Go settlement lacks record projection" }
            val archive = GoAiCheckpointArchive(raw.recordId,
                GoAiCheckpointEntry(GoAiMatchFileCodec.encode(raw.admitted.match()), raw.lineage), raw.completedAtEpochMillis)
            val expected = requireNotNull(qualifiedGoOutcome(start, archive)) { "Nonstandard Go settlement" }
            require(ledger[it.recordId] == expected) { "Go settlement conflicts with core outcome" }
        }
    }

    companion object { const val KIND = "GO_RANKED_SETTLEMENT" }
}

internal class PreparedGoRankedSettlement(val recordId: String) : PreparedGameBackup {
    override val storageSlot = BackupStorageSlot(BackupStorageTable.GO_RANKED_SETTLEMENT, GameType.GO)
    override suspend fun write(database: MocsDatabase) {
        check(database.goRankedDao().insertSettlement(GoRankedSettlementEntity(recordId)) != -1L)
    }
}
