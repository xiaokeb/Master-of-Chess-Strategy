package com.masterofchessstrategy.games.competition_mahjong.challenge

import com.masterofchessstrategy.engine.CompetitionMahjongAction
import com.masterofchessstrategy.engine.CompetitionMahjongFanView
import com.masterofchessstrategy.engine.CompetitionMahjongMeldKind
import com.masterofchessstrategy.engine.CompetitionMahjongPublicView
import com.masterofchessstrategy.engine.CompetitionMahjongRoundActionKind
import com.masterofchessstrategy.engine.CompetitionMahjongRoundPhase
import com.masterofchessstrategy.engine.CompetitionMahjongScoring
import com.masterofchessstrategy.engine.NativeCompetitionMahjongSession
import com.masterofchessstrategy.engine.RestoreResult
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Verifies a fixed local-seat puzzle and returns its visible hand. */
internal object CompetitionMahjongPracticePosition {
    const val PUZZLE_ID = "live-seven-pairs"
    const val DISCARD_PUZZLE_ID = "discard-for-seven-pairs"
    const val ORPHANS_DISCARD_ID = "discard-for-thirteen-orphans"
    const val PURE_STRAIGHT_DISCARD_ID = "discard-for-pure-straight"
    const val MIXED_TRIPLE_CHOW_DISCARD_ID = "discard-for-mixed-triple-chow"
    const val TRIPLE_PUNG_DISCARD_ID = "discard-for-triple-pung"
    const val FLOWER_REPLACEMENT_ID = "self-draw-after-two-flowers"
    const val FOUR_CONCEALED_CLAIM_ID = "claim-four-concealed-pungs"
    const val WRONG_TILE_ID = "false-win-wrong-tile"
    const val NOT_READY_ID = "false-win-not-ready"
    const val ROB_KONG_ID = "respond-to-an-added-kong"
    const val ROB_KONG_TILE = 31
    const val ROB_KONG_PAYER = 1
    private const val ROB_KONG_VIEWER = 2
    const val LAST_WALL_CLAIM_ID = "claim-final-wall-discard"
    const val LAST_WALL_TILE = 27
    const val LAST_WALL_PAYER = 3
    const val NINE_GATES_PUZZLE_ID = "live-nine-gates-exclusion"
    const val ALL_GREEN_PUZZLE_ID = "live-all-green-half-flush"
    const val TERMINALS_PUZZLE_ID = "live-terminals-double-pungs"
    private val dealerHand = listOf(0, 0, 2, 2, 5, 5, 12, 12, 19, 19, 23, 23, 27, 27)
    private const val SEVEN_PAIRS = 19
    private const val THIRTEEN_ORPHANS = 7
    private const val FOUR_CONCEALED_PUNGS = 12
    private const val PURE_STRAIGHT = 28
    private const val MIXED_TRIPLE_CHOW = 41
    private const val NINE_GATES = 4
    private const val FULLY_CONCEALED_SELF_DRAW = 56
    private const val TERMINAL_OR_HONOR_PUNG = 73

    private data class DiscardExercise(
        val openingHand: List<Int>,
        val draws: List<Int>,
        val scoring: CompetitionMahjongScoring,
        val fanId: Int,
        val patternPoints: Int,
        val fromDiscard: Boolean = false,
        val replacements: List<Int> = emptyList(),
    )

