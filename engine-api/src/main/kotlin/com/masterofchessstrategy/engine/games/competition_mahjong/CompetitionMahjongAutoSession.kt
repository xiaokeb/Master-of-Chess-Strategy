package com.masterofchessstrategy.engine

/** Four physical-seat baseline strategies, not the product's accepted difficulty tiers. */
class CompetitionMahjongAutoConfig(
    val seed: Long,
    val dealerSeat: Int = 0,
    val roundWind: Int = 0,
    val viewerSeat: Int = 0,
    botLevels: List<Int> = List(4) { 0 },
    val decisionSeed: Long = seed,
) {
    private val levels = botLevels.toList()
    /** Defensive copies keep the native session's configuration immutable. */
    val botLevels: List<Int> get() = levels.toList()

    init {
        require(seed >= 0 && decisionSeed >= 0)
        require(dealerSeat in 0..3 && roundWind in 0..3 && viewerSeat in 0..3)
        require(levels.size == 4 && levels.all { it in 0..3 })
    }
}

data class CompetitionMahjongAutoAdvance(val acceptedActions: Int, val terminal: Boolean) {
    init {
        require(acceptedActions in 0..32)
        require(acceptedActions > 0 || terminal)
    }
}

/** All four seats act; the immutable viewer is only a privacy projection. */
interface CompetitionMahjongAutoSession : AutoCloseable {
    val config: CompetitionMahjongAutoConfig
    fun snapshot(): CompetitionMahjongPublicView
    /** Atomic 1..32 event batch. A terminal hand stays terminal; no implicit next hand. */
    fun advance(maxActions: Int = 1): CompetitionMahjongAutoAdvance
    /** One native event, not a player's decision or a refundable product undo allowance. */
    fun undo(): Boolean
    /** Private MCMA record, containing hidden hands/wall. Never render or publicly share it. */
    fun serialize(): ByteArray
    /** Replays a candidate; failures preserve this session. Viewer/levels/decision seed must match.
     * Dealer/wind and the actual wall come from the record, not the initial seed. */
    fun restore(data: ByteArray): RestoreResult
}
