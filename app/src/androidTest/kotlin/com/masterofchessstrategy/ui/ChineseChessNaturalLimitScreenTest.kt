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
import com.masterofchessstrategy.engine.ActionResult
import com.masterofchessstrategy.engine.BoardMove
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.ChineseChessFenCodec
import com.masterofchessstrategy.engine.ChineseChessNaturalLimitReview
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.NativeChineseChessEngine
import com.masterofchessstrategy.engine.RestoreResult
import com.masterofchessstrategy.game.ChineseChessGameViewModel
import com.masterofchessstrategy.game.ChineseChessGameUiState
import com.masterofchessstrategy.ui.theme.MocsAppWindow
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
            MocsAppWindow {
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
        }

        val claimButton = composeRule.onNodeWithTag(NATURAL_LIMIT_BUTTON_TAG)
        claimButton.performScrollTo().assertIsEnabled().performClick()
        composeRule.onNodeWithText("当前可计 120 / 120 个半回合。").assertExists()
        screenshot("mocs-natural-limit-dialog.png")
        composeRule.onNodeWithText("取消").performClick()
        assertEquals(0, claims)

        claimButton.performScrollTo().performClick()
        composeRule.onNodeWithText("确认申请").performClick()
        assertEquals(1, claims)
    }

    @Test
    fun incompleteRecordCannotOpenClaimConfirmation() {
        composeRule.setContent {
            MocsAppWindow {
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
        }

        composeRule.onNodeWithTag(NATURAL_LIMIT_BUTTON_TAG)
            .performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("申请自然限着审核？").assertDoesNotExist()
    }

    @Test
    fun nativeLongGameClaimSettlesDrawInRealViewModelAndScreen() {
        NativeChineseChessEngine().use { engine ->
            assertEquals(
                RestoreResult.Restored,
                engine.restore(
                    ChineseChessFenCodec.parse(
                        "5kr2/9/9/9/5P3/9/R8/9/9/5K3 w - - 0 1",
                    ).toEngineState(),
                ),
            )
            val redPath = listOf(
                0 to 6, 1 to 6, 2 to 6, 3 to 6, 3 to 7, 3 to 8,
                3 to 9, 2 to 9, 1 to 9, 0 to 9, 0 to 8, 0 to 7,
            )
            val blackPath = listOf(
                6 to 0, 7 to 0, 8 to 0, 8 to 1, 8 to 2,
                8 to 3, 7 to 3, 6 to 3, 6 to 2, 6 to 1,
            )
            repeat(60) { round ->
                fun move(path: List<Pair<Int, Int>>) {
                    val from = path[round % path.size]
                    val to = path[(round + 1) % path.size]
                    assertEquals(
                        ActionResult.Accepted,
                        engine.apply(
                            BoardMove(
                                BoardPosition(from.first, from.second),
                                BoardPosition(to.first, to.second),
                            ),
                        ),
                    )
                }
                move(redPath)
                move(blackPath)
            }
            val game = ChineseChessGameViewModel(engineFactory = { engine })
            assertEquals(120, game.uiState.naturalLimitReview?.effectivePlies)
            assertEquals(true, game.uiState.canClaimNaturalLimit)
            composeRule.setContent {
                MocsAppWindow {
                    ChineseChessGameScreen(
                        state = game.uiState,
                        onSquareTap = game::onSquareTap,
                        onUndo = game::undo,
                        onHint = game::requestHint,
                        onResign = game::resign,
                        onRestart = game::restart,
                        onBack = {},
                        onNaturalLimitClaim = game::claimNaturalLimit,
                    )
                }
            }
            composeRule.onNodeWithTag(NATURAL_LIMIT_BUTTON_TAG)
                .performScrollTo().assertIsEnabled().performClick()
            composeRule.onNodeWithText("确认申请").performClick()
            composeRule.runOnIdle { assertEquals(GameResult.DRAW, game.uiState.result) }
            composeRule.onNodeWithText("自然限着申请审核属实，本局判和。")
                .performScrollTo().assertExists()
            composeRule.onNodeWithTag(NATURAL_LIMIT_BUTTON_TAG)
                .performScrollTo().assertIsNotEnabled()
            screenshot("mocs-natural-limit-draw.png")
        }
    }

    private fun screenshot(name: String) {
        composeRule.waitForIdle()
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(
                "screencap -p /data/local/tmp/$name",
            ),
        ).use { it.readBytes() }
    }
}
