package com.masterofchessstrategy.custom

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.masterofchessstrategy.data.GameSessionRepository
import com.masterofchessstrategy.data.LoadGameSessionResult
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.ChineseChessAiEngine
import com.masterofchessstrategy.engine.ChineseChessBoard
import com.masterofchessstrategy.engine.ChineseChessPiece
import com.masterofchessstrategy.engine.ChineseChessPieceType
import com.masterofchessstrategy.engine.ChineseChessPositionCodec
import com.masterofchessstrategy.engine.ChineseChessSide
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.NativeChineseChessEngine
import com.masterofchessstrategy.engine.PositionedChineseChessPiece
import com.masterofchessstrategy.engine.RestoreResult
import kotlinx.coroutines.launch

internal enum class ChineseChessSetupFeedback {
    INVALID_PIECE_COUNT,
    INVALID_PLACEMENT,
    MISSING_GENERALS,
    POSITION_NOT_PLAYABLE,
    ENGINE_UNAVAILABLE,
}

internal data class PreparedCustomPosition(
    val engineState: ByteArray,
    val difficulty: Difficulty,
) {
    fun defensiveCopy(): PreparedCustomPosition = copy(engineState = engineState.copyOf())
}

internal data class ChineseChessSetupUiState(
    val board: List<ChineseChessPiece?> = standardBoard(),
    val selectedSide: ChineseChessSide = ChineseChessSide.RED,
    val selectedPieceType: ChineseChessPieceType? = ChineseChessPieceType.SOLDIER,
    val sideToMove: ChineseChessSide = ChineseChessSide.RED,
    val difficulty: Difficulty = Difficulty.EASY,
    val unlockedDifficulties: Set<Difficulty> = emptySet(),
    val isLoadingSavedGame: Boolean = true,
    val savedGameAvailable: Boolean = false,
    val feedback: ChineseChessSetupFeedback? = null,
) {
    init {
        require(board.size == ChineseChessBoard.WIDTH * ChineseChessBoard.HEIGHT)
    }
}