    private val discards = mapOf(
        DISCARD_PUZZLE_ID to DiscardExercise(dealerHand.dropLast(1) + 31, listOf(14, 15, 16, 27),
            CompetitionMahjongScoring.LEGACY, SEVEN_PAIRS, 24),
        ORPHANS_DISCARD_ID to DiscardExercise(
            listOf(0, 8, 9, 17, 18, 26, 27, 28, 29, 30, 31, 32, 33, 4), listOf(14, 15, 16, 33),
            CompetitionMahjongScoring.WMO_2021, THIRTEEN_ORPHANS, 88),
        PURE_STRAIGHT_DISCARD_ID to DiscardExercise(
            (0..8).toList() + listOf(9, 10, 11, 22, 32), listOf(14, 15, 16, 22),
            CompetitionMahjongScoring.WMO_2021, PURE_STRAIGHT, 16),
        MIXED_TRIPLE_CHOW_DISCARD_ID to DiscardExercise(
            listOf(0, 1, 2, 9, 10, 11, 18, 19, 20, 24, 25, 26, 27, 31), listOf(14, 15, 16, 27),
            CompetitionMahjongScoring.WMO_2021, MIXED_TRIPLE_CHOW, 8),
        FOUR_CONCEALED_CLAIM_ID to DiscardExercise(
            listOf(0, 0, 0, 3, 3, 3, 12, 12, 12, 25, 25, 25, 27, 31), listOf(27),
            CompetitionMahjongScoring.WMO_2021, FOUR_CONCEALED_PUNGS, 64, fromDiscard = true),
        TRIPLE_PUNG_DISCARD_ID to DiscardExercise(
            listOf(4, 4, 4, 6, 7, 8, 13, 13, 13, 22, 22, 22, 28, 31), listOf(14, 15, 16, 28),
            CompetitionMahjongScoring.WMO_2021_KONGS, 32, 16),
        FLOWER_REPLACEMENT_ID to DiscardExercise(
            listOf(0, 1, 2, 9, 10, 11, 18, 19, 20, 24, 25, 26, 27, 31), listOf(14, 15, 16, 34),
            CompetitionMahjongScoring.WMO_2021_KONGS, MIXED_TRIPLE_CHOW, 8, replacements = listOf(35, 27)),
    )

    fun isDiscardTraining(id: String): Boolean = id in discards
    fun isClaimTraining(id: String): Boolean = id == ROB_KONG_ID || id == LAST_WALL_CLAIM_ID

    private data class FalseWinExercise(val opening: List<Int>, val discard: Int, val payment: Int)

    private val falseWins = mapOf(
        WRONG_TILE_ID to FalseWinExercise(dealerHand.dropLast(1) + 31, 31, 10),
        NOT_READY_ID to FalseWinExercise(listOf(0, 2, 4, 6, 8, 9, 11, 13, 15, 17, 18, 20, 22, 31), 31, 20),
    )

    fun isFalseWinReview(id: String): Boolean = id in falseWins

