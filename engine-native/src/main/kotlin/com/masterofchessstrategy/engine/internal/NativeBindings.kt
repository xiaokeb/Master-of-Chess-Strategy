package com.masterofchessstrategy.engine.internal

/** JNI declarations remain internal so callers cannot bypass the safe facade. */
internal object NativeBindings {
    init {
        System.loadLibrary("mocs_engine_native")
    }

    external fun healthCheck(): String

    external fun createChineseChessEngine(): Long

    external fun releaseChineseChessEngine(handle: Long)

    external fun chineseChessCurrentPlayer(handle: Long): Int

    external fun resetChineseChess(handle: Long)

    external fun applyChineseChessMove(
        handle: Long,
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
    ): Int

    external fun undoChineseChess(handle: Long): Boolean

    external fun chineseChessLegalMoves(handle: Long): IntArray

    external fun chineseChessBestMove(
        handle: Long,
        difficultyCode: Int,
        networkPath: String?,
    ): IntArray

    external fun chineseChessGameResult(handle: Long): Int

    external fun serializeChineseChess(handle: Long): ByteArray

    external fun restoreChineseChess(handle: Long, data: ByteArray): Int

    external fun chineseChessPieceAt(handle: Long, x: Int, y: Int): Int

    external fun chineseChessIsInCheck(handle: Long, sideCode: Int): Boolean
}
