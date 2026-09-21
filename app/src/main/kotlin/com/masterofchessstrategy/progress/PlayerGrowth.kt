package com.masterofchessstrategy.progress

import com.masterofchessstrategy.data.EndgameProgress
import com.masterofchessstrategy.data.GameStatistics
import com.masterofchessstrategy.data.PlayerStatistics
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameType

/**
 * Version-one offline rank rules. Reaching any threshold promotes the player;
 * permanent win milestones intentionally prevent later demotion below that tier.
 */
internal enum class PlayerRank(
    val title: String,
    val minimumWins: Int,
    val minimumStars: Int,
    val minimumScore: Int,
) {
    BEGINNER("入门", 0, 0, 0),
    APPRENTICE("学徒", 10, 5, 100),
    PLAYER("棋手", 25, 10, 300),
    SCHOLAR("棋士", 50, 20, 700),
    MASTER("棋师", 100, 40, 1_500),
    GRANDMASTER("宗师", 200, 80, 3_000),
}

internal enum class PlayerAppearance(
    val code: Int,
    val title: String,
    val requiredRank: PlayerRank,
) {
    BAMBOO_STUDENT(0, "竹影学童", PlayerRank.BEGINNER),
    TRAVELING_APPRENTICE(1, "游学棋徒", PlayerRank.APPRENTICE),
    JADE_PLAYER(2, "青玉棋手", PlayerRank.PLAYER),
    INK_SCHOLAR(3, "墨韵棋士", PlayerRank.SCHOLAR),
    GOLD_MASTER(4, "金纹棋师", PlayerRank.MASTER),
    CLOUD_GRANDMASTER(5, "云巅宗师", PlayerRank.GRANDMASTER),
    ;

    companion object {
        fun fromCode(code: Int): PlayerAppearance =
            entries.firstOrNull { it.code == code } ?: BAMBOO_STUDENT
    }
}

internal data class PlayerAchievement(
    val id: String,
    val title: String,
    val description: String,
    val isUnlocked: Boolean,
)

internal data class PlayerGrowthSummary(
    val rank: PlayerRank,
    val totalStars: Int,
    val totalScore: Int,
    val statistics: PlayerStatistics,
    val endgameProgress: EndgameProgress,
    val achievements: List<PlayerAchievement>,
) {
    val currentRankStars: Int = totalStars % STARS_PER_RANK
    val unlockedAppearances: Set<PlayerAppearance> =
        PlayerAppearance.entries.filterTo(linkedSetOf()) {
            it.requiredRank.ordinal <= rank.ordinal
        }

    fun gameStatistics(gameType: GameType): GameStatistics =
        statistics.byGame[gameType] ?: GameStatistics()

    companion object {
        const val STARS_PER_RANK = 5

        fun create(
            statistics: PlayerStatistics,
            endgameProgress: EndgameProgress,
        ): PlayerGrowthSummary {
            val totalStars = statistics.netStars + endgameProgress.totalStars
            val totalScore = statistics.score + endgameProgress.totalScore
            val rank = PlayerRank.entries.last { candidate ->
                statistics.totalWins >= candidate.minimumWins ||
                    totalStars >= candidate.minimumStars ||
                    totalScore >= candidate.minimumScore
            }
            val chineseChess = statistics.byGame[GameType.CHINESE_CHESS] ?: GameStatistics()
            val hardWins = statistics.winsAt(GameType.CHINESE_CHESS, Difficulty.HARD)
            val masterWins = statistics.winsAt(GameType.CHINESE_CHESS, Difficulty.MASTER)
            val endgameCount = endgameProgress.completedById.size
            val achievements = listOf(
                PlayerAchievement(
                    id = "first-win",
                    title = "初战告捷",
                    description = "赢得首场排位人机对局",
                    isUnlocked = statistics.totalWins >= 1,
                ),
                PlayerAchievement(
                    id = "xiangqi-rookie",
                    title = "象棋新秀",
                    description = "赢得 10 场中国象棋排位对局",
                    isUnlocked = chineseChess.wins >= 10,
                ),
                PlayerAchievement(
                    id = "endgame-breaker",
                    title = "残局破阵",
                    description = "首次通关中国象棋残局",
                    isUnlocked = endgameCount >= 1,
                ),
                PlayerAchievement(
                    id = "endgame-scholar",
                    title = "残局研习",
                    description = "通关 5 道中国象棋残局",
                    isUnlocked = endgameCount >= 5,
                ),
                PlayerAchievement(
                    id = "hard-winner",
                    title = "迎战强手",
                    description = "战胜困难 AI",
                    isUnlocked = hardWins >= 1,
                ),
                PlayerAchievement(
                    id = "master-winner",
                    title = "弈境登峰",
                    description = "战胜大师 AI",
                    isUnlocked = masterWins >= 1,
                ),
            )
            return PlayerGrowthSummary(
                rank = rank,
                totalStars = totalStars,
                totalScore = totalScore,
                statistics = statistics,
                endgameProgress = endgameProgress,
                achievements = achievements,
            )
        }
    }
}
