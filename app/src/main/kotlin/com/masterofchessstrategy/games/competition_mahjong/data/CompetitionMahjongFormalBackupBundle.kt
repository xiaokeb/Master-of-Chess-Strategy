package com.masterofchessstrategy.games.competition_mahjong.data

import androidx.room.RoomDatabase
import com.masterofchessstrategy.data.GameBackupRecord
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongPlayPolicy
import com.masterofchessstrategy.games.competition_mahjong.match.storage.CompetitionMahjongFormalSnapshotCodec
import com.masterofchessstrategy.games.competition_mahjong.match.storage.room.CompetitionMahjongFormalRoomBackup
import com.masterofchessstrategy.games.competition_mahjong.match.storage.room.CompetitionMahjongFormalRoomBridge
import com.masterofchessstrategy.games.competition_mahjong.match.storage.room.CompetitionMahjongFormalRoomDao
import com.masterofchessstrategy.games.competition_mahjong.match.storage.room.CompetitionMahjongFormalRoomStorage
import java.util.Collections

/** Complete formal facts; legacy COMPETITION_MATCH remains a separate, explicit migration source. */
internal object CompetitionMahjongFormalBackupBundle {
    const val KIND = "COMPETITION_FORMAL"
    const val MAXIMUM_RECORDS = 4
    const val MAXIMUM_RECORD_BYTES = 32 + CompetitionMahjongFormalSnapshotCodec.MAX_BYTES * 2
    private const val LEGACY_KIND = "COMPETITION_MATCH"
    private const val GAME_TYPE = "2"
    private const val HEX = "0123456789abcdef"

    /** Joins the public export transaction. Native admission must run after that transaction ends. */
    fun readInTransaction(database: RoomDatabase, dao: CompetitionMahjongFormalRoomDao): List<GameBackupRecord> {
        check(database.inTransaction()) { "正式备份读取必须加入宿主现有Room事务" }
        return requireStoredViewers(dao).map { viewer ->
            val bytes = requireNotNull(CompetitionMahjongFormalRoomStorage.readMctxInTransaction(database, dao, viewer)) {
                "国标正式备份实际座位归属缺失"
            }
            encode(viewer, bytes)
        }
    }

    /** Inspect every physical key before any payload read or replacement; never filter unknown rows. */
    internal fun requireStoredViewers(dao: CompetitionMahjongFormalRoomDao): List<Int> {
        val viewers = dao.listViewerIds().toList()
        require(viewers.size <= MAXIMUM_RECORDS && viewers.distinct().size == viewers.size &&
            viewers.all { it in 0..3 }) { "国标正式数据存在未知或重复座位，原数据已保留" }
        return viewers.sorted()
    }

    /** Structural validation only: checks ownership and the entire MCTX without creating a Native session. */
    fun normalize(record: GameBackupRecord): GameBackupRecord {
        val (viewer, bytes) = decode(record)
        return encode(viewer, bytes)
    }

    fun encode(viewer: Int, bytes: ByteArray): GameBackupRecord {
        val owned = bytes.copyOf()
        requireNotNull(CompetitionMahjongFormalSnapshotCodec.decode(owned, viewer)) { "国标正式备份局面无效" }
        return GameBackupRecord(KIND, listOf(GAME_TYPE, viewer.toString(), owned.toHex())).also {
            require(it.encodedSizeBytes <= MAXIMUM_RECORD_BYTES) { "国标正式备份记录超过容量限制" }
        }
    }

    /**
     * Call once with the whole container, before any clear/write transaction. Missing viewers become
     * explicit empty MCTX facts, preventing old independent sources from being silently re-imported.
     * trustedPolicy must come from the coordinator's restored/trusted progress, never backup text.
     */
    fun prepareReplacement(records: List<GameBackupRecord>,
                           trustedPolicy: (Int) -> CompetitionMahjongPlayPolicy): CompetitionMahjongFormalRestorePlan {
        require(records.none { it.kind == LEGACY_KIND }) { "旧国标单赛事备份需显式迁移，不可当作正式整库恢复" }
        val owned = records.filter { it.kind == KIND }
        require(owned.size <= MAXIMUM_RECORDS) { "国标正式备份记录过多" }
        val decoded = owned.map(::decode)
        require(decoded.map { it.first }.distinct().size == decoded.size) { "国标正式备份座位重复" }
        val byViewer = decoded.toMap()
        // Admission is eager: a failure in any seat prevents a partially usable restore plan.
        val candidates = (0..3).map { viewer ->
            val policy = trustedPolicy(viewer)
            byViewer[viewer]?.let { CompetitionMahjongFormalRoomBackup.admit(viewer, it, policy) }
                ?: CompetitionMahjongFormalRoomBackup.emptyForRestore(viewer, policy)
        }
        return CompetitionMahjongFormalRestorePlan(candidates)
    }

    private fun decode(record: GameBackupRecord): Pair<Int, ByteArray> {
        require(record.kind == KIND && record.fields.size == 3 && record.fields[0] == GAME_TYPE) {
            "国标正式备份棋种或字段无效"
        }
        require(record.encodedSizeBytes <= MAXIMUM_RECORD_BYTES) { "国标正式备份记录超过容量限制" }
        val viewer = requireNotNull(record.fields[1].toIntOrNull()) { "国标正式备份座位无效" }
        require(viewer in 0..3)
        val text = record.fields[2]
        require(text.length % 2 == 0 && text.length / 2 in 1..CompetitionMahjongFormalSnapshotCodec.MAX_BYTES) {
            "国标正式备份长度异常"
        }
        val bytes = ByteArray(text.length / 2) { index ->
            val high = requireNotNull(text[index * 2].digitToIntOrNull(16)) { "国标正式备份编码无效" }
            val low = requireNotNull(text[index * 2 + 1].digitToIntOrNull(16)) { "国标正式备份编码无效" }
            ((high shl 4) or low).toByte()
        }
        requireNotNull(CompetitionMahjongFormalSnapshotCodec.decode(bytes, viewer)) { "国标正式备份局面无效" }
        return viewer to bytes
    }

    private fun ByteArray.toHex(): String {
        val output = CharArray(size * 2)
        forEachIndexed { index, byte ->
            output[index * 2] = HEX[(byte.toInt() ushr 4) and 15]
            output[index * 2 + 1] = HEX[byte.toInt() and 15]
        }
        return String(output)
    }
}

/** Immutable, fully admitted replacement. It owns no transaction, Native handle or live host. */
internal class CompetitionMahjongFormalRestorePlan internal constructor(candidates: List<CompetitionMahjongFormalRoomBackup>) {
    internal val candidates: List<CompetitionMahjongFormalRoomBackup> = Collections.unmodifiableList(ArrayList(candidates))

    init { require(this.candidates.map { it.viewerPlayerId } == (0..3).toList()) }

    /** Coordinator retires every writer first; publish returned bridges only after outer commit succeeds. */
    fun installInTransaction(database: RoomDatabase, dao: CompetitionMahjongFormalRoomDao): List<CompetitionMahjongFormalRoomBridge> {
        check(database.inTransaction()) { "国标正式整库恢复必须加入宿主现有Room事务" }
        CompetitionMahjongFormalBackupBundle.requireStoredViewers(dao)
        return candidates.map { CompetitionMahjongFormalRoomBridge.installBackupInTransaction(database, dao, it) }
    }
}
