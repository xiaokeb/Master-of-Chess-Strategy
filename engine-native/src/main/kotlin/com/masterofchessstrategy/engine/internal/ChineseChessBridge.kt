package com.masterofchessstrategy.engine.internal

internal interface ChineseChessBridge {
    fun create(): Long

    fun release(handle: Long)

    fun currentPlayer(handle: Long): Int

    fun reset(handle: Long)

    fun applyMove(
        handle: Long,
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
    ): Int

    fun undo(handle: Long): Boolean

    fun legalMoves(handle: Long): IntArray

    fun bestMove(
        handle: Long,
        difficultyCode: Int,
        networkPath: String?,
    ): IntArray

    fun gameResult(handle: Long): Int

    fun serialize(handle: Long): ByteArray

    fun restore(handle: Long, data: ByteArray): Int

    fun pieceAt(handle: Long, x: Int, y: Int): Int

    fun isInCheck(handle: Long, sideCode: Int): Boolean
}

internal object JniChineseChessBridge : ChineseChessBridge {
    override fun create(): Long = NativeBindings.createChineseChessEngine()

    override fun release(handle: Long) =
        NativeBindings.releaseChineseChessEngine(handle)

    override fun currentPlayer(handle: Long): Int =
        NativeBindings.chineseChessCurrentPlayer(handle)

    override fun reset(handle: Long) =
        NativeBindings.resetChineseChess(handle)

    override fun applyMove(
        handle: Long,
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
    ): Int =
        NativeBindings.applyChineseChessMove(
            handle,
            fromX,
            fromY,
            toX,
            toY,
        )

    override fun undo(handle: Long): Boolean =
        NativeBindings.undoChineseChess(handle)

    override fun legalMoves(handle: Long): IntArray =
        NativeBindings.chineseChessLegalMoves(handle)

    override fun bestMove(
        handle: Long,
        difficultyCode: Int,
        networkPath: String?,
    ): IntArray =
        NativeBindings.chineseChessBestMove(
            handle,
            difficultyCode,
            networkPath,
        )

    override fun gameResult(handle: Long): Int =
        NativeBindings.chineseChessGameResult(handle)

    override fun serialize(handle: Long): ByteArray =
        NativeBindings.serializeChineseChess(handle)

    override fun restore(handle: Long, data: ByteArray): Int =
        NativeBindings.restoreChineseChess(handle, data)

    override fun pieceAt(handle: Long, x: Int, y: Int): Int =
        NativeBindings.chineseChessPieceAt(handle, x, y)

    override fun isInCheck(handle: Long, sideCode: Int): Boolean =
        NativeBindings.chineseChessIsInCheck(handle, sideCode)
}
