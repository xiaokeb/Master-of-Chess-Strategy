package com.masterofchessstrategy.games.go

import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import com.masterofchessstrategy.MainActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class GoOpeningStudyScreenInstrumentedTest {
    @get:Rule val composeRule = createAndroidComposeRule<MainActivity>()

    @Test fun catalogPlaysVerifiedLineAndOnlyStartsAutomaticAtImmutableEndpoint() {
        var started: Pair<GoAiOpening, GoAiMatchConfig>? = null
        composeRule.activityRule.scenario.onActivity { activity -> activity.setContent {
            GoOpeningStudyHub(setOf(GoAiDifficulty.SIMPLE, GoAiDifficulty.HARD),
                { opening, config -> started = opening to config }, {})
        } }
        composeRule.onNodeWithText("项目自编线路 · 每一着均由正式规则引擎验证 · 不标作职业定式结论").assertExists()
        composeRule.onNodeWithTag("go_opening_line_9-corners-center").performScrollTo().performClick()
        composeRule.waitUntil(15_000) { composeRule.onAllNodesWithText("行棋 0 / 6 着 · 事件 0 / 6").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("go_opening_start_auto").performScrollTo().assertIsNotEnabled()
        composeRule.onNodeWithText("末步").performClick()
        composeRule.waitUntil(15_000) { composeRule.onAllNodesWithText("行棋 6 / 6 着 · 事件 6 / 6").fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithTag("go_opening_explanation").assertExists()
        composeRule.onNodeWithText("困难").performClick()
        composeRule.onNodeWithTag("go_opening_start_auto").performScrollTo().performClick()
        composeRule.waitUntil(5_000) { started != null }
        val (opening, config) = checkNotNull(started)
        assertEquals(9, opening.boardSize); assertEquals(6, opening.eventCount)
        assertTrue(config.automatic); assertEquals(GoAiDifficulty.HARD, config.difficulty)
    }
}