    /** A public added-Kong window with a fixed viewer and independently verified win/pass outcomes. */
    fun robKong(decision: CompetitionMahjongRoundActionKind? = null): WinningTurn {
        val hand = listOf(0, 8, 8, 9, 17, 18, 26, 27, 28, 29, 30, 32, 33)
        val slots = (8..11).toList() + (24..27).toList() + (40..43).toList() + 51
        val draws = listOf(21, 22, 23)
        val fixed = listOf(0 to ROB_KONG_TILE, 4 to ROB_KONG_TILE, 5 to ROB_KONG_TILE, 6 to 32) +
            slots.zip(hand) + (53..56).toList().zip(draws + ROB_KONG_TILE)
        val score = CompetitionMahjongScoring.WMO_2021
        val journal = TrainingJournal().apply {
            event(CompetitionMahjongRoundActionKind.DISCARD, 0, ROB_KONG_TILE)
            event(CompetitionMahjongRoundActionKind.PUNG, ROB_KONG_PAYER, ROB_KONG_TILE)
            event(CompetitionMahjongRoundActionKind.PASS, ROB_KONG_VIEWER, ROB_KONG_TILE)
            event(CompetitionMahjongRoundActionKind.PASS, 3, ROB_KONG_TILE)
            event(CompetitionMahjongRoundActionKind.DISCARD, ROB_KONG_PAYER, 32)
            passAfter(ROB_KONG_PAYER, 32)
            for ((seat, tile) in listOf(2, 3, 0).zip(draws)) {
                event(CompetitionMahjongRoundActionKind.DRAW, seat)
                event(CompetitionMahjongRoundActionKind.DISCARD, seat, tile)
                passAfter(seat, tile)
            }
            event(CompetitionMahjongRoundActionKind.DRAW, ROB_KONG_PAYER)
            event(CompetitionMahjongRoundActionKind.ADDED_KONG, ROB_KONG_PAYER, ROB_KONG_TILE)
            event(CompetitionMahjongRoundActionKind.PASS, 0, ROB_KONG_TILE)
            event(CompetitionMahjongRoundActionKind.PASS, 3, ROB_KONG_TILE)
        }
        return NativeCompetitionMahjongSession(0, viewerSeat = ROB_KONG_VIEWER, scoring = score).use { session ->
            check(session.restore(withJournal(roundOpening(fixed, score), journal.bytes(), ROB_KONG_VIEWER)) ==
                RestoreResult.Restored)
            val frame = session.snapshot()
            val publicPung = frame.seats[ROB_KONG_PAYER].melds.single()
            check(frame.phase == CompetitionMahjongRoundPhase.ROB_KONG && frame.pendingTile == ROB_KONG_TILE &&
                frame.pendingSeat == ROB_KONG_PAYER && publicPung.kind == CompetitionMahjongMeldKind.PUNG &&
                publicPung.tile == ROB_KONG_TILE && frame.drawnTile == null &&
                frame.seats[ROB_KONG_VIEWER].concealedTiles.sorted() == hand &&
                frame.seats.filter { it.seat != ROB_KONG_VIEWER }.all { it.concealedTiles.isEmpty() })
            val option = WinningTurn(hand, ROB_KONG_TILE, ROB_KONG_PAYER)
            if (decision == null) return@use option
            require(decision == CompetitionMahjongRoundActionKind.WIN || decision == CompetitionMahjongRoundActionKind.PASS)
            val action = CompetitionMahjongAction(decision, ROB_KONG_TILE)
            check(action in session.legalActions())
            val applied = session.apply(action)
            check(applied.accepted && !applied.falseWin)
            val result = session.snapshot()
            val meld = result.seats[ROB_KONG_PAYER].melds.single()
            if (decision == CompetitionMahjongRoundActionKind.PASS) {
                check(result.phase == CompetitionMahjongRoundPhase.DISCARD && result.currentSeat == ROB_KONG_PAYER &&
                    result.winnerSeat == null && meld.kind == CompetitionMahjongMeldKind.KONG && meld.addedKong &&
                    result.wallRemaining < frame.wallRemaining && result.netScores.all { it == 0 })
                option
            } else {
                check(result.phase == CompetitionMahjongRoundPhase.WON && result.winnerSeat == ROB_KONG_VIEWER &&
                    result.payerSeat == ROB_KONG_PAYER && result.winningTile == ROB_KONG_TILE &&
                    result.wallRemaining == frame.wallRemaining && meld == publicPung && result.winningFan.qualifies &&
                    result.winningFan.entries.any { it.id == 47 && it.points == 8 } &&
                    result.winningFan.entries.none { it.id == 58 })
                option.copy(totalFan = result.winningFan.totalFan, netScores = result.netScores)
            }
        }
    }

