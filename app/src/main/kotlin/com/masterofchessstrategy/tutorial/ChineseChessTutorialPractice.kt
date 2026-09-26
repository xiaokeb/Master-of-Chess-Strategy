package com.masterofchessstrategy.tutorial

import com.masterofchessstrategy.endgame.ChineseChessEndgameLevel
import com.masterofchessstrategy.endgame.ChineseChessEndgameTrack
import com.masterofchessstrategy.endgame.ChineseChessEndgameUiState
import com.masterofchessstrategy.engine.Difficulty

/** Link the victory lesson to its stable introductory exercise, never a locked substitute. */
internal fun availableTutorialEndgame(
    tutorial: ChineseChessTutorialUiState,
    endgames: ChineseChessEndgameUiState,
): ChineseChessEndgameLevel? {
    if (!tutorial.isInteractionEnabled || !tutorial.progress.isCompleted || endgames.isLoading) {
        return null
    }
    return endgames.entries.singleOrNull {
        it.level.id == "xq-easy-001" &&
            it.level.difficulty == Difficulty.EASY &&
            it.level.track == ChineseChessEndgameTrack.MAIN &&
            it.isChapterUnlocked && it.isUnlocked
    }?.level
}
