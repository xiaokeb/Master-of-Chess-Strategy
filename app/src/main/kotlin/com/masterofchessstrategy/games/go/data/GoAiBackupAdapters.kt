package com.masterofchessstrategy.games.go.data

import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.go.GoAiMatchFileCodec
import com.masterofchessstrategy.games.go.GoAiSavedMatch

/** Raw AI sources only. GO_LOCAL_RECORD remains the sole owner of Go game_records. */
internal fun createGoAiBackupAdapters(
    admit: (GoAiSavedMatch) -> GoAdmittedAiCheckpoint = ::admitGoAiCheckpoint,
): List<GameBackupAdapter> {
    val key = GameBackupPreparationKey { GoAiBackupAdmissionCache(admit) }
    return listOf(GoAiSessionBackupAdapter(key), GoAiArchiveBackupAdapter(key))
}

internal interface GoAiBackupAdapter : GameBackupAdapter, GameBackupContextualPreparation, GameBackupSnapshotGuard {
    override fun prepareRestore(record: GameBackupRecord): PreparedGameBackup =
        prepareRestore(record, GameBackupPreparationContext())

    override fun validatePreparedSnapshot(records: List<PreparedGameBackup>) =
        GoAiBackupSnapshotGuard.validate(records)
}

internal class GoAiSessionBackupAdapter internal constructor(
    private val admissionKey: GameBackupPreparationKey<GoAiBackupAdmissionCache>,
) : GoAiBackupAdapter {
    override val descriptor = GameBackupDescriptor(KIND, 5,
        BackupStorageSlot(BackupStorageTable.GO_AI_SESSION, GameType.GO), 1, 13, GoAiBackupWire.MAX_RECORD_BYTES)

    override suspend fun read(database: MocsDatabase): List<GameBackupRecord> =
        encodeRows(database.goAiCheckpointDao().listActive())

    internal fun encodeRows(rows: List<GoAiSessionEntity>): List<GameBackupRecord> {
        require(rows.size <= 1) { "Duplicate Go AI active checkpoint" }
        return rows.map { row ->
            val entry = GoAiCheckpointRows.entry(row)
            GameBackupRecord(KIND, listOf(GameType.GO.code.toString(), "1", row.updatedAtEpochMillis.toString(),
                GoAiBackupWire.origin(entry.lineage.origin), entry.lineage.replacedTerminalRecordId ?: "-",
                GoAiBackupWire.hex(entry.copyBytes()))).also { decode(it) }
        }
    }

    override fun validate(record: GameBackupRecord, containerVersion: Int) {
        require(containerVersion == 5)
        decode(record)
    }

    override fun prepareRestore(record: GameBackupRecord, context: GameBackupPreparationContext): PreparedGameBackup {
        val decoded = decode(record)
        return PreparedGoAiSessionBackup(context.get(admissionKey).prepare(decoded.bytes), decoded.lineage, decoded.time)
    }

    private fun decode(record: GameBackupRecord): GoAiBackupDecoded {
        GoAiBackupWire.header(record, KIND, 6)
        return GoAiBackupDecoded(GoAiBackupWire.image(record.fields[5]),
            GoAiBackupWire.lineage(record.fields[3], record.fields[4]), GoAiBackupWire.time(record.fields[2]))
    }

    companion object { const val KIND = "GO_AI_SESSION" }
}

internal class GoAiArchiveBackupAdapter internal constructor(
    private val admissionKey: GameBackupPreparationKey<GoAiBackupAdmissionCache>,
) : GoAiBackupAdapter {
    override val descriptor = GameBackupDescriptor(KIND, 5,
        BackupStorageSlot(BackupStorageTable.GO_AI_ARCHIVE, GameType.GO), 200_000, 14, GoAiBackupWire.MAX_RECORD_BYTES)

    override suspend fun read(database: MocsDatabase): List<GameBackupRecord> =
        encodeRows(database.goAiCheckpointDao().listArchives())

    internal fun encodeRows(rows: List<GoAiArchiveEntity>): List<GameBackupRecord> {
        require(rows.size <= descriptor.maximumRecords && rows.map { it.recordId }.distinct().size == rows.size)
        return rows.sortedWith(compareBy({ it.completedAtEpochMillis }, { it.recordId })).map { row ->
            val archive = GoAiCheckpointRows.archive(row)
            GameBackupRecord(KIND, listOf(GameType.GO.code.toString(), "1", archive.recordId,
                archive.completedAtEpochMillis.toString(), GoAiBackupWire.origin(archive.checkpoint.lineage.origin),
                archive.checkpoint.lineage.replacedTerminalRecordId ?: "-",
                GoAiBackupWire.hex(archive.checkpoint.copyBytes()))).also { decode(it) }
        }
    }

    override fun validate(record: GameBackupRecord, containerVersion: Int) {
        require(containerVersion == 5)
        decode(record)
    }

    override fun prepareRestore(record: GameBackupRecord, context: GameBackupPreparationContext): PreparedGameBackup {
        val decoded = decode(record)
        val admitted = context.get(admissionKey).prepare(decoded.bytes)
        require(admitted.recordId == record.fields[2]) { "Go AI archive key differs from its match identity" }
        return PreparedGoAiArchiveBackup(admitted, decoded.lineage, decoded.time)
    }

    private fun decode(record: GameBackupRecord): GoAiBackupDecoded {
        GoAiBackupWire.header(record, KIND, 7)
        GoAiBackupWire.recordId(record.fields[2])
        val lineage = GoAiBackupWire.lineage(record.fields[4], record.fields[5])
        require(lineage.replacedTerminalRecordId != record.fields[2]) { "Go AI archive cannot be its own parent" }
        return GoAiBackupDecoded(GoAiBackupWire.image(record.fields[6]), lineage, GoAiBackupWire.time(record.fields[3]))
    }

    companion object { const val KIND = "GO_AI_ARCHIVE" }
}

