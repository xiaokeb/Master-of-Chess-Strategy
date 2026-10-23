package com.masterofchessstrategy.games.go.data

import androidx.room.withTransaction
import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.games.go.*
import kotlinx.coroutines.*

/** Explicit fresh-game operation. Ordinary load/save and legacy migration never call this. */
internal class RoomGoRankedMatchCreator(
    private val database: MocsDatabase,
    private val checkpoints: GoAiMatchRepository,
    private val learning: GoLearningProgressRepository,
    private val gate: GoStorageEpochGate = GoStorageEpochGate.forDatabase(database),
    private val ownerEpoch: Long = gate.epoch.value,
    private val worker: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = System::currentTimeMillis,
    private val beforeCommit: suspend () -> Unit = {},
) {
    // Both supplied repositories must be ungated: this owner acquires the epoch gate once.
    suspend fun create(match: GoAiSavedMatch): GoRankedStart = gate.access(ownerEpoch) {
        val start = withContext(worker) { admitGoRankedStart(match, clock()) }
        database.withTransaction {
            require(database.goRankedDao().start(start.recordId) == null &&
                database.goAiCheckpointDao().findArchive(start.recordId) == null &&
                database.gameRecordDao().find(start.recordId) == null &&
                database.matchOutcomeDao().find(start.recordId) == null) { "围棋正式新局身份已使用" }
            val previous = checkpoints.load()
            require(previous?.matchId != match.matchId) { "既有围棋局不能补发正式新局来源" }
            if (previous != null) {
                val admitted = withContext(worker) { admitGoAiCheckpoint(previous) }
                require(admitted.phase == GoPhase.FINISHED && previous.completionReserved &&
                    database.goAiProjectionDao().find(admitted.recordId) == GoAiProjectionEntity(admitted.recordId)) {
                    "请先完成并保存当前围棋对局"
                }
            }
            val progress = learning.load()
            val statistics = when (val result = RoomMatchStatisticsRepository(database).load()) {
                is LoadMatchStatisticsResult.Loaded -> result.statistics
                LoadMatchStatisticsResult.Incompatible -> error("围棋成长账本不兼容")
            }
            val difficulty = when (match.config.difficulty) {
                GoAiDifficulty.SIMPLE -> Difficulty.EASY
                GoAiDifficulty.MEDIUM -> Difficulty.MEDIUM
                GoAiDifficulty.HARD -> Difficulty.HARD
                GoAiDifficulty.MASTER -> Difficulty.MASTER
            }
            val (preceding, requiredWins) = when (difficulty) {
                Difficulty.EASY -> Difficulty.EASY to 0
                Difficulty.MEDIUM -> Difficulty.EASY to 10
                Difficulty.HARD -> Difficulty.MEDIUM to 15
                Difficulty.MASTER -> Difficulty.HARD to 20
            }
            require(GoLearningLibrary.lessons.all { it.id in progress.completedLessonIds } &&
                statistics.winsAt(GameType.GO, preceding) >= requiredWins) {
                "围棋教程或对应难度胜场尚未达标"
            }
            checkpoints.save(match)
            val active = requireNotNull(database.goAiCheckpointDao().findActive(GameType.GO.code))
                .let(GoAiCheckpointRows::entry)
            require(active.lineage == GoAiCheckpointLineage(GoAiCheckpointOrigin.UNVERIFIED_SESSION) &&
                active.copyBytes().contentEquals(start.copyBytes())) { "正式新局未与活动快照原子绑定" }
            database.goRankedDao().insertStart(start.toEntity())
            beforeCommit()
            currentCoroutineContext().ensureActive()
            start
        }
    }
}

/** Old Go outcomes remain exportable, but only the paired formal ledger contributes to growth. */
internal suspend fun MocsDatabase.qualifiedMatchOutcomeRows(): List<MatchOutcomeEntity> = withTransaction {
    requireGoAiProjectionParents()
    requireGoRankedParents()
    val ids = goRankedDao().settlements().map { it.recordId }.toSet()
    matchOutcomeDao().listAll().filter { it.gameTypeCode != GameType.GO.code || it.matchId in ids }
}
