package com.masterofchessstrategy.opening

import com.masterofchessstrategy.engine.ActionResult
import com.masterofchessstrategy.engine.BoardMove
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.ChineseChessPiece
import com.masterofchessstrategy.engine.ChineseChessPieceType
import com.masterofchessstrategy.engine.ChineseChessRuleEngine
import com.masterofchessstrategy.engine.ChineseChessSide
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.EngineError
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.PlayerId
import com.masterofchessstrategy.engine.RestoreResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChineseChessOpeningViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun seedLibraryUsesStableUniqueMetadata() {
        val lines = ChineseChessOpeningLibrary.lines

        assertEquals(3, lines.size)
        assertEquals(lines.size, lines.map { it.id }.distinct().size)
        assertTrue(lines.all { it.steps.size == 4 })
        assertEquals(2, ChineseChessOpeningLibrary.CONTENT_VERSION)
    }

    @Test
    fun selectedLineIsValidatedIntoFramesAndDefensiveEndpoint() = runTest(dispatcher) {
        val engine = FakeOpeningEngine()
        val viewModel = ChineseChessOpeningViewModel(
            unlockedDifficulties = setOf(Difficulty.EASY, Difficulty.MEDIUM),
            dispatcher = dispatcher,
            engineFactory = { engine },
        )

        advanceUntilIdle()

        val line = ChineseChessOpeningLibrary.lines.first()
        assertFalse(viewModel.uiState.isLoading)
        assertFalse(viewModel.uiState.isIncompatible)
        assertEquals(line.steps.size + 1, viewModel.uiState.frames.size)
        assertEquals(line.steps.map { it.move }, engine.moves)
        assertEquals("标准初始局面", viewModel.uiState.currentFrame?.stepTitle)

        viewModel.next()
        assertEquals(line.steps.first().title, viewModel.uiState.currentFrame?.stepTitle)
        viewModel.selectDifficulty(Difficulty.MEDIUM)
        val prepared = requireNotNull(viewModel.prepareAutoPlay())
        assertEquals(Difficulty.MEDIUM, prepared.difficulty)
        assertArrayEquals(byteArrayOf(4), prepared.initialState)
        assertNotSame(viewModel.uiState.endpointState, prepared.initialState)
    }

    @Test
    fun rejectedOpeningStepFailsClosedAndCannotStartAutoPlay() = runTest(dispatcher) {
        val viewModel = ChineseChessOpeningViewModel(
            unlockedDifficulties = setOf(Difficulty.EASY),
            dispatcher = dispatcher,
            engineFactory = { FakeOpeningEngine(rejectAtMove = 2) },
        )

        advanceUntilIdle()

        assertTrue(viewModel.uiState.isIncompatible)
        assertTrue(viewModel.uiState.frames.isEmpty())
        assertNull(viewModel.prepareAutoPlay())
    }

    private class FakeOpeningEngine(
        private val rejectAtMove: Int? = null,
    ) : ChineseChessRuleEngine {
        val moves = mutableListOf<BoardMove>()
        private var side = ChineseChessSide.RED

        override val gameType = GameType.CHINESE_CHESS
        override val currentPlayer: PlayerId
            get() = PlayerId(side.code)

        override fun reset() {
            moves.clear()
            side = ChineseChessSide.RED
        }

        override fun apply(action: BoardMove): ActionResult {
            if (moves.size + 1 == rejectAtMove) {
                return ActionResult.Rejected(EngineError.ILLEGAL_ACTION)
            }
            moves += action
            side = if (side == ChineseChessSide.RED) ChineseChessSide.BLACK else ChineseChessSide.RED
            return ActionResult.Accepted
        }

        override fun undo(): Boolean = false
        override fun legalActions(): List<BoardMove> = emptyList()
        override fun gameResult(): GameResult = GameResult.ONGOING
        override fun serialize(): ByteArray = byteArrayOf(moves.size.toByte())
        override fun restore(data: ByteArray): RestoreResult = RestoreResult.Restored
        override fun close() = Unit

        override fun pieceAt(position: BoardPosition): ChineseChessPiece? =
            if (position == BoardPosition(4, 9)) {
                ChineseChessPiece(ChineseChessPieceType.GENERAL, ChineseChessSide.RED)
            } else {
                null
            }
    }
}
