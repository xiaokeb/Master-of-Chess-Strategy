package com.masterofchessstrategy.records

import com.masterofchessstrategy.data.GameRecord
import com.masterofchessstrategy.data.GameRecordCategory
import com.masterofchessstrategy.data.GameRecordRepository
import com.masterofchessstrategy.data.HighlightCondition
import com.masterofchessstrategy.data.LoadGameRecordsResult
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.runCurrent
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

    @Test
    fun configuredAutoPlayHighlightIsSavedAsFavoriteOnce() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = GameRecordsViewModel(
            repository,
            highlightMaskProvider = { HighlightCondition.MASTER.bit },
        )
        testScheduler.advanceUntilIdle()
        val master = record("master").copy(
            mode = StoredGameMode.AI_AUTO_PLAY,
            difficulty = Difficulty.MASTER,
        )

        viewModel.record(master)
        viewModel.record(master)
        testScheduler.advanceUntilIdle()
        viewModel.selectCategory(GameRecordCategory.FAVORITES)
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("master"), viewModel.uiState.records.map(GameRecord::recordId))
        assertTrue(repository.load("master")!!.isFavorite)
    }

    @Test fun gameFilterCombinesWithFavoritesWithoutChangingTheOtherGamesRecords() = runTest(dispatcher) {
        val go = goRecord("go")
        val repository = FakeRepository(mutableListOf(record("chess"), go))
        val model = GameRecordsViewModel(repository)
        testScheduler.advanceUntilIdle()
        model.selectGameType(GameType.GO); testScheduler.advanceUntilIdle()
        assertEquals(listOf("go"), model.uiState.records.map { it.recordId })
        model.toggleFavorite("go"); testScheduler.advanceUntilIdle()
        model.selectCategory(GameRecordCategory.FAVORITES); testScheduler.advanceUntilIdle()
        assertEquals(GameType.GO, model.uiState.gameType)
        assertEquals(listOf("go"), model.uiState.records.map { it.recordId })
        assertFalse(repository.load("chess")!!.isFavorite)
        model.selectGameType(GameType.CHINESE_CHESS); testScheduler.advanceUntilIdle()
        assertTrue(model.uiState.records.isEmpty())
        model.selectCategory(GameRecordCategory.ALL); testScheduler.advanceUntilIdle()
        assertEquals(listOf("chess"), model.uiState.records.map { it.recordId })
        model.selectGameType(null); testScheduler.advanceUntilIdle()
        assertEquals(2, model.uiState.records.size)
    }

    @Test fun lateOldLoadCannotReplaceTheSelectedGameFilter() = runTest(dispatcher) {
        val releaseOld = CompletableDeferred<Unit>()
        var calls = 0
        val repository = FakeRepository(mutableListOf(record("chess"), goRecord("go"))).apply {
            beforeList = { if (++calls == 1) releaseOld.await() }
        }
        val model = GameRecordsViewModel(repository)
        runCurrent()
        model.selectGameType(GameType.GO)
        runCurrent()
        assertEquals(listOf("go"), model.uiState.records.map { it.recordId })
        releaseOld.complete(Unit); testScheduler.advanceUntilIdle()
        assertEquals(GameType.GO, model.uiState.gameType)
        assertEquals(listOf("go"), model.uiState.records.map { it.recordId })
    }

    private fun goRecord(id: String) = GameRecord(id, GameType.GO, StoredGameMode.LOCAL_TWO_PLAYER, null,
        GameResult.DRAW, byteArrayOf(77, 79, 67, 71, 3, 9, 15, 0, 0, 0, 0, 0, 0, 0, 0, 0), 0,
        completedAtEpochMillis = 1)

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
        var beforeList: suspend () -> Unit = {}
        override suspend fun saveCompleted(record: GameRecord): Boolean {
            if (records.any { it.recordId == record.recordId }) return false
            records += record.defensiveCopy()
            return true
        }

        override suspend fun list(category: GameRecordCategory): LoadGameRecordsResult {
            beforeList()
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