    /** Restores the last discard of a full physical wall with the local response still pending. */
    fun lastWallClaim(decision: CompetitionMahjongRoundActionKind? = null): WinningTurn {
        val hand = (0..8).toList() + listOf(18, 19, 20, LAST_WALL_TILE)
        val dealerSlots = listOf(0, 1, 2, 3, 16, 17, 18, 19, 32, 33, 34, 35, 48)
        val wall = fixedWall(dealerSlots.zip(hand) + (53..60).zip(34..41) + (135 to LAST_WALL_TILE))
        val draws = (143 downTo 136).map { wall[it] } + (61..135).map { wall[it] }
        val journal = TrainingJournal().apply {
            event(CompetitionMahjongRoundActionKind.DISCARD, 0, wall[49])
            passAfter(0, wall[49])
            draws.forEachIndexed { index, tile ->
                val seat = (index + 1) % 4
                event(CompetitionMahjongRoundActionKind.DRAW, seat)
                event(CompetitionMahjongRoundActionKind.DISCARD, seat, tile)
                if (index == draws.lastIndex) {
                    check(seat == LAST_WALL_PAYER && tile == LAST_WALL_TILE)
                    for (other in 1..2) event(CompetitionMahjongRoundActionKind.PASS, other, tile)
                } else passAfter(seat, tile)
            }
        }
        val scoring = CompetitionMahjongScoring.WMO_2021_KONGS
        return NativeCompetitionMahjongSession(0, scoring = scoring).use { session ->
            check(session.restore(withJournal(roundOpening(wall, scoring), journal.bytes())) == RestoreResult.Restored)
            val frame = session.snapshot()
            val local = frame.seats.single { it.seat == frame.viewerSeat }
            check(frame.phase == CompetitionMahjongRoundPhase.CLAIM && frame.wallRemaining == 0 &&
                frame.pendingSeat == LAST_WALL_PAYER && frame.pendingTile == LAST_WALL_TILE &&
                local.concealedTiles.sorted() == hand && local.flowers.size == 2 &&
                frame.seats.filter { it.seat != frame.viewerSeat }.all { it.concealedTiles.isEmpty() })
            val option = WinningTurn(hand, LAST_WALL_TILE, LAST_WALL_PAYER, flowers = local.flowers)
            if (decision == null) return@use option
            require(decision == CompetitionMahjongRoundActionKind.WIN || decision == CompetitionMahjongRoundActionKind.PASS)
            val action = CompetitionMahjongAction(decision, LAST_WALL_TILE)
            check(action in session.legalActions())
            val applied = session.apply(action)
            check(applied.accepted && !applied.falseWin)
            val result = session.snapshot()
            if (decision == CompetitionMahjongRoundActionKind.PASS) {
                check(result.phase == CompetitionMahjongRoundPhase.EXHAUSTIVE_DRAW &&
                    result.winnerSeat == null && result.netScores.all { it == 0 })
                option
            } else {
                val fan = result.winningFan
                val payment = 8 + fan.totalFan
                check(result.phase == CompetitionMahjongRoundPhase.WON && result.winnerSeat == frame.viewerSeat &&
                    result.payerSeat == LAST_WALL_PAYER && result.winningTile == LAST_WALL_TILE && fan.qualifies &&
                    fan.entries.any { it.id == 45 && it.points == 8 && it.count == 1 } &&
                    fan.entries.none { it.id == 44 || it.id == 80 } &&
                    fan.totalFan == fan.fanWithoutFlowers + local.flowers.size &&
                    result.netScores == listOf(payment + 16, -8, -8, -payment))
                option.copy(totalFan = fan.totalFan, netScores = result.netScores, qualifyingFan = fan.fanWithoutFlowers)
            }
        }
    }

    /** Verify the actual rejected claim on a disposable round before showing the teaching hand. */
    fun verifyFalseWin(id: String): List<Int> {
        val exercise = falseWins.getValue(id)
        return NativeCompetitionMahjongSession(0, scoring = CompetitionMahjongScoring.WMO_2021).use { session ->
            check(session.restore(dealerOpening(exercise.opening, listOf(28), CompetitionMahjongScoring.WMO_2021)) ==
                RestoreResult.Restored)
            val claim = replayDiscard(session, exercise.discard, listOf(28), payer = 1)
            check(claim.phase == CompetitionMahjongRoundPhase.CLAIM && claim.pendingSeat == 1 && claim.pendingTile == 28)
            val hand = claim.seats.first().concealedTiles.sorted()
            check(hand == exercise.opening.toMutableList().apply { remove(exercise.discard) }.sorted())
            check(claim.seats.drop(1).all { it.concealedTiles.isEmpty() })
            check(session.legalActions().none { it.kind == CompetitionMahjongRoundActionKind.WIN })
            val win = CompetitionMahjongAction(CompetitionMahjongRoundActionKind.WIN, 28)
            val decision = session.apply(win)
            val result = session.snapshot()
            check(decision.accepted && decision.falseWin && result.winnerSeat == null &&
                result.seats.first().winDisabled && result.netScores == listOf(
                    -3 * exercise.payment, exercise.payment, exercise.payment, exercise.payment))
            hand
        }
    }

    fun verifySevenPairs(): List<Int> = verifyDealerWin(dealerHand, CompetitionMahjongScoring.LEGACY) { fan ->
        fan.entries.any { it.id == SEVEN_PAIRS && it.points == 24 }
    }

