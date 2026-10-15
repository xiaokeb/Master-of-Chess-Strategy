package com.masterofchessstrategy.games.tiger_goat

internal class TigerGoatPassFixture : TigerGoatRulesSession {
    override val spec = TigerGoatBoardSpec(List(4) { TigerGoatPoint(it, it, 0) },
        listOf(TigerGoatEdge(0, 1), TigerGoatEdge(1, 2), TigerGoatEdge(2, 3)))
    var count = 0
    var side = 2
    var reserve = 0
    var phase = TigerGoatPhase.PLAY
    var mobile = false
    var omitPass = false
    var throwAfterApply = false
    override val actionCount get() = count
    override fun snapshot() = TigerGoatSnapshot(listOf(1, 2, 2, 1), phase, side, reserve,
        10, TigerGoatWinner.NONE)
    override fun legalActions() = when {
        omitPass || side != 2 -> emptyList()
        mobile -> listOf(TigerGoatAction(TigerGoatActionKind.STEP, 1, 2))
        reserve > 0 -> listOf(TigerGoatAction(TigerGoatActionKind.PLACE_GOAT, to = 0))
        else -> listOf(TigerGoatAction(TigerGoatActionKind.PASS_GOAT))
    }
    override fun apply(action: TigerGoatAction): Int {
        if (action !in legalActions()) return 2
        count++
        side = 1
        if (throwAfterApply) error("Mutation followed by failed snapshot")
        return 0
    }
    override fun undo(): Boolean {
        if (count == 0) return false
        count--
        side = 2
        return true
    }
    override fun reset() { count = 0; side = 2 }
    override fun serialize() = byteArrayOf(count.toByte(), side.toByte())
    override fun restore(bytes: ByteArray): Boolean {
        if (bytes.size != 2) return false
        count = bytes[0].toInt()
        side = bytes[1].toInt()
        return true
    }
    override fun close() = Unit
}
