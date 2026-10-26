package com.masterofchessstrategy.games.lanzhou_mahjong

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.LanzhouMahjongAction
import com.masterofchessstrategy.engine.LanzhouMahjongActionKind as Kind
import com.masterofchessstrategy.engine.LanzhouMahjongMeld
import com.masterofchessstrategy.engine.LanzhouMahjongMeldKind
import com.masterofchessstrategy.engine.LanzhouMahjongOutcomeView
import com.masterofchessstrategy.engine.LanzhouMahjongPhase
import com.masterofchessstrategy.engine.LanzhouMahjongPublicView
import com.masterofchessstrategy.engine.LanzhouMahjongSeatView
import org.junit.Assert.*
import org.junit.Test

class LanzhouMahjongAssistanceTest {
    @Test
    fun finiteBudgetsAreIndependentAndRestorationDoesNotRefillThem() {
        val used = LanzhouMahjongAssistanceUsage(Difficulty.MEDIUM, undoUsed = 3, hintUsed = 2)
        assertNull(used.consume(LanzhouMahjongAssistanceKind.UNDO))
        val lastHint = used.consume(LanzhouMahjongAssistanceKind.HINT)!!
        assertEquals(3, lastHint.undoUsed)
        assertEquals(3, lastHint.hintUsed)
        assertNull(lastHint.consume(LanzhouMahjongAssistanceKind.HINT))
        val restored = LanzhouMahjongAssistanceUsage(lastHint.difficulty,
            lastHint.undoUsed, lastHint.hintUsed)
        assertEquals(0, restored.remaining(LanzhouMahjongAssistanceKind.HINT))
        assertNull(LanzhouMahjongAssistanceUsage(Difficulty.MASTER)
            .consume(LanzhouMahjongAssistanceKind.HINT))
        assertNull(LanzhouMahjongAssistanceUsage(Difficulty.HARD, undoUsed = 1)
            .consume(LanzhouMahjongAssistanceKind.UNDO))
        assertNull(LanzhouMahjongAssistanceUsage(Difficulty.EASY)
            .remaining(LanzhouMahjongAssistanceKind.HINT))
        assertEquals(Int.MAX_VALUE,
            LanzhouMahjongAssistanceUsage(Difficulty.EASY, hintUsed = Int.MAX_VALUE)
                .consume(LanzhouMahjongAssistanceKind.HINT)!!.hintUsed)
        for (difficulty in Difficulty.entries) {
            assertThrows(IllegalArgumentException::class.java) {
                LanzhouMahjongAssistanceUsage(difficulty, hintUsed = -1)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            LanzhouMahjongAssistanceUsage(Difficulty.HARD, hintUsed = 2)
        }
    }

    @Test
    fun easyShowsAllLegalChoicesAndMasterOffersNone() {
        val view = view(standardHand)
        val actions = listOf(action(Kind.DISCARD, 6), action(Kind.READY_DISCARD, 6),
            action(Kind.DISCARD, 4))
        assertEquals(actions, plan(view, Difficulty.EASY, actions + actions.first())!!.actions)
        assertNull(plan(view, Difficulty.MASTER, actions))
        assertNull(plan(view.copy(phase = LanzhouMahjongPhase.WON), Difficulty.EASY, actions))
        assertNull(plan(view, Difficulty.MEDIUM, emptyList()))
    }

    @Test
    fun standardWaitsAreExactAndHintDoesNotModifyInput() {
        val hand = standardHand.toMutableList()
        val view = view(hand)
        val before = hand.toList()
        val ready = action(Kind.READY_DISCARD, 6)
        val hint = plan(view, Difficulty.HARD,
            listOf(action(Kind.DISCARD, 4), action(Kind.DISCARD, 6), ready))!!
        assertEquals(listOf(ready), hint.actions)
        assertEquals(listOf(4), hint.waits)
        assertEquals(before, hand)
        val boundary = view(listOf(0, 1, 2, 3, 4, 5, 9, 10, 11, 12, 13, 14, 6, 8))
        assertEquals(listOf(0, 3, 6), plan(boundary, Difficulty.MEDIUM,
            listOf(action(Kind.READY_DISCARD, 8)))!!.waits)
    }

    @Test
    fun sevenPairsTreatsFourCopiesAsTwoPairsAndRanksPublicAvailability() {
        val hand = listOf(0, 0, 0, 0, 2, 2, 4, 4, 9, 9, 11, 11, 13, 15)
        val base = view(hand)
        val actions = listOf(action(Kind.READY_DISCARD, 15), action(Kind.READY_DISCARD, 13))
        val depleted = base.copy(seats = base.seats.map {
            if (it.seat == 1) it.copy(discards = listOf(13, 13, 13)) else it
        })
        val hint = plan(depleted, Difficulty.MEDIUM, actions)!!
        assertEquals(listOf(action(Kind.READY_DISCARD, 13)), hint.actions)
        assertEquals(listOf(15), hint.waits)
        assertEquals(listOf(13), plan(base, Difficulty.MEDIUM,
            listOf(action(Kind.READY_DISCARD, 15)))!!.waits)
    }

    @Test
    fun opponentConcealedTilesAndKongsCannotInfluenceAdvice() {
        val hand = listOf(0, 0, 0, 0, 2, 2, 4, 4, 9, 9, 11, 11, 13, 15)
        val base = view(hand)
        val actions = listOf(action(Kind.READY_DISCARD, 15), action(Kind.READY_DISCARD, 13))
        val hidden = base.copy(seats = base.seats.map {
            if (it.seat == 1) it.copy(concealedTiles = listOf(13, 13, 13),
                melds = listOf(LanzhouMahjongMeld(LanzhouMahjongMeldKind.CONCEALED_KONG, -1, 1)))
            else it
        })
        assertEquals(plan(base, Difficulty.MEDIUM, actions),
            plan(hidden, Difficulty.MEDIUM, actions))
        // Even an accidentally unredacted opponent kong must not inform the strategy.
        val leaked = hidden.copy(seats = hidden.seats.map {
            if (it.seat == 1) it.copy(melds = listOf(
                LanzhouMahjongMeld(LanzhouMahjongMeldKind.CONCEALED_KONG, 13, 1))) else it
        })
        assertEquals(plan(base, Difficulty.HARD, actions), plan(leaked, Difficulty.HARD, actions))
    }

    @Test
    fun exposedMeldsCountTowardMissingSuitAndCannotUseFifthCopy() {
        val pung = LanzhouMahjongMeld(LanzhouMahjongMeldKind.PUNG, 18, 1)
        val threeSuits = view(listOf(0, 1, 2, 3, 4, 5, 9, 10, 11, 6, 6), listOf(pung))
        assertTrue(plan(threeSuits, Difficulty.MEDIUM,
            listOf(action(Kind.DISCARD, 6)))!!.waits.isEmpty())
        val kong = LanzhouMahjongMeld(LanzhouMahjongMeldKind.CONCEALED_KONG, 0, 0)
        val ownKong = view(listOf(1, 1, 1, 8, 8, 8, 9, 9, 9, 4, 6), listOf(kong))
        assertEquals(listOf(4), plan(ownKong, Difficulty.MEDIUM,
            listOf(action(Kind.READY_DISCARD, 6)))!!.waits)
        val invalid = ownKong.copy(seats = ownKong.seats.map {
            if (it.seat == 0) it.copy(concealedTiles = it.concealedTiles.dropLast(1) + 0) else it
        })
        assertNull(plan(invalid, Difficulty.MEDIUM, listOf(action(Kind.DISCARD, 4))))
        val afterOneKong = view(listOf(3, 3, 3, 3, 1, 2, 9, 10, 11, 8, 8), listOf(kong))
        assertEquals(listOf(3, 8), plan(afterOneKong, Difficulty.MEDIUM,
            listOf(action(Kind.READY_DISCARD, 3)))!!.waits)
        val afterTwoKongs = view(listOf(1, 2, 9, 10, 11, 8, 8, 18),
            listOf(kong, LanzhouMahjongMeld(LanzhouMahjongMeldKind.CONCEALED_KONG, 3, 0)))
        assertTrue(plan(afterTwoKongs, Difficulty.MEDIUM,
            listOf(action(Kind.DISCARD, 18)))!!.waits.isEmpty())
    }

    @Test
    fun claimsPreserveMissingSuitAndCertifiedWinTakesPriority() {
        val ownMelds = listOf(
            LanzhouMahjongMeld(LanzhouMahjongMeldKind.PUNG, 0, 1),
            LanzhouMahjongMeld(LanzhouMahjongMeldKind.PUNG, 9, 2),
        )
        val claim = view(listOf(1, 2, 3, 10, 11, 12, 18), ownMelds)
            .copy(phase = LanzhouMahjongPhase.CLAIM, pendingTile = 18, pendingSeat = 1)
        val pass = action(Kind.PASS)
        val pung = action(Kind.PUNG)
        assertEquals(listOf(pass), plan(claim, Difficulty.HARD, listOf(pung, pass))!!.actions)
        val win = action(Kind.WIN)
        assertEquals(listOf(win), plan(claim, Difficulty.MEDIUM, listOf(win, pass))!!.actions)
        assertEquals(listOf(pung), plan(claim.copy(pendingTile = 1), Difficulty.MEDIUM,
            listOf(pung, pass))!!.actions)
    }

    @Test
    fun nonReadyDiscardPrefersUncommittedMissingSuitAndAdviceAlwaysIsLegal() {
        val melds = listOf(LanzhouMahjongMeld(LanzhouMahjongMeldKind.PUNG, 0, 1))
        val base = view(listOf(1, 2, 3, 4, 4, 9, 10, 11, 12, 12, 18), melds)
        val actions = listOf(action(Kind.DISCARD, 4), action(Kind.DISCARD, 9),
            action(Kind.DISCARD, 18))
        for (difficulty in listOf(Difficulty.EASY, Difficulty.MEDIUM, Difficulty.HARD)) {
            val hint = plan(base, difficulty, actions)!!
            assertTrue(hint.actions.all { it in actions })
            if (difficulty != Difficulty.EASY) assertEquals(listOf(actions.last()), hint.actions)
        }
        assertNull(LanzhouMahjongHintPlanner.plan(base, 4, Difficulty.MEDIUM, actions))
        val bad = base.copy(seats = base.seats.map {
            if (it.seat == 0) it.copy(concealedCount = 14) else it
        })
        assertNull(plan(bad, Difficulty.MEDIUM, actions))
    }

    private fun plan(view: LanzhouMahjongPublicView, difficulty: Difficulty,
                     legal: List<LanzhouMahjongAction>) =
        LanzhouMahjongHintPlanner.plan(view, 0, difficulty, legal)

    private fun action(kind: Kind, tile: Int = 255) = LanzhouMahjongAction(kind, tile)

    private fun view(hand: List<Int>, melds: List<LanzhouMahjongMeld> = emptyList()) =
        LanzhouMahjongPublicView(LanzhouMahjongPhase.DISCARD, 0, 0, 55, null, null,
            LanzhouMahjongOutcomeView(null, null, 1, false, false, false, false, false),
            (0..3).map { seat ->
                LanzhouMahjongSeatView(seat, if (seat == 0) hand.size else 13,
                    false, false, if (seat == 0) melds else emptyList(), emptyList(),
                    if (seat == 0) hand else emptyList())
            }, List(4) { 0L })

    private val standardHand = listOf(0, 0, 0, 8, 8, 8, 9, 9, 9, 17, 17, 17, 4, 6)
}
