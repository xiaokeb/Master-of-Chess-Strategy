package com.masterofchessstrategy.games.competition_mahjong.match.policy

import com.masterofchessstrategy.engine.Difficulty
import java.util.Collections

/** Coordinator supplies this snapshot from the game's trusted tutorial/win records. */
data class CompetitionMahjongUnlockProgress(
    val tutorialCompleted: Boolean,
    val easyWins: Long = 0,
    val mediumWins: Long = 0,
    val hardWins: Long = 0,
) {
    init { require(easyWins >= 0 && mediumWins >= 0 && hardWins >= 0) }
}

data class CompetitionMahjongDifficultyOption(
    val difficulty: Difficulty,
    val botLevel: Int,
    val unlocked: Boolean,
    val requirement: String,
)

/** A single allowance spans all sixteen hands, and survives undo/recovery. */
data class CompetitionMahjongAssistanceUsage(
    val difficulty: Difficulty,
    val undoUsed: Int = 0,
    val hintUsed: Int = 0,
    val undoInProgress: Boolean = false,
) {
    init {
        require(undoUsed >= 0 && hintUsed >= 0)
        assistanceLimit(difficulty)?.let { require(undoUsed <= it && hintUsed <= it) }
        require(!undoInProgress || (undoUsed > 0 && difficulty != Difficulty.MASTER))
    }

    val undoRemaining: Int? get() = assistanceLimit(difficulty)?.minus(undoUsed)
    val hintRemaining: Int? get() = assistanceLimit(difficulty)?.minus(hintUsed)
    val canUndo: Boolean get() = undoRemaining != 0
    val canHint: Boolean get() = hintRemaining != 0

    fun consumeUndo(): CompetitionMahjongAssistanceUsage {
        check(canUndo) { "本赛事悔棋次数已用完" }
        return copy(undoUsed = increment(undoUsed))
    }

    fun consumeHint(): CompetitionMahjongAssistanceUsage {
        check(canHint) { "本赛事提示次数已用完" }
        return copy(hintUsed = increment(hintUsed))
    }

    private fun increment(value: Int): Int = if (value == Int.MAX_VALUE) value else value + 1
}

private fun assistanceLimit(difficulty: Difficulty): Int? = when (difficulty) {
    Difficulty.EASY -> null
    Difficulty.MEDIUM -> 3
    Difficulty.HARD -> 1
    Difficulty.MASTER -> 0
}

/** Permissions and quotas do not certify the playing strength of a strategy. */
class CompetitionMahjongPlayPolicy private constructor(
    val progress: CompetitionMahjongUnlockProgress,
    bindings: Map<Difficulty, Int>,
) {
    private val strategyBindings = Collections.unmodifiableMap(HashMap(bindings))
    val options: List<CompetitionMahjongDifficultyOption> = Collections.unmodifiableList(
        Difficulty.entries.map { difficulty ->
            CompetitionMahjongDifficultyOption(difficulty, strategyBindings.getValue(difficulty),
                isUnlocked(difficulty), requirement(difficulty))
        },
    )
    val allowedBotLevels: List<Int> = Collections.unmodifiableList(options.filter { it.unlocked }.map { it.botLevel })

    fun freshUsage(botLevel: Int): CompetitionMahjongAssistanceUsage =
        CompetitionMahjongAssistanceUsage(requireDifficulty(botLevel))

    fun requireAuthorized(botLevel: Int, usage: CompetitionMahjongAssistanceUsage?) {
        val difficulty = requireDifficulty(botLevel)
        requireNotNull(usage) { "旧赛事缺少难度与辅助历史，原存档已保留；请返回或明确新开赛事" }
        require(usage.difficulty == difficulty) { "赛事难度与策略权限不一致，原存档已保留" }
    }

    private fun requireDifficulty(botLevel: Int): Difficulty {
        val option = options.firstOrNull { it.botLevel == botLevel }
        requireNotNull(option) { "赛事策略未获配置授权" }
        require(option.unlocked) { "${difficultyLabel(option.difficulty)}尚未解锁：${option.requirement}" }
        return option.difficulty
    }

    private fun isUnlocked(difficulty: Difficulty): Boolean = progress.tutorialCompleted && when (difficulty) {
        Difficulty.EASY -> true
        Difficulty.MEDIUM -> progress.easyWins >= 10
        Difficulty.HARD -> progress.easyWins >= 10 && progress.mediumWins >= 15
        Difficulty.MASTER -> progress.easyWins >= 10 && progress.mediumWins >= 15 && progress.hardWins >= 20
    }

    companion object {
        /** Explicit binding belongs to the integration/AI admission, not to native numeric levels. */
        fun fromProgress(progress: CompetitionMahjongUnlockProgress, strategyBindings: Map<Difficulty, Int>): CompetitionMahjongPlayPolicy {
            require(strategyBindings.keys == Difficulty.entries.toSet())
            require(strategyBindings.values.all { it in 0..3 } && strategyBindings.values.toSet().size == 4)
            return CompetitionMahjongPlayPolicy(progress, strategyBindings)
        }

        fun difficultyLabel(difficulty: Difficulty): String = when (difficulty) {
            Difficulty.EASY -> "简单"
            Difficulty.MEDIUM -> "中等"
            Difficulty.HARD -> "困难"
            Difficulty.MASTER -> "大师"
        }

        private fun requirement(difficulty: Difficulty): String = when (difficulty) {
            Difficulty.EASY -> "完成国标麻将教程"
            Difficulty.MEDIUM -> "简单累计胜10场"
            Difficulty.HARD -> "中等累计胜15场"
            Difficulty.MASTER -> "困难累计胜20场"
        }
    }
}
