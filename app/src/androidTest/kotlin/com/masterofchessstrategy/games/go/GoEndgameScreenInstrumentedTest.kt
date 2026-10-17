package com.masterofchessstrategy.games.go

import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.masterofchessstrategy.MainActivity
import com.masterofchessstrategy.engine.BoardPosition
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class GoEndgameScreenInstrumentedTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun catalogLocksNextPuzzleAndCompletionExposesProgression() {
        val first = GoEndgameLibrary.puzzles.first()
        val page = mutableStateOf(GoEndgameUiState(loading = false,
            exercise = GoEndgameState(first, List(81) { 0 })))
        val answers = mutableListOf<BoardPosition>(); val selected = mutableListOf<String>()
        composeRule.activityRule.scenario.onActivity { activity -> activity.setContent {
            GoEndgameScreen(page.value, answers::add, selected::add, {}, {}, {})
        } }
        composeRule.onNodeWithText("项目自编种子题 · 已完成 0/6").assertExists()
        composeRule.onNodeWithTag("go_endgame_catalog").performClick()
        composeRule.onNodeWithText("2. 边上一口气 · 简单 · 未解锁").assertIsNotEnabled()
        composeRule.onNodeWithText("关闭").performClick()
        composeRule.onNodeWithTag(GO_BOARD_TAG).performTouchInput { click(center) }
        composeRule.waitForIdle(); assertEquals(1, answers.size)

        composeRule.runOnIdle { page.value = page.value.copy(
            exercise = page.value.exercise!!.copy(completed = true, feedback = first.success),
            completedIds = setOf(first.id)) }
        composeRule.onNodeWithTag("go_endgame_completed").assertExists()
        composeRule.onNodeWithText("下一关").performScrollTo().performClick()
        assertEquals(listOf(GoEndgameLibrary.puzzles[1].id), selected)
    }

    @Test fun unsavedSolutionDoesNotExposeCompletedStatusOrEnableNextPuzzle() {
        val first = GoEndgameLibrary.puzzles.first()
        composeRule.activityRule.scenario.onActivity { activity -> activity.setContent {
            GoEndgameScreen(GoEndgameUiState(loading = false,
                exercise = GoEndgameState(first, List(81) { 0 }, completed = true),
                needsSave = true, error = "保存失败"), {}, {}, {}, {}, {})
        } }
        composeRule.onNodeWithTag("go_endgame_pending_save").assertExists()
        composeRule.onNodeWithTag("go_endgame_completed").assertDoesNotExist()
        composeRule.onNodeWithText("下一关").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("重试").performScrollTo().assertIsEnabled()
    }

    @Test fun themeFilteringDoesNotUnlockLockedProblemsAndPassesFilteredRandomRequest() {
        val puzzles = GoEndgameLibrary.puzzles
        val page = mutableStateOf(GoEndgameUiState(loading = false,
            exercise = GoEndgameState(puzzles.first(), List(81) { 0 })))
        val selected = mutableListOf<String>()
        val randomRequests = mutableListOf<Pair<GoEndgameTheme?, GoEndgameLevel?>>()
        composeRule.activityRule.scenario.onActivity { activity -> activity.setContent {
            GoEndgameScreen(page.value, {}, {}, {}, {}, {}, onThemedSelect = selected::add,
                onRandom = { theme, level -> randomRequests += theme to level })
        } }
        composeRule.onNodeWithTag("go_endgame_catalog").performScrollTo().performClick()
        composeRule.onNodeWithTag("go_endgame_filter_CONNECTION").performClick()
        composeRule.onNodeWithTag("go_endgame_puzzle_corner-capture").assertDoesNotExist()
        composeRule.onNodeWithTag("go_endgame_puzzle_bridge-groups").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithTag("go_endgame_filtered_random").performScrollTo().assertIsNotEnabled()
        composeRule.runOnIdle { page.value = page.value.copy(completedIds = puzzles.take(3).map { it.id }.toSet()) }
        composeRule.onNodeWithTag("go_endgame_filtered_random").performScrollTo().performClick()
        assertEquals(listOf(GoEndgameTheme.CONNECTION to null), randomRequests)
        composeRule.onNodeWithTag("go_endgame_catalog").performScrollTo().performClick()
        composeRule.onNodeWithTag("go_endgame_puzzle_bridge-groups").performScrollTo().performClick()
        assertEquals(listOf(puzzles[3].id), selected)
    }

    @Test fun dailyAndRandomButtonsExposeCallbacksAndPersistedModeLabel() {
        val date = java.time.LocalDate.of(2026, 10, 14)
        val page = mutableStateOf(GoEndgameUiState(loading = false,
            exercise = GoEndgameState(GoEndgameLibrary.puzzles.first(), List(81) { 0 })))
        var daily = 0; var random = 0
        composeRule.activityRule.scenario.onActivity { activity -> activity.setContent {
            GoEndgameScreen(page.value, {}, {}, {}, {}, {}, onDaily = { daily++ }, onRandom = { _, _ -> random++ })
        } }
        composeRule.onNodeWithTag("go_endgame_daily").performScrollTo().performClick()
        composeRule.onNodeWithTag("go_endgame_random").performScrollTo().performClick()
        assertEquals(1, daily); assertEquals(1, random)
        composeRule.runOnIdle { page.value = page.value.copy(mode = GoEndgameChallengeMode.DAILY, dailyDate = date) }
        composeRule.onNodeWithTag("go_endgame_mode").performScrollTo().assertTextEquals("每日残局 · 2026-10-14")
        composeRule.onNodeWithText("返回主线").performScrollTo().assertIsEnabled()
    }
}
