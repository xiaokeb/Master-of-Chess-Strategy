package com.masterofchessstrategy.games.lanzhou_mahjong.data

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.games.lanzhou_mahjong.settlement.LanzhouMahjongStatistics
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException

internal data class LanzhouMahjongEntryFacts(
    val learning: LanzhouMahjongLearningSnapshot?,
    val active: LanzhouMahjongStoredSession?,
    val trustedWins: Map<Difficulty, Int> = emptyMap(),
    val activeCompleted: Boolean = false,
    val statistics: LanzhouMahjongStatistics = LanzhouMahjongStatistics(),
) {
    val tutorialCompleted: Boolean get() = learning?.isCompleted == true

    fun canStart(difficulty: Difficulty): Boolean =
        tutorialCompleted && (lanzhouMahjongWinRequirements[difficulty]?.let {
            (trustedWins[it.previous] ?: 0) >= it.minimum
        } ?: true)
}

internal data class LanzhouMahjongWinRequirement(val previous: Difficulty, val minimum: Int)

internal val lanzhouMahjongWinRequirements = mapOf(
    Difficulty.MEDIUM to LanzhouMahjongWinRequirement(Difficulty.EASY, 10),
    Difficulty.HARD to LanzhouMahjongWinRequirement(Difficulty.MEDIUM, 15),
    Difficulty.MASTER to LanzhouMahjongWinRequirement(Difficulty.HARD, 20),
)

internal interface LanzhouMahjongEntryRepository {
    val ownerEpoch: Long
    val epoch: StateFlow<Long>
    suspend fun loadFacts(): LanzhouMahjongEntryFacts
    /** Null means first creation; replacement must name the exact acknowledged current checkpoint. */
    suspend fun authorizeNew(difficulty: Difficulty, expectedCurrent: LanzhouMahjongStoredSession?)
}

internal enum class LanzhouMahjongEntryDenial {
    TUTORIAL_REQUIRED, TRUSTED_WINS_UNAVAILABLE, ACTIVE_GAME_CHANGED, ADMISSION_UNAVAILABLE,
}

internal class LanzhouMahjongEntryDeniedException(val reason: LanzhouMahjongEntryDenial) : IOException(
    when (reason) {
        LanzhouMahjongEntryDenial.TUTORIAL_REQUIRED -> "请先完成兰州麻将十课教程"
        LanzhouMahjongEntryDenial.TRUSTED_WINS_UNAVAILABLE -> "前一难度的可信胜场尚未达到解锁要求"
        LanzhouMahjongEntryDenial.ACTIVE_GAME_CHANGED -> "活动对局已变化，请返回菜单重新读取"
        LanzhouMahjongEntryDenial.ADMISSION_UNAVAILABLE -> "新局资格暂不可用，请返回后重试"
    },
)
