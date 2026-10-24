package com.masterofchessstrategy.games.go

import android.graphics.Bitmap
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.GoPhase
import com.masterofchessstrategy.engine.GoPlayer
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Stateless presentation/callback checks, not Room admission or production navigation evidence. */
class GoAiSetupScreenInstrumentedTest {
    @get:Rule val compose = createComposeRule()

    @Test fun boardColorAutomaticHandicapAndDifficultyBuildTheSelectedNewConfig() {
        val callbacks = Callbacks()
        show(ready(), callbacks)
        for (size in listOf(9, 13, 19)) {
            compose.onNodeWithTag("go_ai_setup_size_$size").performScrollTo().performClick()
            compose.onNodeWithTag("go_ai_setup_create").performScrollTo().performClick()
        }
        compose.runOnIdle {
            assertEquals(listOf(9, 13, 19), callbacks.created.map { it.boardSize })
            assertTrue(callbacks.created.all { it.humanPlayer == GoPlayer.BLACK && it.handicapStones == 0 })
        }
        compose.onNodeWithTag("go_ai_setup_size_13").performScrollTo().performClick()
        compose.onNodeWithTag("go_ai_setup_color_1").performScrollTo().performClick()
        compose.onNodeWithTag("go_ai_setup_difficulty_2").performScrollTo().performClick()
        compose.onNodeWithText("让5子").performScrollTo().performClick()
        compose.onNodeWithText("让子局白方先行、不贴子；不计标准人机胜场。")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("go_ai_setup_create").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(GoAiMatchConfig(13, 5, GoPlayer.WHITE, GoAiDifficulty.HARD), callbacks.created.last()) }

