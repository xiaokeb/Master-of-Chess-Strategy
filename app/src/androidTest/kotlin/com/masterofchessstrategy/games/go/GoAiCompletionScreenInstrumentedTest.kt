package com.masterofchessstrategy.games.go

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GoPhase
import com.masterofchessstrategy.engine.GoPlayer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/** Presentation only. Real Room/native completion and same-ID retry have separate tests. */
class GoAiCompletionScreenInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun fixedResultWithoutStorageBlocksAllReplacementControls() {
        show(finished(completionReserved = true))
        compose.onNodeWithTag("go_ai_completion_fixed").performScrollTo()
            .assertTextEquals("本局结果已固定，不能再悔棋。")
        compose.onNodeWithTag("go_ai_completion_status").performScrollTo()
            .assertTextEquals("本局完成棋谱尚待保存。")
        assertReplacementDisabled()
        compose.onNodeWithTag("go_ai_completion_retry").assertDoesNotExist()
    }

    @Test fun pendingReservationFailureOnlyRetriesCompletionAtLargeFont() {
        val callbacks = Callbacks()
        show(finished(completionPending = true), callbacks, fontScale = 1.5f)
        compose.onNodeWithTag("go_ai_completion_fixed_pending").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("go_ai_completion_status").performScrollTo()
            .assertTextEquals("完成棋谱保存未完成，请重试保存。")
        assertReplacementDisabled()
        compose.onNodeWithTag("go_ai_completion_retry").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(1, callbacks.retries)
            assertEquals(0, callbacks.restarts)
            assertEquals(0, callbacks.undos)
            assertEquals(0, callbacks.imports)
        }
        compose.onNodeWithText("棋谱保存不代表已计入人机胜场，成长以正式来源与结算为准。")
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun enteredSaveDisablesRetryAndReplacementUntilItReturns() {
        val initial = finished(completionReserved = true, completionPending = true)
        show(initial.copy(game = initial.game.copy(saving = true)))
        compose.onNodeWithTag("go_ai_completion_status").performScrollTo()
            .assertTextEquals("正在保存完成棋谱…")
        compose.onNodeWithTag("go_ai_completion_retry").performScrollTo().assertIsNotEnabled()
        assertReplacementDisabled()
    }

    @Test fun storedResultCanRestartButCannotUndoOrRetry() {
        val callbacks = Callbacks()
        show(finished(completionReserved = true, completionStored = true), callbacks)
        compose.onNodeWithTag("go_ai_completion_status").performScrollTo()
            .assertTextEquals("完成棋谱已保存。")
        compose.onNodeWithTag("go_ai_completion_retry").assertDoesNotExist()
        compose.onNodeWithTag("go_ai_undo").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("go_ai_restart").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("go_ai_restart_confirm").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, callbacks.restarts); assertEquals(0, callbacks.retries) }
    }

    @Test fun legacyTrainingFinishedStateKeepsItsExplicitSaveOnRestartBehavior() {
        val callbacks = Callbacks()
        show(finished(immediateHumanCompletion = false), callbacks)
        compose.onNodeWithTag("go_ai_completion_fixed").assertDoesNotExist()
        compose.onNodeWithTag("go_ai_completion_fixed_pending").assertDoesNotExist()
        compose.onNodeWithTag("go_ai_completion_status").assertDoesNotExist()
        compose.onNodeWithTag("go_ai_completion_retry").assertDoesNotExist()
        compose.onNodeWithTag("go_ai_undo").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("go_ai_restart").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("将先固定本局结果并保存完成棋谱，再开始新局。规格、让子、执色和难度保持，辅助次数重新计数。")
            .assertExists()
        compose.onNodeWithTag("go_ai_restart_confirm").performClick()
        compose.runOnIdle { assertEquals(1, callbacks.undos); assertEquals(1, callbacks.restarts) }
    }

    @Test fun alreadyOpenRestartDialogRechecksNewPendingState() {
        val state = mutableStateOf(page(finished()))
        val callbacks = Callbacks()
        showState(state, callbacks)
        compose.onNodeWithTag("go_ai_restart").performScrollTo().performClick()
        compose.onNodeWithTag("go_ai_restart_confirm").assertIsEnabled()
        compose.runOnIdle { state.value = page(finished(completionReserved = true, completionPending = true)) }
        compose.onNodeWithTag("go_ai_restart_confirm").assertIsNotEnabled()
        compose.onNodeWithTag("go_ai_restart_cancel").performClick()
        compose.runOnIdle { assertEquals(0, callbacks.restarts) }
    }

    @Test fun closedPendingResultCannotRetryOrReplace() {
        show(finished(completionReserved = true, completionPending = true).copy(closed = true))
        compose.onNodeWithTag("go_ai_completion_retry").performScrollTo().assertIsNotEnabled()
        assertReplacementDisabled()
    }

    @Test fun finishedStateWithoutStorageCannotOpenReplacement() {
        showState(mutableStateOf(page(finished()).copy(completedStorageReady = false)), Callbacks())
        compose.onNodeWithTag("go_ai_restart").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("go_ai_import_opening").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("完成棋谱保存功能准备好后才能重开；本局已保留。")
            .performScrollTo().assertIsDisplayed()
    }

    @Test fun legacyReservedResultRetriesThroughItsExistingRestartInsteadOfHumanRetry() {
        val callbacks = Callbacks()
        show(finished(completionReserved = true, immediateHumanCompletion = false), callbacks)
        compose.onNodeWithTag("go_ai_completion_status").assertDoesNotExist()
        compose.onNodeWithTag("go_ai_completion_retry").assertDoesNotExist()
        compose.onNodeWithTag("go_ai_undo").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("go_ai_import_opening").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("go_ai_restart").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("go_ai_restart_confirm").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, callbacks.restarts); assertEquals(0, callbacks.retries) }
    }

    @Test fun automaticSeriesKeepsItsOwnSaveRetryEvenWhenHumanPolicyWasConfigured() {
        val callbacks = Callbacks()
        val reserved = finished(completionReserved = true).let {
            it.copy(config = it.config.copy(humanPlayer = null), automaticUsed = true,
                autoContinueEnabled = true, autoContinueMaxGames = 2, autoContinueGameNumber = 2,
                game = it.game.copy(error = "连续对局已结束；完成棋谱保存未完成，请重试。"))
        }
        show(reserved, callbacks)
        compose.onNodeWithTag("go_ai_completion_status").assertDoesNotExist()
        compose.onNodeWithTag("go_ai_completion_retry").assertDoesNotExist()
        compose.onNodeWithTag("go_ai_restart").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("go_ai_import_opening").performScrollTo().assertIsEnabled()
        compose.onNodeWithTag("go_ai_auto_continue_retry").performScrollTo()
            .assertTextEquals("重试保存最后一局").performClick()
        compose.runOnIdle { assertEquals(1, callbacks.autoRetries); assertEquals(0, callbacks.retries) }
    }

    private fun assertReplacementDisabled() {
        compose.onNodeWithTag("go_ai_undo").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("go_ai_restart").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("go_ai_import_opening").performScrollTo().assertIsNotEnabled()
    }

    private fun show(match: GoAiMatchUiState, callbacks: Callbacks = Callbacks(), fontScale: Float = 1f) =
        showState(mutableStateOf(page(match)), callbacks, fontScale)

    private fun showState(state: MutableState<GoAiMatchPageState>, callbacks: Callbacks, fontScale: Float = 1f) {
        compose.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                MaterialTheme {
                    GoAiMatchScreen(state.value, GoPlayer.BLACK, GoScoreDisplayMode.STONES,
                        onAction = {}, onActor = {}, onDisplayMode = {}, onUndo = { callbacks.undos++ },
                        onPause = {}, onResume = {}, onSpeed = {}, onRetryModel = {}, onRetrySearch = {},
                        onBack = {}, onHint = {}, onRestart = { callbacks.restarts++ }, onAutomatic = {},
                        onAutoContinuation = { _, _ -> }, onRetryAutoContinuation = { callbacks.autoRetries++ }, openingMessage = null,
                        pendingOpening = null, onImportOpening = { callbacks.imports++ }, onDismissOpening = {},
                        onConfirmOpening = {}, onRetryCompletion = { callbacks.retries++ })
                }
            }
        }
    }

    private fun page(match: GoAiMatchUiState) = GoAiMatchPageState(
        loadingModel = false, match = match, completedStorageReady = true)

    private fun finished(completionReserved: Boolean = false, completionStored: Boolean = false,
        completionPending: Boolean = false, immediateHumanCompletion: Boolean = true) = GoAiMatchUiState(
        config = GoAiMatchConfig(9),
        game = GoGameUiState(boardSize = 9, phase = GoPhase.FINISHED, result = GameResult.SECOND_PLAYER_WIN,
            sessionReady = true, loading = false, recordAvailable = true),
        eventCount = 4, matchId = "ui-fixture-only", completionReserved = completionReserved,
        completionStored = completionStored, completionPending = completionPending,
        immediateHumanCompletion = immediateHumanCompletion)

    private class Callbacks {
        var retries = 0
        var restarts = 0
        var undos = 0
        var imports = 0
        var autoRetries = 0
    }
}
