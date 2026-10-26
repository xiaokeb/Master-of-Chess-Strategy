package com.masterofchessstrategy.games.lanzhou_mahjong

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.LanzhouMahjongAction
import com.masterofchessstrategy.engine.LanzhouMahjongActionKind as Kind
import com.masterofchessstrategy.engine.LanzhouMahjongMeldKind
import com.masterofchessstrategy.engine.LanzhouMahjongPhase
import com.masterofchessstrategy.engine.LanzhouMahjongPublicView
import com.masterofchessstrategy.engine.LanzhouMahjongSeatView

internal data class LanzhouMahjongHint(
    val actions: List<LanzhouMahjongAction>,
    val explanation: String,
    /** Tile kinds; counts of unseen tiles are estimates, never wall contents. */
    val waits: List<Int> = emptyList(),
)

/** Read-only advice. Native legal actions remain authoritative; hints never apply a move. */
internal object LanzhouMahjongHintPlanner {
    fun plan(
        view: LanzhouMahjongPublicView,
        viewerSeat: Int,
        difficulty: Difficulty,
        legalActions: List<LanzhouMahjongAction>,
    ): LanzhouMahjongHint? {
        if (difficulty == Difficulty.MASTER || viewerSeat !in 0..3 ||
            view.phase == LanzhouMahjongPhase.WON ||
            view.phase == LanzhouMahjongPhase.EXHAUSTIVE_DRAW) return null
        val own = view.seats.singleOrNull { it.seat == viewerSeat } ?: return null
        val counts = handCounts(own) ?: return null
        val legal = legalActions.distinct()
        if (legal.isEmpty()) return null
        if (difficulty == Difficulty.EASY) {
            return LanzhouMahjongHint(legal.toList(), "当前全部合法操作")
        }
        legal.firstOrNull { it.kind == Kind.WIN }?.let {
            return LanzhouMahjongHint(listOf(it), "可以胡牌")
        }
        legal.firstOrNull { it.kind == Kind.DRAW }?.let {
            return LanzhouMahjongHint(listOf(it), "先摸牌，再决定下一步")
        }
        val discards = legal.filter {
            (it.kind == Kind.DISCARD || it.kind == Kind.READY_DISCARD) &&
                it.tile in 0..26 && counts[it.tile] > 0
        }
        if (discards.isNotEmpty()) {
            val known = visibleCounts(view, own, counts)
            val ranked = discards.map { action ->
                val after = counts.copyOf().apply { this[action.tile]-- }
                val waits = waits(after, own)
                val available = waits.sumOf { (4 - known[it]).coerceAtLeast(0) }
                Candidate(action, waits, available,
                    discardScore(counts, own, action.tile))
            }
            // Prefer certified ready actions, then potential unseen winning copies.
            val best = ranked.maxWith(compareBy<Candidate>(
                { if (it.action.kind == Kind.READY_DISCARD) 1 else 0 },
                { it.available }, { it.waits.size }, { it.shapeScore },
                { -it.action.tile },
            ))
            val explanation = when {
                best.action.kind == Kind.READY_DISCARD ->
                    "建议报听；等张按本机手牌计算，未见张数不代表牌墙剩余张数"
                best.waits.isNotEmpty() -> "打出后可以等张；是否报听以当前合法操作为准"
                else -> "建议优先缺一门，并保留对子和连张"
            }
            return LanzhouMahjongHint(listOf(best.action), explanation, best.waits)
        }
        // Do not advise fixing a third suit by claiming a pung or kong.
        val missing = targetMissingSuit(counts, own)
        val claim = legal.firstOrNull {
            it.kind in listOf(Kind.EXPOSED_KONG, Kind.CONCEALED_KONG,
                Kind.ADDED_KONG, Kind.PUNG) &&
                (if (it.tile == 255) view.pendingTile else it.tile)?.let { tile ->
                    tile in 0..26 && tile / 9 != missing && canCommitSuit(own, tile / 9)
                } == true
        }
        val chosen = claim ?: legal.firstOrNull { it.kind == Kind.PASS } ?: return null
        return LanzhouMahjongHint(listOf(chosen),
            if (chosen.kind == Kind.PASS) "过牌，保留当前手牌与缺一门的空间"
            else "可以碰杠；该副露不会固定第三门")
    }

    private data class Candidate(
        val action: LanzhouMahjongAction,
        val waits: List<Int>,
        val available: Int,
        val shapeScore: Int,
    )

