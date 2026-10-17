package com.masterofchessstrategy.engine

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Bounded, versioned decoder. Privacy is checked before a view reaches Compose. */
internal object CompetitionMahjongSnapshotDecoder {
    fun decode(fields: IntArray, viewer: Int): CompetitionMahjongPublicView {
        check(viewer in 0..3 && fields.size in 22..65_536) { "Malformed Mahjong snapshot" }
        val reader = Reader(fields)
        val version = reader.value(1..2)
        check(reader.value(0..3) == viewer)
        val phase = CompetitionMahjongRoundPhase.entries[reader.value(0..5)]
        val dealer = reader.value(0..3)
        val wind = reader.value(0..3)
        val current = reader.value(0..3)
        val wall = reader.value(0..144)
        val pendingTile = reader.optional(0..33)
        val pendingSeat = reader.optional(0..3)
        val winner = reader.optional(0..3)
        val payer = reader.optional(0..3)
        val nextDealer = reader.value(0..3)
        check(nextDealer == (dealer + 1) % 4)
        check((pendingTile == null) == (pendingSeat == null))
        check((phase == CompetitionMahjongRoundPhase.WON) == (winner != null))
        check(payer == null || winner != null)
        val terminal = phase == CompetitionMahjongRoundPhase.WON || phase == CompetitionMahjongRoundPhase.EXHAUSTIVE_DRAW
        if (version == 2) {
            val claim = phase == CompetitionMahjongRoundPhase.CLAIM || phase == CompetitionMahjongRoundPhase.ROB_KONG
            check(claim == (pendingTile != null)) { "Inconsistent Mahjong claim phase" }
            check(payer == null || payer != winner)
        }
        val scores = List(4) { reader.next() }
        check(scores.sumOf { it.toLong() } == 0L) { "Non-zero-sum Mahjong settlement" }
        val fanError = reader.value(0..6)
        val validStructure = reader.flag()
        val baseFan = reader.value(0..65_536)
        val totalFan = reader.value(0..65_536)
        val qualifies = reader.flag()
        val fanEntries = List(reader.value(0..81)) {
            val id = reader.value(1..81)
            val points = reader.value(1..88)
            val count = reader.value(1..144)
            CompetitionMahjongFanEntry(id, reader.text(), points, count)
        }
        check(totalFan >= baseFan && totalFan - baseFan <= 8)
        check(!qualifies || validStructure && fanError == 0 && baseFan >= 8)
        check(winner == null || qualifies)
        val fan = CompetitionMahjongFanView(fanError, validStructure, baseFan, totalFan, qualifies, fanEntries)
        val seats = List(4) { seat ->
            val concealedCount = reader.value(0..14)
            val disabled = reader.flag()
            val flowers = reader.list(8, 34..41)
            check(flowers.distinct().size == flowers.size)
            val discards = reader.list(144, 0..33)
            val melds = List(reader.value(0..4)) {
                val kindCode = reader.value(1..3)
                val kind = CompetitionMahjongMeldKind.entries.first { it.code == kindCode }
                val tile = reader.optional(0..33)
                val from = reader.value(0..3)
                val position = reader.value(0..3)
                val added = reader.flag()
                // Native concealed Kongs use their own seat as their source.
                val hiddenKong = kind == CompetitionMahjongMeldKind.KONG && from == seat && seat != viewer &&
                    (version == 1 || !terminal)
                check((tile == null) == hiddenKong) { "Mahjong concealed-Kong privacy boundary failed" }
                check(!added || kind == CompetitionMahjongMeldKind.KONG && from != seat)
                if (kind == CompetitionMahjongMeldKind.CHOW)
                    check(tile != null && tile < 27 && tile % 9 <= 6 && position in 1..3)
                CompetitionMahjongPublicMeld(kind, tile, from, position, added)
            }
            val visible = reader.list(14, 0..33)
            check(visible.size == if (seat == viewer) concealedCount else 0) {
                "Mahjong concealed-hand privacy boundary failed"
            }
            CompetitionMahjongSeatView(seat, concealedCount, disabled, flowers, discards, melds, visible)
        }
        val canDeclare = if (version == 2) reader.flag() else false
        val drawn = if (version == 2) reader.optional(0..33) else null
        val winning = if (version == 2) reader.optional(0..33) else null
        val winningTiles = if (version == 2) reader.list(14, 0..33) else emptyList()
        if (version == 2) {
            check(drawn == null || phase == CompetitionMahjongRoundPhase.DISCARD && current == viewer &&
                drawn in seats[viewer].concealedTiles) { "Mahjong local-draw privacy boundary failed" }
            check(!canDeclare || !seats[viewer].winDisabled && when (phase) {
                CompetitionMahjongRoundPhase.DISCARD -> current == viewer && drawn != null
                CompetitionMahjongRoundPhase.CLAIM, CompetitionMahjongRoundPhase.ROB_KONG -> pendingSeat != viewer
                else -> false
            }) { "Invalid Mahjong declaration eligibility" }
            if (phase == CompetitionMahjongRoundPhase.WON) {
                check(winning != null && winning in winningTiles && winner != null)
                val winnerView = seats[checkNotNull(winner)]
                check(winningTiles.size == winnerView.concealedCount && winningTiles.size + 3 * winnerView.melds.size == 14)
                val meldTiles = winnerView.melds.flatMap { meld ->
                    val tile = checkNotNull(meld.tile)
                    when (meld.kind) {
                        CompetitionMahjongMeldKind.CHOW -> (tile..tile + 2).toList()
                        CompetitionMahjongMeldKind.PUNG -> List(3) { tile }
                        CompetitionMahjongMeldKind.KONG -> List(4) { tile }
                    }
                }
                check((winningTiles + meldTiles).groupingBy { it }.eachCount().values.all { it <= 4 })
                if (winner == viewer) check(winningTiles.sorted() == seats[viewer].concealedTiles.sorted())
            } else {
                check(winning == null && winningTiles.isEmpty()) { "Premature Mahjong winner-hand revelation" }
            }
        }
        check(reader.finished()) { "Trailing Mahjong snapshot fields" }
        return CompetitionMahjongPublicView(viewer, phase, dealer, wind, current, wall,
            pendingTile, pendingSeat, winner, payer, nextDealer, scores, fan, seats,
            canDeclare, drawn, winning, winningTiles, version)
    }

    private class Reader(private val fields: IntArray) {
        private var index = 0
        fun next(): Int {
            check(index < fields.size) { "Truncated Mahjong snapshot" }
            return fields[index++]
        }
        fun value(range: IntRange): Int = next().also { check(it in range) { "Invalid Mahjong field" } }
        fun flag(): Boolean = value(0..1) == 1
        fun optional(range: IntRange): Int? = next().let {
            check(it == -1 || it in range)
            it.takeUnless { value -> value == -1 }
        }
        fun list(max: Int, range: IntRange): List<Int> = List(value(0..max)) { value(range) }
        fun text(): String {
            val bytes = ByteArray(value(1..256)) { value(0..255).toByte() }
            return try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            } catch (failure: java.nio.charset.CharacterCodingException) {
                throw IllegalStateException("Invalid Mahjong UTF-8 fan name", failure)
            }
        }
        fun finished(): Boolean = index == fields.size
    }
}
