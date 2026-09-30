package com.masterofchessstrategy.ui

import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.engine.ChineseChessNaturalLimitReview
import com.masterofchessstrategy.game.ChineseChessGameUiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChineseChessNaturalLimitScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun claimRequiresConfirmationAndShowsReviewedCount() {
        var claims = 0
        composeRule.setContent {
            ChineseChessGameScreen(
                state = ChineseChessGameUiState(
                    naturalLimitReview = ChineseChessNaturalLimitReview(
                        noCapturePlies = 122,
                        recordedPlies = 122,
                        claimantChecks = 11,
                        effectivePlies = 120,
                        completeRecord = true,
                        eligible = true,
                    ),
                    canClaimNaturalLimit = true,
                ),
                onSquareTap = {}, onUndo = {}, onHint = {}, onResign = {},
                onRestart = {}, onBack = {}, onNaturalLimitClaim = { claims++ },
            )
        }

        val claimButton = composeRule.onNodeWithTag(NATURAL_LIMIT_BUTTON_TAG)
        claimButton.performScrollTo().assertIsEnabled().performClick()
        composeRule.onNodeWithText("当前可计 120 / 120 个半回合。").assertExists()
        composeRule.waitForIdle()
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "screencap -p /data/local/tmp/mocs-natural-limit-dialog.png",
            ),
        ).use { it.readBytes() }
        composeRule.onNodeWithText("取消").performClick()
        assertEquals(0, claims)

        claimButton.performScrollTo().performClick()
        composeRule.onNodeWithText("确认申请").performClick()
        assertEquals(1, claims)
    }

    @Test
    fun incompleteRecordCannotOpenClaimConfirmation() {
        composeRule.setContent {
            ChineseChessGameScreen(
                state = ChineseChessGameUiState(
                    naturalLimitReview = ChineseChessNaturalLimitReview(
                        noCapturePlies = 120,
                        recordedPlies = 1,
                        claimantChecks = 0,
                        effectivePlies = 120,
                        completeRecord = false,
                        eligible = false,
                    ),
                ),
                onSquareTap = {}, onUndo = {}, onHint = {}, onResign = {},
                onRestart = {}, onBack = {},
            )
        }

        composeRule.onNodeWithTag(NATURAL_LIMIT_BUTTON_TAG)
            .performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("申请自然限着审核？").assertDoesNotExist()
    }
}
