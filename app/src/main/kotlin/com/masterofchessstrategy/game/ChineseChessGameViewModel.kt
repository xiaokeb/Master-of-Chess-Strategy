package com.masterofchessstrategy.game

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.masterofchessstrategy.engine.ActionResult
import com.masterofchessstrategy.engine.BoardMove
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.ChineseChessBoard
import com.masterofchessstrategy.engine.ChineseChessRuleEngine
import com.masterofchessstrategy.engine.ChineseChessSide
import com.masterofchessstrategy.engine.EngineError
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.NativeChineseChessEngine

/**
 * Owns one local Chinese chess session and is the only UI-layer JNI consumer.
 *
 * Expected illegal moves remain normal state transitions. Runtime or linkage
 * failures close the session and leave the screen in a stable disabled state.
 */
class ChineseChessGameViewModel internal constructor(
    private val engineFactory: () -> ChineseChessRuleEngine,
) : ViewModel() {
    private var engine: ChineseChessRuleEngine? = null
    private var acceptedMoveCount = 0

    var uiState by mutableStateOf(ChineseChessGameUiState())
        private set

    constructor() : this(engineFactory = { NativeChineseChessEngine() })

    init {
        try {
            engine = engineFactory()
            refresh()
        } catch (_: RuntimeException) {
            disableEngine()
        } catch (_: LinkageError) {
            disableEngine()
        }
    }

    fun onSquareTap(position: BoardPosition) {
        ChineseChessBoard.requireInside(position)
        val activeEngine = engine ?: return
        if (uiState.result != GameResult.ONGOING) {
            uiState = uiState.copy(feedback = ChineseChessFeedback.GAME_FINISHED)
            return
        }

        val piece = uiState.pieceAt(position)
        val selected = uiState.selectedPosition
        if (selected == null) {
            selectPiece(activeEngine, position, piece?.side)
            return
        }

        when {
            position == selected -> clearSelection()
            piece?.side == uiState.currentSide -> selectPiece(activeEngine, position, piece.side)
            position in uiState.legalDestinations -> applyMove(activeEngine, BoardMove(selected, position))
            else -> uiState = uiState.copy(feedback = ChineseChessFeedback.ILLEGAL_MOVE)
        }
    }

    fun undo() {
        val activeEngine = engine ?: return
        if (!uiState.canUndo) {
            uiState = uiState.copy(feedback = ChineseChessFeedback.NOTHING_TO_UNDO)
            return
        }
        runEngineOperation {
            if (activeEngine.undo()) {
                acceptedMoveCount--
                refresh(ChineseChessFeedback.MOVE_UNDONE)
            } else {
                acceptedMoveCount = 0
                refresh(ChineseChessFeedback.NOTHING_TO_UNDO)
            }
        }
    }

    fun restart() {
        val activeEngine = engine ?: return
        runEngineOperation {
            activeEngine.reset()
            acceptedMoveCount = 0
            refresh(ChineseChessFeedback.GAME_RESTARTED)
        }
    }

    fun dismissFeedback() {
        uiState = uiState.copy(feedback = null)
    }

    override fun onCleared() {
        closeEngine()
    }

    private fun selectPiece(
        activeEngine: ChineseChessRuleEngine,
        position: BoardPosition,
        side: ChineseChessSide?,
    ) {
        if (side == null) {
            uiState = uiState.copy(feedback = ChineseChessFeedback.SELECT_OWN_PIECE)
            return
        }
        if (side != uiState.currentSide) {
            uiState = uiState.copy(feedback = ChineseChessFeedback.WRONG_SIDE)
            return
        }
        runEngineOperation {
            val destinations = activeEngine.legalActions()
                .asSequence()
                .filter { it.from == position }
                .map { it.to }
                .toSet()
            uiState = uiState.copy(
                selectedPosition = position,
                legalDestinations = destinations,
                feedback = if (destinations.isEmpty()) {
                    ChineseChessFeedback.ILLEGAL_MOVE
                } else {
                    null
                },
            )
        }
    }

    private fun applyMove(
        activeEngine: ChineseChessRuleEngine,
        move: BoardMove,
    ) {
        runEngineOperation {
            when (val result = activeEngine.apply(move)) {
                ActionResult.Accepted -> {
                    acceptedMoveCount++
                    refresh()
                }

                is ActionResult.Rejected -> {
                    uiState = uiState.copy(feedback = result.error.toFeedback())
                }
            }
        }
    }

    private fun refresh(feedback: ChineseChessFeedback? = null) {
        val activeEngine = engine ?: return
        runEngineOperation {
            val board = buildList(ChineseChessBoard.WIDTH * ChineseChessBoard.HEIGHT) {
                repeat(ChineseChessBoard.HEIGHT) { y ->
                    repeat(ChineseChessBoard.WIDTH) { x ->
                        add(activeEngine.pieceAt(BoardPosition(x, y)))
                    }
                }
            }
            uiState = ChineseChessGameUiState(
                board = board,
                currentSide = when (activeEngine.currentPlayer.value) {
                    ChineseChessSide.RED.code -> ChineseChessSide.RED
                    ChineseChessSide.BLACK.code -> ChineseChessSide.BLACK
                    else -> error("Engine returned an unsupported player")
                },
                result = activeEngine.gameResult(),
                canUndo = acceptedMoveCount > 0,
                feedback = feedback,
            )
        }
    }

    private fun clearSelection() {
        uiState = uiState.copy(
            selectedPosition = null,
            legalDestinations = emptySet(),
            feedback = null,
        )
    }

    private inline fun runEngineOperation(operation: () -> Unit) {
        try {
            operation()
        } catch (_: RuntimeException) {
            disableEngine()
        } catch (_: LinkageError) {
            disableEngine()
        }
    }

    private fun disableEngine() {
        closeEngine()
        uiState = uiState.copy(
            selectedPosition = null,
            legalDestinations = emptySet(),
            canUndo = false,
            isEngineAvailable = false,
            feedback = ChineseChessFeedback.ENGINE_UNAVAILABLE,
        )
    }

    private fun closeEngine() {
        val activeEngine = engine
        engine = null
        try {
            activeEngine?.close()
        } catch (_: RuntimeException) {
            // Cleanup must not replace the stable user-facing failure state.
        } catch (_: LinkageError) {
            // The native library may already be unavailable during teardown.
        }
    }

    private fun EngineError.toFeedback(): ChineseChessFeedback =
        when (this) {
            EngineError.ILLEGAL_ACTION -> ChineseChessFeedback.ILLEGAL_MOVE
            EngineError.INVALID_STATE,
            EngineError.CORRUPTED_DATA,
            EngineError.UNSUPPORTED,
            -> ChineseChessFeedback.MOVE_REJECTED
        }
}
