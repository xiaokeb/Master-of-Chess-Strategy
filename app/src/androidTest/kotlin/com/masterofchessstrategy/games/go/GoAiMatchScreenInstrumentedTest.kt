package com.masterofchessstrategy.games.go

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.MainActivity
import com.masterofchessstrategy.engine.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Production AI UI/session, real Native/AtomicFile, scripted legal driver; no NN strength claim. */
class GoAiMatchScreenInstrumentedTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun specifiedOpeningUndoAndRestartReturnToTheSameTrainingEndpoint() {
        val opening = NativeGoEngine(9).use {
            assertEquals(ActionResult.Accepted, it.apply(GoAction.Place(BoardPosition(0, 0))))
            assertEquals(ActionResult.Accepted, it.apply(GoAction.Place(BoardPosition(1, 0))))
            GoAiOpening.fromSnapshot(it.serialize())
        }
        withMatch(GoAiMatchConfig(9, difficulty = GoAiDifficulty.HARD), passOnly = false, opening = opening) { repository, pictures ->
            waitPhase("轮到你行棋")
            val initial = runBlocking { checkNotNull(repository.load()) }
            assertArrayEquals(opening.copyBytes(), initial.copyBytes()); assertArrayEquals(opening.copyBytes(), initial.copyOpeningBytes())
            composeRule.onNodeWithText("指定开局训练局 · 重开回到原开局 · 不计标准人机胜场").assertExists()
            composeRule.onNodeWithTag("go_ai_undo").performScrollTo().assertIsNotEnabled()
            composeRule.onNodeWithTag("go_intersection_0_0").assertContentDescriptionContains("黑子", substring = true)
            composeRule.onNodeWithTag("go_intersection_1_0").assertContentDescriptionContains("白子", substring = true)
            capture(pictures, "opening-start")
            composeRule.onNodeWithTag("go_intersection_2_0").performClick()
            waitUntilRecord(repository) { it.eventHistory().size == 4 }
            composeRule.onNodeWithTag("go_ai_undo").performScrollTo().performClick()
            waitUntilRecord(repository) { it.eventHistory().size == 2 }
            composeRule.onNodeWithTag("go_ai_undo").assertIsNotEnabled()
            assertArrayEquals(opening.copyBytes(), runBlocking { checkNotNull(repository.load()).copyBytes() })
            capture(pictures, "opening-undo-boundary")
            composeRule.onNodeWithTag("go_ai_restart").performScrollTo().performClick()
            composeRule.onNodeWithText("当前未完成对局将被替换并回到同一指定开局。执色和难度保持，悔棋与提示次数重新计数。")
                .assertExists()
            composeRule.onNodeWithTag("go_ai_restart_confirm").performClick()
            composeRule.waitUntil(15_000) { runBlocking { repository.load()?.matchId != initial.matchId } }
            waitPhase("轮到你行棋")
            val fresh = runBlocking { checkNotNull(repository.load()) }
            assertArrayEquals(opening.copyBytes(), fresh.copyBytes()); assertArrayEquals(opening.copyBytes(), fresh.copyOpeningBytes())
            composeRule.onNodeWithTag("go_ai_undo").performScrollTo().assertIsNotEnabled()
            capture(pictures, "opening-restarted")
        }
    }

    @Test fun openingPreviewCancelAndConfirmAreExplicitAndShowCanonicalEndpoint() {
        val opening = NativeGoEngine(9).use {
            assertEquals(ActionResult.Accepted, it.apply(GoAction.Place(BoardPosition(0, 0))))
            assertEquals(ActionResult.Accepted, it.apply(GoAction.Place(BoardPosition(1, 0))))
            GoAiOpening.fromSnapshot(it.serialize())
        }
        var confirmed = 0
        val game = GoGameUiState(boardSize = 9, stones = List(81) { 0 }, sessionReady = true,
            loading = false, recordAvailable = true)
        val page = GoAiMatchPageState(loadingModel = false,
            match = GoAiMatchUiState(GoAiMatchConfig(9), game))
        composeRule.activityRule.scenario.onActivity { activity -> activity.setContent {
            var pending by remember { mutableStateOf<GoAiOpening?>(null) }
            GoAiMatchScreen(page, GoPlayer.BLACK, GoScoreDisplayMode.STONES, {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                onHint = {}, onRestart = {}, onAutomatic = {}, onAutoContinuation = { _, _ -> },
                onRetryAutoContinuation = {}, openingMessage = null, pendingOpening = pending,
                onImportOpening = { pending = opening }, onDismissOpening = { pending = null },
                onConfirmOpening = { confirmed++; pending = null })
        } }

        composeRule.onNodeWithTag("go_ai_import_opening").performScrollTo().performClick()
        composeRule.onNodeWithTag("go_ai_opening_dialog").assertExists()
        composeRule.onNodeWithText("9路 · 不让子 · 2个历史事件 · 黑方行棋").assertExists()
        composeRule.onNodeWithText("确认后才替换当前活动对局，并生成新的对局身份。重开将回到这个完整历史端点；本局按训练来源保存，不计标准人机胜场或难度解锁。")
            .assertExists()
        assertEquals(0, confirmed)
        composeRule.onNodeWithTag("go_ai_opening_cancel").performClick()
        composeRule.onNodeWithTag("go_ai_opening_dialog").assertDoesNotExist(); assertEquals(0, confirmed)

        composeRule.onNodeWithTag("go_ai_import_opening").performScrollTo().performClick()
        composeRule.onNodeWithTag("go_ai_opening_confirm").performClick()
        composeRule.onNodeWithTag("go_ai_opening_dialog").assertDoesNotExist(); assertEquals(1, confirmed)
    }

    @Test fun automaticContinuationControlsExposeDurableProgressLimitsAndCallbacks() {
        val config = GoAiMatchConfig(9, humanPlayer = null)
        val playing = GoGameUiState(boardSize = 9, stones = List(81) { 0 }, sessionReady = true,
            loading = false, recordAvailable = true)
        val page = mutableStateOf(GoAiMatchPageState(loadingModel = false, completedStorageReady = true,
            match = GoAiMatchUiState(config, playing, autoContinueEnabled = true,
                autoContinueMaxGames = 5, autoContinueGameNumber = 2, matchId = "series")))
        val changes = mutableListOf<Pair<Boolean, Int>>(); var retries = 0
        composeRule.activityRule.scenario.onActivity { activity -> activity.setContent {
            GoAiMatchScreen(page.value, GoPlayer.BLACK, GoScoreDisplayMode.STONES,
                onAction = {}, onActor = {}, onDisplayMode = {}, onUndo = {}, onPause = {}, onResume = {},
                onSpeed = {}, onRetryModel = {}, onRetrySearch = {}, onBack = {}, onHint = {}, onRestart = {},
                onAutomatic = {}, onAutoContinuation = { enabled, max -> changes += enabled to max },
                onRetryAutoContinuation = { retries++ }, openingMessage = null, pendingOpening = null,
                onImportOpening = {}, onDismissOpening = {}, onConfirmOpening = {})
        } }
        composeRule.onNodeWithTag("go_ai_auto_continue_progress").performScrollTo().assertTextEquals("连续局 2 / 5")
        val limit = composeRule.onNodeWithText("最多10局")
        println("Auto limit before scroll: ${limit.fetchSemanticsNode().boundsInRoot}")
        limit.performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        composeRule.runOnIdle { assertEquals(listOf(true to 10), changes) }
        composeRule.onNodeWithTag("go_ai_auto_continue").performScrollTo().assertIsDisplayed().performClick()
        assertEquals(listOf(true to 10, false to 1), changes); assertEquals(0, retries)

        val finished = playing.copy(phase = GoPhase.FINISHED, result = GameResult.FIRST_PLAYER_WIN)
        composeRule.runOnIdle { page.value = page.value.copy(match = page.value.match!!.copy(
            game = finished, autoContinueGameNumber = 5)) }
        composeRule.onNodeWithTag("go_ai_auto_continue_limit").performScrollTo().assertTextEquals("已达到本次连续开局上限。")
        composeRule.onNodeWithTag("go_ai_auto_continue_retry").assertDoesNotExist()

        composeRule.runOnIdle { page.value = page.value.copy(match = page.value.match!!.copy(
            game = finished.copy(error = "完成棋谱保存未完成"))) }
        composeRule.onNodeWithTag("go_ai_auto_continue_retry").performScrollTo().assertTextEquals("重试保存最后一局")
        composeRule.onNodeWithTag("go_ai_auto_continue_retry").performClick()
        assertEquals(1, retries)
    }

    @Test fun modeSwitchKeepsBoardAndWhitePreferencePausedWithPersistentParticipation() {
        withMatch(GoAiMatchConfig(13, 5, GoPlayer.WHITE, GoAiDifficulty.HARD), passOnly = false) { repository, pictures ->
            waitPhase("轮到你行棋")
            val before = runBlocking { checkNotNull(repository.load()) }
            assertEquals(5, before.config.handicapStones)
            capture(pictures, "mode-human-white")
            composeRule.onNodeWithTag("go_ai_mode").performScrollTo().performClick()
            waitPhase("AI 已暂停")
            val automatic = runBlocking { checkNotNull(repository.load()) }
            assertEquals(before.matchId, automatic.matchId); assertArrayEquals(before.copyBytes(), automatic.copyBytes())
            assertTrue(automatic.config.automatic); assertTrue(automatic.automaticUsed)
            assertEquals(GoPlayer.WHITE, automatic.rememberedHuman)
            composeRule.onNodeWithTag("go_ai_mode").assertTextEquals("返回人机（你执白）")
            composeRule.onNodeWithTag("go_ai_hint").assertDoesNotExist()
            composeRule.onNodeWithTag("go_ai_pass").assertIsNotEnabled()
            composeRule.onNodeWithText("本局使用过自动控制，不计人机胜场。").assertExists()
            capture(pictures, "mode-auto-paused")
            composeRule.onNodeWithTag("go_ai_mode").performScrollTo().performClick()
            waitPhase("轮到你行棋")
            val returned = runBlocking { checkNotNull(repository.load()) }
            assertEquals(before.matchId, returned.matchId); assertArrayEquals(before.copyBytes(), returned.copyBytes())
            assertEquals(before.config, returned.config); assertTrue(returned.automaticUsed)
            assertEquals(before.undoUsed, returned.undoUsed); assertEquals(before.hintUsed, returned.hintUsed)
            composeRule.onNodeWithTag("go_ai_mode").assertTextEquals("自动接管双方")
            composeRule.onNodeWithTag("go_ai_hint").assertIsEnabled()
            composeRule.onNodeWithTag("go_ai_pause").assertTextEquals("继续思考")
            capture(pictures, "mode-human-returned")
        }
    }

    @Test fun restartConfirmationCancelPreservesRecordAndConfirmStartsFreshPausedMatch() {
        withMatch(GoAiMatchConfig(9, difficulty = GoAiDifficulty.HARD), passOnly = false) { repository, pictures ->
            waitPhase("轮到你行棋")
            composeRule.onNodeWithTag("go_intersection_0_0").performClick()
            waitUntilRecord(repository) { it.eventHistory().size == 2 }
            waitPhase("轮到你行棋")
            val old = runBlocking { checkNotNull(repository.load()) }
            composeRule.onNodeWithTag("go_ai_restart").performScrollTo().performClick()
            composeRule.onNodeWithTag("go_ai_restart_dialog").assertExists()
            composeRule.waitForIdle()
            // Dialog is a separate Android window: capture the actual display, not one Compose root.
            val screenshot = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            try {
                assertTrue(screenshot.width > screenshot.height)
                File(pictures, "restart-dialog.png").outputStream().use { assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally { screenshot.recycle() }
            composeRule.onNodeWithTag("go_ai_restart_cancel").performClick()
            assertEquals(old.matchId, runBlocking { checkNotNull(repository.load()).matchId })
            assertArrayEquals(old.copyBytes(), runBlocking { checkNotNull(repository.load()).copyBytes() })
            capture(pictures, "restart-cancelled")
            composeRule.onNodeWithTag("go_ai_restart").performScrollTo().performClick()
            composeRule.onNodeWithTag("go_ai_restart_confirm").performClick()
            composeRule.waitUntil(15_000) { runBlocking { repository.load()?.matchId != old.matchId } }
            waitPhase("轮到你行棋")
            val fresh = runBlocking { checkNotNull(repository.load()) }
            assertEquals(old.config, fresh.config)
            assertEquals(0, fresh.undoUsed); assertEquals(0, fresh.hintUsed)
            assertEquals(0, runBlocking { nativeEventCount(repository) })
            composeRule.onNodeWithTag("go_intersection_0_0").assertContentDescriptionContains("空点", substring = true)
            composeRule.onNodeWithTag("go_ai_undo").performScrollTo().assertIsNotEnabled()
            capture(pictures, "restart-fresh")
        }
    }

    @Test fun hintHighlightsWithoutMovingAndKeepsIndependentQuotaAfterPlayAndUndo() {
        withMatch(GoAiMatchConfig(9, difficulty = GoAiDifficulty.HARD), passOnly = false) { repository, pictures ->
            waitPhase("轮到你行棋")
            val before = runBlocking { checkNotNull(repository.load()).copyBytes() }
            composeRule.onNodeWithTag("go_ai_hint").performScrollTo().performClick()
            composeRule.waitUntil(15_000) { runBlocking { repository.load()?.hintUsed == 1 } &&
                composeRule.onAllNodes(hasTestTag("go_ai_hint_result")).fetchSemanticsNodes().isNotEmpty() }
            composeRule.onNodeWithTag("go_ai_hint_result").assertTextEquals("走子建议：第1行第1列。")
            composeRule.onNodeWithTag("go_intersection_0_0").assertContentDescriptionContains("高亮点", substring = true)
            assertArrayEquals(before, runBlocking { checkNotNull(repository.load()).copyBytes() })
            assertEquals(0, runBlocking { nativeEventCount(repository) })
            composeRule.onNodeWithTag("go_ai_hint").assertIsNotEnabled()
            capture(pictures, "hint-only")
            composeRule.onNodeWithTag("go_intersection_0_0").performClick()
            waitUntilRecord(repository) { it.eventHistory().size == 2 }
            waitPhase("轮到你行棋")
            composeRule.onNodeWithTag("go_ai_hint_result").assertDoesNotExist()
            composeRule.onNodeWithTag("go_ai_hint").performScrollTo().assertIsNotEnabled()
            composeRule.onNodeWithTag("go_ai_undo").assertIsEnabled()
            capture(pictures, "hint-after-move")
            composeRule.onNodeWithTag("go_ai_undo").performScrollTo().performClick()
            waitUntilRecord(repository) { it.eventHistory().isEmpty() }
            waitPhase("轮到你行棋")
            val saved = runBlocking { checkNotNull(repository.load()) }
            assertEquals(1, saved.hintUsed)
            assertEquals(1, saved.undoUsed)
            composeRule.onNodeWithTag("go_ai_hint").performScrollTo().assertIsNotEnabled()
            composeRule.onNodeWithText("剩余提示：0次").assertExists()
            capture(pictures, "hint-after-undo")
        }
    }

    @Test fun humanMoveAiResponseUndoAndRuntimeCleanupRenderLandscape() {
        withMatch(GoAiMatchConfig(9, difficulty = GoAiDifficulty.HARD), passOnly = false) { repository, pictures ->
            waitPhase("轮到你行棋")
            capture(pictures, "human-start")
            composeRule.onNodeWithTag("go_intersection_0_0").performClick()
            waitUntilRecord(repository) { it.eventHistory().size == 2 }
            waitPhase("轮到你行棋")
            composeRule.onNodeWithTag("go_intersection_1_0").assertContentDescriptionContains("白子", substring = true)
            capture(pictures, "ai-response")
            composeRule.onNodeWithTag("go_ai_undo").performScrollTo().performClick()
            waitUntilRecord(repository) { it.eventHistory().isEmpty() }
            waitPhase("轮到你行棋")
            composeRule.onNodeWithTag("go_ai_undo").assertIsNotEnabled()
            capture(pictures, "undo-used")
            assertEquals(1, runBlocking { checkNotNull(repository.load()).undoUsed })
        }
    }

    @Test fun autoRemainsPausedThenDoublePassRequiresBothConfirmations() {
        withMatch(GoAiMatchConfig(13, humanPlayer = null), passOnly = true) { repository, pictures ->
            waitPhase("AI 已暂停")
            assertEquals(0, runBlocking { nativeEventCount(repository) })
            capture(pictures, "auto-paused")
            composeRule.onNodeWithTag("go_ai_pause").performScrollTo().performClick()
            waitPhase("等待双方复核死子与数子")
            capture(pictures, "auto-review")
            assertEquals(GameResult.ONGOING, runBlocking { nativeResult(repository) })
            composeRule.onNodeWithText("黑方确认").performScrollTo().performClick()
            waitUntilRecord(repository) { it.scoringState().blackConfirmed }
            assertEquals(GameResult.ONGOING, runBlocking { nativeResult(repository) })
            composeRule.onNodeWithText("白方确认").performScrollTo().performClick()
            waitPhase("白方获胜")
            assertEquals(GameResult.SECOND_PLAYER_WIN, runBlocking { nativeResult(repository) })
            capture(pictures, "auto-final")
        }
    }

    private fun withMatch(config: GoAiMatchConfig, passOnly: Boolean, opening: GoAiOpening? = null,
        block: (GoAiMatchFileRepository, File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "go-ai-page-qa-${UUID.randomUUID()}")
        val pictures = File(context.getExternalFilesDir(null), "go-ai-page-qa/${UUID.randomUUID()}")
        assertTrue(directory.mkdirs()); assertTrue(pictures.mkdirs())
        val file = File(directory, "match.bin")
        val repository = GoAiMatchFileRepository(file)
        val store = ViewModelStore()
        val storeOwner = object : ViewModelStoreOwner { override val viewModelStore = store }
        val driver = ScriptedOwner(passOnly)
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.setContent { CompositionLocalProvider(LocalViewModelStoreOwner provides storeOwner) {
                GoAiMatchRoute("qa", config, { driver }, {}, repository = repository, opening = opening)
            } }
        }
        try { block(repository, pictures) } finally {
            composeRule.activityRule.scenario.onActivity { store.clear() }
            runBlocking { withTimeout(15_000) { driver.closed.await() } }
            assertFalse(driver.running)
            for (owned in listOf(file, File(directory, "match.bin.new"), File(directory, "match.bin.bak"))) {
                if (owned.exists()) assertTrue(owned.delete())
            }
            assertTrue(directory.delete())
        }
    }

    private fun waitPhase(text: String) {
        composeRule.waitUntil(15_000) { composeRule.onAllNodes(hasText(text) and hasTestTag("go_ai_phase")).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("go_ai_phase").assertTextEquals(text)
    }

    private fun waitUntilRecord(repository: GoAiMatchRepository, predicate: (GoRecordEngine) -> Boolean) {
        composeRule.waitUntil(15_000) { runBlocking {
            repository.load()?.let { saved -> NativeGoEngine(saved.config.boardSize).use {
                assertEquals(RestoreResult.Restored, it.restore(saved.copyBytes()))
                predicate(it)
            } } ?: false
        } }
    }
    private suspend fun nativeEventCount(repository: GoAiMatchRepository): Int {
        val saved = checkNotNull(repository.load())
        return NativeGoEngine(saved.config.boardSize).use { assertEquals(RestoreResult.Restored, it.restore(saved.copyBytes())); it.eventHistory().size }
    }
    private suspend fun nativeResult(repository: GoAiMatchRepository): GameResult {
        val saved = checkNotNull(repository.load())
        return NativeGoEngine(saved.config.boardSize).use { assertEquals(RestoreResult.Restored, it.restore(saved.copyBytes())); it.gameResult() }
    }
    private fun capture(directory: File, name: String) {
        composeRule.waitForIdle()
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        try {
            assertTrue("Production Activity must render landscape", bitmap.width > bitmap.height)
            File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }

    private class ScriptedOwner(private val passOnly: Boolean) : GoAiMatchEngineOwner {
        val closed = CompletableDeferred<Unit>()
        @Volatile var running = false
        override suspend fun search(request: GoAiSearchRequest): GoAiSearchOutcome {
            running = true
            try {
                for (size in listOf(9, 13, 19)) NativeGoEngine(size).use { verifier ->
                    if (verifier.restore(request.position.copyBytes()) == RestoreResult.Restored) {
                        val action = if (passOnly) GoAction.Pass else verifier.legalActions().first { it is GoAction.Place }
                        return if (request.cancellation.isCancelled) GoAiSearchOutcome.Cancelled
                            else GoAiSearchOutcome.Move(GoAiSuggestion(action, 1, 1.0, 0.5, 0.0))
                    }
                }
                error("Invalid scripted search source")
            } finally { running = false }
        }
        override suspend fun closeAndJoin() { check(!running); closed.complete(Unit) }
    }
}
