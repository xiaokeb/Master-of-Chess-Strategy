package com.masterofchessstrategy.games.lanzhou_fangqi.data

import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.lanzhou_fangqi.*

internal class FangqiRankedStartBackupAdapter(
    private val validator: FangqiRankedSnapshotValidator = FangqiRankedSnapshotValidator(),
) : GameBackupAdapter, GameBackupCompleteSnapshotGuard {
    override val descriptor = GameBackupDescriptor(FangqiRankedBackupWire.START, 5,
        BackupStorageSlot(BackupStorageTable.FANGQI_RANKED_START, GameType.LANZHOU_SQUARE_CHESS),
        200_000, 32, FangqiRankedBackupWire.MAX_RECORD_BYTES)

    override suspend fun read(database: MocsDatabase) = database.fangqiRankedDao().readStarts().map {
        FangqiRankedBackupWire.encodeStart(it).also { wire -> validate(wire, 5) }
    }
    override fun validate(record: GameBackupRecord, containerVersion: Int) {
        require(containerVersion == 5)
        FangqiRankedRowMapping.decodeStartUnvalidated(FangqiRankedBackupWire.decodeStart(record))
    }
    override fun prepareRestore(record: GameBackupRecord): PreparedFangqiRankedStart {
        validate(record, 5)
        return PreparedFangqiRankedStart(FangqiRankedBackupWire.decodeStart(record))
    }
    override fun validateCompleteSnapshot(records: List<PreparedGameBackup>, outcomes: List<MatchOutcome>, coreRecords: List<GameRecord>) {
        val active = records.filterIsInstance<FangqiBackupAdapter.PreparedFangqiBackup>()
        require(active.size <= 1)
        validator.validate(FangqiRankedStorageSnapshot(
            records.filterIsInstance<PreparedFangqiRankedStart>().map { it.row },
            records.filterIsInstance<PreparedFangqiRankedSettlement>().map { it.row },
            active.singleOrNull()?.activeRow(), outcomes.map { it.toMatchOutcomeEntity() },
            coreRecords.map { it.toGameRecordEntity() } + records.filterIsInstance<PreparedFangqiRecord>().map { it.record.toGameRecordEntity() },
        ))
    }
}

internal class FangqiRankedSettlementBackupAdapter : GameBackupAdapter {
    override val descriptor = GameBackupDescriptor(FangqiRankedBackupWire.SETTLEMENT, 5,
        BackupStorageSlot(BackupStorageTable.FANGQI_RANKED_SETTLEMENT, GameType.LANZHOU_SQUARE_CHESS),
        200_000, 33, FangqiRankedBackupWire.MAX_RECORD_BYTES)

    override suspend fun read(database: MocsDatabase) = database.fangqiRankedDao().readSettlements().map {
        FangqiRankedBackupWire.encodeSettlement(it).also { wire -> validate(wire, 5) }
    }
    override fun validate(record: GameBackupRecord, containerVersion: Int) {
        require(containerVersion == 5)
        FangqiRankedBackupWire.decodeSettlement(record)
    }
    override fun prepareRestore(record: GameBackupRecord): PreparedFangqiRankedSettlement {
        validate(record, 5)
        return PreparedFangqiRankedSettlement(FangqiRankedBackupWire.decodeSettlement(record))
    }
}

internal class PreparedFangqiRankedStart(val row: FangqiRankedStartEntity) : PreparedGameBackup {
    override val storageSlot = BackupStorageSlot(BackupStorageTable.FANGQI_RANKED_START, GameType.LANZHOU_SQUARE_CHESS)
    override suspend fun write(database: MocsDatabase) = database.fangqiRankedDao().insertStart(row)
}

internal class PreparedFangqiRankedSettlement(val row: FangqiRankedSettlementEntity) : PreparedGameBackup {
    override val storageSlot = BackupStorageSlot(BackupStorageTable.FANGQI_RANKED_SETTLEMENT, GameType.LANZHOU_SQUARE_CHESS)
    // Its core outcome parent is inserted later, inside the same deferred-FK restore transaction.
    override suspend fun write(database: MocsDatabase) = database.fangqiRankedDao().insertSettlement(row)
}

/** Canonical wire structure only. Cross-family Native admission runs before the clear transaction. */
internal object FangqiRankedBackupWire {
    const val START = "FANGQI_RANKED_START"
    const val SETTLEMENT = "FANGQI_RANKED_SETTLEMENT"
    const val MAX_RECORD_BYTES = 2 * 1_048_576 + 1024
    private const val HEX = "0123456789abcdef"

    fun encodeStart(row: FangqiRankedStartEntity) = GameBackupRecord(START, listOf("4", "1", idHex(row.sessionId),
        row.formatVersion.toString(), row.startOrigin, row.modeCode.toString(), row.difficultyCode.toString(),
        row.humanPlayerIndex.toString(), row.rulesVersion, row.engineFormatVersion.toString(), hex(row.initialHistory.bytes),
        row.startedAtEpochMillis.toString(), row.acceptedActionCount.toString(), row.undoUseCount.toString(),
        row.hintUseCount.toString(), row.creationSha256)).also { require(row.gameTypeCode == 4) }

