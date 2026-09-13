package com.masterofchessstrategy.progress

import com.masterofchessstrategy.data.LoadMatchStatisticsResult
import com.masterofchessstrategy.data.MatchOutcome
import com.masterofchessstrategy.data.MatchStatisticsRepository
import com.masterofchessstrategy.data.PlayerStatistics
import com.masterofchessstrategy.data.RecordMatchOutcomeResult
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
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
class PlayerStatisticsViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun recordRefreshesAppScopedStatistics() = runTest(dispatcher) {
        val expected = PlayerStatistics(
            completedMatches = 1,
            totalWins = 1,
            stars = 1,
            score = 10,
            winsByGameAndDifficulty = mapOf(
                GameType.CHINESE_CHESS to mapOf(Difficulty.EASY to 1),
            ),
        )
        val repository = FakeStatisticsRepository(expected)
        val viewModel = PlayerStatisticsViewModel(repository)
        advanceUntilIdle()

        viewModel.record(
            MatchOutcome(
                matchId = "match-1",
                gameType = GameType.CHINESE_CHESS,
                mode = StoredGameMode.HUMAN_VS_AI,
                difficulty = Difficulty.EASY,
                playerIndex = 0,
                result = GameResult.FIRST_PLAYER_WIN,
                settledAtEpochMillis = 1L,
            ),
        )
        advanceUntilIdle()

        assertEquals(expected, viewModel.uiState.statistics)
        assertEquals("match-1", repository.recorded?.matchId)
    }

    private class FakeStatisticsRepository(
        private val afterRecord: PlayerStatistics,
    ) : MatchStatisticsRepository {
        var recorded: MatchOutcome? = null

        override suspend fun load(): LoadMatchStatisticsResult =
            LoadMatchStatisticsResult.Loaded(PlayerStatistics.EMPTY)

        override suspend fun record(outcome: MatchOutcome): RecordMatchOutcomeResult {
            recorded = outcome
            return RecordMatchOutcomeResult(true, afterRecord)
        }
    }
}
