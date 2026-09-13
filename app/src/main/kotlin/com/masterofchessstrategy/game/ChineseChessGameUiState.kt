package com.masterofchessstrategy.game

import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.ChineseChessBoard
import com.masterofchessstrategy.engine.ChineseChessPiece
import com.masterofchessstrategy.engine.ChineseChessSide
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult

/** User-facing outcomes. Text is resolved by Compose so this state stays resource-free. */
enum class ChineseChessFeedback {
    SELECT_OWN_PIECE,
    WRONG_SIDE,
    ILLEGAL_MOVE,
    MOVE_REJECTED,
    NOTHING_TO_UNDO,
    MOVE_UNDONE,
    GAME_RESTARTED,
    GAME_FINISHED,
    ENGINE_UNAVAILABLE,
    GAME_RESTORED,
    RESTORE_REJECTED,
    SAVE_FAILED,
    AI_MOVED,
    AI_MOVE_FAILED,
    UNDO_LIMIT_REACHED,
    HINT_READY,
    HINT_LIMIT_REACHED,
    HINT_UNAVAILABLE,
    PLAYER_RESIGNED,
    DRAW_OFFERED,
    DRAW_WAITING,
    DRAW_ACCEPTED,
    DRAW_DECLINED,
    TIME_EXPIRED,
    AUTO_PLAY_PAUSED,
    AUTO_PLAY_RESUMED,
}

/**
 * Immutable snapshot consumed by the game screen.
 *
 * The board is row-major: index = y * 9 + x. Native resources never cross this
 * boundary, which keeps previews and state tests independent from JNI.
 */
data class ChineseChessGameUiState(
    val board: List<ChineseChessPiece?> = List(ChineseChessBoard.WIDTH * ChineseChessBoard.HEIGHT) {
        null
    },
    val currentSide: ChineseChessSide = ChineseChessSide.RED,
    val selectedPosition: BoardPosition? = null,
    val legalDestinations: Set<BoardPosition> = emptySet(),
    val hintedOrigins: Set<BoardPosition> = emptySet(),
    val hintedDestinations: Set<BoardPosition> = emptySet(),
    val result: GameResult = GameResult.ONGOING,
    val canUndo: Boolean = false,
    val undoRemaining: Int? = null,
    val canRequestHint: Boolean = false,
    val hintRemaining: Int? = 0,
    val isEngineAvailable: Boolean = true,
    val isRestoring: Boolean = false,
    val isPersisting: Boolean = false,
    val isAiGame: Boolean = false,
    val isAutoPlay: Boolean = false,
    val isAutoPlayPaused: Boolean = false,
    val autoPlaySpeed: Float = 1f,
    val completedAutoGames: Int = 0,
    val autoContinueGameLimit: Int = 0,
    val difficulty: Difficulty? = null,
    val timeControlMinutes: Int? = null,
    val redRemainingMillis: Long? = null,
    val blackRemainingMillis: Long? = null,
    val pendingDrawOfferSide: ChineseChessSide? = null,
    val canOfferOrAcceptDraw: Boolean = false,
    val isAiThinking: Boolean = false,
    val isHintThinking: Boolean = false,
    val feedback: ChineseChessFeedback? = null,
) {
    val isInteractionEnabled: Boolean
        get() =
            isEngineAvailable &&
                !isRestoring &&
                !isPersisting &&
                !isAiThinking &&
                !isHintThinking &&
                !isAutoPlay &&
                (!isAiGame || currentSide == ChineseChessSide.RED)

    init {
        require(board.size == ChineseChessBoard.WIDTH * ChineseChessBoard.HEIGHT) {
            "Chinese chess board must contain exactly 90 intersections"
        }
    }

    fun pieceAt(position: BoardPosition): ChineseChessPiece? {
        ChineseChessBoard.requireInside(position)
        return board[position.y * ChineseChessBoard.WIDTH + position.x]
    }
}