    fun decodeStart(record: GameBackupRecord): FangqiRankedStartEntity {
        val f = header(record, START, 16)
        val row = FangqiRankedStartEntity(id(f[2]), integer(f[3]), f[4], 4, integer(f[5]), integer(f[6]), integer(f[7]),
            f[8], integer(f[9]), FangqiRankedHistory(unhex(f[10], 1_048_576)), number(f[11]), integer(f[12]),
            integer(f[13]), integer(f[14]), sha(f[15]))
        FangqiRankedRowMapping.decodeStartUnvalidated(row)
        return row
    }

    fun encodeSettlement(row: FangqiRankedSettlementEntity) = GameBackupRecord(SETTLEMENT, listOf("4", "1", idHex(row.sessionId),
        row.formatVersion.toString(), row.creationSha256, row.modeCode.toString(), row.difficultyCode.toString(),
        row.humanPlayerIndex.toString(), row.rulesVersion, row.engineFormatVersion.toString(), hex(row.terminalHistory.bytes),
        row.updatedAtEpochMillis.toString(), row.acceptedActionCount.toString(), row.undoUseCount.toString(),
        row.hintUseCount.toString(), row.resultCode.toString(), row.settledAtEpochMillis.toString(),
        row.recordDeletedAtEpochMillis?.toString() ?: "-")).also { require(row.gameTypeCode == 4) }

    fun decodeSettlement(record: GameBackupRecord): FangqiRankedSettlementEntity {
        val f = header(record, SETTLEMENT, 18)
        val row = FangqiRankedSettlementEntity(id(f[2]), integer(f[3]), sha(f[4]), 4, integer(f[5]), integer(f[6]),
            integer(f[7]), f[8], integer(f[9]), FangqiRankedHistory(unhex(f[10], 1_048_576)), number(f[11]),
            integer(f[12]), integer(f[13]), integer(f[14]), integer(f[15]), number(f[16]),
            if (f[17] == "-") null else number(f[17]))
        require(row.formatVersion == 1 && row.modeCode == FangqiStoredMode.HUMAN_VS_AI.code && row.humanPlayerIndex == 0 &&
            row.rulesVersion == FangqiRankedLedger.RULES_VERSION && row.engineFormatVersion == 1)
        val difficulty = requireNotNull(Difficulty.entries.singleOrNull { it.code == row.difficultyCode })
        val limit = when (difficulty) { Difficulty.EASY -> Int.MAX_VALUE; Difficulty.MEDIUM -> 3; Difficulty.HARD -> 1; Difficulty.MASTER -> 0 }
        require(row.acceptedActionCount in 1..16_384 && row.acceptedActionCount == FangqiSessionAdmission.inspectHistory(row.terminalHistory.bytes) &&
            row.undoUseCount <= limit && row.hintUseCount <= limit && row.resultCode in 1..2 &&
            row.settledAtEpochMillis >= row.updatedAtEpochMillis &&
            (row.recordDeletedAtEpochMillis == null || row.recordDeletedAtEpochMillis >= row.settledAtEpochMillis))
        return row
    }

    private fun header(record: GameBackupRecord, kind: String, count: Int): List<String> {
        require(record.kind == kind && record.fields.size == count && record.encodedSizeBytes <= MAX_RECORD_BYTES)
        require(record.fields[0] == "4" && record.fields[1] == "1")
        return record.fields
    }
    internal fun number(s: String): Long {
        require(s.length in 1..19 && s.all { it in '0'..'9' })
        val value = requireNotNull(s.toLongOrNull())
        require(value.toString() == s)
        return value
    }
    internal fun integer(s: String): Int = number(s).also { require(it <= Int.MAX_VALUE) }.toInt()
    private fun sha(s: String): String = s.also { require(it.length == 64 && it.all { ch -> ch in HEX }) }
    internal fun idHex(s: String): String {
        require(s.length in 1..64 && s.all { it.code in 33..126 })
        return hex(s.toByteArray(Charsets.US_ASCII))
    }
    internal fun id(s: String): String = unhex(s, 64).toString(Charsets.US_ASCII).also {
        require(it.length in 1..64 && it.all { ch -> ch.code in 33..126 } && idHex(it) == s)
    }
    internal fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
        bytes.forEach { append(HEX[(it.toInt() ushr 4) and 15]); append(HEX[it.toInt() and 15]) }
    }
    internal fun unhex(s: String, max: Int): ByteArray {
        require(s.length % 2 == 0 && s.length / 2 in 1..max && s.all { it in HEX })
        return ByteArray(s.length / 2) { ((HEX.indexOf(s[it * 2]) shl 4) or HEX.indexOf(s[it * 2 + 1])).toByte() }
    }
}
