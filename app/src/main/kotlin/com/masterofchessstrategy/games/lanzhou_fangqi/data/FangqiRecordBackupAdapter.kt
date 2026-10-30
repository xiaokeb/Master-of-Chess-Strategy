package com.masterofchessstrategy.games.lanzhou_fangqi.data

import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.lanzhou_fangqi.*
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** Fangqi records do not use the Xiangqi-only core RECORD wire segment. */
internal class FangqiRecordBackupAdapter(
    private val replay: FangqiRankedNativeReplay = NativeFangqiRankedReplay(),
) : GameBackupAdapter {
    override val descriptor = GameBackupDescriptor(KIND, 5,
        BackupStorageSlot(BackupStorageTable.FANGQI_RECORD, GameType.LANZHOU_SQUARE_CHESS),
        200_000, 34, FangqiRankedBackupWire.MAX_RECORD_BYTES)
    override suspend fun read(database: MocsDatabase) = database.gameRecordDao().listAll()
        .filter { it.gameTypeCode == 4 }.map { encode(requireNotNull(it.toGameRecord())) }
    override fun validate(record: GameBackupRecord, containerVersion: Int) {
        require(containerVersion == 5); decode(record)
    }
    override fun prepareRestore(record: GameBackupRecord): PreparedFangqiRecord {
        val value = decode(record)
        if (value.engineFormatVersion == 1) {
            val actual = replay.replay(FangqiRankedHistory(value.engineState))
            require(actual.canonicalHistory.bytes.contentEquals(value.engineState) && actual.pendingCaptures == 0 &&
                actual.result == value.result && actual.result != GameResult.ONGOING)
        }
        // Legacy v2 bytes are preserved as unranked legacy data, never turned into formal receipts.
        return PreparedFangqiRecord(value)
    }

    internal fun encode(record: GameRecord): GameBackupRecord {
        require(record.gameType == GameType.LANZHOU_SQUARE_CHESS)
        val row = record.toGameRecordEntity()
        return GameBackupRecord(KIND, listOf("4", "1", recordIdHex(row.recordId), row.modeCode.toString(),
            row.difficultyCode?.toString() ?: "-", row.resultCode.toString(), row.engineFormatVersion.toString(),
            FangqiRankedBackupWire.hex(row.engineState), row.moveCount.toString(), if(row.isFavorite) "1" else "0",
            if(row.isEndgame) "1" else "0", row.completedAtEpochMillis.toString(), row.playerIndex.toString()))
    }
    private fun decode(record: GameBackupRecord): GameRecord {
        require(record.kind == KIND && record.fields.size == 13 && record.encodedSizeBytes <= descriptor.maximumRecordBytes)
        val f = record.fields
        require(f[0] == "4" && f[1] == "1")
        fun n(index: Int) = FangqiRankedBackupWire.integer(f[index])
        fun bit(index: Int): Boolean { require(f[index] in listOf("0","1")); return f[index] == "1" }
        val row = GameRecordEntity(recordId(f[2]),4,n(3),if(f[4] == "-") null else n(4),n(5),n(6),
            FangqiRankedBackupWire.unhex(f[7],1_048_576),n(8),bit(9),bit(10),FangqiRankedBackupWire.number(f[11]),n(12))
        return requireNotNull(row.toGameRecord()) { "方棋棋谱备份不兼容" }
    }
    companion object {
        const val KIND = "FANGQI_RECORD"
        private const val MAX_ID_BYTES = 256

        // Record IDs keep the existing Unicode model contract; ranked source IDs remain ASCII.
        private fun recordIdHex(value: String): String {
            require(value.isNotBlank() && value.length <= GameRecord.MAX_RECORD_ID_LENGTH)
            val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
            val bytes = ByteArray(encoded.remaining()).also { encoded.get(it) }
            require(bytes.size in 1..MAX_ID_BYTES)
            return FangqiRankedBackupWire.hex(bytes)
        }

        private fun recordId(encoded: String): String {
            val bytes = FangqiRankedBackupWire.unhex(encoded, MAX_ID_BYTES)
            val value = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            require(recordIdHex(value) == encoded)
            return value
        }
    }
}

internal class PreparedFangqiRecord internal constructor(record: GameRecord) : PreparedGameBackup {
    private val owned = record.copy(engineState = record.engineState.copyOf())
    val record: GameRecord get() = owned.copy(engineState = owned.engineState.copyOf())
    override val storageSlot = BackupStorageSlot(BackupStorageTable.FANGQI_RECORD, GameType.LANZHOU_SQUARE_CHESS)
    override val completedRecordIds: Set<String> get() = setOf(owned.recordId)
    override suspend fun write(database: MocsDatabase) {
        check(database.gameRecordDao().insert(owned.toGameRecordEntity()) != -1L) { "方棋备份棋谱ID冲突" }
    }
}
