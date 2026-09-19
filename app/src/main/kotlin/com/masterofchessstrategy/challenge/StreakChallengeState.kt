package com.masterofchessstrategy.challenge

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult

internal data class StreakChallengeState(
    val currentStreak: Int = 0,
    val bestStreak: Int = 0,
    val winsAtDifficulty: Int = 0,
    val lossesAtDifficulty: Int = 0,
    val nextDifficulty: Difficulty? = null,
) {
    init {
        require(currentStreak in 0..MAX_STREAK)
        require(bestStreak in currentStreak..MAX_STREAK)
        require(winsAtDifficulty in 0 until WINS_TO_PROMOTE)
        require(lossesAtDifficulty in 0 until LOSSES_TO_DEMOTE)
    }

    fun complete(result: GameResult, playedDifficulty: Difficulty): StreakChallengeState {
        require(result != GameResult.ONGOING)
        require(nextDifficulty == null)
        return when (result) {
            GameResult.FIRST_PLAYER_WIN -> {
                val streak = (currentStreak + 1).coerceAtMost(MAX_STREAK)
                val wins = winsAtDifficulty + 1
                val promote = wins >= WINS_TO_PROMOTE
                copy(
                    currentStreak = streak,
                    bestStreak = maxOf(bestStreak, streak),
                    winsAtDifficulty = if (promote) 0 else wins,
                    lossesAtDifficulty = 0,
                    nextDifficulty = if (promote) {
                        playedDifficulty.harder()
                    } else {
                        playedDifficulty
                    },
                )
            }

            GameResult.SECOND_PLAYER_WIN -> {
                val losses = lossesAtDifficulty + 1
                val demote = losses >= LOSSES_TO_DEMOTE
                copy(
                    currentStreak = 0,
                    winsAtDifficulty = 0,
                    lossesAtDifficulty = if (demote) 0 else losses,
                    nextDifficulty = if (demote) {
                        playedDifficulty.easier()
                    } else {
                        playedDifficulty
                    },
                )
            }

            GameResult.DRAW -> copy(
                currentStreak = 0,
                winsAtDifficulty = 0,
                lossesAtDifficulty = 0,
                nextDifficulty = playedDifficulty,
            )

            GameResult.ONGOING -> error("Handled by the precondition")
        }
    }

    fun startNextGame(): Pair<Difficulty, StreakChallengeState> {
        val next = requireNotNull(nextDifficulty)
        return next to copy(nextDifficulty = null)
    }

    companion object {
        const val WINS_TO_PROMOTE = 3
        const val LOSSES_TO_DEMOTE = 2
        const val MAX_STREAK = 10_000
    }
}

internal object StreakChallengeStateCodec {
    const val PREFIX = "streak:"
    private const val NO_NEXT_DIFFICULTY = "n"

    fun encode(state: StreakChallengeState): String =
        listOf(
            state.currentStreak,
            state.bestStreak,
            state.winsAtDifficulty,
            state.lossesAtDifficulty,
            state.nextDifficulty?.code ?: NO_NEXT_DIFFICULTY,
        ).joinToString(prefix = PREFIX, separator = ":")

    fun decode(value: String): StreakChallengeState {
        require(value.startsWith(PREFIX))
        val fields = value.removePrefix(PREFIX).split(':')
        require(fields.size == FIELD_COUNT)
        val nextDifficulty = if (fields[4] == NO_NEXT_DIFFICULTY) {
            null
        } else {
            val code = fields[4].toIntOrNull()
            requireNotNull(Difficulty.entries.firstOrNull { it.code == code })
        }
        val state = StreakChallengeState(
            currentStreak = requireNotNull(fields[0].toIntOrNull()),
            bestStreak = requireNotNull(fields[1].toIntOrNull()),
            winsAtDifficulty = requireNotNull(fields[2].toIntOrNull()),
            lossesAtDifficulty = requireNotNull(fields[3].toIntOrNull()),
            nextDifficulty = nextDifficulty,
        )
        require(value == encode(state))
        return state
    }

    private const val FIELD_COUNT = 5
}

private fun Difficulty.harder(): Difficulty =
    Difficulty.entries.getOrElse(ordinal + 1) { this }

private fun Difficulty.easier(): Difficulty =
    Difficulty.entries.getOrElse(ordinal - 1) { this }
