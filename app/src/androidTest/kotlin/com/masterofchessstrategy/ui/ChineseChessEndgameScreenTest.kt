package com.masterofchessstrategy.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.masterofchessstrategy.endgame.ChineseChessEndgameLevel
import com.masterofchessstrategy.endgame.ChineseChessEndgameTrack
import com.masterofchessstrategy.endgame.ChineseChessEndgameUiState
import com.masterofchessstrategy.endgame.EndgameLevelEntry
import com.masterofchessstrategy.engine.BoardMove
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.ChineseChessSide
import com.masterofchessstrategy.engine.Difficulty
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChineseChessEndgameScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun lockedBonusLevelExplainsItsSeparateUnlockPath() {
        val level = ChineseChessEndgameLevel(
            id = "xq-easy-003",
            difficulty = Difficulty.EASY,
            chapterOrder = 3,
            title = "奖励验收",
            theme = "唯一一步杀",
            sideToMove = ChineseChessSide.RED,
            maxPlayerMoves = 1,
            starReward = 1,
            scoreReward = 10,
            pieces = emptyList(),
            principalVariation = listOf(
                BoardMove(BoardPosition(0, 2), BoardPosition(4, 2)),
            ),
            track = ChineseChessEndgameTrack.BONUS,
        )
        val entry = EndgameLevelEntry(
            level = level,
            isChapterUnlocked = true,
            isUnlocked = false,
            completion = null,
        )

        composeRule.setContent {
            ChineseChessEndgameScreen(
                state = ChineseChessEndgameUiState(
                    entries = listOf(entry),
                    visibleEntries = listOf(entry),
                    themes = listOf(level.theme),
                    isLoading = false,
                ),
                onBack = {},
                onModeSelected = {},
                onDifficultySelected = {},
                onThemeSelected = {},
                onRandomChallenge = {},
                onLevelSelected = {},
            )
        }

        composeRule.onNodeWithText("奖励支线", useUnmergedTree = true).assertIsDisplayed()
        composeRule.onNodeWithText(
            "完成本档主线和上一奖励关后解锁",
            useUnmergedTree = true,
        ).assertIsDisplayed()
    }
}
