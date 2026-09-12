package com.masterofchessstrategy.engine

/**
 * Supported game families.
 *
 * Codes are part of the Kotlin/JNI protocol and must remain stable after release.
 */
enum class GameType(val code: Int) {
    CHINESE_CHESS(0),
    GO(1),
    STANDARD_MAHJONG(2),
    LANZHOU_MAHJONG(3),
    LANZHOU_SQUARE_CHESS(4),
    TIGER_AND_GOAT(5),
}

/**
 * Engine difficulty exposed to the application.
 *
 * Codes are persisted and passed across JNI, so existing values must not be reordered.
 */
enum class Difficulty(val code: Int) {
    EASY(0),
    MEDIUM(1),
    HARD(2),
    MASTER(3),
}

/** Zero-based identifier owned by the active game session. */
@JvmInline
value class PlayerId(val value: Int) {
    init {
        require(value >= 0) { "Player id must not be negative" }
    }
}

/** Marker for strongly typed actions accepted by a rule engine. */
sealed interface GameAction

/** Zero-based board coordinate. Board bounds remain game-specific. */
data class BoardPosition(
    val x: Int,
    val y: Int,
) {
    init {
        require(x >= 0 && y >= 0) { "Board coordinates must not be negative" }
    }
}

/** A move between two positions on a board. */
data class BoardMove(
    val from: BoardPosition,
    val to: BoardPosition,
) : GameAction

/** Terminal state shared by every supported game. */
enum class GameResult {
    ONGOING,
    FIRST_PLAYER_WIN,
    SECOND_PLAYER_WIN,
    DRAW,
}

/** Stable failure categories returned at the engine boundary. */
enum class EngineError {
    ILLEGAL_ACTION,
    INVALID_STATE,
    CORRUPTED_DATA,
    UNSUPPORTED,
}

/** Result of applying an action without using exceptions for expected rejection. */
sealed interface ActionResult {
    data object Accepted : ActionResult

    data class Rejected(val error: EngineError) : ActionResult
}

/** Result of restoring a serialized position. */
sealed interface RestoreResult {
    data object Restored : RestoreResult

    data class Rejected(val error: EngineError) : RestoreResult
}
