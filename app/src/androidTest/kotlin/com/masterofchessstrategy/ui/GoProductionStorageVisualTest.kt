package com.masterofchessstrategy.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.games.go.*
import com.masterofchessstrategy.games.go.data.*
import com.masterofchessstrategy.settings.LocalDataWriterRegistry
import com.masterofchessstrategy.settings.awaitLocalDataWriters
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Actual production NavHost/native/Room, with no access to the installation's legacy files. */
class GoProductionStorageVisualTest {
    @get:Rule val compose = createAndroidComposeRule<GoLocalDataCheckActivity>()
    private val database get() = compose.activity.ownedDatabase

    @After fun cleanup() {
        val db = database
        val name = compose.activity.ownedDatabaseName
        val legacy = compose.activity.ownedLegacyDirectory
        compose.activityRule.scenario.close()
        runBlocking {
            awaitLocalDataWriters(LocalDataWriterRegistry.forDatabase(db).snapshot()) { }
            GoStorageEpochGate.forDatabase(db).backup(false) { }
        }
        GoLocalDataCheckActivity.closeOwnedDatabase(name)
        assertTrue(InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(name))
        assertTrue("Legacy fixtures are read-only", legacy.listFiles()!!.isEmpty())
        assertTrue(legacy.delete())
    }

    @Test fun realLocalMoveAndActivityRecreationKeepTheRoomCheckpoint() {
        compose.onNodeWithTag(HOME_GO_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(GO_LOCAL_ENTRY_TAG).performScrollTo().performClick()
        awaitTag("go_intersection_0_0")
        compose.waitUntil(15_000) { runBlocking { database.goLocalSessionDao().find(GameType.GO.code) != null } }
        val before = runBlocking { database.goLocalSessionDao().find(GameType.GO.code)!!.checkpoint }
        compose.onNodeWithTag("go_intersection_0_0").performClick()
        compose.waitUntil(15_000) { runBlocking {
            !database.goLocalSessionDao().find(GameType.GO.code)!!.checkpoint.contentEquals(before)
        } }
        val moved = runBlocking { database.goLocalSessionDao().find(GameType.GO.code)!!.checkpoint }
        capture("local-moved")
        compose.activityRule.scenario.recreate()
        awaitTag("go_intersection_0_0")
        assertArrayEquals(moved, runBlocking { database.goLocalSessionDao().find(GameType.GO.code)!!.checkpoint })
        capture("local-recreated")
        compose.onNodeWithText("返回").performScrollTo().performClick()
        compose.onNodeWithTag(GO_MODES_TAG).assertExists()
    }

    @Test fun completedGoArchiveIsClassifiedAndReplayedWithBlackWhiteAndRealProvenance() {
        val id = runBlocking {
            val identity = GoSessionIdentity.create(GoSessionOrigin.SGF_IMPORT)
            NativeGoEngine(9).use { engine ->
                repeat(2) { assertEquals(ActionResult.Accepted, engine.apply(GoAction.Pass)) }
                val revision = engine.scoringState().proposalRevision
                assertEquals(ActionResult.Accepted, engine.apply(GoAction.ConfirmScore(GoPlayer.BLACK, revision)))
                assertEquals(ActionResult.Accepted, engine.apply(GoAction.ConfirmScore(GoPlayer.WHITE, revision)))
                RoomGoSessionRepository(database).save(GoSavedSession(9, engine.serialize(), GoScoreDisplayMode.STONES, identity = identity))
            }
            identity.recordId
        }
        compose.onNodeWithTag(HOME_RECORDS_TAG).performScrollTo().assertIsDisplayed().performClick()
        awaitTag("record_game_1")
        compose.onNodeWithTag("record_game_1").performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithText("围棋 · 白方获胜").fetchSemanticsNodes().isNotEmpty() }
        capture("go-records")
        compose.onNodeWithText("收藏").performClick()
        compose.waitUntil(15_000) { runBlocking { database.gameRecordDao().find(id)!!.isFavorite } }
        compose.onNodeWithText("回放").performClick()
        awaitTag("go_stored_record_origin")
        compose.onNodeWithText("来源：SGF 导入").performScrollTo().assertExists()
        compose.onNodeWithTag("go_stored_record_export").performScrollTo().assertIsEnabled()
        capture("go-replay-export")
        val records = runBlocking { database.gameRecordDao().listAll() }
        assertEquals(1, records.size)
        assertTrue(records.single().isFavorite)
        assertTrue(runBlocking { database.matchOutcomeDao().listAll().isEmpty() })
    }

    private fun awaitTag(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun capture(label: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "go-production-storage-qa")
        assertTrue(directory.isDirectory || directory.mkdir())
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            assertTrue("Production NavHost must render landscape", bitmap.width > bitmap.height)
            val file = File(directory, "$label-${UUID.randomUUID()}.png")
            file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            println("GO_STORAGE_VISUAL=${file.name}")
        } finally { bitmap.recycle() }
    }
}
