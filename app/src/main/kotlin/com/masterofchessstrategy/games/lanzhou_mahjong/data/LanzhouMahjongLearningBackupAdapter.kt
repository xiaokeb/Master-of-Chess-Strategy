package com.masterofchessstrategy.games.lanzhou_mahjong.data

import com.masterofchessstrategy.data.BackupStorageSlot
import com.masterofchessstrategy.data.BackupStorageTable
import com.masterofchessstrategy.data.GameBackupAdapter
import com.masterofchessstrategy.data.GameBackupDescriptor
import com.masterofchessstrategy.data.GameBackupRecord
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.PreparedGameBackup
import com.masterofchessstrategy.engine.GameType

/** Preserve only explicit ten-lesson progress; legacy four-step rows never enter this kind. */
internal class LanzhouMahjongLearningBackupAdapter : GameBackupAdapter {
    override val descriptor = GameBackupDescriptor(KIND, 5, SLOT, 1, 41, 256)

    override suspend fun read(database: MocsDatabase): List<GameBackupRecord> =
        read(database.lanzhouMahjongLearningProgressDao())

    internal suspend fun read(dao: LanzhouMahjongLearningProgressDao): List<GameBackupRecord> =
        decodeLanzhouLearningRows(dao.readBoundedRows())?.let { listOf(encode(it)) } ?: emptyList()

    override fun validate(record: GameBackupRecord, containerVersion: Int) {
        require(containerVersion == 5)
        decode(record)
    }

    override fun prepareRestore(record: GameBackupRecord): PreparedLearningBackup = PreparedLearningBackup(decode(record))

    internal class PreparedLearningBackup(private val snapshot: LanzhouMahjongLearningSnapshot) : PreparedGameBackup {
        override val storageSlot = SLOT
        override suspend fun write(database: MocsDatabase) = write(database.lanzhouMahjongLearningProgressDao())
        internal suspend fun write(dao: LanzhouMahjongLearningProgressDao) { dao.upsert(snapshot.toLearningEntity()) }
    }

    internal fun encode(snapshot: LanzhouMahjongLearningSnapshot) = GameBackupRecord(KIND, listOf(
        "3", "1", snapshot.contentVersion.toString(), snapshot.completedCount.toString(), snapshot.updatedAtEpochMillis.toString(),
    ))

    internal fun decode(record: GameBackupRecord): LanzhouMahjongLearningSnapshot {
        require(record.kind == KIND && record.fields.size == 5 && record.encodedSizeBytes <= descriptor.maximumRecordBytes)
        val fields = record.fields
        require(fields[0] == "3" && fields[1] == "1")
        return LanzhouMahjongLearningSnapshot(integer(fields[2], Int.MAX_VALUE.toLong()).toInt(),
            integer(fields[3], 10).toInt(), integer(fields[4], Long.MAX_VALUE))
    }

    private fun integer(value: String, maximum: Long): Long {
        require(value.matches(Regex("0|[1-9][0-9]{0,18}")))
        return requireNotNull(value.toLongOrNull()).also { require(it <= maximum) }
    }

    companion object {
        const val KIND = "LANZHOU_MAHJONG_LEARNING"
        val SLOT = BackupStorageSlot(BackupStorageTable.LANZHOU_MAHJONG_LEARNING, GameType.LANZHOU_MAHJONG)
    }
}
