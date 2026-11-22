package com.masterofchessstrategy.games.competition_mahjong.challenge

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.games.competition_mahjong.competitionMahjongTileName
import com.masterofchessstrategy.settings.LocalDataWriterRegistry
import com.masterofchessstrategy.settings.awaitLocalDataWriters
import com.masterofchessstrategy.ui.GoLocalDataCheckActivity
import com.masterofchessstrategy.ui.HOME_COMPETITION_MAHJONG_TAG
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Each run owns a disk database. Host orchestration selects normal or large system font. */
class WmoExclusionTrainingInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<GoLocalDataCheckActivity>()
    private val database get() = compose.activity.ownedDatabase
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val images by lazy {
        File(context.getExternalFilesDir(null), "competition-wmo-exclusion-qa/${UUID.randomUUID()}")
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

    @Test fun allGreenAllowsHalfFlushAndWrongChoiceCannotEarnReward() {
        val hand = CompetitionMahjongPracticePosition.verifyAllGreen()
        assertEquals(listOf(10, 11, 11, 11, 12, 14, 14, 14, 16, 16, 16, 32, 32, 32), hand)
        exercise(CompetitionMahjongPracticePosition.ALL_GREEN_PUZZLE_ID, "b", "a", 32, 3,
            "可以加计混一色 6 番", 16, 770)
    }

    @Test fun terminalsRetainsBothIndependentDoublePungsAndRewardsOnce() {
        val hand = CompetitionMahjongPracticePosition.verifyTerminals()
        assertEquals(listOf(0, 0, 0, 8, 8, 8, 9, 9, 9, 17, 17, 17, 18, 18), hand)
        exercise(CompetitionMahjongPracticePosition.TERMINALS_PUZZLE_ID, "b", "c", 18, 2,
            "各计 2 番，共 4 番", 17, 850)
    }

    private fun exercise(id: String, wrong: String, correct: String, tile: Int, tileCount: Int,
        explanation: String, stars: Int, score: Int) {
        val store = RoomCompetitionMahjongChallengeProgress(database)
        val before = CompetitionMahjongChallengeLibrary.challenges.takeWhile { it.id != id }.map { it.id }.toSet()
        runBlocking {
            before.forEach { store.complete(it) }
            store.select(id)
        }
        compose.onNodeWithTag(HOME_COMPETITION_MAHJONG_TAG).performScrollTo().performClick()
        compose.onNodeWithTag("competition_challenge_entry").performScrollTo().performClick()
        waitFor("competition_mahjong_challenge_hand")
        compose.onNodeWithTag("competition_mahjong_challenge_hand").performScrollTo().assertIsDisplayed()
        compose.onAllNodesWithContentDescription(competitionMahjongTileName(tile), useUnmergedTree = true)
            .assertCountEquals(tileCount)
        compose.onAllNodesWithContentDescription("牌背", useUnmergedTree = true).assertCountEquals(0)
        capture("$id-hand")
        compose.onNodeWithTag("competition_mahjong_challenge_choice_$wrong").performScrollTo().performClick()
        compose.onNodeWithTag("competition_mahjong_challenge_completed").assertDoesNotExist()
        assertEquals(before, runBlocking { store.load().completedIds })

        compose.onNodeWithTag("competition_mahjong_challenge_choice_$correct").performScrollTo().performClick()
        waitFor("competition_mahjong_challenge_completed")
        compose.onNodeWithTag("competition_mahjong_challenge_feedback").performScrollTo()
            .assertTextContains(explanation, substring = true)
        compose.onNodeWithTag("competition_mahjong_challenge_rewards").performScrollTo()
            .assertTextEquals("训练奖励 $stars 星 · $score 分")
        assertEquals(before + id, runBlocking { store.load().completedIds })
        assertEquals(before + id, runBlocking { store.complete(id).completedIds })
        assertTrue(runBlocking { database.matchOutcomeDao().listAll().isEmpty() })
        capture("$id-scoring")

        compose.activityRule.scenario.recreate()
        waitFor("competition_mahjong_challenge_hand")
        assertEquals(id, runBlocking { store.load().lastChallengeId })
        compose.onNodeWithTag("competition_mahjong_challenge_rewards").performScrollTo()
            .assertTextEquals("训练奖励 $stars 星 · $score 分")
    }

    private fun waitFor(tag: String) = compose.waitUntil(15_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        File(images, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
