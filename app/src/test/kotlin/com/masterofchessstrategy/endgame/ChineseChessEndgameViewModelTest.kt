package com.masterofchessstrategy.endgame

import com.masterofchessstrategy.data.CompleteEndgameResult
import com.masterofchessstrategy.data.CompletedEndgameLevel
import com.masterofchessstrategy.data.EndgameProgress
import com.masterofchessstrategy.data.EndgameProgressRepository
import com.masterofchessstrategy.data.LoadEndgameProgressResult
import com.masterofchessstrategy.engine.BoardMove
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.ChineseChessPiece
import com.masterofchessstrategy.engine.ChineseChessPieceType
import com.masterofchessstrategy.engine.ChineseChessSide
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.PositionedChineseChessPiece
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChineseChessEndgameViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val pack = testPack()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun completionUnlocksNextLevelThenNextDifficulty() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = ChineseChessEndgameViewModel(pack, repository)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.entries.entry("xq-easy-001").isUnlocked)
        assertFalse(viewModel.uiState.entries.entry("xq-easy-002").isUnlocked)
        assertFalse(viewModel.uiState.entries.entry("xq-medium-001").isUnlocked)

        viewModel.complete("xq-easy-001", 1)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.entries.entry("xq-easy-002").isUnlocked)
        assertFalse(viewModel.uiState.entries.entry("xq-medium-001").isUnlocked)

        viewModel.complete("xq-easy-002", 1)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.entries.entry("xq-medium-001").isUnlocked)
        assertEquals(2, viewModel.uiState.progress.totalStars)
        assertEquals(20, viewModel.uiState.progress.totalScore)
    }

    @Test
    fun repeatCompletionDoesNotRewardTwice() = runTest(dispatcher) {
        val repository = FakeRepository()
        val viewModel = ChineseChessEndgameViewModel(pack, repository)
        advanceUntilIdle()

        viewModel.complete("xq-easy-001", 1)
        advanceUntilIdle()
        viewModel.complete("xq-easy-001", 1)
        advanceUntilIdle()

        assertEquals(1, viewModel.uiState.progress.totalStars)
        assertEquals(10, viewModel.uiState.progress.totalScore)
        assertEquals(EndgameCatalogFeedback.COMPLETED_AGAIN, viewModel.uiState.feedback)
    }

    @Test
    fun dailyChoiceIsStableAndRandomUsesOnlyUnlockedLevels() = runTest(dispatcher) {
        val viewModel = ChineseChessEndgameViewModel(
            pack = pack,
            repository = FakeRepository(),
            localDayCode = { 2026L * 372L + 8L * 31L + 15L },
            randomIndex = { size -> size - 1 },
        )
        advanceUntilIdle()

        viewModel.selectMode(EndgameCatalogMode.DAILY)
        val firstDaily = viewModel.uiState.visibleEntries.single().level.id
        viewModel.selectMode(EndgameCatalogMode.MAIN)
        viewModel.selectMode(EndgameCatalogMode.DAILY)
        assertEquals(firstDaily, viewModel.uiState.visibleEntries.single().level.id)

        viewModel.randomChallenge()
        assertEquals(
            "xq-easy-001",
            viewModel.uiState.visibleEntries.single().level.id,
        )
    }

    private fun List<EndgameLevelEntry>.entry(id: String): EndgameLevelEntry =
        single { it.level.id == id }

    private class FakeRepository : EndgameProgressRepository {
        private val completed = linkedMapOf<String, CompletedEndgameLevel>()

        override suspend fun load(): LoadEndgameProgressResult =
            LoadEndgameProgressResult.Loaded(EndgameProgress(completed.toMap()))

        override suspend fun complete(
            level: ChineseChessEndgameLevel,
            playerMoves: Int,
            completedAtEpochMillis: Long,
        ): CompleteEndgameResult {
            if (level.id in completed) {
                return CompleteEndgameResult(false, false, 0, 0)
            }
            completed[level.id] = CompletedEndgameLevel(
                levelId = level.id,
                difficulty = level.difficulty,
                bestPlayerMoves = playerMoves,
                starsAwarded = level.starReward,
                scoreAwarded = level.scoreReward,
                completedAtEpochMillis = completedAtEpochMillis,
            )
            return CompleteEndgameResult(
                firstCompletion = true,
                bestMovesImproved = true,
                awardedStars = level.starReward,
                awardedScore = level.scoreReward,
            )
        }
    }
}

private fun testPack(): ChineseChessEndgamePack = ChineseChessEndgamePack(
    version = 2,
    license = "GPL-3.0-or-later",
    author = "Test",
    levels = listOf(
        testLevel("xq-easy-001", Difficulty.EASY, 1),
        testLevel("xq-easy-002", Difficulty.EASY, 2),
        testLevel("xq-medium-001", Difficulty.MEDIUM, 1),
        testLevel("xq-hard-001", Difficulty.HARD, 1),
        testLevel("xq-master-001", Difficulty.MASTER, 1),
    ),
)

private fun testLevel(
    id: String,
    difficulty: Difficulty,
    order: Int,
): ChineseChessEndgameLevel = ChineseChessEndgameLevel(
    id = id,
    difficulty = difficulty,
    chapterOrder = order,
    title = id,
    theme = "一步杀",
    sideToMove = ChineseChessSide.RED,
    maxPlayerMoves = 1,
    starReward = 1,
    scoreReward = 10,
    pieces = listOf(
        PositionedChineseChessPiece(
            BoardPosition(4, 9),
            ChineseChessPiece(ChineseChessPieceType.GENERAL, ChineseChessSide.RED),
        ),
        PositionedChineseChessPiece(
            BoardPosition(4, 0),
            ChineseChessPiece(ChineseChessPieceType.GENERAL, ChineseChessSide.BLACK),
        ),
    ),
    principalVariation = listOf(BoardMove(BoardPosition(4, 9), BoardPosition(4, 8))),
)
