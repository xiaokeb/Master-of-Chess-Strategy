package com.masterofchessstrategy.challenge

import com.masterofchessstrategy.data.GameSessionRepository
import com.masterofchessstrategy.data.GameSessionSnapshot
import com.masterofchessstrategy.data.LoadGameSessionResult
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChineseChessAssessmentViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun newAssessmentAlwaysStartsAtMediumAndRating1200() = runTest(dispatcher) {
        val viewModel = viewModel(LoadGameSessionResult.NotFound)
        advanceUntilIdle()

        assertEquals(
            PreparedAssessmentChallenge(Difficulty.MEDIUM, AssessmentChallengeState()),
            viewModel.prepareNewChallenge(),
        )
    }

    @Test
    fun savedAssessmentRestoresItsCurrentOpponentAndScore() = runTest(dispatcher) {
        val state = AssessmentChallengeState(
            completedGames = 2,
            rating = 1_500,
            wins = 1,
            draws = 1,
        )
        val snapshot = GameSessionSnapshot(
            gameType = GameType.CHINESE_CHESS,
            mode = StoredGameMode.ASSESSMENT_CHALLENGE,
            difficulty = Difficulty.HARD,
            engineState = byteArrayOf(1),
            updatedAtEpochMillis = 1L,
            sessionId = "assessment-1",
            sessionVariantId = AssessmentChallengeStateCodec.encode(state),
        )
        val viewModel = viewModel(LoadGameSessionResult.Loaded(snapshot))

        advanceUntilIdle()

        assertEquals(
            PreparedAssessmentChallenge(Difficulty.HARD, state),
            viewModel.continueSavedChallenge(),
        )
    }

    private fun viewModel(result: LoadGameSessionResult) =
        ChineseChessAssessmentViewModel(FakeSessionRepository(result))

    private class FakeSessionRepository(
        private val result: LoadGameSessionResult,
    ) : GameSessionRepository {
        override suspend fun load(gameType: GameType): LoadGameSessionResult = result
        override suspend fun save(snapshot: GameSessionSnapshot) = Unit
        override suspend fun clear(gameType: GameType) = Unit
    }
}
