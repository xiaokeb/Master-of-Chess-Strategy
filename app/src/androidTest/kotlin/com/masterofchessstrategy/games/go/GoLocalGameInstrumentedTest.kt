package com.masterofchessstrategy.games.go

import android.graphics.Bitmap
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GoPhase
import com.masterofchessstrategy.engine.NativeGoEngine
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Runs the production route, JNI rules and AtomicFile repository on an isolated session. */
class GoLocalGameInstrumentedTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val runId = UUID.randomUUID().toString()
    private val target = File(context.cacheDir, "go-local-$runId.bin")
    private val repository = GoSessionFileRepository(target)

    @Before fun useLandscapeHost() {
        composeRule.activityRule.scenario.onActivity {
            it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        composeRule.waitUntil(10_000) {
            composeRule.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        }
    }

    @After fun tearDown() = runBlocking { repository.clear() }

    @Test fun playAgreeRestoreAndUndoThroughTheProductionRoute() {
        runBlocking {
            NativeGoEngine(9).use { engine ->
                repository.save(GoSavedSession(9, engine.serialize(), GoScoreDisplayMode.STONES))
            }
        }
        composeRule.setContent { MaterialTheme { GoGameRoute(onBack = {}, repository = repository) } }
        awaitPhase("黑方行棋")
        composeRule.onNodeWithTag("go_intersection_0_0").performClick()
        awaitPhase("白方行棋")
        composeRule.onNodeWithTag("go_intersection_8_8").performClick()
        awaitPhase("黑方行棋")
        composeRule.onNodeWithText("虚着").performScrollTo().performClick()
        awaitPhase("白方行棋")
        composeRule.onNodeWithText("虚着").performScrollTo().performClick()
        awaitPhase("双方确认死子与数子")
        saveVisual("agreement")
        composeRule.onNodeWithText("黑方确认").performScrollTo().performClick()
        awaitSaved()
        composeRule.onNodeWithText("白方确认").performScrollTo().performClick()
        awaitPhase("白方获胜")
        saveVisual("final")
        val saved = runBlocking { repository.load()!! }
        NativeGoEngine(9).use { restored ->
            assertEquals(com.masterofchessstrategy.engine.RestoreResult.Restored,
                restored.restore(saved.engineState))
            assertEquals(GoPhase.FINISHED, restored.phase)
            assertEquals(GameResult.SECOND_PLAYER_WIN, restored.gameResult())
        }
        composeRule.onNodeWithText("悔棋").performScrollTo().performClick()
        awaitPhase("双方确认死子与数子")
        val pending = runBlocking { repository.load()!! }
        NativeGoEngine(9).use { restored ->
            assertEquals(com.masterofchessstrategy.engine.RestoreResult.Restored,
                restored.restore(pending.engineState))
            assertEquals(GoPhase.SCORING, restored.phase)
            assertTrue(restored.scoringState().blackConfirmed)
            assertEquals(GameResult.ONGOING, restored.gameResult())
        }
    }

    @Test fun thirteenLineHandicapStartsWhiteAndPersistsTheSetup() {
        composeRule.setContent { MaterialTheme { GoGameRoute(onBack = {}, repository = repository) } }
        awaitPhase("黑方行棋")
        composeRule.onNodeWithText("新对局").performScrollTo().performClick()
        composeRule.onNodeWithText("13路").performClick()
        composeRule.onNodeWithText("5 子").performScrollTo().performClick()
        composeRule.onNodeWithText("开始").performClick()
        awaitPhase("白方行棋")
        saveVisual("13-handicap")
        val saved = runBlocking { repository.load()!! }
        assertEquals(13, saved.boardSize)
        NativeGoEngine(13).use { restored ->
            assertEquals(com.masterofchessstrategy.engine.RestoreResult.Restored,
                restored.restore(saved.engineState))
            assertEquals(5, restored.handicapStones)
            assertEquals(0, restored.blackKomiQuarters)
            assertEquals(1, restored.currentPlayer.value)
            assertEquals(com.masterofchessstrategy.engine.GoStone.BLACK,
                restored.stoneAt(com.masterofchessstrategy.engine.BoardPosition(6, 6)))
        }
    }

    @Test fun nineteenLineCornerMoveAndLastMarkerPersist() {
        composeRule.setContent { MaterialTheme { GoGameRoute(onBack = {}, repository = repository) } }
        awaitPhase("黑方行棋")
        composeRule.onNodeWithTag("go_intersection_18_18").performClick()
        awaitPhase("白方行棋")
        val saved = runBlocking { repository.load()!! }
        assertEquals(19, saved.boardSize)
        assertEquals(360, saved.lastMoveIndex)
        saveVisual("19-corner")
    }

    private fun awaitPhase(text: String) {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() &&
                composeRule.onAllNodesWithText("正在保存对局…").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun awaitSaved() {
        composeRule.waitUntil(10_000) {
            composeRule.onAllNodesWithText("正在保存对局…").fetchSemanticsNodes().isEmpty()
        }
    }

    private fun saveVisual(name: String) {
        composeRule.onNodeWithTag("go_phase").performScrollTo()
        val directory = File(checkNotNull(context.getExternalFilesDir("go-qa")), runId)
        assertTrue(directory.mkdirs() || directory.isDirectory)
        val output = File(directory, "$name.png")
        output.outputStream().use {
            assertTrue(composeRule.onRoot().captureToImage().asAndroidBitmap()
                .compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        println("GO_VISUAL=${output.absolutePath}")
    }
}
