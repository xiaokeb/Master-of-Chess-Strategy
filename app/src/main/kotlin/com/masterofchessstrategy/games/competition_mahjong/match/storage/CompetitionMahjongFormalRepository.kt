package com.masterofchessstrategy.games.competition_mahjong.match.storage

import android.content.Context
import com.masterofchessstrategy.games.competition_mahjong.history.*
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongBotStrategy
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
    private val capturePrivateReplay: Boolean = false,
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
                val next = current.save(checkpoint, playPolicy, clockMillis())
                val records = captureSettledHands(current, checkpoint)
                require(!capturePrivateReplay || records != null ||
                    checkpoint.config.botStrategy == CompetitionMahjongBotStrategy.LEGACY_BATCH) {
                    "逐动作策略赛事缺少私有证据"
                }
                next.withPrivateEvidence(records, retainSettledSeries(current, next, checkpoint, records))
            }
        }
    }

    private fun retainSettledSeries(
        current: CompetitionMahjongFormalSnapshot,
        next: CompetitionMahjongFormalSnapshot,
        checkpoint: CompetitionMahjongMatchCheckpoint,
        records: List<ByteArray>?,
    ): List<CompetitionMahjongArchivedSeries> {
        val previous = current.active
        val archive = next.archivedSeries
        // An older v2 installation may already have a complete current series at replacement time.
        val settled = if (previous != null && previous.config.matchId != checkpoint.config.matchId &&
            previous.ledger.isComplete) previous else checkpoint
        if (!settled.ledger.isComplete || settled.config.botStrategy != CompetitionMahjongBotStrategy.POSITION_SEEDED_V1)
            return archive
        if (archive.any { it.config.matchId == settled.config.matchId }) {
            if (settled === checkpoint && previous != null && previous.config == checkpoint.config) {
                require(checkpoint.engineRecord.contentEquals(previous.engineRecord)) { "完成赛事需先撤回才能修改" }
            }
            return archive
        }
        val prefix = if (settled === checkpoint) records else current.settledHandRecords
        return archive + CompetitionMahjongPrivateSeriesReplay.archive(settled, checkNotNull(prefix))
    }

    private fun captureSettledHands(
        current: CompetitionMahjongFormalSnapshot,
        checkpoint: CompetitionMahjongMatchCheckpoint,
    ): List<ByteArray>? {
        val previous = current.active
        if (previous == null || previous.config.matchId != checkpoint.config.matchId) {
            return if (capturePrivateReplay && checkpoint.activeHandIndex == 0 && checkpoint.ledger.completedHandCount == 0)
                emptyList() else null
        }
        val records = current.settledHandRecords ?: return null
        val handIndex = checkpoint.activeHandIndex
        if (handIndex > previous.activeHandIndex) {
            val record = previous.engineRecord
            CompetitionMahjongPrivateSeriesReplay.verifyResult(previous, previous.activeHandIndex, record)
            return records + listOf(record)
        }
        if (checkpoint.ledger.isComplete) {
            val record = checkpoint.engineRecord
            if (!previous.ledger.isComplete || !record.contentEquals(previous.engineRecord)) {
                CompetitionMahjongPrivateSeriesReplay.verifyResult(checkpoint, handIndex, record)
            }
        }
        return records
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
