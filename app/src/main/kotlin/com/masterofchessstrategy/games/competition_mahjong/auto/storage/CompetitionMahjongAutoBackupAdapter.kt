package com.masterofchessstrategy.games.competition_mahjong.auto.storage

import com.masterofchessstrategy.data.BackupStorageSlot
import com.masterofchessstrategy.data.BackupStorageTable
import com.masterofchessstrategy.data.GameBackupAdapter
import com.masterofchessstrategy.data.GameBackupContainerPreparation
import com.masterofchessstrategy.data.GameBackupDescriptor
import com.masterofchessstrategy.data.GameBackupPreparationContext
import com.masterofchessstrategy.data.GameBackupRecord
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.PreparedGameBackup
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoAdmission
import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoArchive
import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoCheckpointCodec
import com.masterofchessstrategy.games.competition_mahjong.auto.storage.CompetitionMahjongAutoRow.Companion.ACTIVE_KEY
import kotlin.io.encoding.Base64

/** The whole backup candidate is admitted before any existing automatic activity is cleared. */
internal class CompetitionMahjongAutoBackupAdapter : GameBackupAdapter, GameBackupContainerPreparation {
    override val descriptor = GameBackupDescriptor(
        KIND, 5, SLOT, 200_000, 44, ((CompetitionMahjongAutoCheckpointCodec.MAX_BYTES + 2) / 3) * 4 + 128,
    )

    override suspend fun read(database: MocsDatabase): List<GameBackupRecord> =
        mapStoredRows(database) { row ->
            row.checkpoint()
            GameBackupRecord(KIND, listOf(
                GameType.STANDARD_MAHJONG.code.toString(), "1", row.viewerPlayerId.toString(),
                row.recordKey.ifEmpty { "-" }, row.savedAtEpochMillis.toString(),
                if (row.favorite) "1" else "0", Base64.encode(row.payload),
            ))
        }

    override fun validate(record: GameBackupRecord, containerVersion: Int) {
        require(containerVersion == 5)
        decode(record).checkpoint()
    }

    override fun prepareRestore(record: GameBackupRecord): PreparedGameBackup =
        error("自动演局需要完整备份容器")

    override fun prepareContainer(records: List<GameBackupRecord>, context: GameBackupPreparationContext): PreparedGameBackup {
        val rows = records.filter { it.kind == KIND }.map(::decode)
        val byKey = rows.associateBy { CompetitionMahjongAutoRowKey(it.viewerPlayerId, it.recordKey) }
        require(byKey.size == rows.size) { "备份包含重复的自动演局记录" }
        val admission = CompetitionMahjongAutoAdmission()
        for (row in rows) {
            val checkpoint = row.checkpoint()
            if (row.recordKey == ACTIVE_KEY) {
                val archiveKey = CompetitionMahjongAutoRowKey(row.viewerPlayerId,
                    "${checkpoint.config.runId}/${checkpoint.seriesIndex}")
                byKey[archiveKey]?.let { archive ->
                    require(CompetitionMahjongAutoArchive(checkpoint).sameContent(archive.archive())) {
                        "当前自动演局和归档的赛果不一致"
                    }
                }
            }
            admission.validateHistory(checkpoint)
            if (checkpoint.ledger.completedHandCount == checkpoint.activeHandIndex) {
                val config = checkpoint.config.handConfig(
                    checkpoint.seriesIndex, checkpoint.activeHandIndex, checkpoint.openingRecord != null,
                )
                admission.create(config).use { admission.restoreCurrent(it, checkpoint) }
            }
        }
        return Prepared(rows)
    }

    private fun decode(record: GameBackupRecord): CompetitionMahjongAutoRow {
        val fields = record.fields
        require(record.kind == KIND && record.encodedSizeBytes <= descriptor.maximumRecordBytes && fields.size == 7 &&
            fields[0] == GameType.STANDARD_MAHJONG.code.toString() && fields[1] == "1" &&
            fields[2].length == 1 && fields[2][0] in '0'..'3' && fields[3].isNotEmpty() && fields[5] in listOf("0", "1"))
        val savedAt = requireNotNull(fields[4].toLongOrNull())
        require(savedAt >= 0 && savedAt.toString() == fields[4])
        val payload = Base64.decode(fields[6])
        require(Base64.encode(payload) == fields[6]) { "自动演局备份编码不规范" }
        return CompetitionMahjongAutoRow(fields[2].toInt(), if (fields[3] == "-") ACTIVE_KEY else fields[3],
            savedAt, fields[5] == "1", payload)
    }

    private class Prepared(private val rows: List<CompetitionMahjongAutoRow>) : PreparedGameBackup {
        override val storageSlot = SLOT
        override suspend fun write(database: MocsDatabase) {
            check(database.inTransaction())
            rows.forEach { database.competitionMahjongAutoDao().insert(it) }
        }
    }

    companion object {
        private const val KIND = "COMPETITION_AUTO"
        private val SLOT = BackupStorageSlot(BackupStorageTable.COMPETITION_AUTO, GameType.STANDARD_MAHJONG)

        suspend fun requireStoredRecords(database: MocsDatabase) {
            mapStoredRows(database) { it.checkpoint(); Unit }
        }

        private suspend fun <T> mapStoredRows(database: MocsDatabase, transform: (CompetitionMahjongAutoRow) -> T): List<T> {
            check(database.inTransaction())
            val dao = database.competitionMahjongAutoDao()
            return dao.keys().map { key -> transform(checkNotNull(dao.find(key.viewerPlayerId, key.recordKey))) }
        }
    }
}
