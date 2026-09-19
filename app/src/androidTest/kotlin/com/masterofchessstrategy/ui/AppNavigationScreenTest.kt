package com.masterofchessstrategy.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import com.masterofchessstrategy.data.AppSettings
import com.masterofchessstrategy.navigation.HomeGameEntry
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.settings.AppSettingsUiState
import com.masterofchessstrategy.settings.LocalDataBackupUiState
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
    fun chineseChessModesCanOpenEndgameCatalog() {
        composeRule.setContent {
            MasterOfChessStrategyApp()
        }

        composeRule.onNodeWithTag(HOME_CHINESE_CHESS_TAG).performClick()
        composeRule.onNodeWithTag(MODE_ENDGAME_TAG).performClick()
        composeRule.onNodeWithTag(ENDGAME_SCREEN_TAG).assertExists()
        composeRule.onNodeWithTag(ENDGAME_LEVEL_PREFIX + "xq-easy-001").assertExists()
    }

    @Test
    fun chineseChessModesCanOpenCustomPositionEditor() {
        composeRule.setContent {
            MasterOfChessStrategyApp()
        }

        composeRule.onNodeWithTag(HOME_CHINESE_CHESS_TAG).performClick()
        composeRule.onNodeWithTag(MODE_EXTENSIONS_TAG).performClick()
        composeRule.onNodeWithTag(EXTENSIONS_SCREEN_TAG).assertExists()
        composeRule.onNodeWithTag(CUSTOM_SETUP_ENTRY_TAG).performClick()
        composeRule.onNodeWithTag(CUSTOM_SETUP_SCREEN_TAG).assertExists()
        composeRule.onNodeWithTag(CHINESE_CHESS_BOARD_TAG).assertExists()
        composeRule.onNodeWithTag(CUSTOM_SETUP_START_TAG).assertExists()
    }

    @Test
    fun chineseChessExtensionsCanOpenTimedChallengeSetup() {
        composeRule.setContent {
            MasterOfChessStrategyApp()
        }

        composeRule.onNodeWithTag(HOME_CHINESE_CHESS_TAG).performClick()
        composeRule.onNodeWithTag(MODE_EXTENSIONS_TAG).performClick()
        composeRule.onNodeWithTag(TIMED_CHALLENGE_ENTRY_TAG).performClick()
        composeRule.onNodeWithTag(TIMED_CHALLENGE_SETUP_TAG).assertExists()
        composeRule.onNodeWithTag(TIMED_CHALLENGE_START_TAG).assertExists()
    }

    @Test
    fun chineseChessExtensionsCanOpenStreakChallengeSetup() {
        composeRule.setContent {
            MasterOfChessStrategyApp()
        }

        composeRule.onNodeWithTag(HOME_CHINESE_CHESS_TAG).performClick()
        composeRule.onNodeWithTag(MODE_EXTENSIONS_TAG).performClick()
        composeRule.onNodeWithTag(STREAK_CHALLENGE_ENTRY_TAG).performClick()
        composeRule.onNodeWithTag(STREAK_CHALLENGE_SETUP_TAG).assertExists()
        composeRule.onNodeWithTag(STREAK_CHALLENGE_START_TAG).assertExists()
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
                onRecords = {},
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
                onRecords = {},
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
    fun homeCanOpenGameRecords() {
        composeRule.setContent {
            MasterOfChessStrategyApp()
        }

        composeRule.onNodeWithTag(HOME_RECORDS_TAG).performClick()
        composeRule.onNodeWithTag(GAME_RECORDS_SCREEN_TAG).assertExists()
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
                onAdjustAutoContinueLimit = {},
                onSoundEnabled = { requestedSound = it },
                onTimeLimitEnabled = {},
                onAdjustDuration = {},
                backupState = LocalDataBackupUiState(),
                onExportData = {},
                onRestoreData = {},
                onOpenSourceLicenses = {},
            )
        }

        composeRule.onNodeWithTag(SETTINGS_SOUND_TAG).performClick()

        composeRule.runOnIdle {
            assertFalse(requestedSound)
        }
    }

    @Test
    fun settingsDataActionsRemainExplicitUserActions() {
        var exportRequests = 0
        var restoreRequests = 0
        composeRule.setContent {
            SettingsScreen(
                state = AppSettingsUiState(
                    settings = AppSettings.DEFAULT,
                    isLoading = false,
                ),
                onBack = {},
                onDefaultDifficulty = {},
                onAutoContinue = {},
                onAdjustAutoContinueLimit = {},
                onSoundEnabled = {},
                onTimeLimitEnabled = {},
                onAdjustDuration = {},
                backupState = LocalDataBackupUiState(),
                onExportData = { exportRequests++ },
                onRestoreData = { restoreRequests++ },
                onOpenSourceLicenses = {},
            )
        }

        composeRule.onNodeWithTag(SETTINGS_EXPORT_DATA_TAG).performClick()
        composeRule.onNodeWithTag(SETTINGS_RESTORE_DATA_TAG).performClick()

        composeRule.runOnIdle {
            assertEquals(1, exportRequests)
            assertEquals(1, restoreRequests)
        }
    }

    @Test
    fun settingsCanOpenBundledLegalNotices() {
        composeRule.setContent {
            MasterOfChessStrategyApp()
        }

        composeRule.onNodeWithTag(HOME_SETTINGS_TAG).performClick()
        composeRule.onNodeWithTag(SETTINGS_LICENSES_TAG).performClick()
        composeRule.onNodeWithTag(OPEN_SOURCE_LICENSES_SCREEN_TAG).assertExists()
        composeRule.onNodeWithText("本软件不提供任何担保", substring = true).assertExists()
    }

    @Test
    fun completedTutorialMakesEasyDifficultySelectable() {
        var selected: Difficulty? = null
        composeRule.setContent {
            ChineseChessDifficultyScreen(
                onBack = {},
                tutorialCompleted = true,
                onDifficultySelected = { selected = it },
            )
        }

        composeRule.onNodeWithTag(DIFFICULTY_EASY_TAG).performClick()

        composeRule.runOnIdle {
            assertEquals(Difficulty.EASY, selected)
        }
    }

    @Test
    fun tenEasyWinsMakeMediumDifficultySelectable() {
        var selected: Difficulty? = null
        composeRule.setContent {
            ChineseChessDifficultyScreen(
                onBack = {},
                tutorialCompleted = true,
                winsByDifficulty = mapOf(Difficulty.EASY to 10),
                onDifficultySelected = { selected = it },
            )
        }

        composeRule.onNodeWithText("中等").performClick()

        composeRule.runOnIdle {
            assertEquals(Difficulty.MEDIUM, selected)
        }
    }
}