    private fun handCounts(own: LanzhouMahjongSeatView): IntArray? {
        if (own.melds.size > 4 || own.concealedTiles.size != own.concealedCount ||
            own.concealedCount !in 0..14 || own.melds.any { it.tile !in 0..26 }) return null
        val counts = IntArray(27)
        for (tile in own.concealedTiles) {
            if (tile !in 0..26 || ++counts[tile] > 4) return null
        }
        val inventory = counts.copyOf()
        for (meld in own.melds) {
            inventory[meld.tile] += if (meld.kind == LanzhouMahjongMeldKind.PUNG) 3 else 4
            if (inventory[meld.tile] > 4) return null
        }
        return counts
    }

    private fun visibleCounts(
        view: LanzhouMahjongPublicView,
        own: LanzhouMahjongSeatView,
        counts: IntArray,
    ): IntArray = counts.copyOf().apply {
        for (seat in view.seats) {
            // Ignore every opponent's concealedTiles, even in a malformed host view.
            for (tile in seat.discards) if (tile in 0..26) this[tile]++
            for (meld in seat.melds) {
                if (meld.tile in 0..26 &&
                    (seat.seat == own.seat || meld.kind != LanzhouMahjongMeldKind.CONCEALED_KONG)) {
                    this[meld.tile] += if (meld.kind == LanzhouMahjongMeldKind.PUNG) 3 else 4
                }
            }
        }
    }

    private fun canCommitSuit(own: LanzhouMahjongSeatView, suit: Int): Boolean =
        (own.melds.map { it.tile / 9 } + suit).distinct().size <= 2

    private fun targetMissingSuit(counts: IntArray, own: LanzhouMahjongSeatView): Int? {
        val committed = own.melds.map { it.tile / 9 }.toSet()
        val present = (0..2).filter { suit ->
            suit in committed || (suit * 9 until suit * 9 + 9).any { counts[it] > 0 }
        }
        if (present.size < 3) return null
        return (0..2).filter { it !in committed }
            .minWithOrNull(compareBy<Int>({ suit ->
                (suit * 9 until suit * 9 + 9).sumOf { counts[it] }
            }, { it }))
    }

    private fun discardScore(counts: IntArray, own: LanzhouMahjongSeatView, tile: Int): Int {
        val rank = tile % 9
        var score = if (tile / 9 == targetMissingSuit(counts, own)) 1000 else 0
        if (counts[tile] >= 2) score -= 18
        if (rank > 0) score -= counts[tile - 1] * 5
        if (rank < 8) score -= counts[tile + 1] * 5
        if (rank > 1) score -= counts[tile - 2] * 2
        if (rank < 7) score -= counts[tile + 2] * 2
        return score
    }

    /** Exact one-draw waits for the adopted four-meld/pair and closed seven-pairs shapes. */
    private fun waits(counts: IntArray, own: LanzhouMahjongSeatView): List<Int> {
        if (counts.sum() != 13 - own.melds.size * 3) return emptyList()
        val physical = counts.copyOf()
        for (meld in own.melds) {
            physical[meld.tile] += if (meld.kind == LanzhouMahjongMeldKind.PUNG) 3 else 4
        }
        return (0..26).filter { tile ->
            if (physical[tile] >= 4) return@filter false
            counts[tile]++
            try {
                val suits = (counts.indices.filter { counts[it] > 0 }.map { it / 9 } +
                    own.melds.map { it.tile / 9 }).distinct()
                suits.size <= 2 && (
                    (own.melds.isEmpty() && counts.all { it % 2 == 0 }) ||
                        fourMeldPair(counts, 4 - own.melds.size))
            } finally { counts[tile]-- }
        }
    }

    private fun fourMeldPair(counts: IntArray, needed: Int): Boolean {
        for (tile in counts.indices) {
            if (counts[tile] < 2) continue
            counts[tile] -= 2
            val matches = try { melds(counts, needed) } finally { counts[tile] += 2 }
            if (matches) return true
        }
        return false
    }

    private fun melds(counts: IntArray, needed: Int): Boolean {
        val first = counts.indexOfFirst { it > 0 }
        if (first < 0) return needed == 0
        if (needed == 0) return false
        if (counts[first] >= 3) {
            counts[first] -= 3
            val matches = try { melds(counts, needed - 1) } finally { counts[first] += 3 }
            if (matches) return true
        }
        if (first % 9 <= 6 && counts[first + 1] > 0 && counts[first + 2] > 0) {
            counts[first]--; counts[first + 1]--; counts[first + 2]--
            val matches = try { melds(counts, needed - 1) } finally {
                counts[first]++; counts[first + 1]++; counts[first + 2]++
            }
            if (matches) return true
        }
        return false
    }
}