    fun verifyNineGates(): List<Int> {
        // The dealer's fourteenth tile is the extra five; keep the wall order before sorting the display hand.
        val hand = listOf(0, 0, 0, 1, 2, 3, 4, 5, 6, 7, 8, 8, 8, 4)
        return verifyDealerWin(hand, CompetitionMahjongScoring.WMO_2021) { fan ->
            fan.entries.any { it.id == NINE_GATES && it.points == 88 } &&
                fan.entries.any { it.id == FULLY_CONCEALED_SELF_DRAW && it.points == 4 } &&
                fan.entries.none { it.id == TERMINAL_OR_HONOR_PUNG }
        }
    }

    fun verifyAllGreen(): List<Int> = verifyDealerWin(
        listOf(10, 11, 12, 14, 14, 14, 16, 16, 16, 32, 32, 32, 11, 11),
        CompetitionMahjongScoring.WMO_2021,
    ) { fan ->
        exactFan(fan, mapOf(3 to (88 to 1), 33 to (16 to 1), 50 to (6 to 1),
            56 to (4 to 1), 59 to (2 to 1), 78 to (1 to 1)), 117)
    }

    fun verifyTerminals(): List<Int> = verifyDealerWin(
        listOf(0, 0, 0, 8, 8, 8, 9, 9, 9, 17, 17, 17, 18, 18),
        CompetitionMahjongScoring.WMO_2021,
    ) { fan ->
        exactFan(fan, mapOf(8 to (64 to 1), 12 to (64 to 1), 56 to (4 to 1),
            65 to (2 to 2), 79 to (1 to 1)), 137)
    }

    /** Full fan sets also reject accidentally included low fans and wrong multiplicities. */
    private fun exactFan(fan: CompetitionMahjongFanView, entries: Map<Int, Pair<Int, Int>>, total: Int) =
        fan.errorCode == 0 && fan.structureValid && fan.fanWithoutFlowers == total && fan.totalFan == total &&
            fan.entries.size == entries.size && fan.entries.associate { it.id to (it.points to it.count) } == entries

    private fun verifyDealerWin(
        openingHand: List<Int>,
        scoring: CompetitionMahjongScoring,
        verifyFan: (CompetitionMahjongFanView) -> Boolean,
    ): List<Int> = NativeCompetitionMahjongSession(0, scoring = scoring).use { session ->
        check(session.restore(dealerOpening(openingHand, scoring = scoring)) == RestoreResult.Restored) {
            "训练牌局无法通过 Native 恢复"
        }
        val initial = session.snapshot()
        val hand = initial.seats.single { it.seat == initial.viewerSeat }.concealedTiles.sorted()
        check(initial.phase == CompetitionMahjongRoundPhase.DISCARD && initial.currentSeat == initial.viewerSeat) {
            "训练牌局未停在本机出牌阶段"
        }
        check(hand == openingHand.sorted() && initial.seats.filter { it.seat != initial.viewerSeat }.all { it.concealedTiles.isEmpty() }) {
            "训练牌局的本机手牌或隐私视图不一致"
        }
        val win = checkNotNull(session.legalActions().singleOrNull { it.kind == CompetitionMahjongRoundActionKind.WIN }) {
            "训练牌局没有合法和牌动作"
        }
        val result = session.apply(win)
        check(result.accepted && !result.falseWin) { "训练牌局和牌动作被拒绝" }
        val settled = session.snapshot()
        check(settled.phase == CompetitionMahjongRoundPhase.WON && settled.payerSeat == null &&
            settled.winnerSeat == initial.viewerSeat && settled.winningFan.qualifies &&
            verifyFan(settled.winningFan)) { "训练牌局的结算与题面不一致" }
        hand
    }

    fun discardOpeningHand(id: String = DISCARD_PUZZLE_ID): List<Int> {
        val puzzle = discards.getValue(id)
        return NativeCompetitionMahjongSession(0, scoring = puzzle.scoring).use { session ->
            check(session.restore(dealerOpening(puzzle.openingHand, puzzle.draws, puzzle.scoring, puzzle.replacements)) ==
                RestoreResult.Restored)
            val frame = session.snapshot()
            check(frame.phase == CompetitionMahjongRoundPhase.DISCARD && frame.currentSeat == 0 && frame.viewerSeat == 0)
            check(frame.seats.drop(1).all { it.concealedTiles.isEmpty() })
            frame.seats.first().concealedTiles.sorted().also { check(it == puzzle.openingHand.sorted()) }
        }
    }

