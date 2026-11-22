package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.challenge.StreakChallengeState
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult

/** Series position at the start of this game. The terminal display derives from the native result. */
data class GoStreakProgress(
    val current: Int = 0,
    val best: Int = 0,
    val winsAtDifficulty: Int = 0,
    val lossesAtDifficulty: Int = 0,
) {
    private val state = StreakChallengeState(current, best, winsAtDifficulty, lossesAtDifficulty)

    internal fun after(result: GameResult, difficulty: GoAiDifficulty): Pair<GoAiDifficulty, GoStreakProgress> {
        val (next, progress) = state.complete(result, Difficulty.entries[difficulty.ordinal]).startNextGame()
        return GoAiDifficulty.entries[next.ordinal] to GoStreakProgress(
            progress.currentStreak, progress.bestStreak, progress.winsAtDifficulty, progress.lossesAtDifficulty,
        )
    }
}
