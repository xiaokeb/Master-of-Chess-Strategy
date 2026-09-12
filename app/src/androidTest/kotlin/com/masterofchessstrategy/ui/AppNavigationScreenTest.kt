package com.masterofchessstrategy.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.masterofchessstrategy.navigation.HomeGameEntry
import org.junit.Assert.assertEquals
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

    @Test
    fun homeExposesQuickStartOnlyWhenHistoryExists() {
        var quickStarts = 0
        composeRule.setContent {
            HomeScreen(
                onGameSelected = {},
                quickStartEntries = setOf(HomeGameEntry.CHINESE_CHESS),
                onQuickStart = { quickStarts++ },
            )
        }

        composeRule.onNodeWithTag(HOME_CHINESE_CHESS_TAG)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsActions.OnLongClick))
            .performSemanticsAction(SemanticsActions.OnLongClick)

        composeRule.runOnIdle {
            assertEquals(1, quickStarts)
        }
    }

    @Test
    fun homeWithoutHistoryHasNoQuickStartAction() {
        composeRule.setContent {
            HomeScreen(
                onGameSelected = {},
                quickStartEntries = emptySet(),
                onQuickStart = {},
            )
        }

        composeRule.onNodeWithTag(HOME_CHINESE_CHESS_TAG)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
    }
}