        compose.onNodeWithTag("go_ai_setup_size_19").performScrollTo().performClick()
        compose.onNodeWithTag("go_ai_setup_color_2").performScrollTo().performClick()
        compose.onNodeWithTag("go_ai_setup_difficulty_3").performScrollTo().performClick()
        compose.onNodeWithText("不让子").performScrollTo().performClick()
        compose.onNodeWithTag("go_ai_setup_create").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(GoAiMatchConfig(19, 0, null, GoAiDifficulty.MASTER), callbacks.created.last()) }
    }

    @Test fun nineLessonsKeepEveryDifficultyLockedButLearningAndBackStillWork() {
        val callbacks = Callbacks()
        show(ready(eligibility = eligibilitySnapshot(lessons = 9)), callbacks)
        compose.onNodeWithText("教程 9 / 10 课；解锁仅代表进度条件，不代表棋力已标定。")
            .performScrollTo().assertIsDisplayed()
        for (index in 0..3) compose.onNodeWithTag("go_ai_setup_difficulty_$index").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("go_ai_setup_create").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("前往十课教程").performScrollTo().performClick()
        compose.onNodeWithTag("go_ai_setup_back").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, callbacks.learning)
            assertEquals(1, callbacks.back)
            assertTrue(callbacks.created.isEmpty())
        }
    }

    @Test fun refreshedWinFactsUnlockOnlyTheMatchingDifficultyConditions() {
        val callbacks = Callbacks()
        val state = mutableStateOf(ready(eligibility = eligibilitySnapshot(wins = emptyMap())))
        showState(state, callbacks)
        compose.onNodeWithTag("go_ai_setup_difficulty_0").performScrollTo().assertIsEnabled()
        for (index in 1..3) compose.onNodeWithTag("go_ai_setup_difficulty_$index").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("中等：上一档有效胜场 0 / 10").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { state.value = ready(eligibility = eligibilitySnapshot(wins = mapOf(Difficulty.EASY to 10))) }
        compose.onNodeWithTag("go_ai_setup_difficulty_1").performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("go_ai_setup_difficulty_2").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("go_ai_setup_create").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(GoAiDifficulty.MEDIUM, callbacks.created.single().difficulty) }
    }

    @Test fun savingBusyDisablesRepeatedCreateContinueAndSelectionCallbacks() {
        val callbacks = Callbacks()
        val state = mutableStateOf(ready(active = saved()))
        showState(state, callbacks, create = {
            callbacks.created += it
            state.value = state.value.copy(busy = true)
        })
        compose.onNodeWithTag("go_ai_setup_create").performScrollTo().performClick()
        compose.onNodeWithTag("go_ai_setup_saving").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("go_ai_setup_continue").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("go_ai_setup_size_9").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("go_ai_setup_color_2").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("go_ai_setup_difficulty_0").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("前往十课教程").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("go_ai_setup_back").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("go_ai_setup_create").performScrollTo().assertIsNotEnabled().performClick()
        compose.runOnIdle { assertEquals(1, callbacks.created.size); assertEquals(0, callbacks.continued) }
    }

    @Test fun changingNewSelectionsNeverChangesTheCurrentResumeSummary() {
        val callbacks = Callbacks()
        val current = saved(GoAiMatchConfig(13, humanPlayer = GoPlayer.WHITE, difficulty = GoAiDifficulty.HARD))
        show(ready(active = current), callbacks)
        compose.onNodeWithTag("go_ai_setup_size_9").performScrollTo().performClick()
        compose.onNodeWithTag("go_ai_setup_color_2").performScrollTo().performClick()
        compose.onNodeWithTag("go_ai_setup_continue").performScrollTo().assertTextEquals("继续当前对局").performClick()
        compose.onNodeWithText("当前对局：13路 · 困难 · 你执白").performScrollTo().assertIsDisplayed()
        compose.runOnIdle {
            assertEquals(1, callbacks.continued)
            assertTrue(callbacks.created.isEmpty())
            assertEquals(GoAiMatchConfig(13, humanPlayer = GoPlayer.WHITE, difficulty = GoAiDifficulty.HARD), current.config)
        }
    }

    @Test fun finishedUnstoredResultMustBeViewedBeforeAnyNewGame() {
        val callbacks = Callbacks()
        show(ready(active = saved(), phase = GoPhase.FINISHED), callbacks)
        compose.onNodeWithText("上局结果待保存；请先查看并保存，不能通过新建跳过。")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("go_ai_setup_create").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag("go_ai_setup_continue").performScrollTo().assertTextEquals("查看并保存结果").performClick()
        compose.runOnIdle { assertEquals(1, callbacks.continued); assertTrue(callbacks.created.isEmpty()) }
    }

    @Test fun abandonDialogCancelAndConfirmAreDifferentExplicitCallbacks() {
        val callbacks = Callbacks()
        val pending = GoAiMatchConfig(9, humanPlayer = GoPlayer.WHITE)
        val state = mutableStateOf(ready(active = saved()).copy(pendingConfig = pending))
        showState(state, callbacks)
        compose.onNodeWithText("放弃当前未完成对局？").assertIsDisplayed()
        compose.onNodeWithText("当前行棋或数子进度将被替换为9路新局；已完成棋谱不会删除。只有新局保存成功才替换，失败保留旧局。")
            .assertExists()
        compose.onNodeWithText("保留当前对局").performClick()
        compose.runOnIdle {
            assertEquals(1, callbacks.dismissed)
            assertEquals(0, callbacks.confirmed)
            assertTrue(callbacks.created.isEmpty())
            state.value = state.value.copy(pendingConfig = pending)
        }
        compose.onNodeWithTag("go_ai_setup_abandon_confirm").performClick()
        compose.runOnIdle { assertEquals(1, callbacks.confirmed); assertEquals(1, callbacks.dismissed) }
    }

    @Test fun failedReadOffersRetryWithoutInventingAnActiveMatch() {
        val callbacks = Callbacks()
        show(GoAiSetupState(loading = false, error = "读取失败，原局保留"), callbacks)
        compose.onNodeWithText("读取失败，原局保留").assertExists()
        compose.onNodeWithTag("go_ai_setup_retry").performScrollTo().performClick()
        compose.onNodeWithTag("go_ai_setup_continue").assertDoesNotExist()
        compose.onNodeWithTag("go_ai_setup_create").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, callbacks.retried); assertTrue(callbacks.created.isEmpty()) }
    }

    @Test fun largeFontKeepsSummaryAndBottomActionsScrollableAndCapturesCurrentViewport() {
        show(ready(active = saved()), fontScale = 1.5f)
        compose.onNodeWithTag("go_ai_setup_continue").performScrollTo().assertIsDisplayed()
        capture("large-font-current")
        compose.onNodeWithText("前往十课教程").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("go_ai_setup_create").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("go_ai_setup_back").performScrollTo().assertIsDisplayed()
        capture("large-font-actions")
    }

    private fun show(initial: GoAiSetupState, callbacks: Callbacks = Callbacks(), fontScale: Float = 1f) =
        showState(mutableStateOf(initial), callbacks, fontScale)

    private fun showState(state: MutableState<GoAiSetupState>, callbacks: Callbacks, fontScale: Float = 1f,
        create: (GoAiMatchConfig) -> Unit = { callbacks.created += it }) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                MaterialTheme {
                    GoAiSetupScreen(state.value, onCreate = create, onContinue = { callbacks.continued++ },
                        onConfirmAbandon = { callbacks.confirmed++; state.value = state.value.copy(pendingConfig = null) },
                        onDismissAbandon = { callbacks.dismissed++; state.value = state.value.copy(pendingConfig = null) },
                        onRetry = { callbacks.retried++ }, onLearning = { callbacks.learning++ }, onBack = { callbacks.back++ })
                }
            }
        }
    }

    private fun ready(active: GoAiSavedMatch? = null, phase: GoPhase? = active?.let { GoPhase.PLAYING },
        eligibility: GoDifficultyEligibility = eligibilitySnapshot()) = GoAiSetupState(loading = false,
        snapshot = GoAiEntrySnapshot(active, phase, false, eligibility))

    private fun eligibilitySnapshot(lessons: Int = 10,
        wins: Map<Difficulty, Int> = mapOf(Difficulty.EASY to 10, Difficulty.MEDIUM to 15, Difficulty.HARD to 20)) =
        GoDifficultyEligibility.from(GoLearningProgress(GoLearningLibrary.lessons.take(lessons).map { it.id }.toSet()),
            mapOf(GameType.GO to wins))

    // Deliberately non-native bytes: this test only renders an already-supplied immutable summary.
    private fun saved(config: GoAiMatchConfig = GoAiMatchConfig(19)) =
        GoAiSavedMatch("setup-ui-only", config, byteArrayOf(1))

    private fun capture(name: String) {
        compose.waitForIdle()
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.noBackupFilesDir,
            "go-ai-setup-screen-qa/$captureId")
        assertTrue(directory.isDirectory || directory.mkdirs())
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            assertTrue(bitmap.width > 0 && bitmap.height > 0)
            File(directory, "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    private val captureId = UUID.randomUUID().toString()
    private class Callbacks {
        val created = mutableListOf<GoAiMatchConfig>()
        var continued = 0
        var confirmed = 0
        var dismissed = 0
        var retried = 0
        var learning = 0
        var back = 0
    }
}
