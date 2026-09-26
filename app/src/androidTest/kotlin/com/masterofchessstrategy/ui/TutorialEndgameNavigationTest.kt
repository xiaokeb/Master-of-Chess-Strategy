package com.masterofchessstrategy.ui

import android.os.ParcelFileDescriptor
import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.TutorialProgressEntity
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.GameType
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TutorialEndgameNavigationTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var database: MocsDatabase

    @Before
    fun prepareIsolatedQuizCheckpoint() = runBlocking {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
        composeRule.waitForIdle()
        database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext, MocsDatabase::class.java,
        ).build()
        database.tutorialProgressDao().upsert(TutorialProgressEntity(
            gameTypeCode = GameType.CHINESE_CHESS.code, contentVersion = 1,
            completedStepCount = 3, isCompleted = false, updatedAtEpochMillis = 1L,
        ))
    }

    @After
    fun closeDatabase() {
        database.close()
    }

    @Test
    fun quizCompletionOpensRealEndgameAwardsOnceAndReturnsToTutorial() {
        composeRule.setContent { MasterOfChessStrategyApp(database) }
        composeRule.onNodeWithTag(HOME_CHINESE_CHESS_TAG).performClick()
        composeRule.onNodeWithTag(MODE_TUTORIAL_TAG).performClick()
        composeRule.onNodeWithText("双方和棋").performScrollTo().performClick()
        composeRule.onNodeWithTag(TUTORIAL_CONTINUE_TAG).performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag(TUTORIAL_ENDGAME_TAG).assertDoesNotExist()
        composeRule.onNodeWithTag(TUTORIAL_CORRECT_ANSWER_TAG).performScrollTo().performClick()
        composeRule.onNodeWithTag(TUTORIAL_CONTINUE_TAG).performScrollTo().performClick()
        composeRule.waitUntil(10000) {
            runBlocking { database.tutorialProgressDao().find(GameType.CHINESE_CHESS.code)?.isCompleted == true }
        }
        composeRule.onNodeWithTag(TUTORIAL_ENDGAME_TAG).performScrollTo().assertIsEnabled()
        composeRule.waitForIdle()
        // Preserve a real-device rendering artifact for the tutorial handoff.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Gradle removes the test app after execution. A shell-owned test
        // artifact survives that cleanup; no app storage permission is added.
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "screencap -p /data/local/tmp/mocs-tutorial-practice.png",
        )).use { it.readBytes() }
        composeRule.onNodeWithTag(TUTORIAL_ENDGAME_TAG).performClick()
        composeRule.onNodeWithText("残局 · 双马锁宫演示").assertExists()
        composeRule.waitUntil(10000) {
            runBlocking { database.activeGameDao().find(GameType.CHINESE_CHESS.code) != null }
        }
        val board = composeRule.onNodeWithTag(CHINESE_CHESS_BOARD_TAG)
        board.performTouchInput {
            val anchor = Offset(width / 2f, height * 0.25f)
            down(0, anchor - Offset(50f, 0f)); down(1, anchor + Offset(50f, 0f))
            moveTo(0, anchor - Offset(75f, 0f)); moveTo(1, anchor + Offset(75f, 0f))
            up(0); up(1)
        }
        assertTrue(board.fetchSemanticsNode().config[BoardViewportKey].scale > 1.4f)
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(
            "screencap -p /data/local/tmp/mocs-board-zoom.png",
        )).use { it.readBytes() }
        tapSquare(3, 1)
        tapSquare(4, 1)
        composeRule.onNodeWithText("红方胜").assertExists()
        composeRule.waitUntil(10000) { runBlocking { database.endgameProgressDao().listAll().size == 1 } }
        composeRule.onNodeWithTag(GAME_BACK_BUTTON_TAG).assertIsEnabled().performClick()
        composeRule.onNodeWithTag(TUTORIAL_SCREEN_TAG).assertExists()
        composeRule.onNodeWithTag(TUTORIAL_ENDGAME_TAG).performScrollTo().performClick()
        // Revisiting the completed session cannot create another first-clear reward.
        composeRule.onNodeWithText("残局 · 双马锁宫演示").assertExists()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(GAME_BACK_BUTTON_TAG).assertIsEnabled().performClick()
        composeRule.onNodeWithTag(TUTORIAL_SCREEN_TAG).assertExists()
        runBlocking {
            val progress = database.endgameProgressDao().listAll().single()
            assertEquals("xq-easy-001", progress.levelId)
            assertEquals(1, progress.starsAwarded)
            assertEquals(10, progress.scoreAwarded)
            assertTrue(database.tutorialProgressDao().find(GameType.CHINESE_CHESS.code)!!.isCompleted)
        }
    }

    private fun tapSquare(x: Int, y: Int) {
        val board = composeRule.onNodeWithTag(CHINESE_CHESS_BOARD_TAG)
        val node = board.fetchSemanticsNode()
        val size = Size(node.boundsInRoot.width, node.boundsInRoot.height)
        val geometry = calculateBoardGeometry(size.width, size.height)
        val target = node.config[BoardViewportKey].screenPoint(geometry.center(BoardPosition(x, y)), size)
        assertTrue(target.x in 0f..size.width && target.y in 0f..size.height)
        board.performTouchInput { click(target) }
    }
}
