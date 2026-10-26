package com.masterofchessstrategy.engine.internal

/** JNI declarations remain internal so callers cannot bypass the safe facade. */
internal object NativeBindings {
    external fun createLanzhouMahjong(seed: Long, dealer: Int, base: Long, viewer: Int): Long
    external fun releaseLanzhouMahjong(handle: Long)
    /** Phase, current/dealer, wall, pending, outcome and flags; excludes concealed hands. */
    external fun lanzhouMahjongStatus(handle: Long): IntArray
    /** Public melds/discards and only the fixed viewer's concealed tiles. */
    external fun lanzhouMahjongSeatView(handle: Long, seat: Int): IntArray
    external fun lanzhouMahjongNetScores(handle: Long): LongArray
    external fun lanzhouMahjongLegalActions(handle: Long): IntArray
    external fun applyLanzhouMahjongAction(handle: Long, kind: Int, tile: Int): Int
    external fun undoLanzhouMahjong(handle: Long): Boolean
    external fun serializeLanzhouMahjong(handle: Long): ByteArray
    external fun restoreLanzhouMahjong(handle: Long, data: ByteArray): Int
    external fun advanceLanzhouMahjongOtherSeats(handle: Long, difficulty: Int, seed: Long, stepLimit: Int): IntArray

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

    external fun createCompetitionMahjong(seed: Long, dealer: Int, wind: Int, viewer: Int): Long
    external fun releaseCompetitionMahjong(handle: Long)
    /** One atomic versioned public frame, including four seats and fan entries. */
    external fun competitionMahjongSnapshot(handle: Long): IntArray
    external fun competitionMahjongLegalActions(handle: Long): IntArray
    external fun applyCompetitionMahjongAction(handle: Long, kind: Int, tile: Int, sequence: Int): IntArray
    external fun advanceCompetitionMahjongBots(handle: Long, level: Int, seed: Long, budget: Int): Int
    external fun undoCompetitionMahjong(handle: Long): Boolean
    external fun serializeCompetitionMahjong(handle: Long): ByteArray
    external fun restoreCompetitionMahjong(handle: Long, data: ByteArray): Boolean
}
