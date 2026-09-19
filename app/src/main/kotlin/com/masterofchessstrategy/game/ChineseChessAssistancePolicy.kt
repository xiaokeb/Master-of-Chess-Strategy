package com.masterofchessstrategy.game

import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.engine.Difficulty

internal enum class ChineseChessHintMode {
    NONE,
    ALL_LEGAL_MOVES,
    BEST_MOVE,
}

/** Immutable per-match assistance limits resolved from the selected mode. */
internal data class ChineseChessAssistancePolicy(
    val undoLimit: Int?,
    val hintLimit: Int?,
    val hintMode: ChineseChessHintMode,
) {
    fun undoRemaining(used: Int): Int? = undoLimit?.let { (it - used).coerceAtLeast(0) }

    fun hintRemaining(used: Int): Int? = hintLimit?.let { (it - used).coerceAtLeast(0) }

    companion object {
        fun resolve(
            mode: StoredGameMode,
            difficulty: Difficulty?,
        ): ChineseChessAssistancePolicy =
            if (
                mode != StoredGameMode.HUMAN_VS_AI &&
                mode != StoredGameMode.ENDGAME &&
                mode != StoredGameMode.CUSTOM_POSITION &&
                mode != StoredGameMode.TIMED_CHALLENGE
            ) {
                ChineseChessAssistancePolicy(
                    undoLimit = null,
                    hintLimit = 0,
                    hintMode = ChineseChessHintMode.NONE,
                )
            } else {
                when (difficulty) {
                    Difficulty.EASY -> ChineseChessAssistancePolicy(
                        undoLimit = null,
                        hintLimit = null,
                        hintMode = ChineseChessHintMode.ALL_LEGAL_MOVES,
                    )

                    Difficulty.MEDIUM -> ChineseChessAssistancePolicy(
                        undoLimit = 3,
                        hintLimit = 3,
                        hintMode = ChineseChessHintMode.BEST_MOVE,
                    )

                    Difficulty.HARD -> ChineseChessAssistancePolicy(
                        undoLimit = 1,
                        hintLimit = 0,
                        hintMode = ChineseChessHintMode.NONE,
                    )

                    Difficulty.MASTER,
                    null,
                    -> ChineseChessAssistancePolicy(
                        undoLimit = 0,
                        hintLimit = 0,
                        hintMode = ChineseChessHintMode.NONE,
                    )
                }
            }
    }
}
