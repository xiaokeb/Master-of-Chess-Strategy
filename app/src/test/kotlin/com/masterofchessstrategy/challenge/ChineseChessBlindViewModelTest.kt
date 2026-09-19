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
class ChineseChessBlindViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun newChallengeUsesOnlyAnUnlockedDifficulty() = runTest(dispatcher) {
        val viewModel = viewModel(LoadGameSessionResult.NotFound)
        advanceUntilIdle()

        viewModel.selectDifficulty(Difficulty.MEDIUM)
        assertEquals(
            PreparedBlindChallenge(Difficulty.MEDIUM),
            viewModel.prepareNewChallenge(),
        )

        viewModel.selectDifficulty(Difficulty.HARD)
        assertEquals(
            PreparedBlindChallenge(Difficulty.MEDIUM),
            viewModel.prepareNewChallenge(),
        )
    }

    @Test
    fun compatibleSavedChallengeCanBeContinued() = runTest(dispatcher) {
        val viewModel = viewModel(
            LoadGameSessionResult.Loaded(snapshot(Difficulty.MEDIUM)),
        )

        advanceUntilIdle()

        assertEquals(
            PreparedBlindChallenge(Difficulty.MEDIUM),
            viewModel.continueSavedChallenge(),
        )
    }

    @Test
    fun lockedSavedChallengeIsNotExposed() = runTest(dispatcher) {
        val viewModel = viewModel(
            LoadGameSessionResult.Loaded(snapshot(Difficulty.HARD)),
        )

        advanceUntilIdle()

        assertNull(viewModel.continueSavedChallenge())
    }

    private fun viewModel(result: LoadGameSessionResult) =
        ChineseChessBlindViewModel(
            sessionRepository = FakeSessionRepository(result),
            unlockedDifficulties = setOf(Difficulty.EASY, Difficulty.MEDIUM),
        )

    private fun snapshot(difficulty: Difficulty) = GameSessionSnapshot(
        gameType = GameType.CHINESE_CHESS,
        mode = StoredGameMode.BLIND_CHALLENGE,
        difficulty = difficulty,
        engineState = byteArrayOf(1),
        updatedAtEpochMillis = 1L,
        sessionId = "blind-1",
    )

    private class FakeSessionRepository(
        private val result: LoadGameSessionResult,
    ) : GameSessionRepository {
        override suspend fun load(gameType: GameType): LoadGameSessionResult = result
        override suspend fun save(snapshot: GameSessionSnapshot) = Unit
        override suspend fun clear(gameType: GameType) = Unit
    }
}