    data class WinningTurn(
        val hand: List<Int>,
        val tile: Int,
        val payerSeat: Int?,
        val totalFan: Int? = null,
        val netScores: List<Int> = emptyList(),
        val flowers: List<Int> = emptyList(),
        val qualifyingFan: Int? = null,
    )

    /** Replays the fixed teaching line through the local draw or an opponent's pending discard. */
    fun playDiscard(tile: Int, id: String, settle: Boolean = false): WinningTurn? {
        val puzzle = discards.getValue(id)
        val nextDraws = puzzle.draws
        return NativeCompetitionMahjongSession(0, scoring = puzzle.scoring).use { session ->
            check(session.restore(dealerOpening(puzzle.openingHand, nextDraws, puzzle.scoring, puzzle.replacements)) ==
                RestoreResult.Restored)
            val payer = nextDraws.size.takeIf { puzzle.fromDiscard }
            val frame = replayDiscard(session, tile, nextDraws, payer)
            val winningTile = puzzle.replacements.lastOrNull() ?: nextDraws.last()
            if (payer == null) check(frame.currentSeat == 0 && frame.drawnTile == winningTile)
            else check(frame.phase == CompetitionMahjongRoundPhase.CLAIM && frame.pendingSeat == payer &&
                frame.pendingTile == winningTile && frame.drawnTile == null)
            val win = session.legalActions().singleOrNull { it.kind == CompetitionMahjongRoundActionKind.WIN } ?: return@use null
            val hand = frame.seats.first().concealedTiles.sorted()
            val flowers = if (puzzle.replacements.isEmpty()) emptyList() else frame.seats.first().flowers.also {
                check(it == listOf(nextDraws.last()) + puzzle.replacements.dropLast(1))
            }
            if (!settle) return@use WinningTurn(hand, winningTile, payer, flowers = flowers)
            val accepted = session.apply(win)
            val result = session.snapshot()
            check(accepted.accepted && !accepted.falseWin && result.phase == CompetitionMahjongRoundPhase.WON &&
                result.winnerSeat == 0 && result.payerSeat == payer && result.winningTile == winningTile &&
                result.winningFan.qualifies &&
                result.winningFan.entries.any { it.id == puzzle.fanId && it.points == puzzle.patternPoints })
            if (id == TRIPLE_PUNG_DISCARD_ID) {
                check(result.winningFan.entries.none { it.id == 65 })
            }
            if (flowers.isNotEmpty()) {
                val fan = result.winningFan
                check(fan.fanWithoutFlowers >= 8 && fan.totalFan == fan.fanWithoutFlowers + flowers.size &&
                    fan.entries.any { it.id == 81 && it.points == 1 && it.count == flowers.size } &&
                    fan.entries.none { it.id == 46 })
            }
            WinningTurn(hand, winningTile, payer, result.winningFan.totalFan, result.netScores, flowers,
                result.winningFan.fanWithoutFlowers)
        }
    }

    private fun replayDiscard(
        session: NativeCompetitionMahjongSession, tile: Int, nextDraws: List<Int>, payer: Int?,
    ): CompetitionMahjongPublicView {
        val action = CompetitionMahjongAction(CompetitionMahjongRoundActionKind.DISCARD, tile)
        require(action in session.legalActions())
        check(session.apply(action).accepted)
        val opening = session.serialize()
        val journal = TrainingJournal().apply {
            passAfter(0, tile)
            for (seat in 1..(payer ?: nextDraws.lastIndex)) {
                val discarded = nextDraws[seat - 1]
                event(CompetitionMahjongRoundActionKind.DRAW, seat)
                event(CompetitionMahjongRoundActionKind.DISCARD, seat, discarded)
                if (seat == payer) {
                    for (other in 1..3) if (other != payer) event(CompetitionMahjongRoundActionKind.PASS, other, discarded)
                } else passAfter(seat, discarded)
            }
            if (payer == null) event(CompetitionMahjongRoundActionKind.DRAW, 0)
        }
        val round = opening.copyOfRange(12, opening.size - 4)
        check(session.restore(withJournal(round, journal.bytes())) == RestoreResult.Restored)
        return session.snapshot()
    }

