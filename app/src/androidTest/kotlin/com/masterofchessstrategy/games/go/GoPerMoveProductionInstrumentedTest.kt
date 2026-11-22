package com.masterofchessstrategy.games.go

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.sealGoLegacySourcesForRestore
import com.masterofchessstrategy.engine.GoPlayer
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

class GoPerMoveProductionInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<GoLocalDataCheckActivity>()

    @Test fun creationAndResumeRetainThePerMoveDeadlineAndExcludeStandardGrowth() {
        val activity = compose.activity
        val db = activity.ownedDatabase
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val images = File(context.getExternalFilesDir(null), "go-per-move-qa/${UUID.randomUUID()}")
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
            compose.onNodeWithTag("go_ai_setup_size_9").performScrollTo().performClick()
            compose.onNodeWithTag("go_ai_setup_per_move_120").performScrollTo().performClick().assertIsSelected()
            capture(images, "setup")
            compose.onNodeWithTag("go_ai_setup_create").performScrollTo().performClick()
            awaitTurn()
            val initial = checkNotNull(runBlocking { storage.aiRepository().load() })
            assertEquals(120, initial.config.perMoveLimitSeconds)
            compose.onNodeWithTag("go_intersection_0_0").performClick()
            compose.waitUntil(60_000) {
                val active = runBlocking { storage.aiRepository().load() } ?: return@waitUntil false
                NativeGoEngine(9).use { native ->
                    native.restore(active.copyBytes()) == RestoreResult.Restored && native.eventHistory().size == 2
                }
            }
            awaitTurn()
            val saved = checkNotNull(runBlocking { storage.aiRepository().load() })
            assertEquals(GoClockMode.PER_MOVE, saved.clock!!.mode)
            assertEquals(GoPlayer.BLACK, saved.clock.active)
            assertEquals(120_000L, saved.clock.blackMillis)
            assertEquals(9, GoAiMatchFileCodec.encode(saved)[4].toInt())
            capture(images, "playing")

            compose.onNodeWithText("返回").performScrollTo().performClick()
            awaitSetup()
            compose.onNodeWithTag("go_ai_setup_continue").performScrollTo().performClick()
            awaitTurn()
            val resumed = checkNotNull(runBlocking { storage.aiRepository().load() })
            assertArrayEquals(GoAiMatchFileCodec.encode(saved), GoAiMatchFileCodec.encode(resumed))

            compose.onNodeWithTag("go_ai_resign").performScrollTo().performClick()
            compose.onNodeWithTag("go_resign_confirm").performClick()
            compose.waitUntil(30_000) { runBlocking { db.goAiCheckpointDao().listArchives().size } == 1 }
            runBlocking {
                assertTrue(db.goRankedDao().starts().isEmpty())
                assertTrue(db.goRankedDao().settlements().isEmpty())
                val archived = db.goAiCheckpointDao().listArchives().single()
                val final = checkNotNull(GoAiMatchFileCodec.decode(archived.checkpoint))
                assertEquals(saved.config, final.config)
                assertNull(final.clock!!.active)
            }
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
        compose.onNodeWithTag("go_ai_per_move_status").performScrollTo().assertTextContains("每步 120 秒", substring = true)
    }

    private fun capture(directory: File, name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