/** Edits and validates a legal custom start before handing it to the normal game state machine. */
internal class ChineseChessSetupViewModel(
    private val sessionRepository: GameSessionRepository,
    unlockedDifficulties: Set<Difficulty>,
    private val engineFactory: () -> ChineseChessAiEngine = {
        NativeChineseChessEngine()
    },
) : ViewModel() {
    private var savedPosition: PreparedCustomPosition? = null

    var uiState by mutableStateOf(
        ChineseChessSetupUiState(
            unlockedDifficulties = unlockedDifficulties.toSet(),
            difficulty = unlockedDifficulties.firstOrNull() ?: Difficulty.EASY,
        ),
    )
        private set

    init {
        loadSavedCustomGame()
    }

    fun selectSide(side: ChineseChessSide) {
        uiState = uiState.copy(selectedSide = side, feedback = null)
    }

    fun selectPieceType(type: ChineseChessPieceType?) {
        uiState = uiState.copy(selectedPieceType = type, feedback = null)
    }

    fun selectSideToMove(side: ChineseChessSide) {
        uiState = uiState.copy(sideToMove = side, feedback = null)
    }

    fun selectDifficulty(difficulty: Difficulty) {
        if (difficulty !in uiState.unlockedDifficulties) return
        uiState = uiState.copy(difficulty = difficulty, feedback = null)
    }

    fun onSquareTap(position: BoardPosition) {
        ChineseChessBoard.requireInside(position)
        val board = uiState.board.toMutableList()
        val index = position.index()
        val selectedType = uiState.selectedPieceType
        if (selectedType == null) {
            board[index] = null
            uiState = uiState.copy(board = board, feedback = null)
            return
        }
        val piece = ChineseChessPiece(selectedType, uiState.selectedSide)
        if (!isAllowedSquare(position, piece)) {
            uiState = uiState.copy(feedback = ChineseChessSetupFeedback.INVALID_PLACEMENT)
            return
        }
        if (selectedType == ChineseChessPieceType.GENERAL) {
            board.indices.forEach { square ->
                if (
                    board[square]?.side == piece.side &&
                    board[square]?.type == ChineseChessPieceType.GENERAL
                ) {
                    board[square] = null
                }
            }
        }
        board[index] = piece
        if (!withinPieceLimits(board)) {
            uiState = uiState.copy(feedback = ChineseChessSetupFeedback.INVALID_PIECE_COUNT)
            return
        }
        uiState = uiState.copy(board = board, feedback = null)
    }

    fun clearBoard() {
        uiState = uiState.copy(
            board = List(BOARD_SIZE) { null },
            feedback = null,
        )
    }

    fun resetStandardPosition() {
        uiState = uiState.copy(
            board = standardBoard(),
            sideToMove = ChineseChessSide.RED,
            feedback = null,
        )
    }

    fun prepareNewGame(): PreparedCustomPosition? {
        if (uiState.difficulty !in uiState.unlockedDifficulties) {
            uiState = uiState.copy(feedback = ChineseChessSetupFeedback.POSITION_NOT_PLAYABLE)
            return null
        }
        return validateAndEncode(uiState.board, uiState.sideToMove, uiState.difficulty)
    }

    fun continueSavedGame(): PreparedCustomPosition? = savedPosition?.defensiveCopy()

    private fun validateAndEncode(
        board: List<ChineseChessPiece?>,
        sideToMove: ChineseChessSide,
        difficulty: Difficulty,
    ): PreparedCustomPosition? {
        if (!withinPieceLimits(board)) {
            uiState = uiState.copy(feedback = ChineseChessSetupFeedback.INVALID_PIECE_COUNT)
            return null
        }
        if (
            ChineseChessSide.entries.any { side ->
                board.count {
                    it?.side == side && it.type == ChineseChessPieceType.GENERAL
                } != 1
            }
        ) {
            uiState = uiState.copy(feedback = ChineseChessSetupFeedback.MISSING_GENERALS)
            return null
        }
        val pieces = board.mapIndexedNotNull { index, piece ->
            piece?.let {
                val position = BoardPosition(
                    x = index % ChineseChessBoard.WIDTH,
                    y = index / ChineseChessBoard.WIDTH,
                )
                if (!isAllowedSquare(position, piece)) {
                    uiState = uiState.copy(
                        feedback = ChineseChessSetupFeedback.INVALID_PLACEMENT,
                    )
                    return null
                }
                PositionedChineseChessPiece(position, piece)
            }
        }
        val encoded = try {
            ChineseChessPositionCodec.encode(sideToMove, pieces)
        } catch (_: IllegalArgumentException) {
            uiState = uiState.copy(feedback = ChineseChessSetupFeedback.POSITION_NOT_PLAYABLE)
            return null
        }
        val playable = isPlayable(encoded, reportEngineFailure = true)
        if (!playable) {
            if (uiState.feedback == null) {
                uiState = uiState.copy(
                    feedback = ChineseChessSetupFeedback.POSITION_NOT_PLAYABLE,
                )
            }
            return null
        }
        uiState = uiState.copy(feedback = null)
        return PreparedCustomPosition(encoded, difficulty)
    }

    private fun isPlayable(
        encoded: ByteArray,
        reportEngineFailure: Boolean,
    ): Boolean =
        try {
            engineFactory().use { engine ->
                engine.restore(encoded) is RestoreResult.Restored &&
                    engine.gameResult() == GameResult.ONGOING &&
                    engine.legalActions().isNotEmpty()
            }
        } catch (_: RuntimeException) {
            if (reportEngineFailure) {
                uiState = uiState.copy(
                    feedback = ChineseChessSetupFeedback.ENGINE_UNAVAILABLE,
                )
            }
            false
        } catch (_: LinkageError) {
            if (reportEngineFailure) {
                uiState = uiState.copy(
                    feedback = ChineseChessSetupFeedback.ENGINE_UNAVAILABLE,
                )
            }
            false
        }

    private fun loadSavedCustomGame() {
        viewModelScope.launch {
            val loaded = try {
                sessionRepository.load(GameType.CHINESE_CHESS)
            } catch (_: RuntimeException) {
                uiState = uiState.copy(isLoadingSavedGame = false)
                return@launch
            }
            val snapshot = (loaded as? LoadGameSessionResult.Loaded)?.snapshot
            val prepared = if (
                snapshot?.mode == StoredGameMode.CUSTOM_POSITION &&
                snapshot.difficulty != null &&
                snapshot.difficulty in uiState.unlockedDifficulties &&
                snapshot.sessionVariantId.startsWith(CUSTOM_PREFIX)
            ) {
                try {
                    val initialState = CustomPositionStateCodec.decodeSessionVariant(
                        snapshot.sessionVariantId,
                    )
                    if (isPlayable(initialState, reportEngineFailure = false)) {
                        PreparedCustomPosition(
                            engineState = initialState,
                            difficulty = requireNotNull(snapshot.difficulty),
                        )
                    } else {
                        null
                    }
                } catch (_: RuntimeException) {
                    null
                }
            } else {
                null
            }
            savedPosition = prepared
            uiState = uiState.copy(
                isLoadingSavedGame = false,
                savedGameAvailable = prepared != null,
            )
        }
    }

    companion object {
        fun factory(
            repository: GameSessionRepository,
            unlockedDifficulties: Set<Difficulty>,
            engineFactory: () -> ChineseChessAiEngine = {
                NativeChineseChessEngine()
            },
        ): ViewModelProvider.Factory =
            viewModelFactory {
                initializer {
                    ChineseChessSetupViewModel(
                        sessionRepository = repository,
                        unlockedDifficulties = unlockedDifficulties,
                        engineFactory = engineFactory,
                    )
                }
            }

        private const val CUSTOM_PREFIX = CustomPositionStateCodec.PREFIX
        private const val BOARD_SIZE = ChineseChessBoard.WIDTH * ChineseChessBoard.HEIGHT
        private val MAX_PIECES = mapOf(
            ChineseChessPieceType.GENERAL to 1,
            ChineseChessPieceType.ADVISOR to 2,
            ChineseChessPieceType.ELEPHANT to 2,
            ChineseChessPieceType.HORSE to 2,
            ChineseChessPieceType.CHARIOT to 2,
            ChineseChessPieceType.CANNON to 2,
            ChineseChessPieceType.SOLDIER to 5,
        )

        private fun withinPieceLimits(board: List<ChineseChessPiece?>): Boolean =
            ChineseChessSide.entries.all { side ->
                ChineseChessPieceType.entries.all { type ->
                    board.count { it?.side == side && it.type == type } <=
                        requireNotNull(MAX_PIECES[type])
                }
            }

        private fun isAllowedSquare(
            position: BoardPosition,
            piece: ChineseChessPiece,
        ): Boolean =
            when (piece.type) {
                ChineseChessPieceType.GENERAL,
                ChineseChessPieceType.ADVISOR,
                -> position.x in 3..5 && if (piece.side == ChineseChessSide.RED) {
                    position.y in 7..9
                } else {
                    position.y in 0..2
                }

                ChineseChessPieceType.ELEPHANT ->
                    if (piece.side == ChineseChessSide.RED) {
                        position.y in 5..9
                    } else {
                        position.y in 0..4
                    }

                else -> true
            }

        private fun BoardPosition.index(): Int = y * ChineseChessBoard.WIDTH + x
    }
}

