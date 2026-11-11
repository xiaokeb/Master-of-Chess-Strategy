package com.masterofchessstrategy.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
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

/** Real Root/Room/native navigation. Activity recreation is deliberately not process-death proof. */
class GoLessonPracticeNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<GoLocalDataCheckActivity>()
    private val database get() = compose.activity.ownedDatabase
    private val imageDirectory by lazy {
        File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "go-lesson-practice-qa/${UUID.randomUUID()}").apply { check(mkdirs()) }
    }

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
        assertTrue(legacy.listFiles()!!.isEmpty())
        assertTrue(legacy.delete())
    }

    @Test fun lockedPracticeExplainsPrerequisitesAndReturnsWithoutCompletingTheLesson() {
        openLessons()
        val learning = RoomGoLearningProgressRepository(database)
        val endgames = RoomGoEndgameProgressRepository(database)
        val beforeLearning = runBlocking { learning.load() }
        val beforeEndgames = runBlocking { GoEndgameProgressCodec.encode(endgames.load()) }
        compose.onNodeWithTag("go_lesson_practice").performScrollTo().performClick()
        awaitTag("go_endgame_practice_locked")
        compose.onNodeWithTag("go_endgame_practice_locked").performScrollTo()
            .assertTextContains("角上一口气", substring = true)
        assertArrayEquals(beforeEndgames, runBlocking { GoEndgameProgressCodec.encode(endgames.load()) })
        capture("locked-prerequisite")
        compose.onNodeWithTag("go_endgame_required_mainline").performScrollTo().performClick()
        awaitTitle("角上一口气")
        compose.onNodeWithTag("go_intersection_0_1").performClick()
        awaitTag("go_endgame_completed")
        compose.waitUntil(15_000) { runBlocking { "corner-capture" in endgames.load().completedIds } }
        capture("prerequisite-completed")
        compose.onNodeWithTag("go_endgame_back").performScrollTo().performClick()
        awaitLesson("寻找棋子的气")
        assertEquals(beforeLearning, runBlocking { learning.load() })
        assertTrue(runBlocking { database.matchOutcomeDao().listAll().isEmpty() })
        assertNull(runBlocking { database.tutorialProgressDao().find(GameType.GO.code) })
        capture("original-lesson-returned")
    }

    @Test fun correspondingCaptureAndActivityRecreationPreserveTheOriginalCourseEntry() {
        openLessons()
        compose.onNodeWithText("知识点目录").performScrollTo().performClick()
        compose.onNodeWithText("提走最后一口气").performScrollTo().performClick()
        awaitLesson("提走最后一口气")
        compose.waitUntil(15_000) { runBlocking {
            RoomGoLearningProgressRepository(database).load().lastLessonId == "capture"
        } }
        val before = runBlocking { RoomGoLearningProgressRepository(database).load() }
        compose.onNodeWithTag("go_lesson_practice").performScrollTo().performClick()
        awaitTitle("角上一口气")
        compose.onNodeWithTag("go_endgame_source_lesson").performScrollTo()
            .assertTextContains("提走最后一口气", substring = true)
        capture("corresponding-capture")
        compose.activityRule.scenario.recreate()
        awaitTitle("角上一口气")
        compose.onNodeWithTag("go_endgame_source_lesson").performScrollTo()
            .assertTextContains("提走最后一口气", substring = true)
        capture("activity-recreated")
        compose.onNodeWithTag("go_endgame_back").performScrollTo().performClick()
        awaitLesson("提走最后一口气")
        assertEquals(before, runBlocking { RoomGoLearningProgressRepository(database).load() })
        assertTrue(runBlocking { RoomGoEndgameProgressRepository(database).load().completedIds.isEmpty() })
        capture("capture-lesson-returned")
    }

    @Test fun allThreeLinkedExercisesUseTheActualProductionRulesAndReachTheirGoals() {
        val links = GoLearningLibrary.lessons.mapNotNull { GoLessonPracticeLink.forLesson(it.id) }
        assertEquals(3, links.size)
        links.forEach { link ->
            val puzzle = checkNotNull(GoEndgameLibrary.puzzle(link.puzzleId))
            val plan = GoEndgameLibrary.open(puzzle)
            val session = GoEndgameSession(plan)
            assertFalse(session.state().completed)
            assertTrue(session.answer(puzzle.answer).completed)
        }
        assertTrue(runBlocking { database.goProgressDao().listAll().isEmpty() })
        assertTrue(runBlocking { database.matchOutcomeDao().listAll().isEmpty() })
    }

    @Test fun twoMoveCaptureCompletesOnlyAfterBothTargetsAndPersistsProgress() {
        val puzzles = GoEndgameLibrary.puzzles
        val tactical = checkNotNull(GoEndgameLibrary.puzzle("two-corners-two-moves"))
        val preceding = puzzles.takeWhile { it.id != tactical.id }.map { it.id }
        val repository = RoomGoEndgameProgressRepository(database)
        runBlocking { preceding.forEach { repository.complete(it) } }
        compose.onNodeWithTag(HOME_GO_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(GO_ENDGAMES_ENTRY_TAG).performScrollTo().performClick()
        awaitTitle(puzzles.first().title)
        compose.onNodeWithTag("go_endgame_catalog").performScrollTo().performClick()
        compose.onNodeWithTag("go_endgame_puzzle_${tactical.id}").performScrollTo().performClick()
        awaitTitle(tactical.title)

        compose.onNodeWithTag("go_intersection_0_1").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasTestTag("go_endgame_feedback") and hasText("剩余 1 手", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("go_endgame_completed").assertDoesNotExist()
        assertEquals(preceding.toSet(), runBlocking { repository.load().completedIds })
        capture("tactical-first-capture")

        compose.onNodeWithTag("go_intersection_8_7").performClick()
        awaitTag("go_endgame_completed")
        val completed = preceding.toSet() + tactical.id
        assertEquals(completed, runBlocking { repository.load().completedIds })
        capture("tactical-completed")

        compose.onNodeWithTag("go_endgame_back").performScrollTo().performClick()
        compose.onNodeWithTag(GO_ENDGAMES_ENTRY_TAG).performScrollTo().performClick()
        awaitTitle(tactical.title)
        assertEquals(completed, runBlocking { repository.load().completedIds })
        assertTrue(runBlocking { database.matchOutcomeDao().listAll().isEmpty() })
        capture("tactical-reopened")
    }

    private fun openLessons() {
        compose.onNodeWithTag(HOME_GO_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(GO_LEARNING_ENTRY_TAG).performScrollTo().performClick()
        awaitLesson("寻找棋子的气")
    }
    private fun awaitTag(tag: String) {
        compose.waitUntil(15_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun awaitLesson(title: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasTestTag("go_lesson_title") and hasText(title, substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }
    private fun awaitTitle(title: String) {
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasTestTag("go_endgame_title") and hasText(title))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }
    private fun capture(label: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(imageDirectory, "$label.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
