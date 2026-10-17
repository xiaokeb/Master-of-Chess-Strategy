package com.masterofchessstrategy.engine

enum class CompetitionMahjongRoundPhase(val code: Int) {
    DISCARD(0), DRAW(1), CLAIM(2), ROB_KONG(3), WON(4), EXHAUSTIVE_DRAW(5),
}

enum class CompetitionMahjongRoundActionKind(val code: Int) {
    DRAW(0), DISCARD(1), PASS(2), CHOW(3), PUNG(4),
    EXPOSED_KONG(5), CONCEALED_KONG(6), ADDED_KONG(7), WIN(8),
}

/** Seat is deliberately absent: only the session's fixed viewer may act. */
data class CompetitionMahjongAction(
    val kind: CompetitionMahjongRoundActionKind,
    val tile: Int = 0,
    val sequenceStart: Int = 0,
) : GameAction {
    init { require(tile in 0..33 && sequenceStart in 0..33) }
}

enum class CompetitionMahjongRoundError(val code: Int) {
    NONE(0), WRONG_PHASE(1), WRONG_PLAYER(2), ILLEGAL_ACTION(3),
    ALREADY_RESPONDED(4), CORRUPTED_DATA(5), ACTION_LIMIT(6),
}

data class CompetitionMahjongApplyResult(
    val accepted: Boolean,
    val error: CompetitionMahjongRoundError,
    val falseWin: Boolean,
)

enum class CompetitionMahjongMeldKind(val code: Int) { CHOW(1), PUNG(2), KONG(3) }

data class CompetitionMahjongPublicMeld(
    val kind: CompetitionMahjongMeldKind,
    /** Other seats' concealed Kongs are hidden in play, revealed after win/draw in frame v2. */
    val tile: Int?,
    val fromSeat: Int,
    val claimedPosition: Int,
    val addedKong: Boolean,
)

data class CompetitionMahjongSeatView(
    val seat: Int,
    val concealedCount: Int,
    val winDisabled: Boolean,
    val flowers: List<Int>,
    val discards: List<Int>,
    val melds: List<CompetitionMahjongPublicMeld>,
    val concealedTiles: List<Int>,
)

data class CompetitionMahjongFanEntry(val id: Int, val name: String, val points: Int, val count: Int)

data class CompetitionMahjongFanView(
    val errorCode: Int,
    val structureValid: Boolean,
    val fanWithoutFlowers: Int,
    val totalFan: Int,
    val qualifies: Boolean,
    val entries: List<CompetitionMahjongFanEntry>,
)

/** Four-seat settlement stays independent of the two-player GameResult enum. */
data class CompetitionMahjongPublicView(
    val viewerSeat: Int,
    val phase: CompetitionMahjongRoundPhase,
    val dealerSeat: Int,
    val roundWind: Int,
    val currentSeat: Int,
    val wallRemaining: Int,
    val pendingTile: Int?,
    val pendingSeat: Int?,
    val winnerSeat: Int?,
    val payerSeat: Int?,
    val nextDealer: Int,
    val netScores: List<Int>,
    val winningFan: CompetitionMahjongFanView,
    val seats: List<CompetitionMahjongSeatView>,
    /** Eligibility to actively declare (possibly falsely), not an eight-point qualification. */
    val canDeclareWin: Boolean = false,
    /** Only the viewer's freshly received tile; absent after chow/pung and outside local discard. */
    val drawnTile: Int? = null,
    /** Public only after a win; contained once or more in winningTiles. */
    val winningTile: Int? = null,
    /** Winner's concealed portion, including the winning tile; other hands remain private. */
    val winningTiles: List<Int> = emptyList(),
    /** Version 1 lacks the new decision/revelation fields; never infer them from private records. */
    val publicFrameVersion: Int = 1,
)

interface CompetitionMahjongRuleSession : AutoCloseable {
    val viewerSeat: Int
    fun snapshot(): CompetitionMahjongPublicView
    fun legalActions(): List<CompetitionMahjongAction>
    fun apply(action: CompetitionMahjongAction): CompetitionMahjongApplyResult
    /** Baseline levels 0..3, not a claim of accepted product strength. */
    fun advanceBots(level: Int, seed: Long, maxActions: Int = 32): Int
    fun undo(): Boolean
    /** Private complete record; never use it to render opponents' hands. */
    fun serialize(): ByteArray
    fun restore(data: ByteArray): RestoreResult
}
