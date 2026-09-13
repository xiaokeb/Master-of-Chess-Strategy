package com.masterofchessstrategy.data

import com.masterofchessstrategy.engine.GameType

internal data class TutorialProgress(
    val gameType: GameType,
    val contentVersion: Int,
    val completedStepCount: Int,
    val isCompleted: Boolean,
    val updatedAtEpochMillis: Long,
) {
    init {
        require(contentVersion == CURRENT_CONTENT_VERSION) {
            "Unsupported tutorial content version"
        }
        require(completedStepCount in 0..TOTAL_STEP_COUNT) {
            "Tutorial step count is outside the supported range"
        }
        require(isCompleted == (completedStepCount == TOTAL_STEP_COUNT)) {
            "Tutorial completion must match its step count"
        }
    }

    companion object {
        const val CURRENT_CONTENT_VERSION = 1
        const val TOTAL_STEP_COUNT = 4

        fun empty(gameType: GameType): TutorialProgress =
            TutorialProgress(
                gameType = gameType,
                contentVersion = CURRENT_CONTENT_VERSION,
                completedStepCount = 0,
                isCompleted = false,
                updatedAtEpochMillis = 0L,
            )
    }
}

internal sealed interface LoadTutorialProgressResult {
    data object NotFound : LoadTutorialProgressResult

    data class Loaded(val progress: TutorialProgress) : LoadTutorialProgressResult

    data object Incompatible : LoadTutorialProgressResult
}

internal interface TutorialProgressRepository {
    suspend fun load(gameType: GameType): LoadTutorialProgressResult

    suspend fun save(progress: TutorialProgress)

    suspend fun clear(gameType: GameType)
}

internal class RoomTutorialProgressRepository(
    private val dao: TutorialProgressDao,
) : TutorialProgressRepository {
    override suspend fun load(gameType: GameType): LoadTutorialProgressResult {
        val entity = dao.find(gameType.code) ?: return LoadTutorialProgressResult.NotFound
        if (
            entity.gameTypeCode != gameType.code ||
            entity.contentVersion != TutorialProgress.CURRENT_CONTENT_VERSION ||
            entity.completedStepCount !in 0..TutorialProgress.TOTAL_STEP_COUNT ||
            entity.isCompleted !=
            (entity.completedStepCount == TutorialProgress.TOTAL_STEP_COUNT)
        ) {
            return LoadTutorialProgressResult.Incompatible
        }
        return LoadTutorialProgressResult.Loaded(
            TutorialProgress(
                gameType = gameType,
                contentVersion = entity.contentVersion,
                completedStepCount = entity.completedStepCount,
                isCompleted = entity.isCompleted,
                updatedAtEpochMillis = entity.updatedAtEpochMillis,
            ),
        )
    }

    override suspend fun save(progress: TutorialProgress) {
        dao.upsert(
            TutorialProgressEntity(
                gameTypeCode = progress.gameType.code,
                contentVersion = progress.contentVersion,
                completedStepCount = progress.completedStepCount,
                isCompleted = progress.isCompleted,
                updatedAtEpochMillis = progress.updatedAtEpochMillis,
            ),
        )
    }

    override suspend fun clear(gameType: GameType) {
        dao.delete(gameType.code)
    }
}
