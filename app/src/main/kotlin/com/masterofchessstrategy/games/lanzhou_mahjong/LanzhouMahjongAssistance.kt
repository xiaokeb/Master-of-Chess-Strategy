package com.masterofchessstrategy.games.lanzhou_mahjong

import com.masterofchessstrategy.engine.Difficulty

internal enum class LanzhouMahjongAssistanceKind { UNDO, HINT }

/** Counters are independent and must be saved with the round before publishing a use. */
internal data class LanzhouMahjongAssistanceUsage(
    val difficulty: Difficulty,
    val undoUsed: Int = 0,
    val hintUsed: Int = 0,
) {
    val limit: Int? get() = when (difficulty) {
        Difficulty.EASY -> null
        Difficulty.MEDIUM -> 3
        Difficulty.HARD -> 1
        Difficulty.MASTER -> 0
    }

    init {
        require(undoUsed >= 0 && hintUsed >= 0)
        limit?.let { require(undoUsed <= it && hintUsed <= it) }
    }

    fun remaining(kind: LanzhouMahjongAssistanceKind): Int? =
        limit?.minus(if (kind == LanzhouMahjongAssistanceKind.UNDO) undoUsed else hintUsed)

    /** Null means exhausted. The returned usage must be persisted with its action or advice. */
    fun consume(kind: LanzhouMahjongAssistanceKind): LanzhouMahjongAssistanceUsage? {
        if (remaining(kind) == 0) return null
        fun increment(value: Int) = if (value == Int.MAX_VALUE) value else value + 1
        return when (kind) {
            LanzhouMahjongAssistanceKind.UNDO -> copy(undoUsed = increment(undoUsed))
            LanzhouMahjongAssistanceKind.HINT -> copy(hintUsed = increment(hintUsed))
        }
    }
}
