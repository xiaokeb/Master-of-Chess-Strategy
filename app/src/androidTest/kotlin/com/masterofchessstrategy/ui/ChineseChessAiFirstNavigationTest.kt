package com.masterofchessstrategy.ui

import android.content.pm.ActivityInfo
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.ActiveGameEntity
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.TutorialProgressEntity
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.NativeChineseChessEngine
import com.masterofchessstrategy.engine.RestoreResult
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Compose navigation, Room saves, native rules and bundled Pikafish search. */
@RunWith(AndroidJUnit4::class)
class ChineseChessAiFirstNavigationTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var database: MocsDatabase

    @Before fun prepare() = runBlocking {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        composeRule.waitForIdle()
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, MocsDatabase::class.java,
        ).build()
        database.tutorialProgressDao().upsert(TutorialProgressEntity(
            gameTypeCode = GameType.CHINESE_CHESS.code, contentVersion = 1,
            completedStepCount = 4, isCompleted = true, updatedAtEpochMillis = 1L,
        ))
    }

    @After fun close() { database.close() }

    @Test fun settingStartsRealAiThenBlackCanMoveUndoRestoreAndRestartAsRed() {
        composeRule.setContent { MasterOfChessStrategyApp(database) }
        composeRule.onNodeWithTag(HOME_SETTINGS_TAG).performScrollTo().performClick()
        toggleAiFirst(true)
        backFromSettings()
        composeRule.onNodeWithTag(HOME_CHINESE_CHESS_TAG).performClick()
        enterEasyGame()
        waitForMoves(1)
        composeRule.onNodeWithText("玩家执黑 · AI 执红").assertExists()
        composeRule.onNodeWithTag(UNDO_BUTTON_TAG).assertIsNotEnabled()
        val opening = requireNotNull(saved())
        assertEquals(1, opening.playerIndex)
        assertTrue(composeRule.onNodeWithTag(CHINESE_CHESS_BOARD_TAG).fetchSemanticsNode().config[BoardReversedKey])
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "screencap -p /data/local/tmp/mocs-ai-first.png",
        )).use { it.readBytes() }

        // Choose a native-legal black move from the actual AI opening, not a fixed scripted reply.
        val engine = NativeChineseChessEngine()
        val move = try {
            assertTrue(engine.restore(opening.engineState) is RestoreResult.Restored)
            assertEquals(1, engine.currentPlayer.value)
            engine.legalActions().first()
        } finally { engine.close() }
        tapSquare(move.from)
        tapSquare(move.to)
        waitForMoves(3)
        composeRule.onNodeWithTag(UNDO_BUTTON_TAG).assertIsEnabled().performClick()
        waitForMoves(1)
        assertTrue(opening.engineState.contentEquals(requireNotNull(saved()).engineState))
        composeRule.onNodeWithTag(UNDO_BUTTON_TAG).assertIsNotEnabled()

        // Preference changes do not rewrite an active game, including a newly created route/VM.
        composeRule.onNodeWithTag(GAME_SETTINGS_BUTTON_TAG).performClick()
        toggleAiFirst(false)
        backFromSettings()
        composeRule.onNodeWithText("玩家执黑 · AI 执红").assertExists()
        composeRule.onNodeWithTag(GAME_BACK_BUTTON_TAG).performClick()
        composeRule.onNodeWithTag(DIFFICULTY_EASY_TAG).performClick()
        waitForMoves(1)
        composeRule.onNodeWithText("玩家执黑 · AI 执红").assertExists()
        assertEquals(opening.sessionId, requireNotNull(saved()).sessionId)

        // Explicit restart applies the latest preference; the original red-first rule remains intact.
        composeRule.onNodeWithTag(RESTART_BUTTON_TAG).performScrollTo().performClick()
        waitForMoves(0)
        composeRule.onNodeWithText("玩家执红 · AI 执黑").assertExists()
        assertEquals(0, requireNotNull(saved()).playerIndex)
        assertFalse(composeRule.onNodeWithTag(CHINESE_CHESS_BOARD_TAG).fetchSemanticsNode().config[BoardReversedKey])
        assertTrue(opening.sessionId != requireNotNull(saved()).sessionId)
    }

    private fun enterEasyGame() {
        composeRule.onNodeWithTag(MODE_AI_GAME_TAG).performClick()
        composeRule.onNodeWithTag(DIFFICULTY_EASY_TAG).performClick()
    }

    private fun toggleAiFirst(expected: Boolean) {
        composeRule.onNodeWithTag(SETTINGS_AI_FIRST_TAG).performScrollTo().performClick()
        composeRule.waitUntil(10_000) { runBlocking { database.appSettingsDao().find()?.aiFirstEnabled == expected } }
    }

    private fun backFromSettings() { composeRule.onNodeWithText("返回").performScrollTo().performClick() }
    private fun saved(): ActiveGameEntity? = runBlocking { database.activeGameDao().find(GameType.CHINESE_CHESS.code) }
    private fun waitForMoves(count: Int) {
        composeRule.waitUntil(30_000) { saved()?.acceptedMoveCount == count }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(GAME_BACK_BUTTON_TAG).assertIsEnabled()
    }

    private fun tapSquare(position: BoardPosition) {
        val board = composeRule.onNodeWithTag(CHINESE_CHESS_BOARD_TAG)
        val node = board.fetchSemanticsNode()
        val size = Size(node.boundsInRoot.width, node.boundsInRoot.height)
        val geometry = calculateBoardGeometry(size.width, size.height).copy(reversed = node.config[BoardReversedKey])
        val target = node.config[BoardViewportKey].screenPoint(geometry.center(position), size)
        board.performTouchInput { click(target) }
    }
}
