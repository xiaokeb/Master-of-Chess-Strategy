package com.masterofchessstrategy.games.competition_mahjong.match.storage

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongBotStrategy
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongCompetitionConfig
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongPlayPolicy
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongUnlockProgress
import com.masterofchessstrategy.games.competition_mahjong.match.policy.competitionMahjongProductionStrategies
import com.masterofchessstrategy.games.competition_mahjong.statistics.CompetitionMahjongSeriesOutcome
import com.masterofchessstrategy.games.competition_mahjong.statistics.CompetitionMahjongStatistics
import java.util.UUID
import org.junit.Assert.*
import org.junit.Test

class CompetitionMahjongQualifiedGrowthTest {
    private val base = CompetitionMahjongPlayPolicy.fromProgress(CompetitionMahjongUnlockProgress(true),
        competitionMahjongProductionStrategies)

    @Test fun oneSeriesBecomesOneRankedResultAndPublicOnlyRowsAreExcluded() {
        val wins = result(1, Difficulty.EASY)
        val tie = result(2, Difficulty.EASY, share = 2)
        val loss = result(3, Difficulty.EASY, rank = 2)
        val duplicateSeed = result(4, Difficulty.EASY, seed = 1)
        val oldPublic = result(5, Difficulty.EASY)
        val qualified = snapshot(listOf(wins, tie, loss, duplicateSeed), listOf(oldPublic.second)).qualifiedGrowth(base)
        assertEquals(listOf(GameResult.FIRST_PLAYER_WIN, GameResult.DRAW, GameResult.SECOND_PLAYER_WIN),
            qualified.outcomes.map { it.result })
        assertEquals(1L, qualified.policy.progress.easyWins)
        assertEquals(listOf(0), qualified.policy.allowedBotLevels)
    }

    @Test fun chronologicalProofsUnlockExactlyAtTenFifteenAndTwentyWins() {
        val easy = (1..10).map { result(it, Difficulty.EASY) }
        val medium = (11..25).map { result(it, Difficulty.MEDIUM) }
        val hard = (26..45).map { result(it, Difficulty.HARD) }
        fun levels(records: List<Record>) = snapshot(records).qualifiedGrowth(base).policy.allowedBotLevels
        assertEquals(listOf(0), levels(easy.dropLast(1)))
        assertEquals(listOf(0, 1), levels(easy))
        assertEquals(listOf(0, 1), levels(easy + medium.dropLast(1)))
        assertEquals(listOf(0, 1, 2), levels(easy + medium))
        assertEquals(listOf(0, 1, 2), levels(easy + medium + hard.dropLast(1)))
        assertEquals(listOf(0, 1, 2, 3), levels(easy + medium + hard))
        assertThrows(IllegalArgumentException::class.java) { levels(medium.take(1) + easy) }
        assertThrows(IllegalArgumentException::class.java) {
            snapshot(easy).qualifiedGrowth(base.withProgress(CompetitionMahjongUnlockProgress(false)))
        }
    }

    private fun result(id: Int, difficulty: Difficulty, rank: Int = 1, share: Int = 1, seed: Long = id.toLong()): Record {
        val matchId = UUID(0, id.toLong()).toString()
        val config = CompetitionMahjongCompetitionConfig(matchId, seed, 0,
            competitionMahjongProductionStrategies.getValue(difficulty), CompetitionMahjongBotStrategy.POSITION_SEEDED_V1)
        val outcome = CompetitionMahjongSeriesOutcome(matchId, 0, difficulty, 1, rank, 0,
            if (rank == 2) 2 else if (share == 2) 6 else 4, share, 0, 0, 0, 0, 0)
        return CompetitionMahjongArchivedSeries(config, List(16) { shortArrayOf() }) to outcome
    }

    private fun snapshot(records: List<Record>, publicOnly: List<CompetitionMahjongSeriesOutcome> = emptyList()):
        CompetitionMahjongFormalSnapshot {
        val results = records.map { it.second } + publicOnly
        return CompetitionMahjongFormalSnapshot(0, statistics = CompetitionMahjongStatistics.from(results),
            deletedMatchIds = results.map { it.matchId }.toSet(), archivedSeries = records.map { it.first })
    }
}

private typealias Record = Pair<CompetitionMahjongArchivedSeries, CompetitionMahjongSeriesOutcome>
