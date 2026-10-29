package com.masterofchessstrategy.games.lanzhou_mahjong

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.masterofchessstrategy.engine.LanzhouMahjongAction
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.LanzhouMahjongActionKind
import com.masterofchessstrategy.engine.LanzhouMahjongMeld
import com.masterofchessstrategy.engine.LanzhouMahjongMeldKind
import com.masterofchessstrategy.engine.LanzhouMahjongOutcomeView
import com.masterofchessstrategy.engine.LanzhouMahjongPhase
import com.masterofchessstrategy.engine.LanzhouMahjongPublicView
import com.masterofchessstrategy.engine.LanzhouMahjongSeatView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanzhouMahjongTableScreenTest {
    @get:Rule val composeRule = createComposeRule()

    @Test
    fun selectedHandReturnsOnlyItsSuppliedLegalDiscard() {
        val discardFirst = LanzhouMahjongAction(LanzhouMahjongActionKind.DISCARD, 0)
        val discardSecond = LanzhouMahjongAction(LanzhouMahjongActionKind.DISCARD, 1)
        val readyFirst = LanzhouMahjongAction(LanzhouMahjongActionKind.READY_DISCARD, 0)
        val received = mutableListOf<LanzhouMahjongAction>()
        showTable(actions = listOf(discardFirst, discardSecond, readyFirst), onAction = { received += it })

        composeRule.onNodeWithTag("lanzhou_mahjong_discard")
            .performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("lanzhou_mahjong_hand_0").performClick().assertIsSelected()
        composeRule.onNodeWithTag("lanzhou_mahjong_hand_1").assertIsNotSelected()
        composeRule.onNodeWithText("已选 1万").assertExists()
        composeRule.runOnIdle { assertEquals(0, received.size) }

        composeRule.onNodeWithTag("lanzhou_mahjong_discard").performClick()
        composeRule.runOnIdle {
            assertEquals(1, received.size)
            assertSame(discardFirst, received.single())
        }
        composeRule.onNodeWithTag("lanzhou_mahjong_hand_0").assertIsNotSelected()
        composeRule.onNodeWithTag("lanzhou_mahjong_discard").assertIsNotEnabled()

        composeRule.onNodeWithTag("lanzhou_mahjong_hand_2").performClick().assertIsSelected()
        composeRule.onNodeWithTag("lanzhou_mahjong_ready_discard").assertIsNotEnabled()
        composeRule.onNodeWithTag("lanzhou_mahjong_discard").assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals(2, received.size)
            assertSame(discardSecond, received.last())
        }
        composeRule.onNodeWithTag("lanzhou_mahjong_hand_4").assertIsNotEnabled()
    }

    @Test
    fun readyDiscardReturnsTheSuppliedActionForTheSelectedTile() {
        val ready = LanzhouMahjongAction(LanzhouMahjongActionKind.READY_DISCARD, 0)
        val received = mutableListOf<LanzhouMahjongAction>()
        showTable(actions = listOf(ready), onAction = { received += it })

        composeRule.onNodeWithTag("lanzhou_mahjong_ready_discard")
            .performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("lanzhou_mahjong_hand_0").performClick()
        composeRule.onNodeWithTag("lanzhou_mahjong_discard").assertIsNotEnabled()
        composeRule.onNodeWithTag("lanzhou_mahjong_ready_discard").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertSame(ready, received.single()) }
    }

    @Test
    fun failedSaveBlocksActionsNavigationRestartAndOpponentAdvanceUntilRetry() {
        var needsSave by mutableStateOf(false)
        var needsAdvance by mutableStateOf(false)
        var saves = 0
        var advances = 0
        var actions = 0
        val legal = listOf(
            LanzhouMahjongAction(LanzhouMahjongActionKind.DISCARD, 0),
            LanzhouMahjongAction(LanzhouMahjongActionKind.READY_DISCARD, 0),
            LanzhouMahjongAction(LanzhouMahjongActionKind.WIN),
        )
        composeRule.setContent {
            MaterialTheme {
                LanzhouMahjongTableScreen(
                    view = tableView(), viewerSeat = 0, actions = legal, feedback = null,
                    onAction = { actions++ }, onBack = {}, onRestart = {},
                    needsSave = needsSave, needsAdvance = needsAdvance,
                    onRetrySave = { saves++ }, onRetryAdvance = { advances++ },
                )
            }
        }
        composeRule.onNodeWithTag("lanzhou_mahjong_discard").performScrollTo()
        composeRule.onNodeWithTag("lanzhou_mahjong_hand_0").performClick()
        composeRule.runOnIdle {
            needsSave = true
            needsAdvance = true
        }

        composeRule.onNodeWithTag("lanzhou_mahjong_hand_0").assertIsNotEnabled()
        composeRule.onNodeWithTag("lanzhou_mahjong_discard").assertIsNotEnabled()
        composeRule.onNodeWithTag("lanzhou_mahjong_ready_discard").assertIsNotEnabled()
        composeRule.onNodeWithTag("lanzhou_mahjong_action_8_255").assertIsNotEnabled()
        composeRule.onNodeWithText("返回").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("重开").assertIsNotEnabled()
        composeRule.onNodeWithTag("lanzhou_mahjong_retry_advance")
            .performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("lanzhou_mahjong_retry_save")
            .performScrollTo().assertIsEnabled().performClick()
        composeRule.runOnIdle {
            assertEquals(1, saves)
            assertEquals(0, advances)
            assertEquals(0, actions)
        }
    }

    @Test
    fun busyRoundDisablesHandActionsRestartAndBothRetryButtons() {
        showTable(
            actions = listOf(
                LanzhouMahjongAction(LanzhouMahjongActionKind.DISCARD, 0),
                LanzhouMahjongAction(LanzhouMahjongActionKind.WIN),
            ),
            isBusy = true, needsSave = true, needsAdvance = true,
        )

        composeRule.onNodeWithTag("lanzhou_mahjong_busy").assertExists()
        composeRule.onNodeWithTag("lanzhou_mahjong_hand_0").assertIsNotEnabled()
        composeRule.onNodeWithTag("lanzhou_mahjong_discard").assertIsNotEnabled()
        composeRule.onNodeWithTag("lanzhou_mahjong_action_8_255").assertIsNotEnabled()
        composeRule.onNodeWithText("重开").assertIsNotEnabled()
        composeRule.onNodeWithTag("lanzhou_mahjong_retry_save").assertIsNotEnabled()
        composeRule.onNodeWithTag("lanzhou_mahjong_retry_advance").assertIsNotEnabled()
    }

    @Test
    fun processingRoundBlocksLeavingUntilCompleted() {
        var busy by mutableStateOf(true)
        var exits = 0
        composeRule.setContent {
            MaterialTheme {
                LanzhouMahjongTableScreen(view = tableView(), viewerSeat = 0,
                    actions = emptyList(), feedback = null, onAction = {},
                    onBack = { exits++ }, onRestart = {}, isBusy = busy)
            }
        }
        composeRule.onNodeWithText("返回").assertIsNotEnabled()
        composeRule.runOnIdle { busy = false }
        composeRule.onNodeWithText("返回").assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, exits) }
    }

    @Test
    fun opponentAdvanceFailureAllowsRetryWhileBlockingLocalActions() {
        var advances = 0
        showTable(
            actions = listOf(LanzhouMahjongAction(LanzhouMahjongActionKind.DISCARD, 0)),
            needsAdvance = true, onRetryAdvance = { advances++ },
        )

        composeRule.onNodeWithTag("lanzhou_mahjong_hand_0").assertIsNotEnabled()
        composeRule.onNodeWithTag("lanzhou_mahjong_discard").assertIsNotEnabled()
        composeRule.onNodeWithText("对手操作暂停，请重试").assertExists()
        composeRule.onNodeWithTag("lanzhou_mahjong_retry_advance")
            .performScrollTo().assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(1, advances) }
    }

    @Test
    fun opponentConcealedKongSentinelRendersWithoutExposingTiles() {
        val view = tableView()
        val seats = view.seats.toMutableList()
        // Even an overfilled input view must not make an opponent's hand face up.
        seats[1] = seats[1].copy(
            melds = listOf(LanzhouMahjongMeld(LanzhouMahjongMeldKind.CONCEALED_KONG, -1, 1)),
            concealedTiles = List(10) { 26 },
        )
        showTable(view = view.copy(seats = seats))

        composeRule.onNodeWithTag(LANZHOU_MAHJONG_TABLE_TAG).assertExists()
        composeRule.onNodeWithText("碰杠 暗杠").assertExists()
        composeRule.onNodeWithText("9筒", substring = true).assertDoesNotExist()
        composeRule.onNodeWithTag("lanzhou_mahjong_hand_13").assertExists()
        composeRule.onNodeWithTag("lanzhou_mahjong_hand_14").assertDoesNotExist()
    }

    @Test
    fun assistanceCallbacksDoNotApplySuggestedActionsAndShowRemainingCounts() {
        var hints = 0
        var undos = 0
        var actions = 0
        val legal = LanzhouMahjongAction(LanzhouMahjongActionKind.DISCARD, 0)
        composeRule.setContent {
            MaterialTheme {
                LanzhouMahjongTableScreen(tableView(), 0, listOf(legal), null,
                    onAction = { actions++ }, onBack = {}, onRestart = {},
                    assistance = LanzhouMahjongAssistanceUsage(Difficulty.MEDIUM, 1, 2),
                    canUndo = true, onHint = { hints++ }, onUndo = { undos++ },
                    hint = LanzhouMahjongHint(listOf(legal), "建议保留对子", listOf(4)))
            }
        }
        composeRule.onNodeWithText("悔棋 · 余2").performScrollTo().performClick()
        composeRule.onNodeWithText("提示 · 余1").performScrollTo().performClick()
        composeRule.onNodeWithTag("lanzhou_mahjong_hint_result").performScrollTo().assertExists()
        composeRule.onNodeWithText("等张：5万").assertExists()
        composeRule.runOnIdle {
            assertEquals(1, hints)
            assertEquals(1, undos)
            assertEquals(0, actions)
        }
    }

    @Test
    fun assistanceButtonsBlockExhaustedQuotasSavingAndBusyWork() {
        var usage by mutableStateOf(LanzhouMahjongAssistanceUsage(Difficulty.HARD, 1, 1))
        var saving by mutableStateOf(false)
        var busy by mutableStateOf(false)
        composeRule.setContent {
            MaterialTheme {
                LanzhouMahjongTableScreen(tableView(), 0,
                    listOf(LanzhouMahjongAction(LanzhouMahjongActionKind.DISCARD, 0)), null,
                    onAction = {}, onBack = {}, onRestart = {},
                    assistance = usage, canUndo = true, needsSave = saving, isBusy = busy)
            }
        }
        composeRule.onNodeWithTag("lanzhou_mahjong_undo").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("lanzhou_mahjong_hint").assertIsNotEnabled()
        composeRule.runOnIdle {
            usage = LanzhouMahjongAssistanceUsage(Difficulty.MEDIUM)
            saving = true
        }
        composeRule.onNodeWithTag("lanzhou_mahjong_undo").assertIsNotEnabled()
        composeRule.onNodeWithTag("lanzhou_mahjong_hint").assertIsNotEnabled()
        composeRule.runOnIdle { saving = false; busy = true }
        composeRule.onNodeWithTag("lanzhou_mahjong_undo").assertIsNotEnabled()
        composeRule.onNodeWithTag("lanzhou_mahjong_hint").assertIsNotEnabled()
        composeRule.runOnIdle { busy = false }
        composeRule.onNodeWithTag("lanzhou_mahjong_undo").assertIsEnabled()
        composeRule.onNodeWithTag("lanzhou_mahjong_hint").assertIsEnabled()
    }

    private fun showTable(
        view: LanzhouMahjongPublicView = tableView(),
        actions: List<LanzhouMahjongAction> = emptyList(),
        onAction: (LanzhouMahjongAction) -> Unit = {},
        isBusy: Boolean = false,
        needsSave: Boolean = false,
        needsAdvance: Boolean = false,
        onRetryAdvance: () -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                LanzhouMahjongTableScreen(
                    view = view, viewerSeat = 0, actions = actions, feedback = null,
                    onAction = onAction, onBack = {}, onRestart = {},
                    isBusy = isBusy, needsSave = needsSave, needsAdvance = needsAdvance,
                    onRetryAdvance = onRetryAdvance,
                )
            }
        }
    }

    private fun tableView(): LanzhouMahjongPublicView {
        val hand = listOf(0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 8, 8)
        return LanzhouMahjongPublicView(
            phase = LanzhouMahjongPhase.DISCARD, currentSeat = 0, dealerSeat = 0,
            wallRemaining = 55, pendingTile = null, pendingSeat = null,
            outcome = LanzhouMahjongOutcomeView(
                winnerSeat = null, payerSeat = null, nextDealer = 0,
                falseWin = false, sevenPairs = false, ready = false, kongDraw = false,
                lastTile = false,
            ),
            seats = List(4) { seat ->
                LanzhouMahjongSeatView(
                    seat = seat, concealedCount = if (seat == 0) hand.size else 13,
                    ready = false, responded = false, melds = emptyList(), discards = emptyList(),
                    concealedTiles = if (seat == 0) hand else emptyList(),
                )
            },
            netScores = List(4) { 0L },
        )
    }
}
