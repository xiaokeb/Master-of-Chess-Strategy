package com.masterofchessstrategy.games.competition_mahjong.match.hint

import com.masterofchessstrategy.engine.*

/** Public-information combination heuristic. Scores are neither fan nor a promise of a live waiting tile. */
object CompetitionMahjongPublicShapeHintAdvisor : CompetitionMahjongHintAdvisor {
    override val identity = "public-shape-v1"

    override suspend fun recommend(input: CompetitionMahjongHintInput, decisionSeed: Long): CompetitionMahjongAction? {
        val view = input.view
        if (view.publicFrameVersion != 2 || view.phase in listOf(CompetitionMahjongRoundPhase.WON,
                CompetitionMahjongRoundPhase.EXHAUSTIVE_DRAW)) return null
        if (view.viewerSeat !in 0..3 || view.seats.map { it.seat }.sorted() != listOf(0, 1, 2, 3)) return null
        val own = view.seats.singleOrNull { it.seat == view.viewerSeat } ?: return null
        if (own.concealedTiles.size != own.concealedCount || own.concealedTiles.size > 14 || own.melds.size > 4 ||
            own.concealedTiles.any { it !in 0..33 }) return null
        val counts = IntArray(34)
        own.concealedTiles.forEach { counts[it]++ }
        if (counts.any { it > 4 }) return null
        val actions = input.actions.distinct().sortedWith(compareBy({ it.kind.code }, { it.tile }, { it.sequenceStart }))
        // Native legal_actions emits WIN only after evaluate_hu(...).qualifies; active declarations use a separate UI path.
        actions.firstOrNull { it.kind == CompetitionMahjongRoundActionKind.WIN && !own.winDisabled }?.let { return it }
        actions.firstOrNull { it.kind == CompetitionMahjongRoundActionKind.DRAW }?.let { return it }
        val known = counts.copyOf()
        for (seat in view.seats) {
            for (tile in seat.discards) if (tile in 0..33) known[tile]++
            for (meld in seat.melds) {
                val tile = meld.tile ?: continue
                if (tile !in 0..33) continue
                if (meld.kind == CompetitionMahjongMeldKind.CHOW && tile < 27 && tile % 9 <= 6)
                    repeat(3) { known[tile + it]++ }
                else if (meld.kind != CompetitionMahjongMeldKind.CHOW)
                    known[tile] += if (meld.kind == CompetitionMahjongMeldKind.KONG) 4 else 3
            }
        }
        val memo = HashMap<String, Int>()
        fun potential(hand: IntArray): Int {
            val key = hand.joinToString("")
            memo[key]?.let { return it }
            val tile = hand.indexOfFirst { it > 0 }
            if (tile < 0) return 0
            fun group(tiles: IntArray, value: Int): Int {
                tiles.forEach { hand[it]-- }
                val result = value + potential(hand)
                tiles.forEach { hand[it]++ }
                return result
            }
            var best = group(intArrayOf(tile), 0)
            if (hand[tile] >= 3) best = maxOf(best, group(intArrayOf(tile, tile, tile), 8))
            if (hand[tile] >= 2) best = maxOf(best, group(intArrayOf(tile, tile), 3))
            if (tile < 27) {
                if (tile % 9 <= 6 && hand[tile + 1] > 0 && hand[tile + 2] > 0)
                    best = maxOf(best, group(intArrayOf(tile, tile + 1, tile + 2), 8))
                for (gap in 1..2) if (tile % 9 + gap < 9 && hand[tile + gap] > 0)
                    best = maxOf(best, group(intArrayOf(tile, tile + gap), 3 - gap))
            }
            memo[key] = best
            return best
        }
        fun relatedAvailability(hand: IntArray): Int = (0..33).sumOf { tile ->
            var support = 2 * hand[tile]
            if (tile < 27) for (gap in listOf(-2, -1, 1, 2)) {
                if (tile % 9 + gap in 0..8) support += hand[tile + gap] * (3 - kotlin.math.abs(gap))
            }
            support * (4 - known[tile]).coerceAtLeast(0)
        }
        val discards = actions.filter { it.kind == CompetitionMahjongRoundActionKind.DISCARD && counts[it.tile] > 0 }
        if (discards.isNotEmpty()) return discards.maxWithOrNull(compareBy<CompetitionMahjongAction> { action ->
            val hand = counts.copyOf().also { it[action.tile]-- }
            potential(hand) * 1000 + relatedAvailability(hand)
        }.thenBy { -it.tile })
        val baseline = potential(counts)
        val pass = actions.firstOrNull { it.kind == CompetitionMahjongRoundActionKind.PASS }
        var best = pass
        var bestScore = baseline
        for (action in actions) {
            val hand = counts.copyOf()
            val removed = when (action.kind) {
                CompetitionMahjongRoundActionKind.CHOW -> {
                    if (action.sequenceStart >= 27 || action.sequenceStart % 9 > 6 ||
                        action.tile !in action.sequenceStart..action.sequenceStart + 2 || view.pendingTile != action.tile) continue
                    (action.sequenceStart..action.sequenceStart + 2).filter { it != action.tile }
                }
                CompetitionMahjongRoundActionKind.PUNG -> List(2) { action.tile }
                CompetitionMahjongRoundActionKind.EXPOSED_KONG -> List(3) { action.tile }
                CompetitionMahjongRoundActionKind.CONCEALED_KONG -> List(4) { action.tile }
                CompetitionMahjongRoundActionKind.ADDED_KONG -> List(1) { action.tile }
                else -> continue
            }
            removed.forEach { hand[it]-- }
            if (hand.any { it < 0 }) continue
            val bonus = if (action.kind == CompetitionMahjongRoundActionKind.ADDED_KONG) 0 else 8
            val mustDiscard = action.kind == CompetitionMahjongRoundActionKind.CHOW || action.kind == CompetitionMahjongRoundActionKind.PUNG
            val score = bonus + if (mustDiscard) (0..33).filter { hand[it] > 0 }.maxOfOrNull { tile ->
                hand[tile]--
                val value = potential(hand)
                hand[tile]++
                value
            }.let { it ?: Int.MIN_VALUE / 2 } else potential(hand)
            if (score > bestScore) { bestScore = score; best = action }
        }
        return best
    }
}
