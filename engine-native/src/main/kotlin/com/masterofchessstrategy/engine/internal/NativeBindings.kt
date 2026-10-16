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

    external fun chineseChessNaturalLimitReview(handle: Long, sideCode: Int): IntArray

    external fun claimChineseChessNaturalLimit(handle: Long): Int
    external fun createGoEngine(size: Int, blackKomiQuarters: Int, handicapStones: Int): Long
    external fun releaseGoEngine(handle: Long)
    external fun goCurrentPlayer(handle: Long): Int
    external fun goKomiQuarters(handle: Long): Int
    external fun goHandicapStones(handle: Long): Int
    external fun resetGo(handle: Long)
    external fun applyGoAction(handle: Long, kind: Int, a: Int, b: Int, c: Int, d: Int): Int
    external fun undoGo(handle: Long): Boolean
    /** Each action is kind plus four argument fields. */
    external fun goLegalActions(handle: Long): IntArray
    external fun goGameResult(handle: Long): Int
    external fun serializeGo(handle: Long): ByteArray
    external fun restoreGo(handle: Long, data: ByteArray): Int
    external fun goStoneAt(handle: Long, x: Int, y: Int): Int
    /** phase, revision, confirmations, count, then x/y pairs. */
    external fun goScoringState(handle: Long): IntArray
    /** Empty until both sides confirm the current proposal. */
    external fun goFinalScore(handle: Long): IntArray
    external fun goPreviewScore(handle: Long): IntArray
    /** Standard UTF-8 bytes; SGF is not a full agreement save. */
    external fun exportGoSgf(handle: Long): ByteArray
    external fun restoreGoSgf(handle: Long, utf8: ByteArray): Int
    /** version, event count, then kind and four arguments per accepted event. */
    external fun goEventHistory(handle: Long): IntArray

}
