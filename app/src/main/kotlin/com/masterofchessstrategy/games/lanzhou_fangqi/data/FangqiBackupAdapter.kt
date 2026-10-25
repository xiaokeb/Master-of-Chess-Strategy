package com.masterofchessstrategy.games.lanzhou_fangqi.data

import com.masterofchessstrategy.data.ActiveGameDao
import com.masterofchessstrategy.data.ActiveGameEntity
import com.masterofchessstrategy.data.BackupStorageSlot
import com.masterofchessstrategy.data.BackupStorageTable
import com.masterofchessstrategy.data.GameBackupAdapter
import com.masterofchessstrategy.data.GameBackupDescriptor
import com.masterofchessstrategy.data.GameBackupRecord
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.PreparedGameBackup
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiStoredMode
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiStoredSession
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** Complete Fangqi history and metadata; the repository owns gates and the snapshot transaction. */
internal class FangqiBackupAdapter(
    private val admission: FangqiSessionAdmission = FangqiSessionAdmission(),
) : GameBackupAdapter {
    override val descriptor = GameBackupDescriptor(KIND, 5, SLOT, 1, 30, MAX_RECORD_BYTES)

    override suspend fun read(database: MocsDatabase): List<GameBackupRecord> = read(database.activeGameDao())

    internal suspend fun read(dao: ActiveGameDao): List<GameBackupRecord> {
        val row = dao.find(GameType.LANZHOU_SQUARE_CHESS.code) ?: return emptyList()
        return listOf(encode(admission.admit(fromEntity(row))))
    }

    /** Structural decoding never opens Native; complete rule admission happens before replacement. */
    override fun validate(record: GameBackupRecord, containerVersion: Int) {
        require(containerVersion == 5) { "方棋备份需要容器版本5" }
        decode(record)
    }

    override fun prepareRestore(record: GameBackupRecord): PreparedFangqiBackup =
        PreparedFangqiBackup(admission.admit(decode(record)))

    internal class PreparedFangqiBackup internal constructor(snapshot: FangqiStoredSession) : PreparedGameBackup {
        private val owned = FangqiSessionAdmission.copyOwned(snapshot)
        override val storageSlot = SLOT

        override suspend fun write(database: MocsDatabase) = write(database.activeGameDao())

        /** No gate, clear, or nested transaction: the coordinator already owns the complete restore. */
        internal suspend fun write(dao: ActiveGameDao) {
            dao.upsert(toEntity(owned))
        }
    }

    internal fun encode(snapshot: FangqiStoredSession): GameBackupRecord {
        val id = encodeId(snapshot.sessionId)
        val bytes = snapshot.engineState
        FangqiSessionAdmission.inspectHistory(bytes)
        return GameBackupRecord(KIND, listOf(
            GameType.LANZHOU_SQUARE_CHESS.code.toString(), ENVELOPE.toString(),
            FangqiStoredSession.ENGINE_FORMAT_VERSION.toString(), snapshot.mode.code.toString(),
            snapshot.difficulty?.code?.toString() ?: "-", id,
            snapshot.acceptedActionCount.toString(), snapshot.undoUseCount.toString(),
            snapshot.hintUseCount.toString(), snapshot.updatedAtEpochMillis.toString(), hex(bytes),
        )).also { require(it.encodedSizeBytes <= MAX_RECORD_BYTES) }
    }

    internal fun decode(record: GameBackupRecord): FangqiStoredSession {
        require(record.kind == KIND && record.fields.size == 11 && record.encodedSizeBytes <= MAX_RECORD_BYTES) {
            "方棋备份字段数量或大小无效"
        }
        val fields = record.fields
        require(fields[0] == GameType.LANZHOU_SQUARE_CHESS.code.toString() &&
            fields[1] == ENVELOPE.toString() && fields[2] == FangqiStoredSession.ENGINE_FORMAT_VERSION.toString()) {
            "方棋备份归属或版本无效"
        }
        val modeCode = number(fields[3], Int.MAX_VALUE.toLong()).toInt()
        val mode = requireNotNull(FangqiStoredMode.entries.singleOrNull { it.code == modeCode })
        val difficulty = if (fields[4] == "-") null else {
            val code = number(fields[4], Int.MAX_VALUE.toLong()).toInt()
            requireNotNull(Difficulty.entries.singleOrNull { it.code == code })
        }
        val idBytes = unhex(fields[5], MAX_ID_BYTES)
        val id = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(idBytes)).toString()
        val bytes = unhex(fields[10], FangqiStoredSession.MAX_ENGINE_BYTES)
        FangqiSessionAdmission.inspectHistory(bytes)
        return FangqiStoredSession(mode, difficulty, bytes, id,
            number(fields[9], Long.MAX_VALUE), number(fields[6], FangqiSessionAdmission.MAX_ACTIONS.toLong()).toInt(),
            number(fields[7], Int.MAX_VALUE.toLong()).toInt(), number(fields[8], Int.MAX_VALUE.toLong()).toInt())
    }

    private fun fromEntity(row: ActiveGameEntity): FangqiStoredSession {
        require(row.gameTypeCode == GameType.LANZHOU_SQUARE_CHESS.code && row.envelopeVersion == ENVELOPE &&
            row.engineFormatVersion == FangqiStoredSession.ENGINE_FORMAT_VERSION && row.resultOverrideCode == null &&
            row.timeControlMinutes == null && row.redRemainingMillis == null && row.blackRemainingMillis == null &&
            row.turnStartedAtEpochMillis == null && row.pendingDrawOfferSideCode == null && !row.autoPlayPaused &&
            row.autoPlaySpeedPermille == 1_000 && row.completedAutoGames == 0 && row.sessionVariantId.isEmpty() &&
            row.playerIndex == 0) { "方棋活动存档字段不兼容，原数据已保留" }
        val mode = requireNotNull(FangqiStoredMode.entries.singleOrNull { it.code == row.modeCode })
        val difficulty = row.difficultyCode?.let { code -> requireNotNull(Difficulty.entries.singleOrNull { it.code == code }) }
        return FangqiStoredSession(mode, difficulty, row.engineState, row.sessionId, row.updatedAtEpochMillis,
            row.acceptedMoveCount, row.undoUseCount, row.hintUseCount)
    }

    companion object {
        const val KIND = "FANGQI_ACTIVE"
        private const val ENVELOPE = 5
        private const val MAX_ID_BYTES = 256
        // Kind, eleven separators, four fixed/version/mode digits, difficulty, and bounded fields.
        const val MAX_RECORD_BYTES = 13 + 11 + 4 + 1 + MAX_ID_BYTES * 2 + 5 + 10 + 10 + 19 +
            FangqiStoredSession.MAX_ENGINE_BYTES * 2
        val SLOT = BackupStorageSlot(BackupStorageTable.ACTIVE_GAME, GameType.LANZHOU_SQUARE_CHESS)
        private const val HEX = "0123456789abcdef"

        private fun toEntity(snapshot: FangqiStoredSession) = ActiveGameEntity(
            gameTypeCode = GameType.LANZHOU_SQUARE_CHESS.code, modeCode = snapshot.mode.code,
            difficultyCode = snapshot.difficulty?.code, envelopeVersion = ENVELOPE,
            engineFormatVersion = FangqiStoredSession.ENGINE_FORMAT_VERSION, engineState = snapshot.engineState,
            updatedAtEpochMillis = snapshot.updatedAtEpochMillis, sessionId = snapshot.sessionId,
            acceptedMoveCount = snapshot.acceptedActionCount, undoUseCount = snapshot.undoUseCount,
            hintUseCount = snapshot.hintUseCount,
        )

        private fun number(value: String, maximum: Long): Long {
            require(value.length in 1..19 && value.all { it in '0'..'9' }) { "方棋备份数值无效" }
            val parsed = requireNotNull(value.toLongOrNull())
            require(parsed <= maximum && parsed.toString() == value) { "方棋备份数值非规范或超限" }
            return parsed
        }

        private fun encodeId(value: String): String {
            val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(value))
            val bytes = ByteArray(encoded.remaining()).also { encoded.get(it) }
            require(bytes.size in 1..MAX_ID_BYTES)
            return hex(bytes)
        }

        private fun unhex(value: String, maximumBytes: Int): ByteArray {
            require(value.length % 2 == 0 && value.length / 2 in 1..maximumBytes &&
                value.all { it in HEX }) { "方棋备份十六进制非规范或超限" }
            return ByteArray(value.length / 2) { index ->
                ((HEX.indexOf(value[index * 2]) shl 4) or HEX.indexOf(value[index * 2 + 1])).toByte()
            }
        }

        private fun hex(bytes: ByteArray): String = buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                append(HEX[(byte.toInt() ushr 4) and 15]); append(HEX[byte.toInt() and 15])
            }
        }
    }
}
