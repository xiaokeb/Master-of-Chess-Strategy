package com.masterofchessstrategy.games.go

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.MainActivity
import com.masterofchessstrategy.engine.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Native-backed viewer in the production landscape host; no record/reward repository. */
class GoReplayScreenInstrumentedTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun threeSizesShowFixedSetupReadOnlyStepsAndPlayback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("go-learning-v1", Context.MODE_PRIVATE)
        val before = preferences.all.toMap()
        val directory = pictures(context)
        for (size in listOf(9, 13, 19)) {
            val bytes = NativeGoEngine(size, 0, 5).use { native ->
                accepted(native, GoAction.Place(BoardPosition(0, 0)))
                accepted(native, GoAction.Place(BoardPosition(size - 1, size - 1)))
                accepted(native, GoAction.Pass)
                native.serialize()
            }
            show(GoReplaySource.Snapshot(bytes))
            waitCursor("行棋 0 / 3 着 · 事件 0 / 3")
            composeRule.onNodeWithText("${size} 路 · 让 5 子").assertExists()
            composeRule.onNodeWithTag("go_intersection_0_0").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
            capture(directory, "$size-setup")
            composeRule.onNodeWithText("下一步").performScrollTo().performClick()
            waitCursor("行棋 1 / 3 着 · 事件 1 / 3")
            composeRule.onNodeWithTag("go_replay_event").assertTextEquals("落子：第1行第1列")
            composeRule.onNodeWithText("末步").performScrollTo().performClick()
            waitCursor("行棋 3 / 3 着 · 事件 3 / 3")
            composeRule.onNodeWithTag("go_replay_event").assertTextEquals("虚着")
            capture(directory, "$size-end")
            composeRule.onNodeWithText("首步").performScrollTo().performClick()
            waitCursor("行棋 0 / 3 着 · 事件 0 / 3")
            composeRule.onNodeWithTag("go_replay_play").performScrollTo().performClick()
            waitCursor("行棋 3 / 3 着 · 事件 3 / 3")
            assertEquals(3, GoReplaySession.openSnapshot(bytes).use { it.frame().eventCount })
        }
        assertEquals(before, preferences.all)
    }

    @Test fun agreementEventsRetainFinalResultWhileSgfDoesNotInventIt() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = pictures(context)
        NativeGoEngine(9).use { native ->
            accepted(native, GoAction.Place(BoardPosition(0, 0)))
            accepted(native, GoAction.Place(BoardPosition(1, 0)))
            accepted(native, GoAction.Pass); accepted(native, GoAction.Pass)
            accepted(native, GoAction.ToggleDeadGroup(BoardPosition(1, 0), GoPlayer.BLACK))
            val revision = native.scoringState().proposalRevision
            accepted(native, GoAction.ConfirmScore(GoPlayer.BLACK, revision))
            accepted(native, GoAction.ConfirmScore(GoPlayer.WHITE, revision))
            val bytes = native.serialize()
            show(GoReplaySource.Snapshot(bytes))
            waitCursor("行棋 0 / 4 着 · 事件 0 / 7")
            composeRule.onNodeWithText("末步").performScrollTo().performClick()
            waitCursor("行棋 4 / 4 着 · 事件 7 / 7")
            composeRule.onNodeWithTag("go_replay_event").assertTextEquals("白方确认死子提议")
            val result = when (native.gameResult()) {
                GameResult.FIRST_PLAYER_WIN -> "正式终局：黑方获胜"
                GameResult.SECOND_PLAYER_WIN -> "正式终局：白方获胜"
                GameResult.DRAW -> "正式终局：和棋"
                else -> error("Native final result absent")
            }
            composeRule.onNodeWithText(result).assertExists()
            capture(directory, "snapshot-final")
            show(GoReplaySource.Sgf(native.exportSgf()))
            waitCursor("行棋 0 / 4 着 · 事件 0 / 4")
            composeRule.onNodeWithText("末步").performScrollTo().performClick()
            waitCursor("行棋 4 / 4 着 · 事件 4 / 4")
            composeRule.onNodeWithText("死子协商：黑方未确认，白方未确认").assertExists()
            composeRule.onNodeWithText(result).assertDoesNotExist()
            capture(directory, "sgf-not-final")
            assertArrayEquals(bytes, native.serialize())
        }
    }

    private fun show(source: GoReplaySource) {
        val key = UUID.randomUUID().toString()
        composeRule.activityRule.scenario.onActivity { activity ->
            activity.setContent { GoReplayRoute(key, source, {}) }
        }
    }

    private fun waitCursor(text: String) {
        composeRule.waitUntil(15_000) {
            composeRule.onAllNodes(hasText(text)).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("go_replay_cursor").assertTextEquals(text)
    }

    private fun accepted(engine: GoRecordEngine, action: GoAction) = assertEquals(ActionResult.Accepted, engine.apply(action))
    private fun pictures(context: Context) = File(context.getExternalFilesDir(null), "go-replay-qa/${UUID.randomUUID()}").also {
        assertTrue(it.mkdirs())
    }

    private fun capture(directory: File, name: String) {
        composeRule.waitUntil(5_000) { composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
        composeRule.waitForIdle()
        val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
        try {
            assertTrue("Production host must render landscape", bitmap.width > bitmap.height)
            File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
