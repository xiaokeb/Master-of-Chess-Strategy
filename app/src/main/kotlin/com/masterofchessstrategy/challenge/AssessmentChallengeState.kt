package com.masterofchessstrategy.challenge

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult

internal data class AssessmentChallengeState(
    val completedGames: Int = 0,
    val rating: Int = INITIAL_RATING,
    val wins: Int = 0,
    val draws: Int = 0,
    val losses: Int = 0,
    val nextDifficulty: Difficulty? = null,
    val isFinished: Boolean = false,
) {
    init {
        require(completedGames in 0..TOTAL_GAMES)
        require(rating in MIN_RATING..MAX_RATING)
        require(wins >= 0 && draws >= 0 && losses >= 0)
        require(wins + draws + losses == completedGames)
        require(completedGames != 0 || rating == INITIAL_RATING)
        require(nextDifficulty == null || completedGames in 1 until TOTAL_GAMES)
        require(!isFinished || completedGames == TOTAL_GAMES)
        require(!isFinished || nextDifficulty == null)
        require(completedGames < TOTAL_GAMES || isFinished)
    }

    fun complete(result: GameResult, playedDifficulty: Difficulty): AssessmentChallengeState {
        require(result != GameResult.ONGOING)
        require(nextDifficulty == null && !isFinished)
        val completed = completedGames + 1
        val finished = completed == TOTAL_GAMES
        return copy(
            completedGames = completed,
            rating = updatedRating(result, playedDifficulty),
            wins = wins + if (result == GameResult.FIRST_PLAYER_WIN) 1 else 0,
            draws = draws + if (result == GameResult.DRAW) 1 else 0,
            losses = losses + if (result == GameResult.SECOND_PLAYER_WIN) 1 else 0,
            nextDifficulty = if (finished) null else adaptiveDifficulty(result, playedDifficulty),
            isFinished = finished,
        )
    }

    fun startNextGame(): Pair<Difficulty, AssessmentChallengeState> {
        require(!isFinished)
        val next = requireNotNull(nextDifficulty)
        return next to copy(nextDifficulty = null)
    }

    private fun updatedRating(result: GameResult, difficulty: Difficulty): Int {
        val delta = when (result) {
            GameResult.FIRST_PLAYER_WIN -> (difficulty.ordinal + 1) * 100
            GameResult.SECOND_PLAYER_WIN -> -(Difficulty.entries.size - difficulty.ordinal) * 100
            GameResult.DRAW -> 0
            GameResult.ONGOING -> error("Handled by the precondition")
        }
        return (rating + delta).coerceIn(MIN_RATING, MAX_RATING)
    }

    private fun adaptiveDifficulty(result: GameResult, difficulty: Difficulty): Difficulty =
        when (result) {
            GameResult.FIRST_PLAYER_WIN ->
                Difficulty.entries.getOrElse(difficulty.ordinal + 1) { difficulty }
            GameResult.SECOND_PLAYER_WIN ->
                Difficulty.entries.getOrElse(difficulty.ordinal - 1) { difficulty }
            GameResult.DRAW -> difficulty
            GameResult.ONGOING -> error("Handled by the precondition")
        }

    companion object {
        const val TOTAL_GAMES = 5
        const val INITIAL_RATING = 1_200
        const val MIN_RATING = 400
        const val MAX_RATING = 2_400
    }
}

internal object AssessmentChallengeStateCodec {
    const val PREFIX = "assessment:"
    private const val ACTIVE = "n"
    private const val FINISHED = "f"

    fun encode(state: AssessmentChallengeState): String =
        listOf(
            state.completedGames,
            state.rating,
            state.wins,
            state.draws,
            state.losses,
            when {
                state.isFinished -> FINISHED
                state.nextDifficulty != null -> state.nextDifficulty.code.toString()
                else -> ACTIVE
            },
        ).joinToString(prefix = PREFIX, separator = ":")

    fun decode(value: String): AssessmentChallengeState {
        require(value.startsWith(PREFIX))
        val fields = value.removePrefix(PREFIX).split(':')
        require(fields.size == FIELD_COUNT)
        val phase = fields[5]
        val state = AssessmentChallengeState(
            completedGames = requireNotNull(fields[0].toIntOrNull()),
            rating = requireNotNull(fields[1].toIntOrNull()),
            wins = requireNotNull(fields[2].toIntOrNull()),
            draws = requireNotNull(fields[3].toIntOrNull()),
            losses = requireNotNull(fields[4].toIntOrNull()),
            nextDifficulty = if (phase == ACTIVE || phase == FINISHED) {
                null
            } else {
                val code = phase.toIntOrNull()
                requireNotNull(Difficulty.entries.firstOrNull { it.code == code })
            },
            isFinished = phase == FINISHED,
        )
        require(value == encode(state))
        return state
    }

    private const val FIELD_COUNT = 6
}