private fun standardBoard(): List<ChineseChessPiece?> {
    val board = MutableList<ChineseChessPiece?>(
        ChineseChessBoard.WIDTH * ChineseChessBoard.HEIGHT,
    ) { null }
    fun put(x: Int, y: Int, type: ChineseChessPieceType, side: ChineseChessSide) {
        board[y * ChineseChessBoard.WIDTH + x] = ChineseChessPiece(type, side)
    }
    val backRank = listOf(
        ChineseChessPieceType.CHARIOT,
        ChineseChessPieceType.HORSE,
        ChineseChessPieceType.ELEPHANT,
        ChineseChessPieceType.ADVISOR,
        ChineseChessPieceType.GENERAL,
        ChineseChessPieceType.ADVISOR,
        ChineseChessPieceType.ELEPHANT,
        ChineseChessPieceType.HORSE,
        ChineseChessPieceType.CHARIOT,
    )
    backRank.forEachIndexed { x, type ->
        put(x, 0, type, ChineseChessSide.BLACK)
        put(x, 9, type, ChineseChessSide.RED)
    }
    listOf(1, 7).forEach { x ->
        put(x, 2, ChineseChessPieceType.CANNON, ChineseChessSide.BLACK)
        put(x, 7, ChineseChessPieceType.CANNON, ChineseChessSide.RED)
    }
    listOf(0, 2, 4, 6, 8).forEach { x ->
        put(x, 3, ChineseChessPieceType.SOLDIER, ChineseChessSide.BLACK)
        put(x, 6, ChineseChessPieceType.SOLDIER, ChineseChessSide.RED)
    }
    return board
}
