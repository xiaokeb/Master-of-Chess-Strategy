package com.masterofchessstrategy.data

import com.masterofchessstrategy.endgame.ChineseChessEndgameLevel
import com.masterofchessstrategy.endgame.ChineseChessEndgamePack
import com.masterofchessstrategy.engine.Difficulty

internal data class CompletedEndgameLevel(
    val levelId: String,
    val difficulty: Difficulty,
    val bestPlayerMoves: Int,
    val starsAwarded: Int,
    val scoreAwarded: Int,
    val completedAtEpochMillis: Long,
)

internal data class EndgameProgress(
    val completedById: Map<String, CompletedEndgameLevel> = emptyMap(),
) {
    val totalStars: Int = completedById.values.sumOf(CompletedEndgameLevel::starsAwarded)
    val totalScore: Int = completedById.values.sumOf(CompletedEndgameLevel::scoreAwarded)
}

internal sealed interface LoadEndgameProgressResult {
    data class Loaded(val progress: EndgameProgress) : LoadEndgameProgressResult

    data object Incompatible : LoadEndgameProgressResult
}

internal data class CompleteEndgameResult(
    val firstCompletion: Boolean,
    val bestMovesImproved: Boolean,
    val awardedStars: Int,
    val awardedScore: Int,
)

internal interface EndgameProgressRepository {
    suspend fun load(): LoadEndgameProgressResult

    suspend fun complete(
        level: ChineseChessEndgameLevel,
        playerMoves: Int,
        completedAtEpochMillis: Long,
    ): CompleteEndgameResult
}

internal class RoomEndgameProgressRepository(
    private val dao: EndgameProgressDao,
    private val pack: ChineseChessEndgamePack,
) : EndgameProgressRepository {
    private val levelsById = pack.levels.associateBy(ChineseChessEndgameLevel::id)

    override suspend fun load(): LoadEndgameProgressResult {
        val decoded = dao.listAll().map { entity ->
            val level = levelsById[entity.levelId] ?: return LoadEndgameProgressResult.Incompatible
            if (
                entity.contentVersion !in 1..pack.version ||
                entity.difficultyCode != level.difficulty.code ||
                entity.bestPlayerMoves !in 1..level.maxPlayerMoves ||
                entity.starsAwarded !in 1..level.starReward ||
                entity.scoreAwarded !in 1..level.scoreReward ||
                entity.completedAtEpochMillis < 0L
            ) {
                return LoadEndgameProgressResult.Incompatible
            }
            CompletedEndgameLevel(
                levelId = entity.levelId,
                difficulty = level.difficulty,
                bestPlayerMoves = entity.bestPlayerMoves,
                starsAwarded = entity.starsAwarded,
                scoreAwarded = entity.scoreAwarded,
                completedAtEpochMillis = entity.completedAtEpochMillis,
            )
        }
        return LoadEndgameProgressResult.Loaded(
            EndgameProgress(decoded.associateBy(CompletedEndgameLevel::levelId)),
        )
    }

    override suspend fun complete(
        level: ChineseChessEndgameLevel,
        playerMoves: Int,
        completedAtEpochMillis: Long,
    ): CompleteEndgameResult {
        require(levelsById[level.id] == level) { "Level does not belong to this pack" }
        require(playerMoves in 1..level.maxPlayerMoves) { "Completion exceeds move limit" }
        require(completedAtEpochMillis >= 0L)
        val inserted = dao.insert(
            EndgameProgressEntity(
                levelId = level.id,
                contentVersion = pack.version,
                difficultyCode = level.difficulty.code,
                bestPlayerMoves = playerMoves,
                starsAwarded = level.starReward,
                scoreAwarded = level.scoreReward,
                completedAtEpochMillis = completedAtEpochMillis,
            ),
        ) != INSERT_IGNORED
        if (inserted) {
            return CompleteEndgameResult(
                firstCompletion = true,
                bestMovesImproved = true,
                awardedStars = level.starReward,
                awardedScore = level.scoreReward,
            )
        }
        val improved = dao.updateBest(level.id, playerMoves) == 1
        return CompleteEndgameResult(
            firstCompletion = false,
            bestMovesImproved = improved,
            awardedStars = 0,
            awardedScore = 0,
        )
    }

    private companion object {
        const val INSERT_IGNORED = -1L
    }
}
