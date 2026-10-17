package com.masterofchessstrategy.games.competition_mahjong.match

import com.masterofchessstrategy.engine.CompetitionMahjongPublicView
import com.masterofchessstrategy.engine.CompetitionMahjongRoundPhase
import com.masterofchessstrategy.games.competition_mahjong.CompetitionMahjongMatchConfig
import java.util.UUID

/** Player IDs are their original East/South/West/North assignments, never current seats. */
data class CompetitionMahjongCompetitionConfig(
    val matchId: String,
    val seed: Long,
    val viewerPlayerId: Int = 0,
    val botLevel: Int = 1,
) {
    init {
        require(runCatching { UUID.fromString(matchId).toString() == matchId }.getOrDefault(false))
        require(seed >= 0 && viewerPlayerId in 0..3 && botLevel in 0..3)
    }

    internal fun handConfig(index: Int): CompetitionMahjongMatchConfig {
        val seating = CompetitionMahjongMatchSeating.forHand(index)
        return CompetitionMahjongMatchConfig(
            seed = handSeed(index),
            dealerSeat = seating.dealerSeat,
            roundWind = seating.roundWind,
            viewerSeat = seating.seatOfPlayer(viewerPlayerId),
            botLevel = botLevel,
        )
    }

    private fun handSeed(index: Int): Long {
        require(index in 0..15)
        if (index == 0) return seed
        // Version 1: SplitMix64 finalizer, with deliberately wrapping arithmetic.
        var value = seed + index * 0x9e3779b97f4a7c15UL.toLong()
        value = (value xor (value ushr 30)) * 0xbf58476d1ce4e5b9UL.toLong()
        value = (value xor (value ushr 27)) * 0x94d049bb133111ebUL.toLong()
        return (value xor (value ushr 31)) and Long.MAX_VALUE
    }
}

class CompetitionMahjongMatchSeating private constructor(val handIndex: Int) {
    val roundWind: Int get() = handIndex / 4
    val dealerSeat: Int get() = handIndex % 4
    val dealerPlayerId: Int get() = playerAtSeat(dealerSeat)

    fun playerAtSeat(seat: Int): Int {
        require(seat in 0..3)
        return PLAYERS_AT_SEATS[roundWind][seat]
    }

    fun seatOfPlayer(playerId: Int): Int {
        require(playerId in 0..3)
        return PLAYERS_AT_SEATS[roundWind].indexOf(playerId)
    }

    companion object {
        // WMO 2021 Appendix Four, columns physical East/South/West/North.
        private val PLAYERS_AT_SEATS = arrayOf(
            intArrayOf(0, 1, 2, 3), intArrayOf(1, 0, 3, 2),
            intArrayOf(2, 3, 1, 0), intArrayOf(3, 2, 0, 1),
        )

        fun forHand(index: Int): CompetitionMahjongMatchSeating {
            require(index in 0..15)
            return CompetitionMahjongMatchSeating(index)
        }
    }
}

/** Immutable quartet avoids retaining mutable lists supplied by a JNI frame or storage. */
data class CompetitionMahjongPlayerScores(val east: Long, val south: Long, val west: Long, val north: Long) {
    init { require(Math.addExact(Math.addExact(east, south), Math.addExact(west, north)) == 0L) }

    operator fun get(playerId: Int): Long = when (playerId) {
        0 -> east; 1 -> south; 2 -> west; 3 -> north
        else -> throw IllegalArgumentException("玩家编号无效")
    }

    internal operator fun plus(other: CompetitionMahjongPlayerScores) = CompetitionMahjongPlayerScores(
        Math.addExact(east, other.east), Math.addExact(south, other.south),
        Math.addExact(west, other.west), Math.addExact(north, other.north),
    )

    companion object { val ZERO = CompetitionMahjongPlayerScores(0, 0, 0, 0) }
}

@ConsistentCopyVisibility
data class CompetitionMahjongHandResult internal constructor(
    val handIndex: Int,
    val phase: CompetitionMahjongRoundPhase,
    val scores: CompetitionMahjongPlayerScores,
    val winnerPlayerId: Int?,
    val payerPlayerId: Int?,
    val fanWithoutFlowers: Int,
    val totalFan: Int,
) {
    init {
        require(handIndex in 0..15)
        require((0..3).all { scores[it] in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong() })
        require(winnerPlayerId == null || winnerPlayerId in 0..3)
        require(payerPlayerId == null || payerPlayerId in 0..3)
        when (phase) {
            CompetitionMahjongRoundPhase.WON -> {
                require(winnerPlayerId != null && payerPlayerId != winnerPlayerId)
                require(fanWithoutFlowers >= 8 && totalFan.toLong() in fanWithoutFlowers.toLong()..fanWithoutFlowers.toLong() + 8)
            }
            CompetitionMahjongRoundPhase.EXHAUSTIVE_DRAW -> {
                require(winnerPlayerId == null && payerPlayerId == null)
                require(fanWithoutFlowers == 0 && totalFan == 0)
            }
            else -> throw IllegalArgumentException("只记录已结束的盘")
        }
    }
}

