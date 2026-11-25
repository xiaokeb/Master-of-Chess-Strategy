package com.masterofchessstrategy.engine

/** Score only from the caller's own tiles, melds, and public tile counts. */
object NativeCompetitionMahjongDiscardWaits {
    data class Discard(val tile: Int, val availableCopies: Int, val claimableCopies: Int)

    init { System.loadLibrary("mocs_engine_native") }

    fun evaluate(
        view: CompetitionMahjongPublicView,
        knownCopies: IntArray,
        candidates: List<Int>,
        scoring: CompetitionMahjongScoring,
    ): List<Discard> {
        val own = view.seats.single { it.seat == view.viewerSeat }
        val packs = own.melds.flatMap { meld ->
            val offer = if (meld.kind == CompetitionMahjongMeldKind.CHOW) meld.claimedPosition
                else (meld.fromSeat + 4 - view.viewerSeat) % 4
            listOf(meld.kind.code, checkNotNull(meld.tile), offer, if (meld.addedKong) 1 else 0)
        }.toIntArray()
        val result = analyze(own.concealedTiles.toIntArray(), packs, knownCopies, candidates.toIntArray(),
            (view.viewerSeat + 4 - view.dealerSeat) % 4, view.roundWind, scoring.recordVersion)
        check(result.size == candidates.size * 3)
        return candidates.indices.map { index ->
            val offset = index * 3
            check(result[offset] == candidates[index] && result[offset + 1] >= result[offset + 2])
            Discard(result[offset], result[offset + 1], result[offset + 2])
        }
    }

    private external fun analyze(
        hand: IntArray,
        melds: IntArray,
        knownCopies: IntArray,
        candidates: IntArray,
        seatWind: Int,
        roundWind: Int,
        scoring: Int,
    ): IntArray
}
