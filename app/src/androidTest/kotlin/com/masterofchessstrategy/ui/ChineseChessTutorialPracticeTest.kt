package com.masterofchessstrategy.ui

import android.os.ParcelFileDescriptor
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.TutorialProgress
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.tutorial.ChineseChessTutorialUiState
import com.masterofchessstrategy.ui.theme.MocsAppWindow
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChineseChessTutorialPracticeTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun everyPieceDiagramCanBeSelectedAndRenderedOnEmulator() {
        composeRule.setContent {
            MocsAppWindow {
                ChineseChessTutorialScreen(
                    state = ChineseChessTutorialUiState(
                        progress = TutorialProgress(GameType.CHINESE_CHESS, 1, 1, false, 10L),
                        isLoading = false,
                    ),
                    onBack = {}, onContinueReading = {}, onPracticeSquareTap = {},
                    onCompletePractice = {}, onAnswerQuiz = {}, onCompleteQuiz = {},
                )
            }
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        composeRule.onNodeWithTag("tutorial_chariot_diagram").performScrollTo().assertIsEnabled()
        composeRule.onNodeWithText(
            instrumentation.targetContext.getString(TutorialMoveDiagram.CHARIOT.explanationRes),
        ).performScrollTo()
        screenshot("mocs-tutorial-chariot.png")

        TutorialMoveDiagram.entries.forEach { diagram ->
            composeRule.onNodeWithTag("tutorial_${diagram.name.lowercase()}_diagram")
                .performScrollTo().performClick()
            composeRule.onNodeWithText(
                instrumentation.targetContext.getString(diagram.explanationRes),
            ).assertExists()
        }
        composeRule.onNodeWithText(
            instrumentation.targetContext.getString(TutorialMoveDiagram.SOLDIER.explanationRes),
        ).performScrollTo()
        screenshot("mocs-tutorial-soldier.png")
    }

    @Test
    fun practiceRequiresSavedCompletionAndAnAvailableLevel() {
        val state = mutableStateOf(ChineseChessTutorialUiState(
            progress = TutorialProgress(GameType.CHINESE_CHESS, 1, 4, true, 10L),
            isLoading = false, isSaving = true,
        ))
        val title = mutableStateOf<String?>("双马锁宫演示")
        var opened = 0
        composeRule.setContent {
            ChineseChessTutorialScreen(
                state = state.value, onBack = {}, onContinueReading = {},
                onPracticeSquareTap = {}, onCompletePractice = {},
                onAnswerQuiz = {}, onCompleteQuiz = {},
                practiceTitle = title.value, onOpenEndgamePractice = { opened++ },
            )
        }
        composeRule.onNodeWithTag(TUTORIAL_ENDGAME_TAG).performScrollTo().assertIsNotEnabled()
        composeRule.runOnIdle { state.value = state.value.copy(isSaving = false); title.value = null }
        composeRule.onNodeWithTag(TUTORIAL_ENDGAME_TAG).assertIsNotEnabled()
        composeRule.runOnIdle { title.value = "双马锁宫演示" }
        composeRule.onNodeWithTag(TUTORIAL_ENDGAME_TAG).performScrollTo().assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, opened) }

        composeRule.runOnIdle {
            state.value = state.value.copy(
                progress = TutorialProgress(GameType.CHINESE_CHESS, 1, 3, false, 10L),
            )
        }
        composeRule.onNodeWithTag(TUTORIAL_ENDGAME_TAG).assertDoesNotExist()
    }

    private fun screenshot(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        composeRule.waitForIdle()
        ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("screencap -p /data/local/tmp/$name"),
        ).use { it.readBytes() }
    }
}
