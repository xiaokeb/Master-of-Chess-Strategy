package com.masterofchessstrategy.games.competition_mahjong.challenge

import com.masterofchessstrategy.engine.CompetitionMahjongRoundActionKind
import com.masterofchessstrategy.engine.CompetitionMahjongRoundPhase
import com.masterofchessstrategy.engine.NativeCompetitionMahjongSession
import com.masterofchessstrategy.engine.RestoreResult
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/** Verifies a fixed local-seat puzzle and returns its visible hand. */
internal object CompetitionMahjongPracticePosition {
    const val PUZZLE_ID = "live-seven-pairs"
    private val dealerHand = listOf(0, 0, 2, 2, 5, 5, 12, 12, 19, 19, 23, 23, 27, 27)
    const val SEVEN_PAIRS = 19

    fun verifySevenPairs(): List<Int> = NativeCompetitionMahjongSession(0).use { session ->
        check(session.restore(dealerOpening(dealerHand)) == RestoreResult.Restored) { "训练牌局无法通过 Native 恢复" }
        val initial = session.snapshot()
        val hand = initial.seats.single { it.seat == initial.viewerSeat }.concealedTiles.sorted()
        check(initial.phase == CompetitionMahjongRoundPhase.DISCARD && initial.currentSeat == initial.viewerSeat) {
            "训练牌局未停在本机出牌阶段"
        }
        check(hand == dealerHand && initial.seats.filter { it.seat != initial.viewerSeat }.all { it.concealedTiles.isEmpty() }) {
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
            settled.winningFan.entries.any { it.id == SEVEN_PAIRS && it.points == 24 }) { "训练牌局未按七对结算" }
        hand
    }

    /** MOCM v1 with a full physical wall and an empty journal. Native verifies the inventory and deals it. */
    private fun dealerOpening(hand: List<Int>): ByteArray {
        val wall = IntArray(144) { -1 }
        val dealerSlots = listOf(0, 1, 2, 3, 16, 17, 18, 19, 32, 33, 34, 35, 48, 49)
        val remaining = (0..41).flatMap { tile -> List(if (tile < 34) 4 else 1) { tile } }.toMutableList()
        dealerSlots.zip(hand).forEach { (slot, tile) ->
            check(remaining.remove(tile))
            wall[slot] = tile
        }
        val rest = remaining.iterator()
        for (slot in wall.indices) if (wall[slot] < 0) wall[slot] = rest.next()
        check(!rest.hasNext())
        val round = ByteBuffer.allocate(159).order(ByteOrder.LITTLE_ENDIAN)
        round.put("MOCM".toByteArray(Charsets.US_ASCII)).put(byteArrayOf(1, 0, 0))
        wall.forEach { round.put(it.toByte()) }
        round.putInt(0)
        var hash = 2166136261u
        for (index in 0 until round.position()) hash = (hash xor round.array()[index].toUByte().toUInt()) * 16777619u
        round.putInt(hash.toInt())

        // The viewer-bound MCMS envelope is the public Native restore entry point.
        val record = ByteBuffer.allocate(round.capacity() + 16).order(ByteOrder.LITTLE_ENDIAN)
        record.put("MCMS".toByteArray(Charsets.US_ASCII)).put(byteArrayOf(1, 0, 0, 0))
            .putInt(round.capacity()).put(round.array())
        val crc = CRC32().apply { update(record.array(), 0, record.position()) }.value.toInt()
        return record.putInt(crc).array()
    }
}
