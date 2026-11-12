package com.masterofchessstrategy.ui

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.games.competition_mahjong.auto.replay.CompetitionMahjongAutoReplayInstrumentedTest.Companion.nativeArchive
import com.masterofchessstrategy.games.competition_mahjong.auto.storage.RoomCompetitionMahjongAutoStore
import com.masterofchessstrategy.games.competition_mahjong.learning.CompetitionMahjongLearningProofCatalog
import com.masterofchessstrategy.games.competition_mahjong.learning.CompetitionMahjongLearningProofRequest
import com.masterofchessstrategy.games.competition_mahjong.learning.RoomCompetitionMahjongLearningProofStore
import com.masterofchessstrategy.settings.LocalDataWriterRegistry
import com.masterofchessstrategy.settings.awaitLocalDataWriters
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Production NavHost and isolated Room data, with actual Native sessions and system file picker. */
class CompetitionMahjongAutoNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<GoLocalDataCheckActivity>()
    private val database get() = compose.activity.ownedDatabase
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val images by lazy {
        File(instrumentation.targetContext.getExternalFilesDir(null), "competition-auto-navigation-qa/${UUID.randomUUID()}")
            .apply { check(mkdirs()) }
    }

    @After fun cleanup() {
        val db = database
        val name = compose.activity.ownedDatabaseName
        val legacy = compose.activity.ownedLegacyDirectory
        compose.activityRule.scenario.close()
        runBlocking { awaitLocalDataWriters(LocalDataWriterRegistry.forDatabase(db).snapshot()) {} }
        GoLocalDataCheckActivity.closeOwnedDatabase(name)
        assertTrue(instrumentation.targetContext.deleteDatabase(name))
        assertTrue(legacy.listFiles()!!.isEmpty())
        assertTrue(legacy.delete())
    }

    @Test fun lockedViewerCanReachTheTutorialWithoutCreatingActivity() {
        openModes()
        compose.onNodeWithTag("competition_auto_entry").performScrollTo().performClick()
        awaitTag("competition_mahjong_auto_learning")
        compose.onNodeWithText("完成四章国标麻将教程后开放自动演局。").performScrollTo().assertIsDisplayed()
        assertNull(runBlocking { RoomCompetitionMahjongAutoStore(database, 0).load(0) })
        capture("locked")
        compose.onNodeWithTag("competition_mahjong_auto_learning").performScrollTo().performClick()
        awaitTag("competition_mahjong_learning_chapters")
    }

    @Test fun learnedViewerStepsReturnsAndResumesExactlyTheSameStoredEvent() {
        val db = database
        val viewer = 2
        runBlocking {
            val lessons = RoomCompetitionMahjongLearningProofStore(db, db.competitionMahjongLearningProofDao())
            CompetitionMahjongLearningProofCatalog.chapterIds.forEachIndexed { index, chapter ->
                lessons.confirm(CompetitionMahjongLearningProofRequest(
                    UUID.randomUUID().toString(), chapter, CompetitionMahjongLearningProofCatalog.cardIds(chapter), index.toLong(),
                ))
            }
        }
        openModes(viewer)
        openAutomatic()
        val reader = RoomCompetitionMahjongAutoStore(db, viewer)
        val initial = checkNotNull(runBlocking { reader.load(viewer) })
        compose.onNodeWithTag("competition_mahjong_auto_step").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(15_000) { runBlocking { reader.load(viewer)?.eventsAfterOpening == 1 } }
        val saved = checkNotNull(runBlocking { reader.load(viewer) })
        assertEquals(initial.config.runId, saved.config.runId)
        assertEquals(viewer, saved.config.viewerPlayerId)
        assertTrue(saved.settings.paused)
        assertTrue(runBlocking { db.matchOutcomeDao().listAll().isEmpty() })
        capture("played")
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitTag("competition_auto_entry")
        openAutomatic()
        val resumed = checkNotNull(runBlocking { reader.load(viewer) })
        assertEquals(saved.config.runId, resumed.config.runId)
        assertArrayEquals(saved.engineRecord, resumed.engineRecord)
        assertNull(runBlocking { RoomCompetitionMahjongAutoStore(db, 0).load(0) })
        capture("resumed")
    }

    @Test fun productionArchiveReplaysAndSystemFilePickerCancellationPreservesPrivateArchive() {
        val archive = nativeArchive()
        val viewer = archive.checkpoint.config.viewerPlayerId
        val store = RoomCompetitionMahjongAutoStore(database, viewer)
        runBlocking { store.load(viewer); store.save(archive.checkpoint, archive) }
        openModes(viewer)
        compose.onNodeWithTag("competition_auto_archive_entry").performScrollTo().performClick()
        val export = "competition_mahjong_auto_archive_export_${archive.key}"
        try {
            compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag(export) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag(export).performScrollTo().assertIsDisplayed().performClick()
            awaitTag("competition_mahjong_auto_export_confirm")
        } catch (failure: Throwable) {
            runCatching { captureFailure("archive-controls-failure") }
            throw failure
        }
        compose.onNodeWithText("各席暗牌和牌墙", substring = true).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("competition_mahjong_auto_export_confirm").performClick()
        val automation = instrumentation.uiAutomation
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline &&
            automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui", ignoreCase = true) != true) {
            SystemClock.sleep(100)
        }
        assertTrue(automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui", ignoreCase = true) == true)
        repeat(3) {
            if (automation.rootInActiveWindow?.packageName?.toString()?.contains("documentsui", ignoreCase = true) == true) {
                assertTrue(automation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
                SystemClock.sleep(750)
            }
        }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("未写出棋谱，原归档保持不变", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(archive.sameContent(checkNotNull(runBlocking { store.load(archive.key, viewer) })))
        val replay = "competition_mahjong_auto_archive_replay_${archive.key}"
        compose.onNodeWithTag(replay).performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        awaitTag("competition_mahjong_auto_replay_position")
        compose.onNodeWithTag("competition_mahjong_auto_replay_position").performScrollTo()
            .assertTextContains("第1 / 16盘", substring = true)
        val next = "competition_mahjong_auto_replay_next"
        compose.onNodeWithTag(next).performScrollTo().assertIsDisplayed().assertIsEnabled().performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasTestTag("competition_mahjong_auto_replay_position") and hasText("事件 1 /", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        capture("archive-replay")
    }

    private fun openModes(viewer: Int = 0) {
        compose.onNodeWithTag(HOME_COMPETITION_MAHJONG_TAG).performScrollTo().performClick()
        compose.onNodeWithTag("competition_formal_seat_$viewer").performScrollTo().performClick()
    }

    private fun openAutomatic() {
        compose.onNodeWithTag("competition_auto_entry").performScrollTo().performClick()
        awaitTag("competition_mahjong_auto_step")
    }

    private fun awaitTag(tag: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(images, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }

    private fun captureFailure(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        try {
            File(images, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bitmap.recycle()
        }
    }
}
