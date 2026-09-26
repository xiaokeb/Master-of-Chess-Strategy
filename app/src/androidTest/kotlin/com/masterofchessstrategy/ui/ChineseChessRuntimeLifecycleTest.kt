package com.masterofchessstrategy.ui

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.RoomGameSessionRepository
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.NativeChineseChessEngine
import com.masterofchessstrategy.engine.RestoreResult
import com.masterofchessstrategy.game.ChineseChessGameViewModel
import com.masterofchessstrategy.game.PikafishNetworkProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Process-alive lifecycle checks only; this test does not prove Doze/foreground-service support. */
@RunWith(AndroidJUnit4::class)
class ChineseChessRuntimeLifecycleTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var database: MocsDatabase
    private lateinit var game: ChineseChessGameViewModel

    @Before fun prepare() {
        instrumentation.runOnMainSync {
            composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        composeRule.waitForIdle()
        val context = instrumentation.targetContext
        database = Room.inMemoryDatabaseBuilder(context, MocsDatabase::class.java).build()
        val provider = PikafishNetworkProvider(context)
        instrumentation.runOnMainSync {
            game = ViewModelProvider(composeRule.activity, ChineseChessGameViewModel.factory(
                repository = RoomGameSessionRepository(database.activeGameDao()),
                mode = StoredGameMode.AI_AUTO_PLAY, difficulty = Difficulty.EASY,
                timeControlMinutes = 5,
                engineFactory = { NativeChineseChessEngine(provider::requireNetworkPath) },
            ))[ChineseChessGameViewModel::class.java]
        }
        composeRule.setContent {
            ChineseChessRuntimeEffect(game)
            ChineseChessGameScreen(
                state = game.uiState, onSquareTap = game::onSquareTap, onUndo = game::undo,
                onHint = game::requestHint, onResign = game::resign, onRestart = game::restart,
                onBack = {}, onToggleAutoPlay = game::toggleAutoPlayPaused,
            )
        }
        composeRule.waitUntil(30_000L) { saved()?.acceptedMoveCount?.let { it >= 1 } == true }
    }

    @After fun close() {
        instrumentation.runOnMainSync { composeRule.activity.viewModelStore.clear() }
        database.close()
    }

    @Test fun stoppedActivityContinuesSameNativeSessionAndResumesWithoutReloading() = runBlocking {
        val before = requireNotNull(saved())
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        instrumentation.runOnMainSync { assertFalse(game.isRuntimeForeground) }
        // Do not use Compose idling while the Activity is stopped: observe committed state instead.
        withTimeout(30_000L) {
            while ((saved()?.acceptedMoveCount ?: 0) < before.acceptedMoveCount + 3) delay(100L)
        }
        val background = requireNotNull(saved())
        assertEquals(before.sessionId, background.sessionId)
        NativeChineseChessEngine().use {
            assertTrue(it.restore(background.engineState) is RestoreResult.Restored)
        }
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitForIdle()
        instrumentation.runOnMainSync {
            assertTrue(game.isRuntimeForeground)
            assertTrue(game.uiState.isEngineAvailable)
            game.toggleAutoPlayPaused()
        }
        composeRule.waitUntil(10_000L) { !game.uiState.isAiThinking && !game.uiState.isPersisting }
        val paused = requireNotNull(saved())
        assertEquals(before.sessionId, paused.sessionId)
        assertTrue(paused.acceptedMoveCount >= background.acceptedMoveCount)
        assertTrue(paused.autoPlayPaused)
    }

    @Test fun stoppedPausedGameDoesNotAdvanceOrConsumeTimeAndKeepsItsViewingSpeed() = runBlocking {
        instrumentation.runOnMainSync {
            game.setAutoPlaySpeed(1.75f)
            game.toggleAutoPlayPaused()
        }
        composeRule.waitUntil(10_000L) { !game.uiState.isAiThinking && !game.uiState.isPersisting }
        val paused = requireNotNull(saved())
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        delay(2_500L)
        assertEquals(paused, saved())
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        composeRule.waitForIdle()
        instrumentation.runOnMainSync {
            assertTrue(game.isRuntimeForeground)
            assertTrue(game.uiState.isAutoPlayPaused)
            assertEquals(1.75f, game.uiState.autoPlaySpeed)
            assertEquals(paused.redRemainingMillis, game.uiState.redRemainingMillis)
            assertEquals(paused.blackRemainingMillis, game.uiState.blackRemainingMillis)
        }
    }

    private fun saved() = runBlocking { database.activeGameDao().find(GameType.CHINESE_CHESS.code) }
}