    private class TrainingJournal {
        private val events = mutableListOf<Byte>()
        fun event(kind: CompetitionMahjongRoundActionKind, seat: Int, value: Int = 0) {
            events.addAll(listOf(kind.code.toByte(), seat.toByte(), value.toByte(), 0))
        }
        fun passAfter(discarder: Int, tile: Int) {
            for (offset in 1..3) event(CompetitionMahjongRoundActionKind.PASS, (discarder + offset) % 4, tile)
        }
        fun bytes() = events.toByteArray()
    }

    /** Native verifies the complete physical wall before dealing. */
    private fun dealerOpening(
        hand: List<Int>,
        draws: List<Int> = emptyList(),
        scoring: CompetitionMahjongScoring = CompetitionMahjongScoring.LEGACY,
        replacements: List<Int> = emptyList(),
    ): ByteArray {
        val dealerSlots = listOf(0, 1, 2, 3, 16, 17, 18, 19, 32, 33, 34, 35, 48, 49)
        val fixed = dealerSlots.zip(hand) + draws.mapIndexed { index, tile -> 53 + index to tile } +
            replacements.mapIndexed { index, tile -> 143 - index to tile }
        return withJournal(roundOpening(fixed, scoring), byteArrayOf())
    }

    private fun roundOpening(fixed: List<Pair<Int, Int>>, scoring: CompetitionMahjongScoring): ByteArray =
        roundOpening(fixedWall(fixed), scoring)

    private fun fixedWall(fixed: List<Pair<Int, Int>>): IntArray {
        val wall = IntArray(144) { -1 }
        val remaining = (0..41).flatMap { tile -> List(if (tile < 34) 4 else 1) { tile } }.toMutableList()
        fixed.forEach { (slot, tile) ->
            check(wall[slot] == -1)
            check(remaining.remove(tile))
            wall[slot] = tile
        }
        val rest = remaining.iterator()
        for (slot in wall.indices) if (wall[slot] < 0) wall[slot] = rest.next()
        check(!rest.hasNext())
        return wall
    }

    private fun roundOpening(wall: IntArray, scoring: CompetitionMahjongScoring): ByteArray {
        val round = ByteBuffer.allocate(151).order(ByteOrder.LITTLE_ENDIAN)
        round.put("MOCM".toByteArray(Charsets.US_ASCII)).put(byteArrayOf(scoring.recordVersion.toByte(), 0, 0))
        wall.forEach { round.put(it.toByte()) }
        return round.array()
    }

    private fun withJournal(previous: ByteArray, suffix: ByteArray, viewer: Int = 0): ByteArray {
        val previousEvents = if (previous.size == 151) byteArrayOf() else previous.copyOfRange(155, previous.size - 4)
        val events = previousEvents + suffix
        check(events.size % 4 == 0)
        val round = ByteBuffer.allocate(159 + events.size).order(ByteOrder.LITTLE_ENDIAN)
        round.put(previous, 0, 151).putInt(events.size / 4).put(events)
        var hash = 2166136261u
        for (index in 0 until round.position()) hash = (hash xor round.array()[index].toUByte().toUInt()) * 16777619u
        round.putInt(hash.toInt())

        // The viewer-bound MCMS envelope is the public Native restore entry point.
        val record = ByteBuffer.allocate(round.capacity() + 16).order(ByteOrder.LITTLE_ENDIAN)
        record.put("MCMS".toByteArray(Charsets.US_ASCII)).put(byteArrayOf(previous[4], viewer.toByte(), 0, 0))
            .putInt(round.capacity()).put(round.array())
        val crc = CRC32().apply { update(record.array(), 0, record.position()) }.value.toInt()
        return record.putInt(crc).array()
    }
}
