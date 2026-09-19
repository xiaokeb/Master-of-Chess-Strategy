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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChineseChessTimedChallengeViewModelTest {
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
    fun canonicalVariantsCoverEverySupportedClock() {
        TimedChallengeConfig.ALLOWED_SECONDS.forEach { seconds ->
            val encoded = TimedChallengeConfig.sessionVariant(seconds)
            assertEquals(seconds, TimedChallengeConfig.decodeSessionVariant(encoded))
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonCanonicalClockVariantIsRejected() {
        TimedChallengeConfig.decodeSessionVariant("timed:030")
    }

    @Test
    fun newChallengeUsesUnlockedDifficultyAndSelectedClock() = runTest(dispatcher) {
        val viewModel = viewModel(LoadGameSessionResult.NotFound)
        advanceUntilIdle()

        viewModel.selectDifficulty(Difficulty.MEDIUM)
        viewModel.selectSecondsPerMove(10)

        assertEquals(
            PreparedTimedChallenge(Difficulty.MEDIUM, 10),
            viewModel.prepareNewChallenge(),
        )
    }

    @Test
    fun compatibleSavedChallengeCanBeContinued() = runTest(dispatcher) {
        val snapshot = GameSessionSnapshot(
            gameType = GameType.CHINESE_CHESS,
            mode = StoredGameMode.TIMED_CHALLENGE,
            difficulty = Difficulty.MEDIUM,
            engineState = byteArrayOf(1),
            updatedAtEpochMillis = 1L,
            sessionId = "timed-1",
            timeControlMinutes = TimedChallengeConfig.BACKING_CLOCK_MINUTES,
            redRemainingMillis = 30_000L,
            blackRemainingMillis = 30_000L,
            turnStartedAtEpochMillis = 1L,
            sessionVariantId = TimedChallengeConfig.sessionVariant(30),
        )
        val viewModel = viewModel(LoadGameSessionResult.Loaded(snapshot))

        advanceUntilIdle()

        assertEquals(
            PreparedTimedChallenge(Difficulty.MEDIUM, 30),
            viewModel.continueSavedChallenge(),
        )
    }

    @Test
    fun lockedSavedChallengeIsNotExposed() = runTest(dispatcher) {
        val snapshot = GameSessionSnapshot(
            gameType = GameType.CHINESE_CHESS,
            mode = StoredGameMode.TIMED_CHALLENGE,
            difficulty = Difficulty.HARD,
            engineState = byteArrayOf(1),
            updatedAtEpochMillis = 1L,
            sessionId = "timed-locked",
            timeControlMinutes = TimedChallengeConfig.BACKING_CLOCK_MINUTES,
            redRemainingMillis = 10_000L,
            blackRemainingMillis = 10_000L,
            turnStartedAtEpochMillis = 1L,
            sessionVariantId = TimedChallengeConfig.sessionVariant(10),
        )
        val viewModel = viewModel(LoadGameSessionResult.Loaded(snapshot))

        advanceUntilIdle()

        assertNull(viewModel.continueSavedChallenge())
    }

    private fun viewModel(result: LoadGameSessionResult) =
        ChineseChessTimedChallengeViewModel(
            sessionRepository = FakeSessionRepository(result),
            unlockedDifficulties = setOf(Difficulty.EASY, Difficulty.MEDIUM),
        )

    private class FakeSessionRepository(
        private val result: LoadGameSessionResult,
    ) : GameSessionRepository {
        override suspend fun load(gameType: GameType): LoadGameSessionResult = result

        override suspend fun save(snapshot: GameSessionSnapshot) = Unit

        override suspend fun clear(gameType: GameType) = Unit
    }
}
