package com.masterofchessstrategy.games.competition_mahjong.statistics

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchCheckpoint
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongPlayPolicy
import java.util.Collections
import java.util.UUID

@ConsistentCopyVisibility
data class CompetitionMahjongSeriesOutcome internal constructor(
    val matchId: String,
    val viewerPlayerId: Int,
    val difficulty: Difficulty,
    val settledAtEpochMillis: Long,
    val rank: Int,
    val score: Long,
    val standardPointsNumerator: Int,
    val standardPointsDenominator: Int,
    val handWins: Int,
    val selfDrawWins: Int,
    val dealInCount: Int,
    val totalFanWon: Int,
    val highestFan: Int,
) {
    init {
        require(runCatching { UUID.fromString(matchId).toString() == matchId }.getOrDefault(false))
        require(viewerPlayerId in 0..3 && settledAtEpochMillis >= 0 && rank in 1..4)
        require(score in Int.MIN_VALUE.toLong() * 16..Int.MAX_VALUE.toLong() * 16)
        require(standardPointsNumerator in 0..7 && standardPointsDenominator in 1..4 &&
            12 % standardPointsDenominator == 0)
        require(handWins in 0..16 && selfDrawWins in 0..handWins && dealInCount in 0..16)
        require(totalFanWon in 0..1_000_000 && highestFan in 0..1_000_000)
        require(if (handWins == 0) totalFanWon == 0 && highestFan == 0 else
            highestFan >= 8 && totalFanWon >= handWins * 8)
        require(highestFan <= totalFanWon)
    }

    val standardPointsTwelfths: Int
        get() = standardPointsNumerator * (12 / standardPointsDenominator)

    internal fun sameResult(other: CompetitionMahjongSeriesOutcome): Boolean =
        copy(settledAtEpochMillis = other.settledAtEpochMillis) == other

    companion object {
        fun from(checkpoint: CompetitionMahjongMatchCheckpoint,
                 playPolicy: CompetitionMahjongPlayPolicy,
                 settledAtEpochMillis: Long): CompetitionMahjongSeriesOutcome {
            require(checkpoint.ledger.isComplete) { "只统计已完成的十六盘赛事" }
            val usage = requireNotNull(checkpoint.assistance) { "旧赛事缺少正式难度，不计入统计" }
            playPolicy.requireAuthorized(checkpoint.config.botLevel, usage)
            val viewer = checkpoint.config.viewerPlayerId
            val standing = checkpoint.ledger.finalStandings().single { it.playerId == viewer }
            val wins = checkpoint.ledger.results.filter { it.winnerPlayerId == viewer }
            return CompetitionMahjongSeriesOutcome(
                checkpoint.config.matchId,
                viewer,
                usage.difficulty,
                settledAtEpochMillis,
                standing.rank,
                standing.score,
                standing.standardPointsNumerator,
                standing.standardPointsDenominator,
                wins.size,
                wins.count { it.payerPlayerId == null },
                checkpoint.ledger.results.count { it.payerPlayerId == viewer },
                wins.sumOf { it.totalFan },
                wins.maxOfOrNull { it.totalFan } ?: 0,
            )
        }
    }
}

data class CompetitionMahjongDifficultyStatistics(
    val seriesCount: Int = 0,
    val firstPlaceCount: Int = 0,
    val topTwoCount: Int = 0,
    val standardPointsTwelfths: Long = 0,
)

class CompetitionMahjongStatistics internal constructor(outcomes: List<CompetitionMahjongSeriesOutcome>) {
    val outcomes: List<CompetitionMahjongSeriesOutcome> = Collections.unmodifiableList(outcomes.toList())
    val seriesCount: Int = outcomes.size
    val firstPlaceCount: Int = outcomes.count { it.rank == 1 }
    val topTwoCount: Int = outcomes.count { it.rank <= 2 }
    val rankCounts: List<Int> = Collections.unmodifiableList((1..4).map { rank -> outcomes.count { it.rank == rank } })
    val cumulativeScore: Long = outcomes.sumOf { it.score }
    val standardPointsTwelfths: Long = outcomes.sumOf { it.standardPointsTwelfths.toLong() }
    val handWins: Int = outcomes.sumOf { it.handWins }
    val selfDrawWins: Int = outcomes.sumOf { it.selfDrawWins }
    val dealInCount: Int = outcomes.sumOf { it.dealInCount }
    val totalFanWon: Long = outcomes.sumOf { it.totalFanWon.toLong() }
    val highestFan: Int = outcomes.maxOfOrNull { it.highestFan } ?: 0
    val bestScore: Long? = outcomes.maxOfOrNull { it.score }
    val byDifficulty: Map<Difficulty, CompetitionMahjongDifficultyStatistics> =
        Collections.unmodifiableMap(Difficulty.entries.associateWith { difficulty ->
            val selected = outcomes.filter { it.difficulty == difficulty }
            CompetitionMahjongDifficultyStatistics(selected.size, selected.count { it.rank == 1 },
                selected.count { it.rank <= 2 }, selected.sumOf { it.standardPointsTwelfths.toLong() })
        })

    init {
        require(outcomes.map { it.matchId }.toSet().size == outcomes.size)
        require(outcomes.map { it.viewerPlayerId }.toSet().size <= 1)
        require(outcomes.zipWithNext().all { (first, second) ->
            first.settledAtEpochMillis >= second.settledAtEpochMillis
        })
    }

    companion object {
        fun empty() = CompetitionMahjongStatistics(emptyList())
        internal fun from(outcomes: List<CompetitionMahjongSeriesOutcome>) =
            CompetitionMahjongStatistics(outcomes.sortedWith(
                compareByDescending<CompetitionMahjongSeriesOutcome> { it.settledAtEpochMillis }
                    .thenBy { it.matchId }))
    }
}