internal class PreparedGoAiSessionBackup internal constructor(
    val admitted: GoAdmittedAiCheckpoint,
    val lineage: GoAiCheckpointLineage,
    val updatedAtEpochMillis: Long,
) : PreparedGameBackup {
    init { require(updatedAtEpochMillis >= 0) }
    override val storageSlot = BackupStorageSlot(BackupStorageTable.GO_AI_SESSION, GameType.GO)
    override suspend fun write(database: MocsDatabase) {
        database.goAiCheckpointDao().saveActive(GoAiCheckpointRows.active(
            GoAiCheckpointEntry(GoAiMatchFileCodec.encode(admitted.match()), lineage), updatedAtEpochMillis))
    }
}

internal class PreparedGoAiArchiveBackup internal constructor(
    val admitted: GoAdmittedAiCheckpoint,
    val lineage: GoAiCheckpointLineage,
    val completedAtEpochMillis: Long,
) : PreparedGameBackup {
    init { require(completedAtEpochMillis >= 0) }
    val recordId: String get() = admitted.recordId
    override val storageSlot = BackupStorageSlot(BackupStorageTable.GO_AI_ARCHIVE, GameType.GO)
    override suspend fun write(database: MocsDatabase) {
        database.goAiCheckpointDao().insertArchive(GoAiCheckpointRows.archive(GoAiCheckpointArchive(recordId,
            GoAiCheckpointEntry(GoAiMatchFileCodec.encode(admitted.match()), lineage), completedAtEpochMillis)))
    }
}

private class GoAiBackupDecoded(val bytes: ByteArray, val lineage: GoAiCheckpointLineage, val time: Long)

/** Strict structural wire checks only: no Native engine, rank proof or database access. */
internal object GoAiBackupWire {
    const val MAX_RECORD_BYTES = GoAiMatchFileCodec.MAX_BYTES * 2 + 512

    fun header(record: GameBackupRecord, kind: String, count: Int) {
        require(record.kind == kind && record.fields.size == count &&
            record.fields[0] == GameType.GO.code.toString() && record.fields[1] == "1") { "Invalid Go AI backup envelope" }
        require(record.encodedSizeBytes <= MAX_RECORD_BYTES)
    }

    fun canonical(bytes: ByteArray): GoAiSavedMatch {
        require(bytes.size in 5..GoAiMatchFileCodec.MAX_BYTES && bytes[4] == 6.toByte()) { "Go AI backup requires GAI1 v6" }
        val match = requireNotNull(GoAiMatchFileCodec.decode(bytes)) { "Invalid Go AI backup checkpoint" }
        require(GoAiMatchFileCodec.encode(match).contentEquals(bytes)) { "Noncanonical Go AI backup checkpoint" }
        return match
    }

    fun time(value: String): Long = requireNotNull(value.toLongOrNull()).also {
        require(it >= 0 && it.toString() == value) { "Invalid Go AI backup time" }
    }

    fun recordId(value: String): String = value.also { require(it.matches(Regex("[0-9a-f]{64}"))) }

    fun origin(value: GoAiCheckpointOrigin): String = when (value) {
        GoAiCheckpointOrigin.LEGACY_UNVERIFIED -> "1"
        GoAiCheckpointOrigin.UNVERIFIED_SESSION -> "2"
        GoAiCheckpointOrigin.TERMINAL_CONTINUATION -> "3"
    }

    fun lineage(code: String, parent: String): GoAiCheckpointLineage = GoAiCheckpointLineage(when (code) {
        "1" -> GoAiCheckpointOrigin.LEGACY_UNVERIFIED
        "2" -> GoAiCheckpointOrigin.UNVERIFIED_SESSION
        "3" -> GoAiCheckpointOrigin.TERMINAL_CONTINUATION
        else -> throw IllegalArgumentException("Unknown Go AI backup provenance")
    }, parent.takeUnless { it == "-" }?.let(::recordId))

    fun image(value: String): ByteArray {
        require(value.length % 2 == 0 && value.length / 2 in 1..GoAiMatchFileCodec.MAX_BYTES &&
            value.all { it in '0'..'9' || it in 'a'..'f' }) { "Invalid Go AI backup hex or size" }
        return ByteArray(value.length / 2) { index ->
            ((value[index * 2].digitToInt(16) shl 4) or value[index * 2 + 1].digitToInt(16)).toByte()
        }.also { canonical(it) }
    }

    fun hex(bytes: ByteArray): String {
        val digits = "0123456789abcdef"
        return buildString(bytes.size * 2) { bytes.forEach {
            val value = it.toInt() and 255
            append(digits[value ushr 4]); append(digits[value and 15])
        } }
    }
}
