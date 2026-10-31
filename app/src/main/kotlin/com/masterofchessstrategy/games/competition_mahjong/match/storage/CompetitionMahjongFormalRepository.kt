package com.masterofchessstrategy.games.competition_mahjong.match.storage

import android.content.Context
import com.masterofchessstrategy.games.competition_mahjong.history.*
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchCheckpoint
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchStore
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongPlayPolicy
import com.masterofchessstrategy.games.competition_mahjong.statistics.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

/** Explicit integration option: all formal persistence views share a single atomic transaction. */
class CompetitionMahjongFormalRepository internal constructor(
    private val storage: CompetitionMahjongFormalStorage,
    val viewerPlayerId: Int,
    private val playPolicy: CompetitionMahjongPlayPolicy,
    private val admitCheckpoint: suspend (CompetitionMahjongMatchCheckpoint) -> Unit,
    private val clockMillis: () -> Long = System::currentTimeMillis,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    constructor(context: Context, viewerPlayerId: Int, playPolicy: CompetitionMahjongPlayPolicy,
                admitCheckpoint: suspend (CompetitionMahjongMatchCheckpoint) -> Unit) : this(
        CompetitionMahjongFormalFileStorage(context.applicationContext.filesDir, viewerPlayerId),
        viewerPlayerId, playPolicy, admitCheckpoint)

    init { require(viewerPlayerId in 0..3) }

    val migration = CompetitionMahjongFormalMigration(storage, viewerPlayerId, playPolicy, admitCheckpoint, dispatcher)

    val matchStore: CompetitionMahjongMatchStore = object : CompetitionMahjongMatchStore {
        override suspend fun load(viewerPlayerId: Int): CompetitionMahjongMatchCheckpoint? =
            query(viewerPlayerId) { it.active }

        override suspend fun save(checkpoint: CompetitionMahjongMatchCheckpoint) {
            require(checkpoint.config.viewerPlayerId == viewerPlayerId)
            playPolicy.requireAuthorized(checkpoint.config.botLevel, checkpoint.assistance)
            change(viewerPlayerId) { current ->
                admitCheckpoint(checkpoint)
                current.save(checkpoint, playPolicy, clockMillis())
            }
        }
    }

    /** Derived results can only change through a validated MCMT save. */
    val statisticsStore: CompetitionMahjongStatisticsStore = object : CompetitionMahjongStatisticsStore {
        override suspend fun load(viewerPlayerId: Int): CompetitionMahjongStatistics =
            query(viewerPlayerId) { it.statistics }

        override suspend fun record(outcome: CompetitionMahjongSeriesOutcome): CompetitionMahjongRecordStatisticsResult =
            throw UnsupportedOperationException("统一仓库仅通过正式赛事保存结算统计")

        override suspend fun withdraw(viewerPlayerId: Int, matchId: String): CompetitionMahjongStatistics =
            throw UnsupportedOperationException("统一仓库仅通过正式赛事撤回统计")
    }

    val historyStore: CompetitionMahjongMatchHistoryStore = object : CompetitionMahjongMatchHistoryStore {
        override suspend fun load(viewerPlayerId: Int): CompetitionMahjongMatchHistory =
            query(viewerPlayerId) { it.history }

        override suspend fun setFavorite(viewerPlayerId: Int, matchId: String, favorite: Boolean): CompetitionMahjongMatchHistory =
            change(viewerPlayerId) { it.favorite(matchId, favorite) }.history

        override suspend fun delete(viewerPlayerId: Int, matchId: String): CompetitionMahjongMatchHistory =
            change(viewerPlayerId) { it.delete(matchId) }.history

        override suspend fun record(entry: CompetitionMahjongMatchHistoryEntry): CompetitionMahjongRecordHistoryResult =
            throw UnsupportedOperationException("统一仓库仅通过正式赛事保存归档")

        override suspend fun withdraw(viewerPlayerId: Int, matchId: String): CompetitionMahjongMatchHistory =
            throw UnsupportedOperationException("统一仓库仅通过正式赛事撤回归档")
    }

    private suspend fun <T> query(viewer: Int, select: (CompetitionMahjongFormalSnapshot) -> T): T =
        withContext(dispatcher) {
            require(viewer == viewerPlayerId) { "观察玩家归属不一致" }
            storage.mutex.withLock { select(read()) }
        }

    private suspend fun change(viewer: Int,
                               transform: suspend (CompetitionMahjongFormalSnapshot) -> CompetitionMahjongFormalSnapshot):
        CompetitionMahjongFormalSnapshot = withContext(dispatcher) {
        require(viewer == viewerPlayerId) { "观察玩家归属不一致" }
        storage.mutex.withLock {
            val current = read()
            val next = transform(current)
            val bytes = CompetitionMahjongFormalSnapshotCodec.encode(next)
            if (!bytes.contentEquals(CompetitionMahjongFormalSnapshotCodec.encode(current))) storage.replace(bytes)
            next
        }
    }

    private suspend fun read(): CompetitionMahjongFormalSnapshot {
        val bytes = storage.read() ?: return CompetitionMahjongFormalSnapshot(viewerPlayerId)
        val snapshot = CompetitionMahjongFormalSnapshotCodec.decode(bytes, viewerPlayerId)
            ?: throw IOException("国标麻将正式事务存档损坏或版本不兼容，原数据已保留")
        snapshot.active?.let {
            playPolicy.requireAuthorized(it.config.botLevel, it.assistance)
            admitCheckpoint(it)
        }
        return snapshot
    }
}
