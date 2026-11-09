package com.masterofchessstrategy.games.competition_mahjong.match.storage

import com.masterofchessstrategy.data.MatchOutcome
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongAssistanceUsage
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongPlayPolicy
import com.masterofchessstrategy.games.competition_mahjong.statistics.CompetitionMahjongSeriesOutcome

internal data class CompetitionMahjongQualifiedGrowth(
    val policy: CompetitionMahjongPlayPolicy,
    val outcomes: List<MatchOutcome>,
)

/** The local writer and backup admission are responsible for proving the archive before it reaches this projection. */
internal fun CompetitionMahjongFormalSnapshot.qualifiedGrowth(base: CompetitionMahjongPlayPolicy): CompetitionMahjongQualifiedGrowth {
    val settled = statistics.outcomes.associateBy { it.matchId }
    val seenSeeds = mutableSetOf<Long>()
    val outcomes = mutableListOf<MatchOutcome>()
    var policy = base
    for (proof in archivedSeries) {
        val config = proof.config
        val outcome = settled.getValue(config.matchId)
        policy.requireAuthorized(config.botLevel, CompetitionMahjongAssistanceUsage(outcome.difficulty))
        if (!seenSeeds.add(config.seed)) continue
        val row = outcome.toGrowthOutcome()
        outcomes.add(row)
        if (row.isWin) {
            val current = policy.progress
            policy = when (row.difficulty) {
                Difficulty.EASY -> policy.withProgress(current.copy(easyWins = Math.addExact(current.easyWins, 1)))
                Difficulty.MEDIUM -> policy.withProgress(current.copy(mediumWins = Math.addExact(current.mediumWins, 1)))
                Difficulty.HARD -> policy.withProgress(current.copy(hardWins = Math.addExact(current.hardWins, 1)))
                Difficulty.MASTER -> policy
            }
        }
    }
    return CompetitionMahjongQualifiedGrowth(policy, outcomes)
}

private fun CompetitionMahjongSeriesOutcome.toGrowthOutcome() = MatchOutcome(
    "competition-mahjong:$viewerPlayerId:$matchId", GameType.STANDARD_MAHJONG, StoredGameMode.HUMAN_VS_AI,
    difficulty, 0, when {
        rank != 1 -> GameResult.SECOND_PLAYER_WIN
        standardPointsDenominator == 1 -> GameResult.FIRST_PLAYER_WIN
        else -> GameResult.DRAW
    }, settledAtEpochMillis,
)
