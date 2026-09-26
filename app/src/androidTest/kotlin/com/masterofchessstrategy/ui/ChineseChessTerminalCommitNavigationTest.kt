package com.masterofchessstrategy.ui

import android.content.pm.ActivityInfo
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.TutorialProgressEntity
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.game.ChineseChessBackgroundController
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Exercises the production navigation/repositories, with an isolated DB and a real SQL failure. */
class ChineseChessTerminalCommitNavigationTest {
    @get:Rule val composeRule = createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var database: MocsDatabase
    @Before fun prepare() = runBlocking {
        instrumentation.runOnMainSync { composeRule.activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        composeRule.waitForIdle()
        database = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, MocsDatabase::class.java).build()
        database.tutorialProgressDao().upsert(TutorialProgressEntity(GameType.CHINESE_CHESS.code, 1, 4, true, 1L))
        composeRule.setContent { MasterOfChessStrategyApp(database) }
    }
    @After fun cleanup() {
        instrumentation.runOnMainSync { composeRule.activity.viewModelStore.clear() }
        database.close()
    }
    @Test fun failedTerminalTransactionStaysOnGameAndRetryCommitsAllRows() = runBlocking {
        composeRule.onNodeWithTag(HOME_CHINESE_CHESS_TAG).performClick()
        composeRule.onNodeWithTag(MODE_AI_GAME_TAG).performScrollTo().performClick()
        composeRule.onNodeWithTag(DIFFICULTY_EASY_TAG).assertIsEnabled().performClick()
        composeRule.waitForIdle()
        val controller = ChineseChessBackgroundController.get(instrumentation.targetContext)
        composeRule.waitUntil(30_000L) { controller.game?.uiState?.let { !it.isRestoring && !it.isPersisting } == true }
        val game = requireNotNull(controller.game)
        val before = requireNotNull(database.activeGameDao().find(GameType.CHINESE_CHESS.code))
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_terminal_save BEFORE INSERT ON active_games BEGIN SELECT RAISE(ABORT, 'injected final-write failure'); END",
        )
        composeRule.runOnIdle { game.resign() }
        composeRule.waitUntil(15_000L) { game.uiState.hasUncommittedResult && !game.uiState.isPersisting }
        composeRule.onNodeWithTag(GAME_BACK_BUTTON_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(GAME_SETTINGS_BUTTON_TAG).assertIsNotEnabled()
        composeRule.onNodeWithTag(RESTART_BUTTON_TAG).assertIsNotEnabled()
        assertEquals(before, database.activeGameDao().find(GameType.CHINESE_CHESS.code))
        assertTrue(database.gameRecordDao().listAll().isEmpty())
        assertTrue(database.matchOutcomeDao().listAll().isEmpty())
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_terminal_save")
        composeRule.onNodeWithTag("retry_terminal_save").performScrollTo().assertIsEnabled().performClick()
        composeRule.waitUntil(15_000L) { !game.uiState.hasUncommittedResult && !game.uiState.isPersisting }
        assertEquals(before.sessionId, database.gameRecordDao().listAll().single().recordId)
        assertEquals(before.sessionId, database.matchOutcomeDao().listAll().single().matchId)
        assertEquals(2, database.activeGameDao().find(GameType.CHINESE_CHESS.code)?.resultOverrideCode)
        composeRule.onNodeWithTag(GAME_BACK_BUTTON_TAG).assertIsEnabled().performClick()
        composeRule.waitUntil(10_000L) { controller.game == null }
    }
}
