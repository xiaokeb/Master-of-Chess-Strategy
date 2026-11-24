package com.masterofchessstrategy.ui

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.engine.games.tiger_goat.NativeTigerGoatSession
import com.masterofchessstrategy.games.tiger_goat.TigerGoatBoardSpec
import com.masterofchessstrategy.games.tiger_goat.TigerGoatEdge
import com.masterofchessstrategy.games.tiger_goat.TigerGoatPoint
import com.masterofchessstrategy.games.tiger_goat.TigerGoatTutorialPractice
import com.masterofchessstrategy.games.tiger_goat.TigerGoatTutorialProgress
import com.masterofchessstrategy.games.tiger_goat.TigerGoatTutorialStep
import com.masterofchessstrategy.games.tiger_goat.tigerGoatViewport
import com.masterofchessstrategy.settings.LocalDataWriterRegistry
import com.masterofchessstrategy.settings.awaitLocalDataWriters
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real Home/NavHost/VM/Room/Native path; this test owns only its UUID disk database. */
class TigerGoatNavigationIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<TigerGoatNavigationCheckActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var databaseName: String
    private lateinit var database: MocsDatabase

    @Before fun prepare() {
        database = compose.activity.ownedDatabase
        databaseName = compose.activity.ownedDatabaseName
    }
    @After fun cleanup() {
        compose.activityRule.scenario.close()
        runBlocking { awaitLocalDataWriters(LocalDataWriterRegistry.forDatabase(database).snapshot()) {} }
        TigerGoatNavigationCheckActivity.closeOwnedDatabase(databaseName)
        assertTrue(context.deleteDatabase(databaseName))
    }

    @Test fun actualMenuMoveExitReenterAndUndoKeepTheDiskCheckpoint() {
        enterFromHome()
        awaitCheckpoint(0)
        saveImage("opening")
        tapPoint(6)
        awaitCheckpoint(1)
        compose.onNodeWithText("羊放置一只，待摆 14 只").assertIsDisplayed()
        saveImage("removed")
        val checkpoint = runBlocking { database.activeGameDao().find(5)!! }
        compose.onNodeWithText("返回").performClick()
        compose.waitUntil(10_000) { LocalDataWriterRegistry.forDatabase(database).snapshot().isEmpty() }
        compose.onNodeWithTag(TIGER_GOAT_LOCAL_TAG).performScrollTo().performClick()
        chooseLocal()
        awaitCheckpoint(1)
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("羊放置一只，待摆 14 只").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(checkpoint, runBlocking { database.activeGameDao().find(5)!! })
        saveImage("restored")
        checkScrollableControls("controls-restored")
        compose.onNodeWithText("悔棋").performScrollTo().performClick()
        awaitCheckpoint(0)
        compose.onNodeWithText("老虎先从中央环移去一只羊").assertIsDisplayed()
    }

    @Test fun formalModeRequiresTutorialConfirmsReplacementAndResumesTheRealAiTurn() {
        enterFromHome()
        awaitCheckpoint(0)
        tapPoint(6)
        awaitCheckpoint(1)
        val local = runBlocking { database.activeGameDao().find(5)!! }
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithTag(TIGER_GOAT_LOCAL_TAG).performScrollTo().performClick()
        val start = compose.onNodeWithTag("tiger_goat_start_formal_1")
        start.performScrollTo().assertIsNotEnabled()
        saveImage("formal-locked")
        compose.onNodeWithTag("tiger_goat_modes_tutorial").performScrollTo().performClick()
        compose.onNodeWithTag("tiger_goat_tutorial").assertExists()
        runBlocking { for (step in 0..3) TigerGoatTutorialProgress(database).complete(step) }
        compose.onNodeWithText("返回模式选择").performClick()
        start.performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithText("取消").performClick()
        assertEquals(local, runBlocking { database.activeGameDao().find(5) })
        start.performScrollTo().performClick()
        saveImage("formal-confirm", system = true)
        compose.onNodeWithText("确认替换").performClick()
        awaitFormalBoard()
        awaitCheckpoint(0)
        tapPoint(6)
        awaitCheckpoint(2)
        val formal = runBlocking { database.activeGameDao().find(5)!! }
        assertNotEquals(local.sessionId, formal.sessionId)
        assertEquals(1, formal.modeCode)
        saveImage("formal-ai-reply")
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithTag(TIGER_GOAT_LOCAL_TAG).performScrollTo().performClick()
        compose.onNodeWithTag("tiger_goat_resume_formal").performScrollTo().performClick()
        awaitFormalBoard()
        awaitCheckpoint(2)
        assertEquals(formal, runBlocking { database.activeGameDao().find(5) })
        compose.onNodeWithText("悔棋").performScrollTo().performClick()
        awaitCheckpoint(0)
        compose.onNodeWithText("老虎先从中央环移去一只羊").assertIsDisplayed()
    }

    @Test fun goatSideStartsWithAnAiOpeningRemoval() {
        runBlocking { for (step in 0..3) TigerGoatTutorialProgress(database).complete(step) }
        compose.onNodeWithTag(HOME_LANZHOU_TRADITIONAL_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(TIGER_GOAT_LOCAL_TAG).performScrollTo().performClick()
        compose.onNodeWithTag("tiger_goat_start_formal_2").performScrollTo().performClick()
        awaitFormalBoard()
        awaitCheckpoint(1)
        compose.onNodeWithText("正式人机 · 简单 · 执羊").assertIsDisplayed()
        compose.onNodeWithText("羊放置一只，待摆 14 只").assertIsDisplayed()
        val row = runBlocking { database.activeGameDao().find(5)!! }
        assertEquals(1, row.playerIndex)
        assertEquals(1, row.modeCode)
    }

    @Test fun activityRecreationPreservesTheSameNativeHistoryAndDiskRow() {
        enterFromHome()
        awaitCheckpoint(0)
        tapPoint(6)
        awaitCheckpoint(1)
        val checkpoint = runBlocking { database.activeGameDao().find(5)!! }
        compose.activityRule.scenario.recreate()
        assertSame(database, compose.activity.ownedDatabase)
        assertEquals(databaseName, compose.activity.ownedDatabaseName)
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("tiger_goat_board").fetchSemanticsNodes().isNotEmpty()
        }
        awaitCheckpoint(1)
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("羊放置一只，待摆 14 只").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(checkpoint, runBlocking { database.activeGameDao().find(5)!! })
        saveImage("recreated")
        checkScrollableControls("controls-recreated")
    }

    @Test fun tutorialPracticeAndQuizPersistWithoutChangingTheActiveGame() {
        enterFromHome()
        awaitCheckpoint(0)
        val active = runBlocking { database.activeGameDao().find(5) }
        fun step(label: String) = compose.waitUntil(10_000) {
            compose.onAllNodesWithText(label, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("tiger_goat_open_tutorial").performScrollTo().performClick()
        step("1 / 4 · 开局移羊")
        saveImage("tutorial-opening")
        tapPoint(6)
        step("2 / 4 · 摆放待摆羊")
        compose.activityRule.scenario.recreate()
        step("2 / 4 · 摆放待摆羊")
        tapPoint(0)
        step("3 / 4 · 老虎沿线走棋")
        val (from, to) = TigerGoatTutorialPractice(TigerGoatTutorialStep.MOVE).use { practice ->
            val source = practice.state().legalDestinations.first()
            assertFalse(practice.tap(source))
            source to practice.state().legalDestinations.first()
        }
        tapPoint(from)
        tapPoint(to)
        step("4 / 4 · 复核连跳规则")
        compose.onNodeWithText("必须继续跳吃").performScrollTo().performClick()
        assertEquals(3, runBlocking { database.tutorialProgressDao().find(5)?.completedStepCount })
        saveImage("tutorial-quiz")
        compose.onNodeWithTag("tiger_goat_quiz_correct").performScrollTo().performClick()
        step("教程已完成")
        assertTrue(runBlocking { database.tutorialProgressDao().find(5)?.isCompleted == true })
        saveImage("tutorial-completed")
        compose.onNodeWithText("返回对局").performClick()
        awaitCheckpoint(0)
        assertEquals(active, runBlocking { database.activeGameDao().find(5) })
    }

    private fun enterFromHome() {
        compose.onNodeWithTag(HOME_LANZHOU_TRADITIONAL_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(TIGER_GOAT_LOCAL_TAG).performScrollTo().performClick()
        chooseLocal()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("tiger_goat_board").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun chooseLocal() {
        compose.onNodeWithTag("tiger_goat_choose_local").performScrollTo().performClick()
    }

    private fun awaitFormalBoard() {
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("tiger_goat_formal_board").fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasText("认输") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun awaitCheckpoint(count: Int) {
        compose.waitUntil(10_000) {
            runBlocking { database.activeGameDao().find(5)?.acceptedMoveCount == count }
        }
        compose.waitForIdle()
    }

    private fun tapPoint(point: Int) {
        val geometry = NativeTigerGoatSession().use { it.geometry }
        val spec = TigerGoatBoardSpec(
            geometry.points.mapIndexed { index, it -> TigerGoatPoint(index, it.x, it.y) },
            geometry.edges.map { TigerGoatEdge(it.from, it.to) },
        )
        val board = compose.onNodeWithTag("tiger_goat_board")
        val bounds = board.fetchSemanticsNode().boundsInRoot
        val center = tigerGoatViewport(bounds.width, bounds.height, spec).centers[point]
        board.performTouchInput { click(Offset(center.x, center.y)) }
    }

    private fun checkScrollableControls(name: String) {
        compose.onNodeWithText("认输").performScrollTo().assertIsDisplayed().assertIsEnabled()
        saveImage(name)
    }

    private fun saveImage(name: String, system: Boolean = false) {
        val image = if (system) checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            else compose.onRoot().captureToImage().asAndroidBitmap()
        assertTrue("The production layout must be tested in landscape", image.width > image.height)
        val directory = File(context.cacheDir, "tiger-goat-navigation-visual")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create the visual test cache" }
        File(directory, "$name.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
