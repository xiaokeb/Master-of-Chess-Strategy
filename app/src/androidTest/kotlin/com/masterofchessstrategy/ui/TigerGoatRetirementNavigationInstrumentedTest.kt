package com.masterofchessstrategy.ui

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.createProductionGameBackupRepository
import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import com.masterofchessstrategy.engine.NativeChineseChessEngine
import com.masterofchessstrategy.engine.RestoreResult
import com.masterofchessstrategy.engine.games.tiger_goat.NativeTigerGoatSession
import com.masterofchessstrategy.games.go.data.GoLocalStorage
import com.masterofchessstrategy.games.lanzhou_fangqi.data.FangqiLocalStorage
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
import org.junit.Rule
import org.junit.Test

/** Actual navigation and production factory; replacement deliberately tests the epoch observer too. */
class TigerGoatRetirementNavigationInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<TigerGoatNavigationCheckActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val database: MocsDatabase get() = compose.activity.ownedDatabase

    @After fun cleanup() {
        val db = database
        val name = compose.activity.ownedDatabaseName
        compose.activityRule.scenario.close()
        runBlocking { awaitLocalDataWriters(LocalDataWriterRegistry.forDatabase(db).snapshot()) {} }
        TigerGoatNavigationCheckActivity.closeOwnedDatabase(name)
        assertTrue(context.deleteDatabase(name))
    }

    @Test fun fullProductionRestoreStopsTheOpenPageAndReentryLoadsRestoredHistory() {
        enter()
        checkpoint(0)
        val original = runBlocking { database.activeGameDao().find(5)!! }
        val backup = runBlocking { repository().export(20) }
        removeGoat()
        // Home may also own a Xiangqi checkpoint; this case verifies the tiger row below.
        runBlocking { assertTrue(repository().restore(backup).activeSessionCount >= 1) }
        returnFromRetired("success")
        compose.onNodeWithTag(TIGER_GOAT_LOCAL_TAG).performScrollTo().performClick()
        checkpoint(0)
        compose.onNodeWithText("老虎先从中央环移去一只羊").assertIsDisplayed()
        assertEquals(original, runBlocking { database.activeGameDao().find(5)!! })
        image("success-reopened")
    }

    @Test fun failedProductionRestoreStopsTheOldPageAndReentryKeepsTheCommittedHistory() {
        enter()
        checkpoint(0)
        removeGoat()
        val committed = runBlocking { database.activeGameDao().find(5)!! }
        assertTrue(runBlocking { runCatching { repository().restore(byteArrayOf(0)) }.isFailure })
        returnFromRetired("failure")
        compose.onNodeWithTag(TIGER_GOAT_LOCAL_TAG).performScrollTo().performClick()
        checkpoint(1)
        compose.onNodeWithText("羊放置一只，待摆 14 只").assertIsDisplayed()
        assertEquals(committed, runBlocking { database.activeGameDao().find(5)!! })
        image("failure-reopened")
    }

    private fun repository() = createProductionGameBackupRepository(database,
        ChineseChessEndgamePackParser.loadBundled(context.assets), GoLocalStorage(database, context),
        FangqiLocalStorage.forDatabase(database)) { bytes ->
        NativeChineseChessEngine().use { it.restore(bytes) == RestoreResult.Restored }
    }

    private fun enter() {
        compose.onNodeWithTag(HOME_LANZHOU_TRADITIONAL_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(TIGER_GOAT_LOCAL_TAG).performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("tiger_goat_board").fetchSemanticsNodes().isNotEmpty() }
    }

    private fun checkpoint(count: Int) {
        compose.waitUntil(10_000) { runBlocking { database.activeGameDao().find(5)?.acceptedMoveCount == count } }
        compose.waitForIdle()
    }

    private fun removeGoat() {
        val geometry = NativeTigerGoatSession().use { it.geometry }
        val spec = TigerGoatBoardSpec(geometry.points.mapIndexed { id, p -> TigerGoatPoint(id, p.x, p.y) },
            geometry.edges.map { TigerGoatEdge(it.from, it.to) })
        val board = compose.onNodeWithTag("tiger_goat_board")
        val bounds = board.fetchSemanticsNode().boundsInRoot
        val center = tigerGoatViewport(bounds.width, bounds.height, spec).centers[6]
        board.performTouchInput { click(Offset(center.x, center.y)) }
        checkpoint(1)
    }

    private fun returnFromRetired(prefix: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("本地数据已替换，请返回后重新打开虎羊棋").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("对局已停止").assertIsDisplayed()
        image("$prefix-retired")
        compose.onNode(hasText("返回") and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(10_000) { LocalDataWriterRegistry.forDatabase(database).snapshot().isEmpty() }
        compose.onNodeWithTag(TIGER_GOAT_LOCAL_TAG).assertExists()
        image("$prefix-menu")
    }

    private fun image(name: String) {
        compose.waitForIdle()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        assertTrue(bitmap.width > bitmap.height)
        val directory = File(context.cacheDir, "tiger-goat-retirement-visual")
        check(directory.isDirectory || directory.mkdirs())
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
