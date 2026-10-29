package com.masterofchessstrategy.games.lanzhou_mahjong.data

import com.masterofchessstrategy.engine.Difficulty
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException

/** These are stored facts; absence of a trusted wins ledger is never a fabricated zero wins. */
internal data class LanzhouMahjongEntryFacts(
    val learning: LanzhouMahjongLearningSnapshot?,
    val active: LanzhouMahjongStoredSession?,
) {
    val tutorialCompleted: Boolean get() = learning?.isCompleted == true
}

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
        LanzhouMahjongEntryDenial.TRUSTED_WINS_UNAVAILABLE -> "此难度的可信胜场资格尚未接入"
        LanzhouMahjongEntryDenial.ACTIVE_GAME_CHANGED -> "活动对局已变化，请返回菜单重新读取"
        LanzhouMahjongEntryDenial.ADMISSION_UNAVAILABLE -> "新局资格暂不可用，请返回后重试"
    },
)
