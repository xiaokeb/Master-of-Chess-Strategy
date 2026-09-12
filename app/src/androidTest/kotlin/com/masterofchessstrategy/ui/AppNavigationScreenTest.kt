package com.masterofchessstrategy.ui

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class AppNavigationScreenTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun homeCanOpenModesAndDifficultyRulesWithoutCreatingGame() {
        composeRule.setContent {
            MasterOfChessStrategyApp()
        }

        composeRule.onNodeWithTag(HOME_CHINESE_CHESS_TAG).performClick()
        composeRule.onNodeWithTag(MODE_AI_GAME_TAG).performClick()
        composeRule.onNodeWithTag(DIFFICULTY_SCREEN_TAG).assertExists()
    }
}
