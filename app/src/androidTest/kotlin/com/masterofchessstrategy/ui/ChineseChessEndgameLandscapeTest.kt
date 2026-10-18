package com.masterofchessstrategy.ui

import android.content.pm.ActivityInfo
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.R
import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import com.masterofchessstrategy.endgame.ChineseChessEndgameUiState
import com.masterofchessstrategy.endgame.EndgameCatalogMode
import com.masterofchessstrategy.endgame.EndgameLevelEntry
import com.masterofchessstrategy.engine.Difficulty
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChineseChessEndgameLandscapeTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun fullMixedPieceCardIsVisibleBesideFiltersInLandscape() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        composeRule.waitForIdle()
        val context = instrumentation.targetContext
        val level = ChineseChessEndgamePackParser.loadBundled(context.assets).levels
            .single { it.id == "xq-medium-004" }
        val entry = EndgameLevelEntry(level, true, true, null)
        composeRule.setContent {
            ChineseChessEndgameScreen(
                state = ChineseChessEndgameUiState(
                    selectedDifficulty = Difficulty.MEDIUM,
                    entries = listOf(entry), visibleEntries = listOf(entry),
                    themes = listOf(level.theme), isLoading = false,
                ),
                onBack = {}, onModeSelected = {}, onDifficultySelected = {},
                onThemeSelected = {}, onRandomChallenge = {}, onLevelSelected = {},
            )
        }
        composeRule.onNodeWithTag(ENDGAME_LEVEL_PREFIX + level.id).assertIsDisplayed()
        composeRule.onNodeWithText("马跃宫口", substring = true, useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithText(
            context.getString(R.string.endgame_goal, 2, 1, 20), useUnmergedTree = true,
        ).assertIsDisplayed()
        composeRule.waitForIdle()
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(
                "screencap -p /data/local/tmp/mocs-xiangqi-medium-004-landscape.png",
            ),
        ).use { it.readBytes() }
    }

    @Test
    fun progressSummaryRemainsReachableWithThemeFiltersAtLargeText() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        composeRule.waitForIdle()
        composeRule.setContent {
            val deviceDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(deviceDensity.density, 1.5f)) {
                ChineseChessEndgameScreen(
                    state = ChineseChessEndgameUiState(
                        mode = EndgameCatalogMode.THEME,
                        themes = listOf("两步强制胜", "唯一一步杀", "多解胜局"),
                        isLoading = false,
                    ),
                    onBack = {}, onModeSelected = {}, onDifficultySelected = {},
                    onThemeSelected = {}, onRandomChallenge = {}, onLevelSelected = {},
                )
            }
        }
        composeRule.onNodeWithTag(ENDGAME_SUMMARY_TAG)
            .performScrollTo().assertIsDisplayed()
    }
}
