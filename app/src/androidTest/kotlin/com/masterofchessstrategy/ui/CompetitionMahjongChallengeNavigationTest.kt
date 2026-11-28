package com.masterofchessstrategy.ui

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.engine.CompetitionMahjongRoundActionKind
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

    @Test fun wrongTileWhileReadyPaysTenPerOpponent() = verifyFalseWinReview(
        CompetitionMahjongPracticePosition.WRONG_TILE_ID, answer = "a", wrong = "b", points = 10, rewards = "22 星 · 1250 分",
    )

    @Test fun falseDeclarationBeforeTenpaiPaysTwentyPerOpponent() = verifyFalseWinReview(
        CompetitionMahjongPracticePosition.NOT_READY_ID, answer = "b", wrong = "a", points = 20, rewards = "23 星 · 1330 分",
    )

    private fun verifyFalseWinReview(id: String, answer: String, wrong: String, points: Int, rewards: String) {
        val store = RoomCompetitionMahjongChallengeProgress(database)
        val preceding = CompetitionMahjongChallengeLibrary.challenges.takeWhile { it.id != id }.map { it.id }.toSet()
        runBlocking {
            preceding.forEach { store.complete(it) }
            store.select(id)
        }
        compose.onNodeWithTag(HOME_COMPETITION_MAHJONG_TAG).performScrollTo().performClick()
        compose.onNodeWithTag("competition_challenge_entry").performScrollTo().performClick()
        waitFor("competition_mahjong_challenge_hand")
        compose.onNodeWithText("规则索引：竞赛规则第 3.11.6 节").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("competition_mahjong_challenge_choice_$wrong").performScrollTo().performClick()
        compose.onNodeWithTag("competition_mahjong_challenge_feedback").performScrollTo()
            .assertTextContains("先核对是否听牌", substring = true)
        assertEquals(preceding, runBlocking { store.load().completedIds })
        capture("false-win-rejected")

        compose.onNodeWithTag("competition_mahjong_challenge_choice_$answer").performScrollTo().performClick()
        waitFor("competition_mahjong_challenge_completed")
        compose.onNodeWithTag("competition_mahjong_challenge_rewards").performScrollTo()
            .assertTextEquals("训练奖励 $rewards")
        compose.onNodeWithTag("competition_mahjong_challenge_feedback").performScrollTo()
            .assertTextContains("三家各得 $points 分", substring = true)
        assertEquals(preceding + id, runBlocking { store.load().completedIds })
        assertTrue(runBlocking { database.matchOutcomeDao().listAll().isEmpty() })
        capture("false-win-explained")
    }

    @Test fun addedKongCanBePassedOrRobbedWithNativeSettlement() {
        val id = CompetitionMahjongPracticePosition.ROB_KONG_ID
        val store = RoomCompetitionMahjongChallengeProgress(database)
        val preceding = CompetitionMahjongChallengeLibrary.challenges.takeWhile { it.id != id }.map { it.id }.toSet()
        runBlocking {
            preceding.forEach { store.complete(it) }
            store.select(id)
        }
        compose.onNodeWithTag(HOME_COMPETITION_MAHJONG_TAG).performScrollTo().performClick()
        compose.onNodeWithTag("competition_challenge_entry").performScrollTo().performClick()
        waitFor("competition_mahjong_challenge_hand")
        compose.onNodeWithText("南家加杠 中").performScrollTo().assertIsDisplayed()
        capture("robbing-window")

        compose.onNodeWithTag("competition_mahjong_challenge_pass_kong").performScrollTo().performClick()
        compose.onNodeWithTag("competition_mahjong_challenge_feedback").performScrollTo()
            .assertTextContains("南家完成加杠并补牌", substring = true)
        assertEquals(preceding, runBlocking { store.load().completedIds })
        capture("robbing-pass")

        compose.onNodeWithTag("competition_mahjong_challenge_confirm_win").performScrollTo()
            .assertTextEquals("抢杠和").performClick()
        waitFor("competition_mahjong_challenge_completed")
        compose.onNodeWithTag("competition_mahjong_challenge_feedback").performScrollTo()
            .assertTextContains("抢杠和计 8 番，和绝张不叠加", substring = true)
        val settlement = CompetitionMahjongPracticePosition.robKong(CompetitionMahjongRoundActionKind.WIN)
        val fan = checkNotNull(settlement.totalFan)
        assertEquals(listOf(-8, -8 - fan, 24 + fan, -8), settlement.netScores)
        compose.onNodeWithTag("competition_mahjong_challenge_settlement").performScrollTo()
        for (label in listOf("本盘总番 $fan", "东 -8", "南 ${-8 - fan}", "西 +${24 + fan}", "北 -8")) {
            compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("competition_mahjong_challenge_settlement")))
                .assertIsDisplayed()
        }
        compose.onNodeWithTag("competition_mahjong_challenge_rewards").performScrollTo()
            .assertTextEquals("训练奖励 24 星 · 1410 分")
        assertEquals(preceding + id, runBlocking { store.load().completedIds })
        assertTrue(runBlocking { database.matchOutcomeDao().listAll().isEmpty() })
        capture("robbing-settled")
    }

    @Test fun concealedPungsTrainingClaimsTheDiscardAndShowsAsymmetricPayment() = verifyDiscardExercise(
        CompetitionMahjongPracticePosition.FOUR_CONCEALED_CLAIM_ID, wrong = 0, correct = 31, draw = "东",
        resultText = "四暗刻计 64 番", rewardText = "21 星 · 1170 分", claim = true,
    )

    private fun verifyDiscardExercise(
        puzzleId: String, wrong: Int, correct: Int, draw: String, resultText: String, rewardText: String, claim: Boolean = false,
        expectedFlowers: List<Int> = emptyList(),
    ) {
        val hand = CompetitionMahjongPracticePosition.discardOpeningHand(puzzleId)
        assertEquals(14, hand.size)
        val winningDiscards = hand.distinct().filter { CompetitionMahjongPracticePosition.playDiscard(it, puzzleId) != null }
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
        compose.onNodeWithText(if (claim) "南家弃牌 $draw" else "本次摸牌 $draw").performScrollTo().assertIsDisplayed()
        if (expectedFlowers.isNotEmpty()) {
            compose.onNodeWithTag("competition_mahjong_challenge_flowers").performScrollTo()
                .assertTextEquals("补花 春、夏")
        }
        compose.onNodeWithTag("competition_mahjong_challenge_confirm_win").assertTextEquals(if (claim) "点和" else "自摸")
        compose.onNodeWithTag("competition_mahjong_challenge_settlement").assertDoesNotExist()
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
        if (claim) {
            val result = checkNotNull(CompetitionMahjongPracticePosition.playDiscard(correct, puzzleId, settle = true))
            val fan = checkNotNull(result.totalFan)
            assertEquals(1, result.payerSeat)
            assertEquals(13, result.hand.size)
            assertTrue(fan >= 64)
            assertEquals(listOf(24 + fan, -8 - fan, -8, -8), result.netScores)
            compose.onNodeWithTag("competition_mahjong_challenge_settlement").performScrollTo()
            for (label in listOf("本盘总番 $fan", "东 +${24 + fan}", "南 ${-8 - fan}", "西 -8", "北 -8")) {
                compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("competition_mahjong_challenge_settlement")))
                    .assertIsDisplayed()
            }
        }
        if (expectedFlowers.isNotEmpty()) {
            val result = checkNotNull(CompetitionMahjongPracticePosition.playDiscard(correct, puzzleId, settle = true))
            assertEquals(expectedFlowers, result.flowers)
            assertNull(result.payerSeat)
            val qualifying = checkNotNull(result.qualifyingFan)
            val fan = checkNotNull(result.totalFan)
            assertEquals(qualifying + 2, fan)
            val each = 8 + fan
            assertEquals(listOf(3 * each, -each, -each, -each), result.netScores)
            val settlement = compose.onNodeWithTag("competition_mahjong_challenge_settlement")
            settlement.performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("计入起和 $qualifying 番 · 花牌 2 分").assertExists()
            for (label in listOf("本盘总番 $fan", "东 +${3 * each}", "南 -$each", "西 -$each", "北 -$each")) {
                compose.onNode(hasText(label) and hasAnyAncestor(hasTestTag("competition_mahjong_challenge_settlement"))).assertExists()
            }
            InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply {
                putInt("flower_training_qualifying", qualifying)
                putInt("flower_training_total", fan)
                putInt("flower_training_each_pays", each)
            })
        }
        capture("discard-win")
    }

    @Test fun pureStraightTrainingConfirmsItsNativeFan() = verifyDiscardExercise(
        CompetitionMahjongPracticePosition.PURE_STRAIGHT_DISCARD_ID, wrong = 0, correct = 32, draw = "5筒",
        resultText = "清龙计 16 番", rewardText = "19 星 · 1010 分",
    )

    @Test fun mixedTripleChowTrainingConfirmsItsNativeFan() = verifyDiscardExercise(
        CompetitionMahjongPracticePosition.MIXED_TRIPLE_CHOW_DISCARD_ID, wrong = 0, correct = 31, draw = "东",
        resultText = "三色三同顺计 8 番", rewardText = "20 星 · 1090 分",
    )

    @Test fun triplePungTrainingExcludesDoublePungsUnderCurrentScoring() {
        val id = CompetitionMahjongPracticePosition.TRIPLE_PUNG_DISCARD_ID
        val win = checkNotNull(CompetitionMahjongPracticePosition.playDiscard(31, id, settle = true))
        assertEquals(37, win.totalFan)
        assertEquals(listOf(135, -45, -45, -45), win.netScores)
        verifyDiscardExercise(id, wrong = 4, correct = 31, draw = "南",
            resultText = "三同刻计 16 番", rewardText = "25 星 · 1490 分")
    }

    @Test fun flowerReplacementTrainingShowsTheTwoFlowersAndSettlement() = verifyDiscardExercise(
        CompetitionMahjongPracticePosition.FLOWER_REPLACEMENT_ID, wrong = 0, correct = 31, draw = "东",
        resultText = "不计入起和", rewardText = "26 星 · 1570 分", expectedFlowers = listOf(34, 35),
    )

    @Test fun lastWallClaimShowsExhaustiveDrawOrNativeSettlementAndAwardsOnce() {
        val id = CompetitionMahjongPracticePosition.LAST_WALL_CLAIM_ID
        val before = CompetitionMahjongChallengeLibrary.challenges.takeWhile { it.id != id }.map { it.id }.toSet()
        val store = RoomCompetitionMahjongChallengeProgress(database)
        runBlocking {
            before.forEach { store.complete(it) }
            store.select(id)
        }
        compose.onNodeWithTag(HOME_COMPETITION_MAHJONG_TAG).performScrollTo().performClick()
        compose.onNodeWithTag("competition_challenge_entry").performScrollTo().performClick()
        waitFor("competition_mahjong_challenge_hand")
        compose.onNodeWithTag("competition_mahjong_challenge_flowers").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("competition_mahjong_challenge_pass_discard").performScrollTo().assertIsDisplayed()
        capture("last-wall-pending")

        compose.onNodeWithTag("competition_mahjong_challenge_pass_discard").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasTestTag("competition_mahjong_challenge_feedback") and
                hasText("本盘流局，四家均为 0 分", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag("competition_mahjong_challenge_feedback").performScrollTo()
            .assertTextContains("本盘流局，四家均为 0 分", substring = true)
        assertEquals(before, runBlocking { store.load().completedIds })
        capture("last-wall-pass")

        compose.onNodeWithTag("competition_mahjong_challenge_confirm_win").performScrollTo().performClick()
        waitFor("competition_mahjong_challenge_completed")
        val result = CompetitionMahjongPracticePosition.lastWallClaim(CompetitionMahjongRoundActionKind.WIN)
        val fan = checkNotNull(result.totalFan)
        assertEquals(checkNotNull(result.qualifyingFan) + 2, fan)
        assertEquals(listOf(24 + fan, -8, -8, -8 - fan), result.netScores)
        InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply {
            putInt("last_wall_total_fan", fan)
            putInt("last_wall_qualifying_fan", checkNotNull(result.qualifyingFan))
        })
        compose.onNodeWithTag("competition_mahjong_challenge_settlement").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("本盘总番 $fan").assertIsDisplayed()
        compose.onNodeWithTag("competition_mahjong_challenge_feedback").performScrollTo()
            .assertTextContains("海底捞月计 8 番", substring = true)
        assertEquals(before + id, runBlocking { store.load().completedIds })
        assertTrue(runBlocking { database.matchOutcomeDao().listAll().isEmpty() })
        compose.onNodeWithTag("competition_mahjong_challenge_rewards").performScrollTo()
            .assertTextEquals("训练奖励 27 星 · 1650 分")
        capture("last-wall-settlement")
    }

    @Test fun finalDrawSelfWinSettlesAllThreeOpponentsAndDiscardDrawsTheRound() {
        val id = CompetitionMahjongPracticePosition.LAST_WALL_SELF_DRAW_ID
        val before = CompetitionMahjongChallengeLibrary.challenges.takeWhile { it.id != id }.map { it.id }.toSet()
        val store = RoomCompetitionMahjongChallengeProgress(database)
        runBlocking {
            before.forEach { store.complete(it) }
            store.select(id)
        }
        compose.onNodeWithTag(HOME_COMPETITION_MAHJONG_TAG).performScrollTo().performClick()
        compose.onNodeWithTag("competition_challenge_entry").performScrollTo().performClick()
        waitFor("competition_mahjong_challenge_hand")
        compose.onNodeWithText("本次摸牌 东").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("competition_mahjong_challenge_flowers").performScrollTo().assertIsDisplayed()
        capture("last-wall-self-draw-ready")

        compose.onNodeWithTag("competition_mahjong_challenge_discard_final_draw").performScrollTo().performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasTestTag("competition_mahjong_challenge_feedback") and
                hasText("打出东风后三家均过，本盘流局", substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(before, runBlocking { store.load().completedIds })
        compose.onNodeWithTag("competition_mahjong_challenge_feedback").performScrollTo().assertIsDisplayed()
        capture("last-wall-self-draw-discard")

        val win = compose.onNodeWithTag("competition_mahjong_challenge_confirm_win")
        win.performScrollTo().assertTextEquals("自摸").performClick()
        waitFor("competition_mahjong_challenge_completed")
        val result = CompetitionMahjongPracticePosition.lastWallSelfDraw(CompetitionMahjongRoundActionKind.WIN)
        val fan = checkNotNull(result.totalFan)
        val payment = 8 + fan
        assertNull(result.payerSeat)
        assertEquals(checkNotNull(result.qualifyingFan) + 2, fan)
        assertEquals(listOf(-payment, -payment, -payment, 3 * payment), result.netScores)
        InstrumentationRegistry.getInstrumentation().sendStatus(2, android.os.Bundle().apply {
            putInt("last_wall_self_draw_total_fan", fan)
            putInt("last_wall_self_draw_qualifying_fan", checkNotNull(result.qualifyingFan))
        })
        compose.onNodeWithTag("competition_mahjong_challenge_settlement").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("本盘总番 $fan").assertIsDisplayed()
        compose.onNodeWithTag("competition_mahjong_challenge_feedback").performScrollTo()
            .assertTextContains("妙手回春计 8 番", substring = true)
        assertEquals(before + id, runBlocking { store.load().completedIds })
        assertTrue(runBlocking { database.matchOutcomeDao().listAll().isEmpty() })
        compose.onNodeWithTag("competition_mahjong_challenge_rewards").performScrollTo()
            .assertTextEquals("训练奖励 28 星 · 1730 分")
        capture("last-wall-self-draw-settlement")
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
