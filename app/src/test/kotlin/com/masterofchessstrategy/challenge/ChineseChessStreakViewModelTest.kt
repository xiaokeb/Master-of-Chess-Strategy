package com.masterofchessstrategy.challenge

import com.masterofchessstrategy.data.GameSessionRepository
import com.masterofchessstrategy.data.GameSessionSnapshot
import com.masterofchessstrategy.data.LoadGameSessionResult
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
class ChineseChessStreakViewModelTest {
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
    fun threeWinsPromoteAndKeepTheOverallStreak() {
        var state = StreakChallengeState()
        repeat(2) {
            state = state.complete(GameResult.FIRST_PLAYER_WIN, Difficulty.EASY)
            val (_, nextState) = state.startNextGame()
            state = nextState
        }
        state = state.complete(GameResult.FIRST_PLAYER_WIN, Difficulty.EASY)

        assertEquals(3, state.currentStreak)
        assertEquals(3, state.bestStreak)
        assertEquals(0, state.winsAtDifficulty)
        assertEquals(Difficulty.MEDIUM, state.nextDifficulty)

        val (difficulty, nextState) = state.startNextGame()
        assertEquals(Difficulty.MEDIUM, difficulty)
        assertEquals(3, nextState.currentStreak)
        assertEquals(null, nextState.nextDifficulty)
    }

    @Test
    fun twoLossesDemoteButEasyRemainsTheFloor() {
        var state = StreakChallengeState()
            .complete(GameResult.SECOND_PLAYER_WIN, Difficulty.MEDIUM)
            .startNextGame().second
        state = state.complete(GameResult.SECOND_PLAYER_WIN, Difficulty.MEDIUM)

        assertEquals(Difficulty.EASY, state.nextDifficulty)

        state = state.startNextGame().second
            .complete(GameResult.SECOND_PLAYER_WIN, Difficulty.EASY)
            .startNextGame().second
            .complete(GameResult.SECOND_PLAYER_WIN, Difficulty.EASY)
        assertEquals(Difficulty.EASY, state.nextDifficulty)
    }

    @Test
    fun canonicalStateRoundTripsAndRejectsLeadingZeroes() {
        val state = StreakChallengeState(
            currentStreak = 4,
            bestStreak = 7,
            winsAtDifficulty = 2,
            lossesAtDifficulty = 0,
            nextDifficulty = Difficulty.HARD,
        )
        val encoded = StreakChallengeStateCodec.encode(state)

        assertEquals(state, StreakChallengeStateCodec.decode(encoded))
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) {
            StreakChallengeStateCodec.decode("streak:04:7:2:0:2")
        }
    }

    @Test
    fun savedSeriesCanContinueEvenAfterAutomaticPromotion() = runTest(dispatcher) {
        val state = StreakChallengeState(
            currentStreak = 3,
            bestStreak = 3,
            nextDifficulty = Difficulty.MEDIUM,
        )
        val snapshot = GameSessionSnapshot(
            gameType = GameType.CHINESE_CHESS,
            mode = StoredGameMode.STREAK_CHALLENGE,
            difficulty = Difficulty.EASY,
            engineState = byteArrayOf(1),
            updatedAtEpochMillis = 1L,
            sessionId = "streak-1",
            sessionVariantId = StreakChallengeStateCodec.encode(state),
        )
        val viewModel = ChineseChessStreakViewModel(
            sessionRepository = FakeSessionRepository(
                LoadGameSessionResult.Loaded(snapshot),
            ),
            unlockedDifficulties = setOf(Difficulty.EASY),
        )

        advanceUntilIdle()

        assertEquals(
            PreparedStreakChallenge(Difficulty.EASY, state),
            viewModel.continueSavedChallenge(),
        )
    }

    private class FakeSessionRepository(
        private val result: LoadGameSessionResult,
    ) : GameSessionRepository {
        override suspend fun load(gameType: GameType): LoadGameSessionResult = result

        override suspend fun save(snapshot: GameSessionSnapshot) = Unit

        override suspend fun clear(gameType: GameType) = Unit
    }
}
