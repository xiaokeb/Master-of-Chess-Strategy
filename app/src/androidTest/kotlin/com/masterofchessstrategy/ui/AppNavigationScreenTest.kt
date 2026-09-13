package com.masterofchessstrategy.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.masterofchessstrategy.data.AppSettings
import com.masterofchessstrategy.navigation.HomeGameEntry
import com.masterofchessstrategy.settings.AppSettingsUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun chineseChessModesCanOpenTutorial() {
        composeRule.setContent {
            MasterOfChessStrategyApp()
        }

        composeRule.onNodeWithTag(HOME_CHINESE_CHESS_TAG).performClick()
        composeRule.onNodeWithTag(MODE_TUTORIAL_TAG).performClick()
        composeRule.onNodeWithTag(TUTORIAL_SCREEN_TAG).assertExists()
    }

    @Test
    fun homeExposesQuickStartOnlyWhenHistoryExists() {
        var quickStarts = 0
        composeRule.setContent {
            HomeScreen(
                onGameSelected = {},
                quickStartEntries = setOf(HomeGameEntry.CHINESE_CHESS),
                onQuickStart = { quickStarts++ },
                onSettings = {},
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
                onSettings = {},
            )
        }

        composeRule.onNodeWithTag(HOME_CHINESE_CHESS_TAG)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnLongClick))
    }

    @Test
    fun homeCanOpenSettings() {
        composeRule.setContent {
            MasterOfChessStrategyApp()
        }

        composeRule.onNodeWithTag(HOME_SETTINGS_TAG).performClick()
        composeRule.onNodeWithTag(SETTINGS_SCREEN_TAG).assertExists()
    }

    @Test
    fun settingsSoundSwitchEmitsRequestedValue() {
        var requestedSound = true
        composeRule.setContent {
            SettingsScreen(
                state = AppSettingsUiState(
                    settings = AppSettings.DEFAULT,
                    isLoading = false,
                ),
                onBack = {},
                onDefaultDifficulty = {},
                onAutoContinue = {},
                onSoundEnabled = { requestedSound = it },
                onTimeLimitEnabled = {},
                onAdjustDuration = {},
            )
        }

        composeRule.onNodeWithTag(SETTINGS_SOUND_TAG).performClick()

        composeRule.runOnIdle {
            assertFalse(requestedSound)
        }
    }
}
