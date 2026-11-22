package com.masterofchessstrategy.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.games.competition_mahjong.challenge.CompetitionMahjongChallengeLibrary
import com.masterofchessstrategy.games.competition_mahjong.challenge.CompetitionMahjongPracticePosition
import com.masterofchessstrategy.games.competition_mahjong.challenge.RoomCompetitionMahjongChallengeProgress
import com.masterofchessstrategy.games.competition_mahjong.competitionMahjongTileName
import com.masterofchessstrategy.settings.LocalDataWriterRegistry
import com.masterofchessstrategy.settings.awaitLocalDataWriters
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CompetitionMahjongChallengeNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<GoLocalDataCheckActivity>()
    private val database get() = compose.activity.ownedDatabase
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val images by lazy {
        File(context.getExternalFilesDir(null), "competition-challenge-navigation-qa/${UUID.randomUUID()}")
            .apply { check(mkdirs()) }
    }

    @After fun cleanup() {
        val db = database
        val name = compose.activity.ownedDatabaseName
        val legacy = compose.activity.ownedLegacyDirectory
        compose.activityRule.scenario.close()
        runBlocking { awaitLocalDataWriters(LocalDataWriterRegistry.forDatabase(db).snapshot()) {} }
        GoLocalDataCheckActivity.closeOwnedDatabase(name)
        assertTrue(context.deleteDatabase(name))
        assertTrue(legacy.listFiles()!!.isEmpty() && legacy.delete())
    }

    @Test fun wrongAnswerKeepsNextLockedAndFirstClearReachesProfile() {
        val store = RoomCompetitionMahjongChallengeProgress(database)
        compose.onNodeWithTag(HOME_COMPETITION_MAHJONG_TAG).performScrollTo().performClick()
        compose.onNodeWithTag("competition_challenge_entry").performScrollTo().performClick()
        waitFor("competition_mahjong_challenge_title")
        val rewards = compose.onNodeWithTag("competition_mahjong_challenge_rewards")
        rewards.performScrollTo().assertTextEquals("训练奖励 0 星 · 0 分")
        compose.onNodeWithTag("competition_mahjong_challenge_choice_a").performScrollTo().performClick()
        compose.onNodeWithTag("competition_mahjong_challenge_next").assertDoesNotExist()
        assertTrue(runBlocking { store.load().completedIds.isEmpty() })
        capture("challenge-rejected")

        compose.onNodeWithTag("competition_mahjong_challenge_choice_b").performScrollTo().performClick()
        waitFor("competition_mahjong_challenge_completed")
        rewards.performScrollTo().assertTextEquals("训练奖励 1 星 · 10 分")
        capture("challenge-reward")
        compose.onNodeWithTag("competition_mahjong_challenge_next").performScrollTo().performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasTestTag("competition_mahjong_challenge_title") and hasText("最后一张自摸"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.activityRule.scenario.recreate()
        waitFor("competition_mahjong_challenge_title")
        assertEquals("last-wall-self-draw", runBlocking { store.load().lastChallengeId })
        assertEquals(setOf("seven-plus-flowers"), runBlocking { store.load().completedIds })
        assertTrue(runBlocking { database.matchOutcomeDao().listAll().isEmpty() })

        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        waitFor(COMPETITION_MAHJONG_MODES_TAG)
        compose.onNodeWithTag(COMPETITION_MAHJONG_MODES_BACK_TAG).performScrollTo().performClick()
        compose.onNodeWithTag(HOME_PROFILE_TAG).performScrollTo().performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("累计星级 1 · 累计积分 10").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("累计星级 1 · 累计积分 10").performScrollTo().assertIsDisplayed()
        capture("challenge-profile")
    }

    private fun waitFor(tag: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun nativeSevenPairsShowsOnlyTheLocalHandAndAwardsFirstClear() {
        assertEquals(14, CompetitionMahjongPracticePosition.verifySevenPairs().size)
        val store = RoomCompetitionMahjongChallengeProgress(database)
        val puzzleId = CompetitionMahjongPracticePosition.PUZZLE_ID
        val prerequisites = CompetitionMahjongChallengeLibrary.challenges.takeWhile { it.id != puzzleId }.map { it.id }
        runBlocking {
            prerequisites.forEach { store.complete(it) }
            store.select(puzzleId)
        }
        compose.onNodeWithTag(HOME_COMPETITION_MAHJONG_TAG).performScrollTo().performClick()
        compose.onNodeWithTag("competition_challenge_entry").performScrollTo().performClick()
        waitFor("competition_mahjong_challenge_hand")
        compose.onNodeWithTag("competition_mahjong_challenge_hand").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithContentDescription("东", useUnmergedTree = true).assertCountEquals(2)
        compose.onAllNodesWithContentDescription("牌背", useUnmergedTree = true).assertCountEquals(0)
        capture("native-local-hand")
        compose.onNodeWithTag("competition_mahjong_challenge_choice_b").performScrollTo().performClick()
        compose.onNodeWithTag("competition_mahjong_challenge_completed").assertDoesNotExist()
        assertEquals(prerequisites.toSet(), runBlocking { store.load().completedIds })
        capture("native-wrong-fan")
        compose.onNodeWithTag("competition_mahjong_challenge_choice_a").performScrollTo().performClick()
        waitFor("competition_mahjong_challenge_completed")
        compose.onNodeWithTag("competition_mahjong_challenge_feedback").performScrollTo()
            .assertTextContains("七对计 24 番", substring = true)
        assertEquals(prerequisites.toSet() + puzzleId, runBlocking { store.load().completedIds })
        assertTrue(runBlocking { database.matchOutcomeDao().listAll().isEmpty() })
        capture("native-verified-fan")
    }

    @Test fun discardTrainingReplaysEveryChoiceAndSavesOnlyAfterConfirmedWin() = verifyDiscardExercise(
        CompetitionMahjongPracticePosition.DISCARD_PUZZLE_ID, wrong = 27, correct = 31, draw = "东",
        resultText = "七对计 24 番", rewardText = "14 星 · 610 分",
    )

    @Test fun orphansTrainingRequiresUniqueDiscardAndConfirmsNativeWin() = verifyDiscardExercise(
        CompetitionMahjongPracticePosition.ORPHANS_DISCARD_ID, wrong = 0, correct = 4, draw = "白",
        resultText = "十三幺计 88 番", rewardText = "18 星 · 930 分",
    )

    private fun verifyDiscardExercise(
        puzzleId: String, wrong: Int, correct: Int, draw: String, resultText: String, rewardText: String,
    ) {
        val hand = CompetitionMahjongPracticePosition.discardOpeningHand(puzzleId)
        assertEquals(14, hand.size)
        val winningDiscards = hand.distinct().filter { CompetitionMahjongPracticePosition.discardAndDraw(it, id = puzzleId) != null }
        assertEquals(listOf(correct), winningDiscards)
        val store = RoomCompetitionMahjongChallengeProgress(database)
        val prerequisites = CompetitionMahjongChallengeLibrary.challenges.takeWhile { it.id != puzzleId }.map { it.id }.toSet()
        runBlocking {
            prerequisites.forEach { store.complete(it) }
            store.select(puzzleId)
        }
        compose.onNodeWithTag(HOME_COMPETITION_MAHJONG_TAG).performScrollTo().performClick()
        compose.onNodeWithTag("competition_challenge_entry").performScrollTo().performClick()
        waitFor("competition_mahjong_challenge_hand")
        compose.onNodeWithTag("competition_mahjong_challenge_discard_$wrong").performScrollTo().performClick()
        compose.onNodeWithTag("competition_mahjong_challenge_feedback").performScrollTo()
            .assertTextContains("无法和牌", substring = true)
        assertEquals(prerequisites, runBlocking { store.load().completedIds })
        capture("discard-rejected")

        compose.onNodeWithTag("competition_mahjong_challenge_discard_$correct").performScrollTo().performClick()
        waitFor("competition_mahjong_challenge_confirm_win")
        compose.onNodeWithText("本次摸牌 $draw").performScrollTo().assertIsDisplayed()
        assertEquals(prerequisites, runBlocking { store.load().completedIds })
        capture("discard-next-draw")

        compose.onNodeWithTag("competition_mahjong_challenge_confirm_win").performScrollTo().performClick()
        waitFor("competition_mahjong_challenge_completed")
        compose.onNodeWithTag("competition_mahjong_challenge_feedback").performScrollTo()
            .assertTextContains(resultText, substring = true)
        assertEquals(prerequisites + puzzleId, runBlocking { store.load().completedIds })
        assertTrue(runBlocking { database.matchOutcomeDao().listAll().isEmpty() })
        compose.onNodeWithTag("competition_mahjong_challenge_rewards").performScrollTo()
            .assertTextEquals("训练奖励 $rewardText")
        capture("discard-win")
    }

    @Test fun nativeNineGatesTeachesWmoExclusionAndSavesOneReward() {
        val hand = CompetitionMahjongPracticePosition.verifyNineGates()
        assertEquals(listOf(0, 0, 0, 1, 2, 3, 4, 4, 5, 6, 7, 8, 8, 8), hand)
        val store = RoomCompetitionMahjongChallengeProgress(database)
        val puzzle = CompetitionMahjongPracticePosition.NINE_GATES_PUZZLE_ID
        val before = CompetitionMahjongChallengeLibrary.challenges.takeWhile { it.id != puzzle }.map { it.id }.toSet()
        runBlocking {
            before.forEach { store.complete(it) }
            store.select(puzzle)
        }
        compose.onNodeWithTag(HOME_COMPETITION_MAHJONG_TAG).performScrollTo().performClick()
        compose.onNodeWithTag("competition_challenge_entry").performScrollTo().performClick()
        waitFor("competition_mahjong_challenge_hand")
        compose.onNodeWithTag("competition_mahjong_challenge_hand").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithContentDescription(competitionMahjongTileName(0), useUnmergedTree = true).assertCountEquals(3)
        compose.onAllNodesWithContentDescription("牌背", useUnmergedTree = true).assertCountEquals(0)
        capture("nine-gates-hand")

        compose.onNodeWithTag("competition_mahjong_challenge_choice_c").performScrollTo().performClick()
        compose.onNodeWithTag("competition_mahjong_challenge_completed").assertDoesNotExist()
        assertEquals(before, runBlocking { store.load().completedIds })

        compose.onNodeWithTag("competition_mahjong_challenge_choice_b").performScrollTo().performClick()
        waitFor("competition_mahjong_challenge_completed")
        compose.onNodeWithTag("competition_mahjong_challenge_feedback").performScrollTo()
            .assertTextContains("可以加计不求人 4 番", substring = true)
        compose.onNodeWithTag("competition_mahjong_challenge_rewards").performScrollTo()
            .assertTextEquals("训练奖励 15 星 · 690 分")
        assertEquals(before + puzzle, runBlocking { store.load().completedIds })
        assertTrue(runBlocking { database.matchOutcomeDao().listAll().isEmpty() })
        capture("nine-gates-scoring")
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(images, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
