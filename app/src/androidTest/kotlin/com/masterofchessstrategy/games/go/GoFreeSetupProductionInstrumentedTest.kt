package com.masterofchessstrategy.games.go

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.sealGoLegacySourcesForRestore
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.GoPlayer
import com.masterofchessstrategy.engine.GoStone
import com.masterofchessstrategy.engine.NativeGoEngine
import com.masterofchessstrategy.engine.RestoreResult
import com.masterofchessstrategy.games.go.data.GoLocalStorage
import com.masterofchessstrategy.games.go.data.RoomGoLearningProgressRepository
import com.masterofchessstrategy.settings.LocalDataWriterRegistry
import com.masterofchessstrategy.settings.awaitLocalDataWriters
import com.masterofchessstrategy.ui.GoLocalDataCheckActivity
import com.masterofchessstrategy.ui.HOME_GO_TAG
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GoFreeSetupProductionInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<GoLocalDataCheckActivity>()

    @Test fun whiteStartsFromCustomRootAndCanReplayTheFinishedGame() = verifyCustomRoot(size = 9)

    @Test fun thirteenLineEditorRejectsAnInvalidRootAndCanStartAfterCorrection() =
        verifyCustomRoot(size = 13, rejectInvalidRoot = true)

    @Test fun nineteenLineEditorPreservesCornerStonesAndWhiteFirst() = verifyCustomRoot(size = 19)

    private fun verifyCustomRoot(size: Int, rejectInvalidRoot: Boolean = false) {
        val activity = compose.activity
        val db = activity.ownedDatabase
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val images = File(context.getExternalFilesDir(null), "go-free-setup-qa/${UUID.randomUUID()}")
        check(images.mkdirs())
        val storage = GoLocalStorage(db, activity)
        try {
            runBlocking {
                db.localDataMigrationDao().sealGoLegacySourcesForRestore()
                RoomGoLearningProgressRepository(db).save(GoLearningProgress(GoLearningLibrary.lessons.map { it.id }.toSet()))
            }
            compose.onNodeWithTag(HOME_GO_TAG).performScrollTo().performClick()
            compose.onNodeWithTag("go_navigation_ai").performScrollTo().performClick()
            awaitSetup()
            compose.onNodeWithTag("go_ai_setup_free").performScrollTo().performClick()
            compose.onNodeWithTag("go_free_size_$size").performScrollTo().performClick()
            if (rejectInvalidRoot) {
                compose.onNodeWithTag("go_intersection_1_0").performClick()
                compose.onNodeWithTag("go_intersection_0_1").performClick()
                compose.onNodeWithTag("go_free_tool_2").performScrollTo().performClick()
                compose.onNodeWithTag("go_intersection_0_0").performClick()
                compose.onNodeWithTag("go_free_start").performScrollTo().performClick()
                val invalid = "原生规则拒绝该局面。请检查无气棋块或先行方是否有合法落子。"
                compose.waitUntil(15_000) { compose.onAllNodesWithText(invalid).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithText(invalid).performScrollTo().assertIsDisplayed()
                assertNull(runBlocking { storage.aiRepository().load() })
                capture(images, "invalid-root")
                compose.onNodeWithText("清空棋盘").performScrollTo().performClick()
                compose.onNodeWithTag("go_free_tool_1").performScrollTo().performClick()
            }
            val edge = size - 1
            compose.onNodeWithTag("go_intersection_0_0").performClick()
            compose.onNodeWithTag("go_free_tool_2").performScrollTo().performClick()
            compose.onNodeWithTag("go_intersection_${edge}_$edge").performClick()
            compose.onNodeWithTag("go_free_first_1").performScrollTo().performClick()
            compose.onNodeWithTag("go_free_human_1").performScrollTo().performClick()
            capture(images, "editor")
            compose.onNodeWithTag("go_free_start").performScrollTo().performClick()
            awaitTurn()
            val first = checkNotNull(runBlocking { storage.aiRepository().load() })
            assertEquals(size, first.config.boardSize)
            assertEquals(GoPlayer.WHITE, first.config.humanPlayer)
            val opening = GoAiOpening.fromSnapshot(checkNotNull(first.copyOpeningBytes()))
            assertTrue(opening.hasCustomSetup)
            assertEquals(0, opening.eventCount)
            assertEquals(GoPlayer.WHITE, opening.currentPlayer)
            opening.createEngine().use { assertEquals(GoStone.WHITE, it.stoneAt(BoardPosition(edge, edge))) }
            capture(images, "playing")

            compose.onNodeWithTag("go_intersection_1_1").performClick()
            compose.waitUntil(60_000) {
                val active = runBlocking { storage.aiRepository().load() } ?: return@waitUntil false
                NativeGoEngine(size).use { engine ->
                    engine.restore(active.copyBytes()) == RestoreResult.Restored && engine.eventHistory().size == 2
                }
            }
            compose.onNodeWithTag("go_ai_restart").performScrollTo().performClick()
            compose.onNodeWithTag("go_ai_restart_confirm").performClick()
            awaitTurn()
            val restarted = checkNotNull(runBlocking { storage.aiRepository().load() })
            assertNotEquals(first.matchId, restarted.matchId)
            assertArrayEquals(opening.copyBytes(), restarted.copyBytes())

            compose.onNodeWithTag("go_ai_resign").performScrollTo().performClick()
            compose.onNodeWithTag("go_resign_confirm").performClick()
            compose.waitUntil(30_000) { runBlocking { db.gameRecordDao().listAll().size } == 1 }
            val record = runBlocking {
                assertTrue(db.goRankedDao().starts().isEmpty())
                assertTrue(db.goRankedDao().settlements().isEmpty())
                db.gameRecordDao().listAll().single()
            }
            assertEquals(StoredGameMode.CUSTOM_POSITION.code, record.modeCode)
            assertEquals(1, record.playerIndex)
            assertEquals(0, record.moveCount)
            GoReplaySession.openSnapshot(record.engineState).use { replay ->
                assertEquals(GoPlayer.WHITE, replay.frame().currentPlayer)
                assertEquals(GoStone.BLACK.code, replay.frame().stones.first())
                assertEquals(GoStone.WHITE.code, replay.frame().stones.last())
                val edgeCoordinate = ('a' + edge).toString().repeat(2)
                assertTrue(replay.exportSgf().contains("AB[aa]AW[$edgeCoordinate]PL[W]"))
            }
            capture(images, "terminal")
        } finally {
            compose.activityRule.scenario.close()
            runBlocking { awaitLocalDataWriters(LocalDataWriterRegistry.forDatabase(db).snapshot()) {} }
            GoLocalDataCheckActivity.closeOwnedDatabase(activity.ownedDatabaseName)
            assertTrue(context.deleteDatabase(activity.ownedDatabaseName))
            val legacy = activity.ownedLegacyDirectory
            assertTrue(legacy.listFiles()?.isEmpty() == true && legacy.delete())
        }
    }

    private fun awaitSetup() = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag("go_ai_setup").fetchSemanticsNodes().isNotEmpty() &&
            compose.onAllNodesWithText("正在读取当前对局与解锁进度…").fetchSemanticsNodes().isEmpty()
    }

    private fun awaitTurn() {
        compose.waitUntil(60_000) {
            compose.onAllNodes(hasTestTag("go_ai_phase") and hasText("轮到你行棋")).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun capture(directory: File, name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
