package com.masterofchessstrategy.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.masterofchessstrategy.game.ChineseChessGameUiState
import org.junit.Rule
import org.junit.Test

class ChineseChessGameScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun foundationControlsExposeCurrentCapabilityBoundary() {
        composeRule.setContent {
            ChineseChessGameScreen(
                state = ChineseChessGameUiState(),
                onSquareTap = {},
                onUndo = {},
                onHint = {},
                onResign = {},
                onRestart = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithTag(CHINESE_CHESS_BOARD_TAG).assertExists()
        composeRule.onNodeWithTag(UNDO_BUTTON_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(HINT_BUTTON_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(RESIGN_BUTTON_TAG).assertIsEnabled()
        composeRule.onNodeWithTag(RESTART_BUTTON_TAG).assertIsEnabled()
        composeRule.onNodeWithTag(AI_BUTTON_TAG).assertIsNotEnabled()
    }

    @Test
    fun mediumAssistanceControlsExposeRemainingCapability() {
        composeRule.setContent {
            ChineseChessGameScreen(
                state = ChineseChessGameUiState(
                    canUndo = true,
                    undoRemaining = 2,
                    canRequestHint = true,
                    hintRemaining = 3,
                    isAiGame = true,
                ),
                onSquareTap = {},
                onUndo = {},
                onHint = {},
                onResign = {},
                onRestart = {},
                onBack = {},
            )
        }

        composeRule.onNodeWithTag(UNDO_BUTTON_TAG).assertIsEnabled()
        composeRule.onNodeWithTag(HINT_BUTTON_TAG).assertIsEnabled()
        composeRule.onNodeWithTag(RESIGN_BUTTON_TAG).assertIsEnabled()
    }
}
