package com.masterofchessstrategy.games.lanzhou_fangqi.data

import com.masterofchessstrategy.data.BackupStorageSlot
import com.masterofchessstrategy.data.BackupStorageTable
import com.masterofchessstrategy.data.GameBackupAdapter
import com.masterofchessstrategy.data.GameBackupDescriptor
import com.masterofchessstrategy.data.GameBackupRecord
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.PreparedGameBackup
import com.masterofchessstrategy.engine.GameType

/** A complete bounded collection; backup restores learning without issuing game rewards. */
internal class FangqiLearningBackupAdapter : GameBackupAdapter {
    override val descriptor = GameBackupDescriptor(KIND, 5, SLOT, 1, 31, MAX_RECORD_BYTES)

    override suspend fun read(database: MocsDatabase): List<GameBackupRecord> =
        read(database.fangqiLearningProgressDao())

    internal suspend fun read(dao: FangqiLearningProgressDao): List<GameBackupRecord> {
        val snapshot = FangqiLearningSnapshot(dao.readAll())
        return if (snapshot.rows.isEmpty()) emptyList() else listOf(encode(snapshot))
    }

    override fun validate(record: GameBackupRecord, containerVersion: Int) {
        require(containerVersion == 5) { "方棋学习备份需要容器版本5" }
        decode(record)
    }

    override fun prepareRestore(record: GameBackupRecord): PreparedLearningBackup =
        PreparedLearningBackup(decode(record))

    internal class PreparedLearningBackup internal constructor(snapshot: FangqiLearningSnapshot) : PreparedGameBackup {
        private val rows = snapshot.rows.toList()
        override val storageSlot = SLOT

        override suspend fun write(database: MocsDatabase) = write(database.fangqiLearningProgressDao())

        /** The repository owns clearing, gates and the single replacement transaction. */
        internal suspend fun write(dao: FangqiLearningProgressDao) {
            rows.forEach { dao.insert(it) }
        }
    }

    internal fun encode(snapshot: FangqiLearningSnapshot): GameBackupRecord {
        require(snapshot.rows.isNotEmpty()) { "空学习集合不应输出备份段" }
        val fields = mutableListOf(GAME_CODE, FORMAT, snapshot.rows.size.toString())
        snapshot.rows.forEach { row ->
            fields += row.contentKey
            fields += row.contentVersion.toString()
            fields += row.completedAtEpochMillis.toString()
        }
        return GameBackupRecord(KIND, fields).also { require(it.encodedSizeBytes <= MAX_RECORD_BYTES) }
    }

    internal fun decode(record: GameBackupRecord): FangqiLearningSnapshot {
        require(record.kind == KIND && record.encodedSizeBytes <= MAX_RECORD_BYTES && record.fields.size >= 6) {
            "方棋学习备份种类或大小无效"
        }
        val fields = record.fields
        require(fields[0] == GAME_CODE && fields[1] == FORMAT) { "方棋学习备份归属或版本无效" }
        val count = integer(fields[2], FangqiLearningCatalog.keys.size.toLong()).toInt()
        require(count >= 1 && fields.size == 3 + 3 * count) { "方棋学习备份记录数量不符" }
        val rows = (0 until count).map { index ->
            val offset = 3 + 3 * index
            FangqiLearningProgressEntity(fields[offset],
                integer(fields[offset + 1], Int.MAX_VALUE.toLong()).toInt(), integer(fields[offset + 2], Long.MAX_VALUE))
        }
        val snapshot = FangqiLearningSnapshot(rows)
        require(rows == snapshot.rows) { "方棋学习备份必须按内容键规范排序" }
        return snapshot
    }

    private fun integer(value: String, maximum: Long): Long {
        require(value.matches(Regex("0|[1-9][0-9]{0,18}"))) { "方棋学习备份数字非规范形式" }
        return requireNotNull(value.toLongOrNull()).also { require(it <= maximum) }
    }

    companion object {
        const val KIND = "FANGQI_LEARNING"
        private const val GAME_CODE = "4"
        private const val FORMAT = "1"
        private const val MAX_RECORD_BYTES = 4096
        val SLOT = BackupStorageSlot(BackupStorageTable.FANGQI_LEARNING, GameType.LANZHOU_SQUARE_CHESS)
    }
}
