package com.masterofchessstrategy.challenge

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AssessmentChallengeStateTest {
    @Test
    fun winsRaiseRatingAndOpponentDifficultyUntilMaster() {
        var state = AssessmentChallengeState()
        var difficulty = Difficulty.MEDIUM

        state = state.complete(GameResult.FIRST_PLAYER_WIN, difficulty)
        assertEquals(1_400, state.rating)
        assertEquals(Difficulty.HARD, state.nextDifficulty)

        repeat(3) {
            val next = state.startNextGame()
            difficulty = next.first
            state = next.second.complete(GameResult.FIRST_PLAYER_WIN, difficulty)
        }

        val finalActive = state.startNextGame()
        state = finalActive.second.complete(GameResult.FIRST_PLAYER_WIN, finalActive.first)
        assertTrue(state.isFinished)
        assertEquals(AssessmentChallengeState.MAX_RATING, state.rating)
        assertEquals(5, state.wins)
    }

    @Test
    fun lossesLowerRatingAndOpponentDifficultyWithEasyFloor() {
        var state = AssessmentChallengeState()
        var difficulty = Difficulty.MEDIUM

        repeat(5) { index ->
            state = state.complete(GameResult.SECOND_PLAYER_WIN, difficulty)
            if (index < 4) {
                val next = state.startNextGame()
                difficulty = next.first
                state = next.second
            }
        }

        assertTrue(state.isFinished)
        assertEquals(AssessmentChallengeState.MIN_RATING, state.rating)
        assertEquals(5, state.losses)
    }

    @Test
    fun drawKeepsRatingAndDifficulty() {
        val completed = AssessmentChallengeState().complete(
            GameResult.DRAW,
            Difficulty.MEDIUM,
        )

        assertEquals(AssessmentChallengeState.INITIAL_RATING, completed.rating)
        assertEquals(Difficulty.MEDIUM, completed.nextDifficulty)
        assertEquals(1, completed.draws)
    }

    @Test
    fun codecIsCanonicalForActivePendingAndFinishedPhases() {
        val pending = AssessmentChallengeState().complete(
            GameResult.FIRST_PLAYER_WIN,
            Difficulty.MEDIUM,
        )
        val active = pending.startNextGame().second
        var finished = AssessmentChallengeState()
        var difficulty = Difficulty.MEDIUM
        repeat(5) { index ->
            finished = finished.complete(GameResult.DRAW, difficulty)
            if (index < 4) {
                val next = finished.startNextGame()
                difficulty = next.first
                finished = next.second
            }
        }

        listOf(AssessmentChallengeState(), pending, active, finished).forEach { state ->
            assertEquals(
                state,
                AssessmentChallengeStateCodec.decode(
                    AssessmentChallengeStateCodec.encode(state),
                ),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            AssessmentChallengeStateCodec.decode("assessment:01:1200:1:0:0:1")
        }
    }
}
