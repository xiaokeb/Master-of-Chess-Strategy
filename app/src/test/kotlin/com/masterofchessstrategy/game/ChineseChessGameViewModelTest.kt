package com.masterofchessstrategy.game

import com.masterofchessstrategy.engine.ActionResult
import com.masterofchessstrategy.engine.BoardMove
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.ChineseChessPiece
import com.masterofchessstrategy.engine.ChineseChessPieceType
import com.masterofchessstrategy.engine.ChineseChessRuleEngine
import com.masterofchessstrategy.engine.ChineseChessSide
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.PlayerId
import com.masterofchessstrategy.engine.RestoreResult
import com.masterofchessstrategy.data.StoredGameMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChineseChessGameViewModelTest {
    private val redRook = BoardPosition(0, 9)
    private val redRookDestination = BoardPosition(0, 8)
    private val blackRook = BoardPosition(0, 0)

    @Test
    fun initialSnapshotUsesEngineBoardAndRedStarts() {
        val viewModel = ChineseChessGameViewModel { FakeChineseChessEngine() }

        assertEquals(ChineseChessSide.RED, viewModel.uiState.currentSide)
        assertEquals(ChineseChessSide.RED, viewModel.uiState.pieceAt(redRook)?.side)
        assertEquals(ChineseChessSide.BLACK, viewModel.uiState.pieceAt(blackRook)?.side)
        assertFalse(viewModel.uiState.canUndo)
    }

    @Test
    fun selectingOwnPieceShowsOnlyItsLegalDestinations() {
        val viewModel = ChineseChessGameViewModel { FakeChineseChessEngine() }

        viewModel.onSquareTap(redRook)

        assertEquals(redRook, viewModel.uiState.selectedPosition)
        assertEquals(setOf(redRookDestination), viewModel.uiState.legalDestinations)
        assertNull(viewModel.uiState.feedback)
    }

    @Test
    fun legalDestinationAppliesMoveAndSwitchesSide() {
        val viewModel = ChineseChessGameViewModel { FakeChineseChessEngine() }

        viewModel.onSquareTap(redRook)
        viewModel.onSquareTap(redRookDestination)

        assertNull(viewModel.uiState.pieceAt(redRook))
        assertEquals(ChineseChessSide.RED, viewModel.uiState.pieceAt(redRookDestination)?.side)
        assertEquals(ChineseChessSide.BLACK, viewModel.uiState.currentSide)
        assertTrue(viewModel.uiState.canUndo)
        assertNull(viewModel.uiState.selectedPosition)
    }

    @Test
    fun wrongSideAndEmptyIntersectionReturnSpecificFeedback() {
        val viewModel = ChineseChessGameViewModel { FakeChineseChessEngine() }

        viewModel.onSquareTap(blackRook)
        assertEquals(ChineseChessFeedback.WRONG_SIDE, viewModel.uiState.feedback)

        viewModel.onSquareTap(BoardPosition(4, 4))
        assertEquals(ChineseChessFeedback.SELECT_OWN_PIECE, viewModel.uiState.feedback)
    }

    @Test
    fun undoAndRestartRestoreStableSessionState() {
        val engine = FakeChineseChessEngine()
        val viewModel = ChineseChessGameViewModel { engine }
        viewModel.onSquareTap(redRook)
        viewModel.onSquareTap(redRookDestination)

        viewModel.undo()
        assertEquals(ChineseChessSide.RED, viewModel.uiState.pieceAt(redRook)?.side)
        assertFalse(viewModel.uiState.canUndo)
        assertEquals(ChineseChessFeedback.MOVE_UNDONE, viewModel.uiState.feedback)

        viewModel.restart()
        assertEquals(1, engine.resetCalls)
        assertEquals(ChineseChessSide.RED, viewModel.uiState.currentSide)
        assertEquals(ChineseChessFeedback.GAME_RESTARTED, viewModel.uiState.feedback)
    }

    @Test
    fun linkageFailureProducesDisabledStateWithoutLeakingDetails() {
        val viewModel = ChineseChessGameViewModel {
            throw UnsatisfiedLinkError("C:\\private\\native.dll")
        }

        assertFalse(viewModel.uiState.isEngineAvailable)
        assertFalse(viewModel.uiState.canUndo)
        assertEquals(ChineseChessFeedback.ENGINE_UNAVAILABLE, viewModel.uiState.feedback)
    }

    @Test
    fun timedGameChargesOnlyActiveSideAndTimeoutLoses() {
        var now = 1_000L
        val viewModel = ChineseChessGameViewModel(
            nowEpochMillis = { now },
            mode = StoredGameMode.LOCAL_TWO_PLAYER,
            initialTimeControlMinutes = 5,
            clockTickIntervalMillis = null,
            engineFactory = { FakeChineseChessEngine() },
        )

        now += 2_000L
        viewModel.onSquareTap(redRook)
        viewModel.onSquareTap(redRookDestination)

        assertEquals(298_000L, viewModel.uiState.redRemainingMillis)
        assertEquals(300_000L, viewModel.uiState.blackRemainingMillis)

        now += 300_001L
        viewModel.synchronizeClock()

        assertEquals(0L, viewModel.uiState.blackRemainingMillis)
        assertEquals(GameResult.FIRST_PLAYER_WIN, viewModel.uiState.result)
        assertEquals(ChineseChessFeedback.TIME_EXPIRED, viewModel.uiState.feedback)
    }

    @Test
    fun drawRequiresTheOtherSideToAccept() {
        val viewModel = ChineseChessGameViewModel { FakeChineseChessEngine() }

        viewModel.offerOrAcceptDraw()
        assertEquals(ChineseChessSide.RED, viewModel.uiState.pendingDrawOfferSide)
        assertEquals(GameResult.ONGOING, viewModel.uiState.result)

        viewModel.onSquareTap(redRook)
        viewModel.onSquareTap(redRookDestination)
        viewModel.offerOrAcceptDraw()

        assertEquals(GameResult.DRAW, viewModel.uiState.result)
        assertNull(viewModel.uiState.pendingDrawOfferSide)
        assertEquals(ChineseChessFeedback.DRAW_ACCEPTED, viewModel.uiState.feedback)
    }

    private inner class FakeChineseChessEngine : ChineseChessRuleEngine {
        override val gameType = GameType.CHINESE_CHESS
        private val positions = mutableMapOf(
            redRook to ChineseChessPiece(ChineseChessPieceType.CHARIOT, ChineseChessSide.RED),
            blackRook to ChineseChessPiece(ChineseChessPieceType.CHARIOT, ChineseChessSide.BLACK),
        )
        private var player = PlayerId(ChineseChessSide.RED.code)
        private var canUndo = false
        var resetCalls = 0
            private set

        override val currentPlayer: PlayerId
            get() = player

        override fun reset() {
            resetCalls++
            positions.clear()
            positions[redRook] =
                ChineseChessPiece(ChineseChessPieceType.CHARIOT, ChineseChessSide.RED)
            positions[blackRook] =
                ChineseChessPiece(ChineseChessPieceType.CHARIOT, ChineseChessSide.BLACK)
            player = PlayerId(ChineseChessSide.RED.code)
            canUndo = false
        }

        override fun apply(action: BoardMove): ActionResult {
            if (action != BoardMove(redRook, redRookDestination)) {
                return ActionResult.Rejected(
                    com.masterofchessstrategy.engine.EngineError.ILLEGAL_ACTION,
                )
            }
            positions[redRookDestination] = positions.remove(redRook)!!
            player = PlayerId(ChineseChessSide.BLACK.code)
            canUndo = true
            return ActionResult.Accepted
        }

        override fun undo(): Boolean {
            if (!canUndo) return false
            positions[redRook] = positions.remove(redRookDestination)!!
            player = PlayerId(ChineseChessSide.RED.code)
            canUndo = false
            return true
        }

        override fun legalActions(): List<BoardMove> =
            if (positions.containsKey(redRook) && player.value == ChineseChessSide.RED.code) {
                listOf(BoardMove(redRook, redRookDestination))
            } else {
                emptyList()
            }

        override fun gameResult(): GameResult = GameResult.ONGOING

        override fun serialize(): ByteArray = byteArrayOf()

        override fun restore(data: ByteArray): RestoreResult = RestoreResult.Restored

        override fun pieceAt(position: BoardPosition): ChineseChessPiece? = positions[position]

        override fun close() = Unit
    }
}
