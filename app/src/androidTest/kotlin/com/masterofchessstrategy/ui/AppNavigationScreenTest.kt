package com.masterofchessstrategy.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import com.masterofchessstrategy.data.AppSettings
import com.masterofchessstrategy.data.HighlightCondition
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
        composeRule.onNodeWithTag(CUSTOM_SETUP_FEN_INPUT_TAG).assertExists()
        composeRule.onNodeWithTag(CUSTOM_SETUP_FEN_IMPORT_TAG).assertExists()
        composeRule.onNodeWithTag(CUSTOM_SETUP_FEN_EXPORT_TAG).assertExists()
        composeRule.onNodeWithTag(CUSTOM_SETUP_FEN_EXPORT_TAG).performClick()
        composeRule.onNodeWithTag(CUSTOM_SETUP_FEN_INPUT_TAG)
            .assertTextContains("rnbakabnr", substring = true)
    }

    @Test
    fun customPositionFenImportAndExportRoundTripOnDevice() {
        composeRule.setContent { MasterOfChessStrategyApp() }
        composeRule.onNodeWithTag(HOME_CHINESE_CHESS_TAG).performClick()
        composeRule.onNodeWithTag(MODE_EXTENSIONS_TAG).performClick()
        composeRule.onNodeWithTag(CUSTOM_SETUP_ENTRY_TAG).performClick()

        val fen = "4k4/9/9/9/9/4P4/9/9/9/4K4 w - - 0 1"
        composeRule.onNodeWithTag(CUSTOM_SETUP_FEN_INPUT_TAG).performTextInput(fen)
        composeRule.onNodeWithTag(CUSTOM_SETUP_FEN_IMPORT_TAG).performClick()
        composeRule.onNodeWithTag(CUSTOM_SETUP_FEN_EXPORT_TAG).performClick()
        composeRule.onNodeWithTag(CUSTOM_SETUP_FEN_INPUT_TAG).assertTextContains(fen)
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
    fun chineseChessExtensionsCanOpenBlindChallengeSetup() {
        composeRule.setContent {
            MasterOfChessStrategyApp()
        }

        composeRule.onNodeWithTag(HOME_CHINESE_CHESS_TAG).performClick()
        composeRule.onNodeWithTag(MODE_EXTENSIONS_TAG).performClick()
        composeRule.onNodeWithTag(BLIND_CHALLENGE_ENTRY_TAG).performClick()
        composeRule.onNodeWithTag(BLIND_CHALLENGE_SETUP_TAG).assertExists()
        composeRule.onNodeWithTag(BLIND_CHALLENGE_START_TAG).assertExists()
    }

    @Test
    fun chineseChessExtensionsCanOpenAssessmentSetup() {
        composeRule.setContent {
            MasterOfChessStrategyApp()
        }

        composeRule.onNodeWithTag(HOME_CHINESE_CHESS_TAG).performClick()
        composeRule.onNodeWithTag(MODE_EXTENSIONS_TAG).performClick()
        composeRule.onNodeWithTag(ASSESSMENT_CHALLENGE_ENTRY_TAG).performClick()
        composeRule.onNodeWithTag(ASSESSMENT_CHALLENGE_SETUP_TAG).assertExists()
        composeRule.onNodeWithTag(ASSESSMENT_CHALLENGE_START_TAG).assertExists()
    }

    @Test
    fun chineseChessExtensionsCanOpenOpeningTraining() {
        composeRule.setContent {
            MasterOfChessStrategyApp()
        }

        composeRule.onNodeWithTag(HOME_CHINESE_CHESS_TAG).performClick()
        composeRule.onNodeWithTag(MODE_EXTENSIONS_TAG).performClick()
        composeRule.onNodeWithTag(OPENING_TRAINING_ENTRY_TAG).performClick()
        composeRule.onNodeWithTag(OPENING_TRAINING_SCREEN_TAG).assertExists()
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
    fun homeCanOpenProfile() {
        composeRule.setContent {
            MasterOfChessStrategyApp()
        }

        composeRule.onNodeWithTag(HOME_PROFILE_TAG).performClick()
        composeRule.onNodeWithTag(PROFILE_SCREEN_TAG).assertExists()
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
                onHighlightCondition = { _, _ -> },
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
    fun highlightConditionSwitchEmitsRequestedValue() {
        var requested: Pair<HighlightCondition, Boolean>? = null
        composeRule.setContent {
            SettingsScreen(
                state = AppSettingsUiState(settings = AppSettings.DEFAULT, isLoading = false),
                onBack = {},
                onDefaultDifficulty = {},
                onAutoContinue = {},
                onAdjustAutoContinueLimit = {},
                onSoundEnabled = {},
                onHighlightCondition = { condition, enabled ->
                    requested = condition to enabled
                },
                onTimeLimitEnabled = {},
                onAdjustDuration = {},
                backupState = LocalDataBackupUiState(),
                onExportData = {},
                onRestoreData = {},
                onOpenSourceLicenses = {},
            )
        }

        composeRule.onNodeWithTag(SETTINGS_HIGHLIGHT_MASTER_TAG).performClick()

        composeRule.runOnIdle {
            assertEquals(HighlightCondition.MASTER to true, requested)
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
                onHighlightCondition = { _, _ -> },
                onTimeLimitEnabled = {},
                onAdjustDuration = {},
                backupState = LocalDataBackupUiState(),
                onExportData = { exportRequests++ },
                onRestoreData = { restoreRequests++ },
                onOpenSourceLicenses = {},
            )
        }

        composeRule.onNodeWithTag(SETTINGS_EXPORT_DATA_TAG).performScrollTo().performClick()
        composeRule.onNodeWithTag(SETTINGS_RESTORE_DATA_TAG).performScrollTo().performClick()

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
        composeRule.onNodeWithTag(SETTINGS_LICENSES_TAG).performScrollTo().performClick()
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
