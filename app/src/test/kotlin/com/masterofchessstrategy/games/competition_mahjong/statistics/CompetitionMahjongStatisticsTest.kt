package com.masterofchessstrategy.games.competition_mahjong.statistics

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.games.competition_mahjong.match.*
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongAssistanceUsage
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongPlayPolicy
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongUnlockProgress
import org.junit.Assert.*
import org.junit.Test

class CompetitionMahjongStatisticsTest {
    @Test fun completeFormalSeriesProducesExactViewerStatistics() {
        val config = matchConfig()
        val checkpoint = CompetitionMahjongMatchCheckpoint(config, matchLedger(config, 16), 15,
            matchRecord(config.handConfig(15).viewerSeat, terminal = true),
            CompetitionMahjongAssistanceUsage(Difficulty.HARD))
        val outcome = CompetitionMahjongSeriesOutcome.from(checkpoint, policy(), 1234)

        assertEquals(config.matchId, outcome.matchId)
        assertEquals(Difficulty.HARD, outcome.difficulty)
        assertEquals(1, outcome.rank)
        assertEquals(512, outcome.score)
        assertEquals(4, outcome.standardPointsNumerator)
        assertEquals(1, outcome.standardPointsDenominator)
        assertEquals(16, outcome.handWins)
        assertEquals(0, outcome.selfDrawWins)
        assertEquals(0, outcome.dealInCount)
        assertEquals(128, outcome.totalFanWon)
        assertEquals(8, outcome.highestFan)
    }

    @Test fun incompleteOrLegacySeriesCannotPolluteFormalStatistics() {
        val config = matchConfig()
        val record = matchRecord(config.handConfig(15).viewerSeat)
        assertThrows(IllegalArgumentException::class.java) {
            CompetitionMahjongSeriesOutcome.from(CompetitionMahjongMatchCheckpoint(
                config, matchLedger(config, 15), 15, record, CompetitionMahjongAssistanceUsage(Difficulty.MEDIUM)), policy(), 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompetitionMahjongSeriesOutcome.from(CompetitionMahjongMatchCheckpoint(
                config, matchLedger(config, 16), 15,
                matchRecord(config.handConfig(15).viewerSeat, terminal = true)), policy(), 1)
        }
    }

    @Test fun aggregatePreservesRanksRationalStandardPointsAndDifficultyBreakdown() {
        val outcomes = listOf(
            outcome(id = 1, difficulty = Difficulty.EASY, time = 100, rank = 1, score = 80,
                numerator = 4, denominator = 1, wins = 3, selfDraws = 2, dealIns = 0, fan = 30, high = 16),
            outcome(id = 2, difficulty = Difficulty.HARD, time = 300, rank = 2, score = 10,
                numerator = 3, denominator = 2, wins = 2, selfDraws = 0, dealIns = 1, fan = 18, high = 10),
            outcome(id = 3, difficulty = Difficulty.HARD, time = 200, rank = 4, score = -90,
                numerator = 0, denominator = 1, wins = 0, selfDraws = 0, dealIns = 4, fan = 0, high = 0),
        )
        val stats = CompetitionMahjongStatistics.from(outcomes)
        assertEquals(listOf(300L, 200L, 100L), stats.outcomes.map { it.settledAtEpochMillis })
        assertEquals(3, stats.seriesCount)
        assertEquals(1, stats.firstPlaceCount)
        assertEquals(2, stats.topTwoCount)
        assertEquals(listOf(1, 1, 0, 1), stats.rankCounts)
        assertEquals(0, stats.cumulativeScore)
        assertEquals(66, stats.standardPointsTwelfths)
        assertEquals(5, stats.handWins)
        assertEquals(2, stats.selfDrawWins)
        assertEquals(5, stats.dealInCount)
        assertEquals(48, stats.totalFanWon)
        assertEquals(16, stats.highestFan)
        assertEquals(2, stats.byDifficulty.getValue(Difficulty.HARD).seriesCount)
    }

    @Test fun codecRoundTripsCanonicalOrderAndRejectsCorruptionOwnershipAndReservedBytes() {
        val values = listOf(
            outcome(1, Difficulty.MEDIUM, 100, 2, 5, 3, 2, 1, 0, 2, 8, 8),
            outcome(2, Difficulty.MASTER, 200, 1, 50, 4, 1, 2, 1, 0, 20, 12),
        )
        val bytes = CompetitionMahjongStatisticsCodec.encode(0, values)
        val decoded = CompetitionMahjongStatisticsCodec.decode(bytes, 0)!!
        assertEquals(listOf(values[1], values[0]), decoded.outcomes)
        assertArrayEquals(bytes, CompetitionMahjongStatisticsCodec.encode(0, decoded.outcomes))
        assertNull(CompetitionMahjongStatisticsCodec.decode(bytes, 1))
        assertNull(CompetitionMahjongStatisticsCodec.decode(bytes.copyOf().also { it[6] = 1 }, 0))
        assertNull(CompetitionMahjongStatisticsCodec.decode(bytes.copyOf().also {
            it[it.lastIndex] = (it.last().toInt() xor 1).toByte()
        }, 0))
        assertNull(CompetitionMahjongStatisticsCodec.decode(ByteArray(
            CompetitionMahjongStatisticsCodec.MAX_BYTES + 1), 0))
    }

    @Test fun outcomeAndStatisticsRejectConflictingOwnershipOrDuplicateIds() {
        val first = outcome(1, Difficulty.EASY, 1, 1, 1, 4, 1, 1, 1, 0, 8, 8)
        assertThrows(IllegalArgumentException::class.java) {
            CompetitionMahjongStatistics.from(listOf(first, first.copy(score = 2)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            CompetitionMahjongStatistics.from(listOf(first, first.copy(
                matchId = "90000000-0000-4000-8000-000000000002", viewerPlayerId = 1)))
        }
    }

    private fun outcome(id: Int, difficulty: Difficulty, time: Long, rank: Int, score: Long,
                        numerator: Int, denominator: Int, wins: Int, selfDraws: Int, dealIns: Int,
                        fan: Int, high: Int) = CompetitionMahjongSeriesOutcome(
        "90000000-0000-4000-8000-${id.toString().padStart(12, '0')}", 0, difficulty, time,
        rank, score, numerator, denominator, wins, selfDraws, dealIns, fan, high)

    private fun policy() = CompetitionMahjongPlayPolicy.fromProgress(
        CompetitionMahjongUnlockProgress(true, 10, 15, 20),
        Difficulty.entries.associateWith { it.code },
    )
}
