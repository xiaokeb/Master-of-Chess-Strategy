package com.masterofchessstrategy.records

import com.masterofchessstrategy.data.GameRecord
import com.masterofchessstrategy.data.GameRecordCategory
import com.masterofchessstrategy.data.GameRecordRepository
import com.masterofchessstrategy.data.LoadGameRecordsResult
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GameRecordsViewModelTest {
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
    fun categoryAndFavoriteChangesReloadVisibleRecords() = runTest(dispatcher) {
        val repository = FakeRepository(mutableListOf(record("one"), record("end", true)))
        val viewModel = GameRecordsViewModel(repository)
        testScheduler.advanceUntilIdle()

        assertEquals(2, viewModel.uiState.records.size)
        viewModel.selectCategory(GameRecordCategory.ENDGAME)
        testScheduler.advanceUntilIdle()
        assertEquals(listOf("end"), viewModel.uiState.records.map(GameRecord::recordId))

        viewModel.selectCategory(GameRecordCategory.ALL)
        testScheduler.advanceUntilIdle()
        viewModel.toggleFavorite("one")
        testScheduler.advanceUntilIdle()
        assertTrue(viewModel.uiState.records.first { it.recordId == "one" }.isFavorite)
    }

    @Test
    fun duplicateTerminalSaveDoesNotCreateSecondVisibleRecord() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = GameRecordsViewModel(repository)
        testScheduler.advanceUntilIdle()

        viewModel.record(record("same"))
        viewModel.record(record("same"))
        testScheduler.advanceUntilIdle()

        assertEquals(1, viewModel.uiState.records.size)
        assertFalse(viewModel.uiState.isLoading)
    }

    private fun record(id: String, endgame: Boolean = false) =
        GameRecord(
            recordId = id,
            gameType = GameType.CHINESE_CHESS,
            mode = if (endgame) StoredGameMode.ENDGAME else StoredGameMode.HUMAN_VS_AI,
            difficulty = Difficulty.EASY,
            result = GameResult.FIRST_PLAYER_WIN,
            engineState = byteArrayOf(1),
            moveCount = 0,
            isEndgame = endgame,
            completedAtEpochMillis = 1L,
        )

    private class FakeRepository(
        private val records: MutableList<GameRecord> = mutableListOf(),
    ) : GameRecordRepository {
        override suspend fun saveCompleted(record: GameRecord): Boolean {
            if (records.any { it.recordId == record.recordId }) return false
            records += record.defensiveCopy()
            return true
        }

        override suspend fun list(category: GameRecordCategory): LoadGameRecordsResult {
            val filtered = when (category) {
                GameRecordCategory.ALL -> records
                GameRecordCategory.FAVORITES -> records.filter(GameRecord::isFavorite)
                GameRecordCategory.ENDGAME -> records.filter(GameRecord::isEndgame)
            }
            return LoadGameRecordsResult.Loaded(filtered.map(GameRecord::defensiveCopy))
        }

        override suspend fun load(recordId: String): GameRecord? =
            records.firstOrNull { it.recordId == recordId }?.defensiveCopy()

        override suspend fun setFavorite(recordId: String, favorite: Boolean): Boolean {
            val index = records.indexOfFirst { it.recordId == recordId }
            if (index < 0) return false
            records[index] = records[index].copy(isFavorite = favorite)
            return true
        }
    }
}
