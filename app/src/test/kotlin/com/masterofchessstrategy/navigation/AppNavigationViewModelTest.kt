package com.masterofchessstrategy.navigation

import com.masterofchessstrategy.data.LastGameSelection
import com.masterofchessstrategy.data.LastSelectionRepository
import com.masterofchessstrategy.data.LoadLastSelectionResult
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppNavigationViewModelTest {
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
    fun noHistoryLeavesQuickStartUnavailable() = runTest(dispatcher) {
        val viewModel = AppNavigationViewModel(
            FakeLastSelectionRepository(LoadLastSelectionResult.NotFound),
        )

        assertNull(viewModel.chineseChessQuickStartDestination())
        advanceUntilIdle()

        assertFalse(viewModel.uiState.isLoadingSelection)
        assertNull(viewModel.chineseChessQuickStartDestination())
    }

    @Test
    fun localHistoryQuickStartsGame() = runTest(dispatcher) {
        val selection = selection(StoredGameMode.LOCAL_TWO_PLAYER)
        val viewModel = AppNavigationViewModel(
            FakeLastSelectionRepository(LoadLastSelectionResult.Loaded(selection)),
        )

        advanceUntilIdle()

        assertEquals(
            QuickStartDestination.GAME,
            viewModel.chineseChessQuickStartDestination(),
        )
    }

    @Test
    fun recordModePersistsAndRefreshesQuickStart() = runTest(dispatcher) {
        val repository = FakeLastSelectionRepository(LoadLastSelectionResult.NotFound)
        val viewModel = AppNavigationViewModel(repository, nowEpochMillis = { 88L })
        advanceUntilIdle()

        viewModel.recordChineseChessSelection(StoredGameMode.HUMAN_VS_AI)
        advanceUntilIdle()

        assertEquals(StoredGameMode.HUMAN_VS_AI, repository.saved?.mode)
        assertEquals(88L, repository.saved?.updatedAtEpochMillis)
        assertEquals(
            QuickStartDestination.DIFFICULTY,
            viewModel.chineseChessQuickStartDestination(),
        )
    }

    @Test
    fun incompatibleHistoryIsClearedAndNeverExposed() = runTest(dispatcher) {
        val repository = FakeLastSelectionRepository(LoadLastSelectionResult.Incompatible)
        val viewModel = AppNavigationViewModel(repository)

        advanceUntilIdle()

        assertEquals(1, repository.clearCalls)
        assertNull(viewModel.uiState.lastChineseChessSelection)
    }

    private fun selection(mode: StoredGameMode) = LastGameSelection(
        gameType = GameType.CHINESE_CHESS,
        mode = mode,
        difficulty = null,
        updatedAtEpochMillis = 1L,
    )

    private class FakeLastSelectionRepository(
        private val loadResult: LoadLastSelectionResult,
    ) : LastSelectionRepository {
        var saved: LastGameSelection? = null
        var clearCalls = 0

        override suspend fun load(gameType: GameType): LoadLastSelectionResult = loadResult

        override suspend fun save(selection: LastGameSelection) {
            saved = selection
        }

        override suspend fun clear(gameType: GameType) {
            clearCalls++
        }
    }
}
