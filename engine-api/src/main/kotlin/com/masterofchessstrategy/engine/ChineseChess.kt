package com.masterofchessstrategy.engine

/** Stable sides used by Chinese chess persistence and JNI. */
enum class ChineseChessSide(val code: Int) {
    RED(0),
    BLACK(1),
}

/** Stable Chinese chess piece codes shared with the C++ engine. */
enum class ChineseChessPieceType(val code: Int) {
    GENERAL(1),
    ADVISOR(2),
    ELEPHANT(3),
    HORSE(4),
    CHARIOT(5),
    CANNON(6),
    SOLDIER(7),
}

data class ChineseChessPiece(
    val type: ChineseChessPieceType,
    val side: ChineseChessSide,
)

/** Board dimensions and coordinate validation for the 9 × 10 board. */
object ChineseChessBoard {
    const val WIDTH = 9
    const val HEIGHT = 10

    fun requireInside(position: BoardPosition) {
        require(position.x < WIDTH && position.y < HEIGHT) {
            "Chinese chess position is outside the board"
        }
    }
}

/** Typed game contract used by UI and business layers. */
interface ChineseChessRuleEngine : RuleEngine<BoardMove> {
    fun pieceAt(position: BoardPosition): ChineseChessPiece?
}

/**
 * Optional computer-player capability implemented by engines with search.
 *
 * A null move is valid only when the current position has no legal action.
 */
interface ChineseChessAiEngine : ChineseChessRuleEngine {
    fun chooseMove(difficulty: Difficulty): BoardMove?
}
