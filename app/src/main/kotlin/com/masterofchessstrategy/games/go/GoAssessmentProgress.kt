package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.challenge.AssessmentChallengeState
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult

/** State at the start of a placement game. The fifth result is derived from the archived game. */
data class GoAssessmentProgress(
    val rating: Int = AssessmentChallengeState.INITIAL_RATING,
    val wins: Int = 0,
    val draws: Int = 0,
    val losses: Int = 0,
) {
    init {
        val beforeFinal = 0 until TOTAL_GAMES
        require(wins in beforeFinal && draws in beforeFinal && losses in beforeFinal && completedGames in beforeFinal)
        require(rating in AssessmentChallengeState.MIN_RATING..AssessmentChallengeState.MAX_RATING)
        require(completedGames != 0 || rating == AssessmentChallengeState.INITIAL_RATING)
    }

    val completedGames get() = wins + draws + losses

    internal fun after(result: GameResult, difficulty: GoAiDifficulty): AssessmentChallengeState =
        AssessmentChallengeState(completedGames, rating, wins, draws, losses)
            .complete(result, Difficulty.entries[difficulty.ordinal])

    internal fun next(result: GameResult, difficulty: GoAiDifficulty): Pair<GoAiDifficulty, GoAssessmentProgress>? {
        val settled = after(result, difficulty)
        val next = settled.nextDifficulty ?: return null
        return GoAiDifficulty.entries[next.ordinal] to
            GoAssessmentProgress(settled.rating, settled.wins, settled.draws, settled.losses)
    }

    companion object {
        const val TOTAL_GAMES = AssessmentChallengeState.TOTAL_GAMES
    }
}
