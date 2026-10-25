package com.masterofchessstrategy.engine.games.lanzhou_fangqi

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult

data class FangqiPoint(val x: Int, val y: Int) {
    init {
        require(x in 0..6 && y in 0..6) { "Fangqi coordinates must be inside the 7 by 7 board" }
    }

    val index: Int get() = y * 7 + x
}

enum class FangqiSide(val code: Int) {
    RED(0),
    BLACK(1);

    val pieceCode: Int get() = code + 1
    val opponent: FangqiSide get() = if (this == RED) BLACK else RED
}

sealed interface FangqiAction {
    data class Move(val from: FangqiPoint, val to: FangqiPoint) : FangqiAction
    data class Capture(val point: FangqiPoint) : FangqiAction
}

/** Stable Fangqi protocol codes, mapped explicitly from the native rule errors. */
enum class FangqiRejection(val code: Int) {
    NONE(0),
    GAME_OVER(1),
    CAPTURE_REQUIRED(2),
    NO_CAPTURE_DUE(3),
    OUT_OF_BOUNDS(4),
    WRONG_PIECE(5),
    OCCUPIED(6),
    NOT_ADJACENT(7),
    REPEATED_POSITION(8),
    INVALID_ACTION(9),
    RESOURCE_LIMIT(10),
}

data class FangqiMoveResult(val accepted: Boolean, val rejection: FangqiRejection) {
    init {
        require(accepted == (rejection == FangqiRejection.NONE))
    }
}

/** Board entries use row-major order: 0 empty, 1 red, 2 black. */
data class FangqiSnapshot(
    val board: List<Int>,
    val currentPlayer: FangqiSide,
    val pendingCaptures: Int,
    val result: GameResult,
    val canUndo: Boolean,
)

interface FangqiSession : AutoCloseable {
    fun snapshot(): FangqiSnapshot
    fun apply(action: FangqiAction): FangqiMoveResult
    fun legalActions(): List<FangqiAction>
    /** Undo the entire latest turn, including any captures already selected. */
    fun undo(): Boolean
    fun reset()
    fun serialize(): ByteArray
    fun restore(data: ByteArray): Boolean
    fun chooseAction(difficulty: Difficulty): FangqiAction?
}
