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

    private fun enterFromHome() {
        compose.onNodeWithTag(HOME_LANZHOU_TRADITIONAL_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(TIGER_GOAT_LOCAL_TAG).performScrollTo().performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("tiger_goat_board").fetchSemanticsNodes().isNotEmpty()
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

    private fun saveImage(name: String) {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        assertTrue("The production layout must be tested in landscape", image.width > image.height)
        val directory = File(context.cacheDir, "tiger-goat-navigation-visual")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create the visual test cache" }
        File(directory, "$name.png").outputStream().use {
            image.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
