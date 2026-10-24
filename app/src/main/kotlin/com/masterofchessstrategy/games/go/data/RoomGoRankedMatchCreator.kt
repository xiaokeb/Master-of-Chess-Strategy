package com.masterofchessstrategy.games.go.data

import androidx.room.withTransaction
import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.games.go.*
import java.util.UUID
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
            persistNewInTransaction(match, start, checkExpected = false, expectedActive = null, abandonOngoing = false)
            start
        }
    }

    /** Only the setup owner calls this while holding its single epoch gate and Room transaction.
     * It shares creation/eligibility checks with create(), not its non-reentrant gate.
     */
    internal suspend fun createEntryInTransaction(
        match: GoAiSavedMatch,
        expectedActive: GoAiSavedMatch?,
        abandonOngoing: Boolean,
    ) {
        check(database.inTransaction())
        require(runCatching { UUID.fromString(match.matchId).toString() }.getOrNull() == match.matchId) {
            "围棋新局需要全新规范身份"
        }
        val start = withContext(worker) {
            if (!match.config.automatic && match.config.handicapStones == 0) admitGoRankedStart(match, clock())
            else {
                val admitted = admitGoAiCheckpoint(match)
                require(admitted.phase == GoPhase.PLAYING && admitted.eventCount == 0 &&
                    !match.hasSpecifiedOpening && !match.completionReserved && match.undoUsed == 0 && match.hintUsed == 0 &&
                    !match.autoContinueEnabled && match.autoContinueGameNumber == 1 && match.autoContinueMaxGames == 1 &&
                    GoAiMatchFileCodec.encode(admitted.match()).contentEquals(GoAiMatchFileCodec.encode(match))) {
                    "围棋训练新局必须使用全新初态"
                }
                null
            }
        }
        persistNewInTransaction(match, start, checkExpected = true, expectedActive, abandonOngoing)
    }

    internal suspend fun eligibilityInTransaction(): GoDifficultyEligibility {
        check(database.inTransaction())
        val progress = learning.load()
        val statistics = when (val result = RoomMatchStatisticsRepository(database).load()) {
            is LoadMatchStatisticsResult.Loaded -> result.statistics
            LoadMatchStatisticsResult.Incompatible -> error("围棋成长账本不兼容")
        }
        return GoDifficultyEligibility.from(progress, statistics.winsByGameAndDifficulty)
    }

    private suspend fun persistNewInTransaction(
        match: GoAiSavedMatch,
        start: GoRankedStart?,
        checkExpected: Boolean,
        expectedActive: GoAiSavedMatch?,
        abandonOngoing: Boolean,
    ) {
        val recordId = start?.recordId ?: withContext(worker) { admitGoAiCheckpoint(match).recordId }
        require(database.goRankedDao().start(recordId) == null &&
            database.goAiCheckpointDao().findArchive(recordId) == null &&
            database.gameRecordDao().find(recordId) == null &&
            database.matchOutcomeDao().find(recordId) == null) { "围棋正式新局身份已使用" }
        val previous = checkpoints.load()
        if (checkExpected) require(sameCheckpoint(previous, expectedActive)) { "当前围棋对局已改变，请重新确认" }
        require(previous?.matchId != match.matchId) { "既有围棋局不能补发正式新局来源" }
        if (previous != null) {
            val admitted = withContext(worker) { admitGoAiCheckpoint(previous) }
            if (admitted.phase == GoPhase.FINISHED) {
                require(previous.completionReserved &&
                    database.goAiProjectionDao().find(admitted.recordId) == GoAiProjectionEntity(admitted.recordId)) {
                    "请先完成并保存当前围棋对局"
                }
            } else require(checkExpected && abandonOngoing) { "请先完成并保存当前围棋对局" }
        }
        val difficulty = when (match.config.difficulty) {
            GoAiDifficulty.SIMPLE -> Difficulty.EASY
            GoAiDifficulty.MEDIUM -> Difficulty.MEDIUM
            GoAiDifficulty.HARD -> Difficulty.HARD
            GoAiDifficulty.MASTER -> Difficulty.MASTER
        }
        require(eligibilityInTransaction().condition(difficulty).isUnlocked) { "围棋教程或对应难度胜场尚未达标" }
        // Clear only this active slot, after every admission check and in the same transaction.
        // Archives, completed projections and old creation facts are never deleted here.
        if (previous != null && checkExpected && abandonOngoing)
            database.goAiCheckpointDao().clearActive(GameType.GO.code)
        checkpoints.save(match)
        val active = requireNotNull(database.goAiCheckpointDao().findActive(GameType.GO.code))
            .let(GoAiCheckpointRows::entry)
        require(active.lineage == GoAiCheckpointLineage(GoAiCheckpointOrigin.UNVERIFIED_SESSION) &&
            active.copyBytes().contentEquals(GoAiMatchFileCodec.encode(match))) { "正式新局未与活动快照原子绑定" }
        start?.let { database.goRankedDao().insertStart(it.toEntity()) }
        beforeCommit()
        currentCoroutineContext().ensureActive()
    }

    private fun sameCheckpoint(first: GoAiSavedMatch?, second: GoAiSavedMatch?): Boolean =
        if (first == null || second == null) first == null && second == null
        else GoAiMatchFileCodec.encode(first).contentEquals(GoAiMatchFileCodec.encode(second))
}

/** Old Go outcomes remain exportable, but only the paired formal ledger contributes to growth. */
internal suspend fun MocsDatabase.qualifiedMatchOutcomeRows(): List<MatchOutcomeEntity> = withTransaction {
    requireGoAiProjectionParents()
    requireGoRankedParents()
    val ids = goRankedDao().settlements().map { it.recordId }.toSet()
    matchOutcomeDao().listAll().filter { it.gameTypeCode != GameType.GO.code || it.matchId in ids }
}
