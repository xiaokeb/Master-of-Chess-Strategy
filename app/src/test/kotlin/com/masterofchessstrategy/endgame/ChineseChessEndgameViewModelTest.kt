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
import java.io.File

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
        assertTrue(viewModel.uiState.entries.entry("xq-easy-003").isUnlocked)
        assertFalse(viewModel.uiState.entries.entry("xq-easy-004").isUnlocked)
        assertFalse(viewModel.uiState.entries.entry("xq-medium-002").isUnlocked)
        assertEquals(2, viewModel.uiState.progress.totalStars)
        assertEquals(20, viewModel.uiState.progress.totalScore)

        viewModel.complete("xq-medium-001", 1)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.entries.entry("xq-hard-001").isUnlocked)
        assertTrue(viewModel.uiState.entries.entry("xq-medium-002").isUnlocked)

        viewModel.complete("xq-easy-003", 1)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.entries.entry("xq-easy-004").isUnlocked)
        assertTrue(viewModel.uiState.entries.entry("xq-hard-001").isUnlocked)
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
    fun newMediumBonusLevelsFollowExistingCompletionIds() = runTest(dispatcher) {
        val bundled = ChineseChessEndgamePackParser.parse(
            File("src/main/assets/endgames/chinese_chess/endgames-v5.txt").readText(Charsets.UTF_8),
        )
        val viewModel = ChineseChessEndgameViewModel(bundled, FakeRepository())
        advanceUntilIdle()
        assertFalse(viewModel.uiState.entries.entry("xq-medium-003").isUnlocked)

        listOf("xq-easy-001", "xq-easy-002", "xq-medium-001", "xq-medium-002")
            .forEach { levelId ->
                viewModel.complete(levelId, 1)
                advanceUntilIdle()
            }
        assertTrue(viewModel.uiState.entries.entry("xq-medium-003").isUnlocked)
        assertFalse(viewModel.uiState.entries.entry("xq-medium-004").isUnlocked)

        viewModel.selectDifficulty(Difficulty.MEDIUM)
        assertEquals(
            (1..13).map { "xq-medium-${it.toString().padStart(3, '0')}" },
            viewModel.uiState.visibleEntries.map { it.level.id },
        )
        viewModel.complete("xq-medium-003", 2)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.entries.entry("xq-medium-004").isUnlocked)
        assertFalse(viewModel.uiState.entries.entry("xq-medium-006").isUnlocked)
        listOf("xq-medium-004", "xq-medium-005", "xq-medium-006").forEach { levelId ->
            viewModel.complete(levelId, 1)
            advanceUntilIdle()
        }
        assertTrue(viewModel.uiState.entries.entry("xq-medium-007").isUnlocked)
        assertFalse(viewModel.uiState.entries.entry("xq-medium-008").isUnlocked)
        listOf("xq-medium-007", "xq-medium-008", "xq-medium-009").forEach { levelId ->
            assertTrue(viewModel.uiState.entries.entry(levelId).isUnlocked)
            viewModel.complete(levelId, 2)
            advanceUntilIdle()
        }
        assertTrue(viewModel.uiState.entries.entry("xq-medium-010").isUnlocked)
        assertFalse(viewModel.uiState.entries.entry("xq-medium-011").isUnlocked)

        val starsBefore = viewModel.uiState.progress.totalStars
        val scoreBefore = viewModel.uiState.progress.totalScore
        // Each appended bonus level unlocks only its successor, without double rewards.
        val appended = (10..13).map { "xq-medium-${it.toString().padStart(3, '0')}" }
        appended.forEachIndexed { index, levelId ->
            assertTrue(viewModel.uiState.entries.entry(levelId).isUnlocked)
            appended.drop(index + 1).forEach { lockedId ->
                assertFalse(viewModel.uiState.entries.entry(lockedId).isUnlocked)
            }
            viewModel.complete(levelId, 2)
            advanceUntilIdle()
            assertEquals(EndgameCatalogFeedback.FIRST_COMPLETION, viewModel.uiState.feedback)
            assertEquals(starsBefore + index + 1, viewModel.uiState.progress.totalStars)
            assertEquals(scoreBefore + (index + 1) * 20, viewModel.uiState.progress.totalScore)

            viewModel.complete(levelId, 2)
            advanceUntilIdle()
            assertEquals(EndgameCatalogFeedback.COMPLETED_AGAIN, viewModel.uiState.feedback)
            assertEquals(starsBefore + index + 1, viewModel.uiState.progress.totalStars)
            assertEquals(scoreBefore + (index + 1) * 20, viewModel.uiState.progress.totalScore)
        }
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
    version = 5,
    license = "GPL-3.0-or-later",
    author = "Test",
    levels = listOf(
        testLevel("xq-easy-001", Difficulty.EASY, 1),
        testLevel("xq-easy-002", Difficulty.EASY, 2),
        testLevel("xq-easy-003", Difficulty.EASY, 3, ChineseChessEndgameTrack.BONUS),
        testLevel("xq-easy-004", Difficulty.EASY, 4, ChineseChessEndgameTrack.BONUS),
        testLevel("xq-medium-001", Difficulty.MEDIUM, 1),
        testLevel("xq-medium-002", Difficulty.MEDIUM, 2, ChineseChessEndgameTrack.BONUS),
        testLevel("xq-hard-001", Difficulty.HARD, 1),
        testLevel("xq-master-001", Difficulty.MASTER, 1),
    ),
)

private fun testLevel(
    id: String,
    difficulty: Difficulty,
    order: Int,
    track: ChineseChessEndgameTrack = ChineseChessEndgameTrack.MAIN,
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
    track = track,
)
