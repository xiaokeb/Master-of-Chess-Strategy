package com.masterofchessstrategy.games.go

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.sealGoLegacySourcesForRestore
import com.masterofchessstrategy.data.StoredGameMode
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

class GoAssessmentProductionInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<GoLocalDataCheckActivity>()

    @Test fun fiveRoundsPersistAndDoNotGrantStandardGrowth() {
        val activity = compose.activity
        val db = activity.ownedDatabase
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val images = File(context.getExternalFilesDir(null), "go-assessment-qa/${UUID.randomUUID()}")
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
            compose.onNodeWithTag("go_ai_setup_assessment").performScrollTo().performClick().assertIsSelected()
            capture(images, "setup")
            compose.onNodeWithTag("go_ai_setup_create").performScrollTo().performClick()
            awaitTurn()
            val first = checkNotNull(runBlocking { storage.aiRepository().load() })
            assertEquals(GoAssessmentProgress(), first.config.assessment)
            assertEquals(GoAiDifficulty.MEDIUM, first.config.difficulty)
            assertEquals(11, GoAiMatchFileCodec.encode(first)[4].toInt())
            val rankings = listOf(900, 500, 400, 400, 400)
            repeat(5) { index ->
                compose.onNodeWithTag("go_ai_hint").performScrollTo().assertIsNotEnabled()
                compose.onNodeWithTag("go_ai_undo").performScrollTo().assertIsNotEnabled()
                compose.onNodeWithTag("go_ai_resign").performScrollTo().performClick()
                compose.onNodeWithTag("go_resign_confirm").performClick()
                compose.waitUntil(30_000) { runBlocking { db.gameRecordDao().listAll().size } == index + 1 }
                val prefix = if (index == 4) "最终评分" else "当前评分"
                compose.onNodeWithTag("go_ai_assessment_rating").performScrollTo()
                    .assertTextEquals("$prefix ${rankings[index]}")
                if (index < 4) {
                    compose.onNodeWithTag("go_ai_restart").performScrollTo().performClick()
                    compose.onNodeWithTag("go_ai_restart_confirm").performClick()
                    awaitTurn()
                    val active = checkNotNull(runBlocking { storage.aiRepository().load() })
                    assertEquals(GoAiDifficulty.SIMPLE, active.config.difficulty)
                    assertEquals(index + 1, active.config.assessment?.losses)
                    if (index == 1) {
                        compose.onNodeWithText("返回").performScrollTo().performClick()
                        awaitSetup()
                        compose.onNodeWithTag("go_ai_setup_continue").performScrollTo().performClick()
                        awaitTurn()
                        val resumed = checkNotNull(runBlocking { storage.aiRepository().load() })
                        assertArrayEquals(GoAiMatchFileCodec.encode(active), GoAiMatchFileCodec.encode(resumed))
                        capture(images, "resumed")
                    }
                }
            }
            compose.onNodeWithTag("go_ai_restart").performScrollTo().assertIsNotEnabled()
            compose.onNodeWithTag("go_ai_assessment_rounds").performScrollTo().assertTextContains("5/5", substring = true)
            capture(images, "final")
            runBlocking {
                assertTrue(db.goRankedDao().starts().isEmpty())
                assertTrue(db.goRankedDao().settlements().isEmpty())
                assertTrue(db.gameRecordDao().listAll().all { it.modeCode == StoredGameMode.ASSESSMENT_CHALLENGE.code })
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
    }

    private fun capture(directory: File, name: String) {
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally { bitmap.recycle() }
    }
}