data class CompetitionMahjongMatchStanding(
    val playerId: Int,
    val rank: Int,
    val score: Long,
    /** Exact rational; ties share the points of their occupied ranks. */
    val standardPointsNumerator: Int,
    val standardPointsDenominator: Int,
) {
    init {
        require(playerId in 0..3 && rank in 1..4)
        require(standardPointsNumerator in 0..7 && standardPointsDenominator in 1..4)
    }
}

class CompetitionMahjongMatchLedger private constructor(results: List<CompetitionMahjongHandResult>) {
    private val history = results.toList()
    val results: List<CompetitionMahjongHandResult> get() = history.toList()
    val completedHandCount: Int get() = history.size
    val isComplete: Boolean get() = completedHandCount == 16
    val cumulativeScores: CompetitionMahjongPlayerScores get() =
        history.fold(CompetitionMahjongPlayerScores.ZERO) { sum, result -> sum + result.scores }

    init {
        require(history.size <= 16 && history.withIndex().all { (index, result) -> result.handIndex == index })
    }

    /** Duplicate delivery is harmless; changing a previously committed hand is rejected. */
    internal fun append(result: CompetitionMahjongHandResult): CompetitionMahjongMatchLedger {
        if (result.handIndex < completedHandCount) {
            require(history[result.handIndex] == result) { "已结算盘的结果不一致" }
            return this
        }
        require(!isComplete && result.handIndex == completedHandCount) { "不可跳盘或追加第十七盘" }
        return CompetitionMahjongMatchLedger(history + result)
    }

    internal fun beforeUndoCurrent(index: Int): CompetitionMahjongMatchLedger {
        require(index == completedHandCount || index == completedHandCount - 1)
        return if (index == completedHandCount - 1) CompetitionMahjongMatchLedger(history.dropLast(1)) else this
    }

    fun recordTerminal(
        config: CompetitionMahjongCompetitionConfig,
        handIndex: Int,
        view: CompetitionMahjongPublicView,
    ): CompetitionMahjongMatchLedger = append(captureTerminal(config, handIndex, view))

    fun finalStandings(): List<CompetitionMahjongMatchStanding> {
        check(isComplete) { "十六盘结束后才产生局排名" }
        val scores = cumulativeScores
        val sorted = (0..3).sortedByDescending { scores[it] }
        val points = intArrayOf(4, 2, 1, 0)
        return sorted.map { player ->
            val better = sorted.count { scores[it] > scores[player] }
            val equal = sorted.count { scores[it] == scores[player] }
            CompetitionMahjongMatchStanding(
                player, better + 1, scores[player],
                (better until better + equal).sumOf { points[it] }, equal,
            )
        }
    }

    companion object {
        fun empty(): CompetitionMahjongMatchLedger = CompetitionMahjongMatchLedger(emptyList())

        internal fun restored(results: List<CompetitionMahjongHandResult>) = CompetitionMahjongMatchLedger(results)

        internal fun validateFrame(
            config: CompetitionMahjongCompetitionConfig,
            index: Int,
            view: CompetitionMahjongPublicView,
        ) {
            val expected = config.handConfig(index)
            require(view.viewerSeat == expected.viewerSeat && view.dealerSeat == expected.dealerSeat &&
                view.roundWind == expected.roundWind && view.nextDealer == (expected.dealerSeat + 1) % 4)
            require(view.netScores.size == 4 && view.netScores.sumOf { it.toLong() } == 0L)
            require(view.seats.size == 4 && view.seats.map { it.seat }.sorted() == listOf(0, 1, 2, 3))
        }

        internal fun captureTerminal(
            config: CompetitionMahjongCompetitionConfig,
            index: Int,
            view: CompetitionMahjongPublicView,
        ): CompetitionMahjongHandResult {
            validateFrame(config, index, view)
            val seating = CompetitionMahjongMatchSeating.forHand(index)
            val scores = (0..3).map { view.netScores[seating.seatOfPlayer(it)].toLong() }
            if (view.phase == CompetitionMahjongRoundPhase.WON) {
                require(view.winningFan.errorCode == 0 && view.winningFan.structureValid && view.winningFan.qualifies)
            }
            fun player(seat: Int?): Int? = seat?.let(seating::playerAtSeat)
            return CompetitionMahjongHandResult(
                index, view.phase, CompetitionMahjongPlayerScores(scores[0], scores[1], scores[2], scores[3]),
                player(view.winnerSeat), player(view.payerSeat),
                if (view.phase == CompetitionMahjongRoundPhase.WON) view.winningFan.fanWithoutFlowers else 0,
                if (view.phase == CompetitionMahjongRoundPhase.WON) view.winningFan.totalFan else 0,
            )
        }
    }
}
