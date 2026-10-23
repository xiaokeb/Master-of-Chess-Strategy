package com.masterofchessstrategy.games.go.data

import androidx.room.withTransaction
import com.masterofchessstrategy.data.GameRecord
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.RoomGameRecordRepository
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.go.GoSessionIdentity
import com.masterofchessstrategy.games.go.GoSessionOrigin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** Immutable, read-only archive snapshot. Missing historical provenance stays unknown. */
internal class GoStoredRecord(record: GameRecord, val source: GoSessionIdentity?, val aiSource: GoAiCheckpointArchive? = null) {
    private val owned = record.defensiveCopy()
    val record: GameRecord get() = owned.defensiveCopy()
    val origin: GoSessionOrigin get() = source?.origin ?: GoSessionOrigin.LEGACY_UNKNOWN
}

/** Native record admission belongs to the existing repository; neither load nor replay grants rewards. */
internal class GoStoredRecordLoader(private val database: MocsDatabase) {
    private val records = RoomGameRecordRepository(database.gameRecordDao())

    suspend fun load(recordId: String): GoStoredRecord = withContext(Dispatchers.IO) {
        require(recordId.isNotBlank() && recordId.length <= GameRecord.MAX_RECORD_ID_LENGTH)
        database.withTransaction {
            val record = records.load(recordId) ?: throw IOException("围棋归档不存在或无法读取")
            val source = database.goRecordSourceDao().find(recordId)
            val local = storedGoRecordSnapshot(record, source)
            val association = database.goAiProjectionDao().find(recordId)
            if (association == null) local else {
                require(association.projectionVersion == 1 && source == null) { "围棋棋谱来源关联无效" }
                val raw = requireNotNull(database.goAiCheckpointDao().findArchive(recordId)) { "围棋人机棋谱缺少原始来源" }
                    .let(GoAiCheckpointRows::archive)
                val projection = withContext(Dispatchers.Default) { projectGoAiRecord(raw) }
                requireSameGoAiRecord(record, projection)
                GoStoredRecord(record, null, raw)
            }
        }
    }
}

/** Structural provenance check after the repository's actual completed-history admission. */
internal fun storedGoRecordSnapshot(record: GameRecord, source: GoRecordSourceEntity?): GoStoredRecord {
    require(record.gameType == GameType.GO) { "所选归档不是围棋棋谱" }
    val identity = source?.let {
        require(it.gameTypeCode == GameType.GO.code && it.recordId == record.recordId) {
            "围棋归档来源归属不符"
        }
        val origin = requireNotNull(GoSessionOrigin.entries.singleOrNull { origin -> origin.code == it.originCode }) {
            "围棋归档来源版本不兼容"
        }
        GoSessionIdentity(it.sessionId, origin, it.archiveRevision).also { identity ->
            require(identity.recordId == record.recordId && record.mode == StoredGameMode.LOCAL_TWO_PLAYER &&
                record.difficulty == null && !record.isEndgame && record.playerIndex == 0) {
                "围棋归档身份与本地对局不符"
            }
        }
    }
    return GoStoredRecord(record, identity)
}
