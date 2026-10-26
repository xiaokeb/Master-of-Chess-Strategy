package com.masterofchessstrategy.engine

/** Four-seat round phases are separate from the two-player GameResult contract. */
enum class LanzhouMahjongPhase(val code: Int) {
    DISCARD(0), DRAW(1), CLAIM(2), ROB_KONG(3), WON(4), EXHAUSTIVE_DRAW(5),
}

enum class LanzhouMahjongActionKind(val code: Int) {
    DRAW(0), DISCARD(1), READY_DISCARD(2), PASS(3), PUNG(4),
    EXPOSED_KONG(5), CONCEALED_KONG(6), ADDED_KONG(7), WIN(8),
}

/** Tile 255 is the native no-tile sentinel for actions such as DRAW. */
data class LanzhouMahjongAction(
    val kind: LanzhouMahjongActionKind,
    val tile: Int = 255,
) : GameAction {
    init { require(tile in 0..26 || tile == 255) }
}

enum class LanzhouMahjongMeldKind(val code: Int) {
    PUNG(0), EXPOSED_KONG(1), CONCEALED_KONG(2),
}

data class LanzhouMahjongMeld(
    val kind: LanzhouMahjongMeldKind,
    /** -1 hides an opponent's concealed kong; never pass it to a tile renderer. */
    val tile: Int,
    val fromSeat: Int,
) {
    val isTileHidden: Boolean get() = tile == -1
}

/** Opponents have a count, but never concealed tile values in this view. */
data class LanzhouMahjongSeatView(
    val seat: Int,
    val concealedCount: Int,
    val ready: Boolean,
    val responded: Boolean,
    val melds: List<LanzhouMahjongMeld>,
    val discards: List<Int>,
    val concealedTiles: List<Int>,
)

data class LanzhouMahjongOutcomeView(
    val winnerSeat: Int?,
    val payerSeat: Int?,
    val nextDealer: Int,
    val falseWin: Boolean,
    val sevenPairs: Boolean,
    val ready: Boolean,
    val kongDraw: Boolean,
    val lastTile: Boolean,
)

data class LanzhouMahjongPublicView(
    val phase: LanzhouMahjongPhase,
    val currentSeat: Int,
    val dealerSeat: Int,
    val wallRemaining: Int,
    val pendingTile: Int?,
    val pendingSeat: Int?,
    val outcome: LanzhouMahjongOutcomeView,
    val seats: List<LanzhouMahjongSeatView>,
    val netScores: List<Long>,
)

/** A fixed viewer seat owns one round; no method reveals other concealed hands. */
interface LanzhouMahjongRuleSession : AutoCloseable {
    val viewerSeat: Int
    fun snapshot(): LanzhouMahjongPublicView
    fun legalActions(): List<LanzhouMahjongAction>
    fun apply(action: LanzhouMahjongAction): ActionResult
    fun undo(): Boolean
    /** Full record is for controlled persistence, never for rendering a table. */
    fun serialize(): ByteArray
    fun restore(data: ByteArray): RestoreResult
}

enum class LanzhouMahjongBotStop(val code: Int) {
    VIEWER_ACTION(0), TERMINAL(1), STEP_LIMIT(2), REJECTED(3),
}

data class LanzhouMahjongBotAdvance(val stop: LanzhouMahjongBotStop, val steps: Int)

/** Optional bounded AI capability; viewer identity cannot change after creation. */
interface LanzhouMahjongBotSession : LanzhouMahjongRuleSession {
    fun advanceOtherSeats(
        difficulty: Difficulty,
        choiceSeed: Long,
        stepLimit: Int = 512,
    ): LanzhouMahjongBotAdvance
}
