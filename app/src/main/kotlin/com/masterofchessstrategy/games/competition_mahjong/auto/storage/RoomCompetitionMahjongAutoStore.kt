package com.masterofchessstrategy.games.competition_mahjong.auto.storage

import androidx.room.withTransaction
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoArchive
import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoCheckpoint
import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoCheckpointCodec
import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoRunConfig
import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoStore
import com.masterofchessstrategy.games.competition_mahjong.auto.replay.CompetitionMahjongAutoArchiveLibrary
import com.masterofchessstrategy.games.competition_mahjong.auto.replay.CompetitionMahjongAutoArchiveSummary
import com.masterofchessstrategy.games.competition_mahjong.auto.replay.summary
import com.masterofchessstrategy.games.competition_mahjong.auto.storage.CompetitionMahjongAutoRow.Companion.ACTIVE_KEY
import com.masterofchessstrategy.games.competition_mahjong.learning.CompetitionMahjongStorageEpochGate
import java.util.UUID

/** One navigation owner holds one observer slot across the lifetime of its controller. */
internal class RoomCompetitionMahjongAutoStore(
    private val database: MocsDatabase,
    private val viewer: Int,
    private val now: () -> Long = System::currentTimeMillis,
) : CompetitionMahjongAutoStore, CompetitionMahjongAutoArchiveLibrary {
    private val dao = database.competitionMahjongAutoDao()
    private val gate = CompetitionMahjongStorageEpochGate.forDatabase(database)
    private val owner = gate.captureOwner()
    private var loaded = false
    private var observed: ByteArray? = null

    init { require(viewer in 0..3) }

    override suspend fun load(viewerPlayerId: Int): CompetitionMahjongAutoCheckpoint? = access(viewerPlayerId) {
        val row = dao.find(viewer, ACTIVE_KEY)
        val checkpoint = row?.checkpoint()
        observed = row?.payload
        loaded = true
        checkpoint
    }

    override suspend fun save(checkpoint: CompetitionMahjongAutoCheckpoint, archive: CompetitionMahjongAutoArchive?) =
        access(checkpoint.config.viewerPlayerId) {
            check(loaded) { "请先加载自动演局活动" }
            val payload = CompetitionMahjongAutoCheckpointCodec.encode(checkpoint)
            val savedAt = now().also { require(it >= 0) }
            database.withTransaction {
                val current = dao.find(viewer, ACTIVE_KEY)?.payload
                check(current.contentEquals(observed) || current.contentEquals(payload)) { "自动演局活动已变化，请重新打开" }
                if (archive != null) {
                    require(CompetitionMahjongAutoArchive(checkpoint).sameContent(archive)) { "归档必须对应本次完成的赛事" }
                    val existing = dao.find(viewer, archive.key)
                    if (existing == null) {
                        dao.insert(CompetitionMahjongAutoRow(viewer, archive.key, savedAt, false, payload))
                    } else {
                        require(existing.archive().sameContent(archive)) { "同一赛事已有不同的归档，原记录已保留" }
                    }
                }
                if (!current.contentEquals(payload)) {
                    dao.upsert(CompetitionMahjongAutoRow(viewer, ACTIVE_KEY, savedAt, false, payload))
                }
            }
            observed = payload
        }

    override suspend fun list(viewerPlayerId: Int): List<CompetitionMahjongAutoArchiveSummary> = access(viewerPlayerId) {
        database.withTransaction {
            dao.archiveKeys(viewer).map { key ->
                val row = checkNotNull(dao.find(viewer, key))
                row.archive().summary(row.savedAtEpochMillis, row.favorite)
            }
        }
    }

    override suspend fun load(key: String, viewerPlayerId: Int): CompetitionMahjongAutoArchive? = access(viewerPlayerId) {
        requireArchiveKey(key)
        dao.find(viewer, key)?.archive()
    }

    override suspend fun setFavorite(key: String, viewerPlayerId: Int, favorite: Boolean): Unit = access(viewerPlayerId) {
        requireArchiveKey(key)
        database.withTransaction {
            requireNotNull(dao.find(viewer, key)).archive()
            check(dao.setFavorite(viewer, key, favorite) == 1)
        }
    }

    override suspend fun delete(key: String, viewerPlayerId: Int): Unit = access(viewerPlayerId) {
        requireArchiveKey(key)
        database.withTransaction {
            requireNotNull(dao.find(viewer, key)).archive()
            check(dao.delete(viewer, key) == 1)
        }
    }

    private suspend fun <T> access(viewerPlayerId: Int, block: suspend () -> T): T {
        require(viewerPlayerId == viewer) { "自动演局观察者不一致" }
        return gate.access(owner, block)
    }

    private fun requireArchiveKey(key: String) {
        val run = key.substringBefore('/')
        val series = key.substringAfter('/', "").toIntOrNull()
        require(series != null && series in 0 until CompetitionMahjongAutoRunConfig.MAX_SERIES &&
            UUID.fromString(run).toString() == run && key == "$run/$series") { "自动赛事归档标识无效" }
    }
}
