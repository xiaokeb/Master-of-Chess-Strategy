package com.masterofchessstrategy.custom

import com.masterofchessstrategy.data.GameSessionRepository
import com.masterofchessstrategy.data.GameSessionSnapshot
import com.masterofchessstrategy.data.LoadGameSessionResult
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.engine.ActionResult
import com.masterofchessstrategy.engine.BoardMove
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.ChineseChessAiEngine
import com.masterofchessstrategy.engine.ChineseChessPiece
import com.masterofchessstrategy.engine.ChineseChessPieceType
import com.masterofchessstrategy.engine.ChineseChessPositionCodec
import com.masterofchessstrategy.engine.ChineseChessSide
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.PlayerId
import com.masterofchessstrategy.engine.PositionedChineseChessPiece
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChineseChessSetupViewModelTest {
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
    fun standardPositionPassesCodecAndEngineValidation() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        val prepared = viewModel.prepareNewGame()

        assertNotNull(prepared)
        assertEquals(Difficulty.EASY, prepared?.difficulty)
        assertTrue(prepared!!.engineState.isNotEmpty())
        assertEquals(null, viewModel.uiState.feedback)
    }

    @Test
    fun palacePieceCannotBePlacedOutsideItsOwnPalace() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()
        val before = viewModel.uiState.board
        viewModel.selectSide(ChineseChessSide.RED)
        viewModel.selectPieceType(ChineseChessPieceType.ADVISOR)

        viewModel.onSquareTap(BoardPosition(0, 5))

        assertEquals(before, viewModel.uiState.board)
        assertEquals(
            ChineseChessSetupFeedback.INVALID_PLACEMENT,
            viewModel.uiState.feedback,
        )
    }

    @Test
    fun emptyBoardExplainsThatBothGeneralsAreRequired() = runTest(dispatcher) {
        val viewModel = viewModel()
        advanceUntilIdle()

        viewModel.clearBoard()
        val prepared = viewModel.prepareNewGame()

        assertEquals(null, prepared)
        assertEquals(
            ChineseChessSetupFeedback.MISSING_GENERALS,
            viewModel.uiState.feedback,
        )
    }

    @Test
    fun savedCustomSessionRestoresItsOriginalSetupRoute() = runTest(dispatcher) {
        val original = ChineseChessPositionCodec.encode(
            ChineseChessSide.RED,
            listOf(
                PositionedChineseChessPiece(
                    BoardPosition(4, 9),
                    ChineseChessPiece(
                        ChineseChessPieceType.GENERAL,
                        ChineseChessSide.RED,
                    ),
                ),
                PositionedChineseChessPiece(
                    BoardPosition(4, 0),
                    ChineseChessPiece(
                        ChineseChessPieceType.GENERAL,
                        ChineseChessSide.BLACK,
                    ),
                ),
                PositionedChineseChessPiece(
                    BoardPosition(0, 9),
                    ChineseChessPiece(
                        ChineseChessPieceType.CHARIOT,
                        ChineseChessSide.RED,
                    ),
                ),
            ),
        )
        val repository = FakeSessionRepository(
            LoadGameSessionResult.Loaded(
                GameSessionSnapshot(
                    gameType = GameType.CHINESE_CHESS,
                    mode = StoredGameMode.CUSTOM_POSITION,
                    difficulty = Difficulty.MEDIUM,
                    engineState = byteArrayOf(9),
                    updatedAtEpochMillis = 1L,
                    sessionId = "custom-1",
                    sessionVariantId = CustomPositionStateCodec.sessionVariant(original),
                ),
            ),
        )
        val viewModel = ChineseChessSetupViewModel(
            sessionRepository = repository,
            unlockedDifficulties = setOf(Difficulty.EASY, Difficulty.MEDIUM),
            engineFactory = ::FakeAiEngine,
        )

        advanceUntilIdle()
        val saved = viewModel.continueSavedGame()

        assertFalse(viewModel.uiState.isLoadingSavedGame)
        assertTrue(viewModel.uiState.savedGameAvailable)
        assertEquals(Difficulty.MEDIUM, saved?.difficulty)
        assertArrayEquals(original, saved?.engineState)
    }

    private fun viewModel(): ChineseChessSetupViewModel =
        ChineseChessSetupViewModel(
            sessionRepository = FakeSessionRepository(LoadGameSessionResult.NotFound),
            unlockedDifficulties = setOf(Difficulty.EASY),
            engineFactory = ::FakeAiEngine,
        )

    private class FakeSessionRepository(
        private val result: LoadGameSessionResult,
    ) : GameSessionRepository {
        override suspend fun load(gameType: GameType): LoadGameSessionResult = result

        override suspend fun save(snapshot: GameSessionSnapshot) = Unit

        override suspend fun clear(gameType: GameType) = Unit
    }

    private class FakeAiEngine : ChineseChessAiEngine {
        override val gameType = GameType.CHINESE_CHESS
        override val currentPlayer = PlayerId(ChineseChessSide.RED.code)

        override fun reset() = Unit

        override fun apply(action: BoardMove): ActionResult = ActionResult.Accepted

        override fun undo(): Boolean = false

        override fun legalActions(): List<BoardMove> =
            listOf(BoardMove(BoardPosition(0, 9), BoardPosition(0, 8)))

        override fun chooseMove(difficulty: Difficulty): BoardMove? = legalActions().single()

        override fun gameResult(): GameResult = GameResult.ONGOING

        override fun serialize(): ByteArray = byteArrayOf(1)

        override fun restore(data: ByteArray): RestoreResult = RestoreResult.Restored

        override fun pieceAt(position: BoardPosition): ChineseChessPiece? = null

        override fun close() = Unit
    }
}
