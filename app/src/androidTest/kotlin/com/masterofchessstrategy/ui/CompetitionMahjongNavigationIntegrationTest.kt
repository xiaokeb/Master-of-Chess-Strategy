package com.masterofchessstrategy.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.CompetitionMahjongMatchArchive
import com.masterofchessstrategy.data.TutorialProgressEntity
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.NativeCompetitionMahjongSession
import com.masterofchessstrategy.games.competition_mahjong.data.finishCompetitionMahjongTestHand
import com.masterofchessstrategy.games.competition_mahjong.learning.*
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongCompetitionConfig
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchCheckpoint
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchCheckpointCodec
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchLedger
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchController
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchStore
import com.masterofchessstrategy.games.competition_mahjong.match.storage.CompetitionMahjongFormalSnapshotCodec
import com.masterofchessstrategy.games.competition_mahjong.match.storage.room.CompetitionMahjongFormalHost
import com.masterofchessstrategy.games.competition_mahjong.match.storage.room.CompetitionMahjongFormalRoomStorage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real navigation and Room commits, isolated from every existing user/tutorial row. */
class CompetitionMahjongNavigationIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<TigerGoatNavigationCheckActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var database: MocsDatabase
    private val runId = UUID.randomUUID().toString()
    private val cardTag = "competition_mahjong_learning_card"
    private val nextTag = "competition_mahjong_learning_next"

    @Before fun prepare() {
        database = compose.activity.ownedDatabase
        compose.waitForIdle()
    }

    @After fun cleanup() {
        val name = compose.activity.ownedDatabaseName
        compose.activityRule.scenario.close()
        runBlocking {
            com.masterofchessstrategy.settings.awaitLocalDataWriters(
                com.masterofchessstrategy.settings.LocalDataWriterRegistry.forDatabase(database).snapshot()) {}
        }
        TigerGoatNavigationCheckActivity.closeOwnedDatabase(name)
        assertTrue(instrumentation.targetContext.deleteDatabase(name))
    }
    @Test fun navigationPreservesLearningAndKeepsUnqualifiedFormalPlayLocked() {
        enterModes()
        capture("modes")
        clickReady("competition_formal_entry")
        waitForTag("competition_formal_summary")
        compose.onNodeWithTag("competition_formal_play").assertIsNotEnabled()
        capture("formal-locked")
        compose.onNodeWithText("返回").performScrollTo().performClick()
        waitForTag(COMPETITION_MAHJONG_MODES_TAG)
        enterLearning()
        waitForCard(CompetitionMahjongLearningLibrary.chapters.first().cards.first())
        compose.onNodeWithTag(nextTag).performScrollTo().assertIsNotEnabled()
        capture("first-card")
        compose.onNodeWithText("返回").performClick()
        compose.onNodeWithTag(COMPETITION_MAHJONG_MODES_TAG).assertExists()
        compose.onNodeWithTag(COMPETITION_MAHJONG_MODES_BACK_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(HOME_COMPETITION_MAHJONG_TAG).assertExists()
        assertNull(progress())
        assertOtherGamesUntouched()
    }

    @Test fun qualifiedFormalNavigationSavesARealMoveAndReopensTheSameMatch() {
        runBlocking {
            val store = RoomCompetitionMahjongLearningProofStore(database, database.competitionMahjongLearningProofDao())
            for (chapter in CompetitionMahjongLearningProofCatalog.chapterIds) {
                store.confirm(CompetitionMahjongLearningProofRequest(UUID.randomUUID().toString(), chapter,
                    CompetitionMahjongLearningProofCatalog.cardIds(chapter), 1))
            }
        }
        enterModes()
        clickReady("competition_formal_entry")
        waitForTag("competition_formal_summary")
        capture("formal-unlocked")
        clickReady("competition_formal_statistics")
        waitForTag("competition_mahjong_statistics_empty")
        capture("formal-statistics")
        compose.onNodeWithText("返回").performScrollTo().performClick()
        clickReady("competition_formal_history")
        waitForTag("competition_mahjong_history_empty")
        capture("formal-history")
        compose.onNodeWithText("返回").performScrollTo().performClick()

        clickReady("competition_formal_play")
        waitForTag("competition_mahjong_action_bar")
        val start = checkNotNull(activeMatch())
        val discard = hasText("出 ", substring = true) and hasClickAction() and isEnabled()
        compose.waitUntil(10_000) { compose.onAllNodes(discard).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("competition_mahjong_local_tiles").assertIsDisplayed()
        capture("formal-table")
        compose.onNodeWithTag("competition_mahjong_action_bar").performScrollTo()
        capture("formal-actions")
        compose.onAllNodes(discard).onFirst().performScrollTo().performClick()
        compose.waitUntil(10_000) { activeMatch()?.engineRecord?.contentEquals(start.engineRecord) == false }
        compose.onNodeWithText("返回").performScrollTo().performClick()
        waitForTag("competition_formal_summary")
        clickReady("competition_formal_play")
        waitForTag("competition_mahjong_table")
        assertEquals(start.config.matchId, activeMatch()?.config?.matchId)
        capture("formal-resumed")
        assertOtherGamesUntouched()
    }

    @Test fun legacyArchiveCanBeCancelledThenConfirmedWithoutDeletingOldMatch() {
        val config = CompetitionMahjongCompetitionConfig(UUID.randomUUID().toString(), 321, botLevel = 0)
        val checkpoint = NativeCompetitionMahjongSession(config.seed, 0, 0, 0).use {
            CompetitionMahjongMatchCheckpoint(config, CompetitionMahjongMatchLedger.empty(), 0, it.serialize())
        }
        val bytes = CompetitionMahjongMatchCheckpointCodec.encode(checkpoint)
        runBlocking {
            database.competitionMahjongMatchDao().upsert(CompetitionMahjongMatchArchive(0, config.matchId, 1, bytes).toEntity())
        }
        enterModes()
        clickReady("competition_formal_entry")
        waitForTag("competition_formal_legacy_archive")
        capture("legacy-archive-offer", useSystemScreenshot = true)
        compose.onNodeWithText("取消").performClick()
        waitForTag(COMPETITION_MAHJONG_MODES_TAG)
        assertTrue(runBlocking { database.withTransaction { database.competitionMahjongFormalRoomDao().listViewerIds() } }.isEmpty())

        clickReady("competition_formal_entry")
        waitForTag("competition_formal_legacy_archive")
        compose.onNodeWithText("保留旧档").performClick()
        waitForTag("competition_formal_summary")
        compose.onNodeWithTag("competition_formal_play").assertIsNotEnabled()
        capture("legacy-archive-confirmed")
        assertNull(activeMatch())
        assertArrayEquals(bytes, runBlocking { database.competitionMahjongMatchDao().find(2) }!!.checkpoint)
    }

    @Test fun completedSeriesHistoryCanBeViewedFavoritedAndDeletedThroughProductionNavigation() {
        val matchId = runBlocking { createCompletedSeries() }
        enterModes()
        clickReady("competition_formal_entry")
        waitForTag("competition_formal_summary")
        clickReady("competition_formal_history")
        waitForTag("competition_mahjong_history_$matchId")
        compose.onNodeWithText("查看").performScrollTo().performClick()
        waitForTag("competition_mahjong_history_detail")
        compose.onNodeWithTag("competition_mahjong_history_hand_15").performScrollTo().assertIsDisplayed()
        compose.onNode(hasText("玩家1 0分", substring = true) and
            hasAnyAncestor(hasTestTag("competition_mahjong_history_hand_15"))).assertIsDisplayed()
        capture("formal-history-last-hand")
        compose.onNodeWithText("收藏").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("取消收藏") and isEnabled()).fetchSemanticsNodes().size == 1 }
        capture("formal-history-favorite")
        compose.onNodeWithText("删除").performScrollTo().performClick()
        waitForTag("competition_mahjong_history_confirm_delete")
        compose.onNodeWithText("取消").performClick()
        waitForTag("competition_mahjong_history_detail")
        compose.onNodeWithText("删除").performScrollTo().performClick()
        compose.onNodeWithTag("competition_mahjong_history_confirm_delete").performClick()
        waitForTag("competition_mahjong_history_empty")
        assertNotNull(activeMatch())
        compose.onNodeWithText("返回").performScrollTo().performClick()
        clickReady("competition_formal_statistics")
        waitForTag("competition_mahjong_statistics_series_$matchId")
        compose.onNodeWithTag("competition_mahjong_statistics_series_$matchId").performScrollTo().assertIsDisplayed()
        capture("formal-statistics-after-history-delete")
    }

    private suspend fun createCompletedSeries(): String {
        val learning = RoomCompetitionMahjongLearningProofStore(database, database.competitionMahjongLearningProofDao())
        for (chapter in CompetitionMahjongLearningProofCatalog.chapterIds) {
            learning.confirm(CompetitionMahjongLearningProofRequest(UUID.randomUUID().toString(), chapter,
                CompetitionMahjongLearningProofCatalog.cardIds(chapter), 1))
        }
        val config = CompetitionMahjongCompetitionConfig(UUID.randomUUID().toString(), 20261009, botLevel = 0)
        val host = CompetitionMahjongFormalHost(database, 0)
        val session = (host.open() as CompetitionMahjongFormalHost.Entry.Ready).session
        val usage = session.policy.freshUsage(config.botLevel)
        val store = object : CompetitionMahjongMatchStore {
            override suspend fun load(viewerPlayerId: Int) = session.match.load(viewerPlayerId)
            override suspend fun save(checkpoint: CompetitionMahjongMatchCheckpoint) =
                session.match.save(checkpoint.withAssistance(usage))
        }
        val controller = CompetitionMahjongMatchController(config, store)
        try {
            assertTrue(controller.open())
            for (index in 0 until 16) {
                finishCompetitionMahjongTestHand(controller, index)
                if (index < 15) assertTrue(controller.nextHand(index))
            }
            assertEquals(config.matchId, session.history.load(0).entries.single().matchId)
            return config.matchId
        } finally {
            controller.close()
            host.close()
        }
    }

    @Test fun allTwentyNineStepsCommitFourChaptersBeforeCompletionCallback() {
        enterModes(); enterLearning()
        val chapters = CompetitionMahjongLearningLibrary.chapters
        assertEquals(29, chapters.sumOf { it.cards.size })
        chapters.forEachIndexed { index, chapter ->
            chapter.cards.forEach(::solveAndAdvance)
            compose.waitUntil(15_000) { progress()?.completedChapterCount == index + 1 }
            assertEquals(index == chapters.lastIndex, progress()?.isCompleted)
        }
        compose.onNodeWithTag("competition_mahjong_learning_complete").assertIsEnabled()
        compose.waitUntil(5_000) {
            val chip = compose.onNodeWithTag("competition_mahjong_learning_chapter_3").getUnclippedBoundsInRoot()
            val row = compose.onNodeWithTag("competition_mahjong_learning_chapters").getUnclippedBoundsInRoot()
            chip.left >= row.left && chip.right <= row.right
        }
        capture("committed")
        compose.onNodeWithTag("competition_mahjong_learning_complete").performClick()
        compose.onNodeWithTag(COMPETITION_MAHJONG_MODES_TAG).assertExists()
        val saved = checkNotNull(progress())
        assertTrue(saved.isCompleted)
        enterLearning()
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasTestTag("competition_mahjong_learning_complete")).fetchSemanticsNodes().isNotEmpty()
        }
        assertArrayEquals(CompetitionMahjongLearningProofCodec.encode(saved),CompetitionMahjongLearningProofCodec.encode(checkNotNull(progress())))
        assertOtherGamesUntouched()
    }

    @Test fun sqlSaveFailureWithholdsCompletionAndRetryCommitsOnlyPassedChapter() {
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_mahjong_lesson BEFORE INSERT ON competition_mahjong_learning_proof " +
                "BEGIN SELECT RAISE(ABORT, 'injected lesson save failure'); END",
        )
        enterModes(); enterLearning()
        CompetitionMahjongLearningLibrary.chapters.first().cards.forEach(::solveAndAdvance)
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasTestTag("competition_mahjong_learning_retry")).fetchSemanticsNodes().isNotEmpty()
        }
        assertNull(progress())
        compose.onNodeWithTag("competition_mahjong_learning_complete").assertDoesNotExist()
        // Include the recovery action in the evidence, not only its error message.
        compose.onNodeWithTag("competition_mahjong_learning_retry").performScrollTo().assertIsDisplayed()
        capture("save-failed")
        database.openHelper.writableDatabase.execSQL("DROP TRIGGER reject_mahjong_lesson")
        compose.onNodeWithTag("competition_mahjong_learning_retry").performScrollTo().performClick()
        compose.waitUntil(15_000) { progress()?.completedChapterCount == 1 }
        assertFalse(checkNotNull(progress()).isCompleted)
        assertOtherGamesUntouched()
    }

    @Test fun oldSharedCompletedRowKeepsHistoryButStartsTheDedicatedCourseAtZero() = runBlocking {
        val legacy = TutorialProgressEntity(GameType.STANDARD_MAHJONG.code,1,4,true,7L)
        database.tutorialProgressDao().upsert(legacy)
        enterModes(); enterLearning()
        waitForCard(CompetitionMahjongLearningLibrary.chapters.first().cards.first())
        compose.onNodeWithTag("competition_mahjong_learning_complete").assertDoesNotExist()
        assertNull(progress())
        assertEquals(legacy,database.tutorialProgressDao().find(GameType.STANDARD_MAHJONG.code))
        capture("legacy-does-not-unlock")
        assertOtherGamesUntouched()
    }

    @Test fun dedicatedConfirmedProofReopensFinalChapterWithoutChangingReceipts() = runBlocking {
        // QA precondition only; actual29-card interaction is exercised by the full-course test.
        val store = RoomCompetitionMahjongLearningProofStore(database,database.competitionMahjongLearningProofDao())
        CompetitionMahjongLearningProofCatalog.chapterIds.forEachIndexed { index,chapter ->
            store.confirm(CompetitionMahjongLearningProofRequest(UUID.randomUUID().toString(),chapter,
                CompetitionMahjongLearningProofCatalog.cardIds(chapter),100L+index))
        }
        val saved = CompetitionMahjongLearningProofCodec.encode(checkNotNull(progress()))
        enterModes(); enterLearning()
        waitForCard(CompetitionMahjongLearningLibrary.chapters.last().cards.first())
        compose.onNodeWithTag("competition_mahjong_learning_complete").assertIsEnabled()
        capture("completed-reopen")
        compose.onNodeWithTag("competition_mahjong_learning_complete").performClick()
        compose.onNodeWithTag(COMPETITION_MAHJONG_MODES_TAG).assertExists()
        assertArrayEquals(saved,CompetitionMahjongLearningProofCodec.encode(checkNotNull(progress())))
        assertOtherGamesUntouched()
    }
    private fun enterModes() {
        compose.onNodeWithTag(HOME_COMPETITION_MAHJONG_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(COMPETITION_MAHJONG_MODES_TAG).assertExists()
    }

    private fun waitForTag(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().size == 1 }
    }

    private fun activeMatch() = runBlocking {
        database.withTransaction {
            val bytes = CompetitionMahjongFormalRoomStorage.readMctxInTransaction(
                database, database.competitionMahjongFormalRoomDao(), 0,
            )
            bytes?.let { CompetitionMahjongFormalSnapshotCodec.decode(it, 0)?.active }
        }
    }

    private fun enterLearning() {
        compose.onNodeWithTag(COMPETITION_MAHJONG_LEARNING_ENTRY_TAG).performScrollTo().performClick()
        compose.onNodeWithTag("competition_mahjong_learning").assertExists()
    }

    private fun waitForCard(card: MahjongLessonCard) {
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasTestTag(cardTag) and hasText(card.title)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun solveAndAdvance(card: MahjongLessonCard) {
        waitForCard(card)
        when (val task = card.task) {
            MahjongLessonTask.Reading -> clickReady("competition_mahjong_learning_read")
            is MahjongLessonTask.Choice -> {
                val tag = "competition_mahjong_learning_option_${task.correctIndex}"
                clickReady(tag)
                compose.waitUntil(10_000) {
                    compose.onAllNodes(hasTestTag(tag) and isSelected()).fetchSemanticsNodes().isNotEmpty()
                }
                clickReady("competition_mahjong_learning_submit")
            }
            is MahjongLessonTask.Tiles -> {
                // A tile's nearest scroller is horizontal; expose its row vertically first.
                compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.HorizontalScrollAxisRange) and
                    hasAnyDescendant(hasTestTag("competition_mahjong_learning_tile_0")))
                    .performScrollTo()
                task.acceptedSelections.first().forEach { index ->
                    val tag = "competition_mahjong_learning_tile_$index"
                    clickReady(tag)
                    compose.waitUntil(10_000) {
                        compose.onAllNodes(hasTestTag(tag) and isSelected()).fetchSemanticsNodes().isNotEmpty()
                    }
                }
                clickReady("competition_mahjong_learning_submit")
            }
        }
        compose.waitUntil(10_000) {
            val nodes = compose.onAllNodes(hasTestTag(nextTag)).fetchSemanticsNodes()
            nodes.size == 1 && !nodes.single().config.contains(SemanticsProperties.Disabled)
        }
        compose.onNodeWithTag(nextTag).performScrollTo().performClick()
    }

    private fun clickReady(tag: String) {
        // IO-backed actions can publish a selection/card before their finally block enables input.
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().size == 1
        }
        compose.onNodeWithTag(tag).performScrollTo().assertIsEnabled().performClick()
    }

    private fun progress(): CompetitionMahjongLearningProof? = runBlocking {
        database.competitionMahjongLearningProofDao().readBoundedPayload(8,4096)?.let(CompetitionMahjongLearningProofCodec::decode)
    }
    private fun assertOtherGamesUntouched() = runBlocking {
        GameType.entries.filter { it != GameType.STANDARD_MAHJONG }.forEach {
            assertNull(database.tutorialProgressDao().find(it.code))
        }
        assertTrue(database.gameRecordDao().listAll().isEmpty())
        assertTrue(database.matchOutcomeDao().listAll().isEmpty())
    }

    private fun capture(name: String, useSystemScreenshot: Boolean = false) {
        compose.waitForIdle()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "competition-navigation-qa/$runId")
        assertTrue(directory.isDirectory || directory.mkdirs())
        val bitmap = if (useSystemScreenshot) checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            else compose.onRoot().captureToImage().asAndroidBitmap()
        try {
            assertTrue("Production Activity must render landscape", bitmap.width > bitmap.height)
            File(directory, "$name.png").outputStream().use {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }
}
