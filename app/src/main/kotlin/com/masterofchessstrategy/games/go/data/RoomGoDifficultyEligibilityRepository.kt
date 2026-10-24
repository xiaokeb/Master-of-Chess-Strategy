package com.masterofchessstrategy.games.go.data

import androidx.room.withTransaction
import com.masterofchessstrategy.data.LoadMatchStatisticsResult
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.RoomMatchStatisticsRepository
import com.masterofchessstrategy.games.go.GoDifficultyEligibility
import com.masterofchessstrategy.games.go.GoDifficultyEligibilityRepository
import java.io.IOException

/** The production implementation must include migration and statistics in one Room transaction. */
internal fun interface GoDifficultyFactsStore {
    suspend fun readConsistently(): GoDifficultyEligibility
}

internal class RoomGoDifficultyEligibilityRepository(
    private val store: GoDifficultyFactsStore,
    private val gate: GoStorageEpochGate,
    private val ownerEpoch: Long = gate.epoch.value,
) : GoDifficultyEligibilityRepository {
    constructor(database: MocsDatabase, legacy: GoLearningLegacySource? = null,
        gate: GoStorageEpochGate = GoStorageEpochGate.forDatabase(database),
    ) : this(RoomGoDifficultyFactsStore(database, legacy), gate)

    override val isCurrent: Boolean get() = gate.epoch.value == ownerEpoch
    override suspend fun load(): GoDifficultyEligibility = gate.access(ownerEpoch) { store.readConsistently() }
}

private class RoomGoDifficultyFactsStore(
    private val database: MocsDatabase,
    legacy: GoLearningLegacySource?,
) : GoDifficultyFactsStore {
    // Ungated delegates: the repository above acquires the non-reentrant gate exactly once.
    private val learning = RoomGoLearningProgressRepository(database, legacy)
    private val statistics = RoomMatchStatisticsRepository(database)

    override suspend fun readConsistently(): GoDifficultyEligibility = database.withTransaction {
        val progress = learning.load()
        val stats = when (val result = statistics.load()) {
            is LoadMatchStatisticsResult.Loaded -> result.statistics
            LoadMatchStatisticsResult.Incompatible -> throw IOException("战绩数据不兼容，围棋解锁状态未更新")
        }
        // A statistics failure rolls back any first-read learning migration and its fence as well.
        GoDifficultyEligibility.from(progress, stats.winsByGameAndDifficulty)
    }
}
