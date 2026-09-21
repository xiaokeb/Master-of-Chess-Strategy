package com.masterofchessstrategy.progress

import com.masterofchessstrategy.data.CompletedEndgameLevel
import com.masterofchessstrategy.data.EndgameProgress
import com.masterofchessstrategy.data.GameStatistics
import com.masterofchessstrategy.data.PlayerStatistics
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerGrowthTest {
    @Test
    fun highestThresholdAcrossWinsStarsAndScoreDeterminesRank() {
        val summary = PlayerGrowthSummary.create(
            statistics = PlayerStatistics(
                totalWins = 12,
                netStars = 6,
                score = 750,
            ),
            endgameProgress = EndgameProgress(),
        )

        assertEquals(PlayerRank.SCHOLAR, summary.rank)
        assertEquals(4, summary.unlockedAppearances.size)
    }

    @Test
    fun endgameRewardsContributeToGrowthWithoutChangingRankedWins() {
        val progress = EndgameProgress(
            completedById = mapOf(
                "level" to CompletedEndgameLevel(
                    levelId = "level",
                    difficulty = Difficulty.EASY,
                    bestPlayerMoves = 2,
                    starsAwarded = 3,
                    scoreAwarded = 120,
                    completedAtEpochMillis = 1L,
                ),
            ),
        )

        val summary = PlayerGrowthSummary.create(PlayerStatistics(), progress)

        assertEquals(PlayerRank.APPRENTICE, summary.rank)
        assertEquals(3, summary.totalStars)
        assertEquals(120, summary.totalScore)
        assertEquals(0, summary.statistics.totalWins)
        assertTrue(summary.achievements.first { it.id == "endgame-breaker" }.isUnlocked)
    }

    @Test
    fun achievementsUseChineseChessDifficultyLedger() {
        val summary = PlayerGrowthSummary.create(
            statistics = PlayerStatistics(
                totalWins = 10,
                byGame = mapOf(
                    GameType.CHINESE_CHESS to GameStatistics(
                        completedMatches = 12,
                        wins = 10,
                        losses = 1,
                        draws = 1,
                    ),
                ),
                winsByGameAndDifficulty = mapOf(
                    GameType.CHINESE_CHESS to mapOf(
                        Difficulty.HARD to 1,
                        Difficulty.MASTER to 0,
                    ),
                ),
            ),
            endgameProgress = EndgameProgress(),
        )

        assertTrue(summary.achievements.first { it.id == "xiangqi-rookie" }.isUnlocked)
        assertTrue(summary.achievements.first { it.id == "hard-winner" }.isUnlocked)
        assertFalse(summary.achievements.first { it.id == "master-winner" }.isUnlocked)
    }
}
