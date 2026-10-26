package com.masterofchessstrategy.games.lanzhou_mahjong.data

import com.masterofchessstrategy.data.ActiveGameDao
import com.masterofchessstrategy.data.ActiveGameEntity
import com.masterofchessstrategy.data.BackupStorageSlot
import com.masterofchessstrategy.data.BackupStorageTable
import com.masterofchessstrategy.data.GameBackupAdapter
import com.masterofchessstrategy.data.GameBackupDescriptor
import com.masterofchessstrategy.data.GameBackupRecord
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.PreparedGameBackup
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.lanzhou_mahjong.LanzhouMahjongSessionRecordCodec

/** The four-field wire owns the complete LZSR record, never a public-view reconstruction. */
internal class LanzhouMahjongBackupAdapter(
    private val admission: LanzhouMahjongSessionAdmission = LanzhouMahjongSessionAdmission(),
) : GameBackupAdapter {
    override val descriptor = GameBackupDescriptor(KIND, 5, SLOT, 1, 40, MAX_RECORD_BYTES)

    override suspend fun read(database: MocsDatabase): List<GameBackupRecord> = read(database.activeGameDao())

    internal suspend fun read(dao: ActiveGameDao): List<GameBackupRecord> {
        val row = dao.find(GameType.LANZHOU_MAHJONG.code) ?: return emptyList()
        val admitted = admission.admitRow(row)
        return listOf(encode(LanzhouMahjongStoredSession(admitted, row.updatedAtEpochMillis)))
    }

    /** Structural validation only. It deliberately cannot establish legal Native history. */
    override fun validate(record: GameBackupRecord, containerVersion: Int) {
        require(containerVersion == 5) { "兰州麻将备份需要容器版本5" }
        decode(record)
    }

    /** Legacy outer v1 becomes canonical outer v2 without modifying LZMJ rule/usage bytes. */
    override fun normalize(record: GameBackupRecord, containerVersion: Int): GameBackupRecord {
        validate(record, containerVersion)
        return encode(decode(record))
    }

    override fun prepareRestore(record: GameBackupRecord): PreparedLanzhouMahjongBackup {
        val snapshot = decode(record)
        // Exactly one complete Native admission occurs before the public repository clears anything.
        return PreparedLanzhouMahjongBackup(admission.createRow(snapshot.record, snapshot.updatedAtEpochMillis))
    }

    internal class PreparedLanzhouMahjongBackup internal constructor(row: ActiveGameEntity) : PreparedGameBackup {
        private val owned = row.copy(engineState = row.engineState.copyOf())
        override val storageSlot = SLOT

        override suspend fun write(database: MocsDatabase) = write(database.activeGameDao())

        /** Only an upsert: the complete restore already owns every gate and its single transaction. */
        internal suspend fun write(dao: ActiveGameDao) {
            dao.upsert(owned.copy(engineState = owned.engineState.copyOf()))
        }
    }

    internal fun encode(snapshot: LanzhouMahjongStoredSession): GameBackupRecord =
        GameBackupRecord(KIND, listOf("3", "1", snapshot.updatedAtEpochMillis.toString(),
            hex(LanzhouMahjongSessionRecordCodec.encode(snapshot.record))))
            .also { require(it.encodedSizeBytes <= MAX_RECORD_BYTES) }

    internal fun decode(record: GameBackupRecord): LanzhouMahjongStoredSession {
        require(record.kind == KIND && record.fields.size == 4 && record.encodedSizeBytes <= MAX_RECORD_BYTES) {
            "兰州麻将备份字段数量或大小无效"
        }
        val fields = record.fields
        require(fields[0] == "3" && fields[1] == "1") { "兰州麻将备份归属或格式无效" }
        val timestamp = number(fields[2])
        val bytes = unhex(fields[3])
        val parsed = requireNotNull(LanzhouMahjongSessionRecordCodec.decode(bytes)) { "兰州麻将备份存档结构无效" }
        return LanzhouMahjongStoredSession(parsed, timestamp)
    }

    companion object {
        const val KIND = "LANZHOU_MAHJONG_ACTIVE"
        const val MAX_RECORD_BYTES = 65_536
        val SLOT = BackupStorageSlot(BackupStorageTable.ACTIVE_GAME, GameType.LANZHOU_MAHJONG)
        private const val HEX = "0123456789abcdef"

        private fun number(value: String): Long {
            require(value.length in 1..19 && value.all { it in '0'..'9' }) { "兰州麻将备份时间无效" }
            val parsed = requireNotNull(value.toLongOrNull())
            require(parsed.toString() == value) { "兰州麻将备份时间不是规范整数" }
            return parsed
        }

        private fun unhex(value: String): ByteArray {
            require(value.length % 2 == 0 && value.length / 2 in 1..LanzhouMahjongSessionRecordCodec.MAX_BYTES &&
                value.all { it in HEX }) { "兰州麻将备份十六进制非规范或超限" }
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
