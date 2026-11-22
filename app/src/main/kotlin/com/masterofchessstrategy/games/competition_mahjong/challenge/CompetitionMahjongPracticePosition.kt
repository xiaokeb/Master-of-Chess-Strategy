package com.masterofchessstrategy.games.competition_mahjong.challenge

import com.masterofchessstrategy.engine.CompetitionMahjongAction
import com.masterofchessstrategy.engine.CompetitionMahjongFanView
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
    const val FOUR_CONCEALED_CLAIM_ID = "claim-four-concealed-pungs"
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
    )

    fun isDiscardTraining(id: String): Boolean = id in discards

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
            check(session.restore(dealerOpening(puzzle.openingHand, puzzle.draws, puzzle.scoring)) == RestoreResult.Restored)
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
    )

    /** Replays the fixed teaching line through the local draw or an opponent's pending discard. */
    fun playDiscard(tile: Int, id: String, settle: Boolean = false): WinningTurn? {
        val puzzle = discards.getValue(id)
        val nextDraws = puzzle.draws
        return NativeCompetitionMahjongSession(0, scoring = puzzle.scoring).use { session ->
            check(session.restore(dealerOpening(puzzle.openingHand, nextDraws, puzzle.scoring)) == RestoreResult.Restored)
            val action = CompetitionMahjongAction(CompetitionMahjongRoundActionKind.DISCARD, tile)
            require(action in session.legalActions())
            check(session.apply(action).accepted)
            val opening = session.serialize()
            val journal = mutableListOf<Byte>()
            fun event(kind: CompetitionMahjongRoundActionKind, seat: Int, value: Int = 0) {
                journal.addAll(listOf(kind.code.toByte(), seat.toByte(), value.toByte(), 0))
            }
            fun pass(discarder: Int, discarded: Int) {
                for (offset in 1..3) event(CompetitionMahjongRoundActionKind.PASS, (discarder + offset) % 4, discarded)
            }
            pass(0, tile)
            val payer = nextDraws.size.takeIf { puzzle.fromDiscard }
            val opponentTurns = payer ?: nextDraws.lastIndex
            for (seat in 1..opponentTurns) {
                event(CompetitionMahjongRoundActionKind.DRAW, seat)
                event(CompetitionMahjongRoundActionKind.DISCARD, seat, nextDraws[seat - 1])
                if (seat == payer) {
                    // Leave only the local seat's decision pending.
                    for (other in 1..3) if (other != payer)
                        event(CompetitionMahjongRoundActionKind.PASS, other, nextDraws.last())
                } else pass(seat, nextDraws[seat - 1])
            }
            if (payer == null) event(CompetitionMahjongRoundActionKind.DRAW, 0)
            val round = opening.copyOfRange(12, opening.size - 4)
            check(session.restore(withJournal(round, journal.toByteArray())) == RestoreResult.Restored)
            val frame = session.snapshot()
            val winningTile = nextDraws.last()
            if (payer == null) check(frame.currentSeat == 0 && frame.drawnTile == winningTile)
            else check(frame.phase == CompetitionMahjongRoundPhase.CLAIM && frame.pendingSeat == payer &&
                frame.pendingTile == winningTile && frame.drawnTile == null)
            val win = session.legalActions().singleOrNull { it.kind == CompetitionMahjongRoundActionKind.WIN } ?: return@use null
            val hand = frame.seats.first().concealedTiles.sorted()
            if (!settle) return@use WinningTurn(hand, winningTile, payer)
            val accepted = session.apply(win)
            val result = session.snapshot()
            check(accepted.accepted && !accepted.falseWin && result.phase == CompetitionMahjongRoundPhase.WON &&
                result.winnerSeat == 0 && result.payerSeat == payer && result.winningTile == winningTile &&
                result.winningFan.qualifies &&
                result.winningFan.entries.any { it.id == puzzle.fanId && it.points == puzzle.patternPoints })
            WinningTurn(hand, winningTile, payer, result.winningFan.totalFan, result.netScores)
        }
    }

    /** Native verifies the complete physical wall before dealing. */
    private fun dealerOpening(
        hand: List<Int>,
        draws: List<Int> = emptyList(),
        scoring: CompetitionMahjongScoring = CompetitionMahjongScoring.LEGACY,
    ): ByteArray {
        val wall = IntArray(144) { -1 }
        val dealerSlots = listOf(0, 1, 2, 3, 16, 17, 18, 19, 32, 33, 34, 35, 48, 49)
        val remaining = (0..41).flatMap { tile -> List(if (tile < 34) 4 else 1) { tile } }.toMutableList()
        (dealerSlots.zip(hand) + draws.mapIndexed { index, tile -> 53 + index to tile }).forEach { (slot, tile) ->
            check(remaining.remove(tile))
            wall[slot] = tile
        }
        val rest = remaining.iterator()
        for (slot in wall.indices) if (wall[slot] < 0) wall[slot] = rest.next()
        check(!rest.hasNext())
        val round = ByteBuffer.allocate(151).order(ByteOrder.LITTLE_ENDIAN)
        round.put("MOCM".toByteArray(Charsets.US_ASCII)).put(byteArrayOf(scoring.recordVersion.toByte(), 0, 0))
        wall.forEach { round.put(it.toByte()) }
        return withJournal(round.array(), byteArrayOf())
    }

    private fun withJournal(previous: ByteArray, suffix: ByteArray): ByteArray {
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
        record.put("MCMS".toByteArray(Charsets.US_ASCII)).put(byteArrayOf(previous[4], 0, 0, 0))
            .putInt(round.capacity()).put(round.array())
        val crc = CRC32().apply { update(record.array(), 0, record.position()) }.value.toInt()
        return record.putInt(crc).array()
    }
}
