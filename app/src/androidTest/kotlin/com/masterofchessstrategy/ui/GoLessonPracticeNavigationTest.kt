package com.masterofchessstrategy.ui

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.SystemClock
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

    @Test fun localGamePlaysSavedMovesAndHonorsGlobalMuteAfterReentry() {
        fun audio() = ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("dumpsys audio"),
        ).bufferedReader().use { it.readText() }
        val pattern = Regex("new player piid:(\\d+)[^\\r\\n]*package:com\\.masterofchessstrategy[^\\r\\n]*")
        fun players() = pattern.findAll(audio()).map { it.groupValues[1] }.toSet()
        fun open(): String {
            val before = players()
            compose.onNodeWithTag(HOME_GO_TAG).performScrollTo().performClick()
            compose.onNodeWithTag(GO_LOCAL_ENTRY_TAG).performScrollTo().performClick()
            awaitTag("go_phase")
            compose.waitUntil(5_000) { (players() - before).size == 1 }
            return (players() - before).single()
        }
        fun awaitTurn(text: String) {
            compose.waitUntil(10_000) {
                compose.onAllNodes(hasTestTag("go_phase") and hasText(text)).fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodesWithText("正在保存对局…").fetchSemanticsNodes().isEmpty()
            }
        }
        val first = open()
        awaitTurn("黑方行棋")
        compose.onNodeWithTag("go_intersection_0_0").performClick()
        awaitTurn("白方行棋")
        compose.waitUntil(5_000) { "player piid:$first event:started" in audio() }
        capture("local-go-sound")
        compose.onNodeWithText("返回").performScrollTo().performClick()
        compose.waitUntil(5_000) { "releasing player piid:$first" in audio() }
        compose.onNodeWithTag(GO_MODES_BACK_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(HOME_SETTINGS_TAG).performScrollTo().performClick()
        val setting = compose.onNodeWithTag(SETTINGS_SOUND_TAG)
        setting.performScrollTo().assertIsOn().performClick()
        compose.waitUntil(5_000) { runBlocking { database.appSettingsDao().find()?.soundEnabled } == false }
        setting.assertIsOff()
        compose.onNodeWithText("返回").performScrollTo().performClick()
        val second = open()
        awaitTurn("白方行棋")
        compose.onNodeWithTag("go_intersection_1_0").performClick()
        awaitTurn("黑方行棋")
        SystemClock.sleep(350)
        assertFalse("player piid:$second event:started" in audio())
        capture("local-go-muted")
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

    @Test fun counterCaptureUnlocksDoubleConnectionAndAlternateAnswerPersists() {
        val puzzles = GoEndgameLibrary.puzzles
        val escape = checkNotNull(GoEndgameLibrary.puzzle("corner-counter-capture"))
        val connection = checkNotNull(GoEndgameLibrary.puzzle("connect-between-white-groups"))
        val preceding = puzzles.takeWhile { it.id != escape.id }.map { it.id }
        val repository = RoomGoEndgameProgressRepository(database)
        runBlocking {
            preceding.forEach { repository.complete(it) }
            repository.select(escape.id)
        }
        compose.onNodeWithTag(HOME_GO_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(GO_ENDGAMES_ENTRY_TAG).performScrollTo().performClick()
        awaitTitle(escape.title)
        compose.onNodeWithTag("go_intersection_1_1").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasTestTag("go_endgame_feedback") and hasText("没有完成本题目标", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(preceding.toSet(), runBlocking { repository.load().completedIds })
        capture("escape-direct-extension-rejected")
        compose.onNodeWithTag("go_intersection_0_1").performClick()
        awaitTag("go_endgame_completed")
        compose.onNodeWithTag("go_endgame_completed").performScrollTo().assertIsDisplayed()
        capture("escape-counter-capture")

        compose.onNodeWithText("下一关").performScrollTo().performClick()
        awaitTitle(connection.title)
        compose.onNodeWithTag("go_intersection_9_9").performClick()
        awaitTag("go_endgame_completed")
        compose.onNodeWithTag("go_endgame_completed").performScrollTo().assertIsDisplayed()
        capture("connection-alternate-answer")

        compose.onNodeWithTag("go_endgame_back").performScrollTo().performClick()
        compose.onNodeWithTag(GO_ENDGAMES_ENTRY_TAG).performScrollTo().performClick()
        awaitTitle(connection.title)
        assertEquals(preceding.toSet() + escape.id + connection.id, runBlocking { repository.load().completedIds })
        assertTrue(runBlocking { database.matchOutcomeDao().listAll().isEmpty() })
    }

    @Test fun firstClearRewardsReachProfileOnceAndSurviveRecreation() {
        compose.onNodeWithTag(HOME_GO_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(GO_ENDGAMES_ENTRY_TAG).performScrollTo().performClick()
        awaitTitle("角上一口气")
        val rewards = compose.onNodeWithTag("go_endgame_rewards")
        rewards.performScrollTo().assertTextEquals("残局奖励 0 星 · 0 分")
        compose.onNodeWithTag("go_intersection_0_1").performClick()
        awaitTag("go_endgame_completed")
        rewards.performScrollTo().assertTextEquals("残局奖励 1 星 · 10 分")
        capture("go-first-clear-reward")

        compose.onNodeWithText("重置本关").performScrollTo().performClick()
        compose.waitUntil(15_000) { compose.onAllNodesWithTag("go_endgame_completed").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithTag("go_intersection_0_1").performClick()
        awaitTag("go_endgame_completed")
        rewards.performScrollTo().assertTextEquals("残局奖励 1 星 · 10 分")
        assertTrue(runBlocking { database.matchOutcomeDao().listAll().isEmpty() })
        capture("go-repeat-clear-reward")

        compose.onNodeWithTag("go_endgame_back").performScrollTo().performClick()
        awaitTag(GO_ENDGAMES_ENTRY_TAG)
        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitTag(HOME_PROFILE_TAG)
        compose.onNodeWithTag(HOME_PROFILE_TAG).performScrollTo().performClick()
        fun checkProfile() {
            compose.waitUntil(15_000) {
                compose.onAllNodesWithText("累计星级 1 · 累计积分 10").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText("累计星级 1 · 累计积分 10").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("总胜场：0").performScrollTo().assertIsDisplayed()
        }
        checkProfile()
        compose.activityRule.scenario.recreate()
        checkProfile()
        capture("go-profile-reward")
    }

    @Test fun masterPuzzleRequiresThreeMovesAndAwardsEightyPointsOnce() {
        val puzzle = checkNotNull(GoEndgameLibrary.puzzle("corner-liberty-race"))
        val preceding = GoEndgameLibrary.puzzles.takeWhile { it.id != puzzle.id }.map { it.id }.toSet()
        val repository = RoomGoEndgameProgressRepository(database)
        runBlocking {
            preceding.forEach { repository.complete(it) }
            repository.select(puzzle.id)
        }
        compose.onNodeWithTag(HOME_GO_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(GO_ENDGAMES_ENTRY_TAG).performScrollTo().performClick()
        awaitTitle(puzzle.title)
        val rewards = compose.onNodeWithTag("go_endgame_rewards")
        rewards.performScrollTo().assertTextEquals("残局奖励 9 星 · 200 分")
        capture("master-initial")
        for ((point, remaining) in listOf("0_1" to 2, "8_7" to 1)) {
            compose.onNodeWithTag("go_intersection_$point").performClick()
            compose.waitUntil(15_000) {
                compose.onAllNodes(hasTestTag("go_endgame_feedback") and hasText("剩余 $remaining 手", substring = true))
                    .fetchSemanticsNodes().isNotEmpty()
            }
            assertEquals(preceding, runBlocking { repository.load().completedIds })
        }
        compose.onNodeWithTag("go_endgame_feedback").performScrollTo().assertIsDisplayed()
        capture("master-second-move")
        compose.onNodeWithTag("go_intersection_7_8").performClick()
        awaitTag("go_endgame_completed")
        rewards.performScrollTo().assertTextEquals("残局奖励 10 星 · 280 分")
        assertEquals(preceding + puzzle.id, runBlocking { repository.load().completedIds })
        assertTrue(runBlocking { database.matchOutcomeDao().listAll().isEmpty() })
        capture("master-first-clear")
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
