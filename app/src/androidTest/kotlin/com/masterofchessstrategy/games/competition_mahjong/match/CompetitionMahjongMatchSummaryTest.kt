package com.masterofchessstrategy.games.competition_mahjong.match

import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.masterofchessstrategy.engine.CompetitionMahjongFanView
import com.masterofchessstrategy.engine.CompetitionMahjongPublicView
import com.masterofchessstrategy.engine.CompetitionMahjongRoundPhase
import com.masterofchessstrategy.engine.CompetitionMahjongSeatView
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CompetitionMahjongMatchSummaryTest {
    @get:Rule val compose = createComposeRule()

    @Test fun summaryKeepsPlayerScoresAndNextCallbackUsesRenderedHandIndex() {
        val state = CompetitionMahjongCompetitionState(
            viewerPlayerId = 1, activeHandIndex = 3, completedHandCount = 4,
            cumulativeScores = CompetitionMahjongPlayerScores(20, 30, -10, -40), table = drawFrame(),
        )
        var next = -1
        compose.setContent {
            MaterialTheme { CompetitionMahjongMatchSummary(state, { next = it }, {}, modifier = Modifier.height(144.dp)) }
        }
        compose.onNodeWithTag("competition_mahjong_match_progress").assertTextContains("已完成 4 / 16 盘")
        compose.onNodeWithTag("competition_mahjong_match_next").assertIsDisplayed().assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(3, next) }
        compose.onNodeWithTag("competition_mahjong_match_score_1").performScrollTo().assertTextContains("你：30")
        compose.onNodeWithTag("competition_mahjong_match_score_0").performScrollTo().assertTextContains("玩家1：20")
    }

    @Test fun unsavedSummaryRequiresRetryAndWithholdsFinalCompletion() {
        val pending = CompetitionMahjongCompetitionState(activeHandIndex = 15, completedHandCount = 16, needsSave = true)
        var retries = 0
        var next = 0
        compose.setContent {
            MaterialTheme { CompetitionMahjongMatchSummary(pending, { next++ }, { retries++ }, modifier = Modifier.height(144.dp)) }
        }
        compose.onNodeWithTag("competition_mahjong_match_retry").assertIsDisplayed().assertIsEnabled().performClick()
        compose.onNodeWithText("十六盘结束。").assertDoesNotExist()
        compose.onNodeWithTag("competition_mahjong_match_next").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, retries); assertEquals(0, next) }
    }

    @Test fun tiedFinalStandingDisplaysSevenQuartersAndNoSeventeenthHand() {
        val complete = CompetitionMahjongCompetitionState(activeHandIndex = 15, completedHandCount = 16,
            standings = (0..3).map { CompetitionMahjongMatchStanding(it, 1, 0, 7, 4) })
        compose.setContent { MaterialTheme { CompetitionMahjongMatchSummary(complete, {}, {}) } }
        compose.onNodeWithTag("competition_mahjong_match_score_0").assertTextContains("标准分 1.75", substring = true)
        compose.onNodeWithTag("competition_mahjong_match_next").assertDoesNotExist()
        compose.onNodeWithText("十六盘结束。").assertExists()
    }

    private fun drawFrame() = CompetitionMahjongPublicView(1, CompetitionMahjongRoundPhase.EXHAUSTIVE_DRAW,
        3, 0, 3, 0, null, null, null, null, 0, listOf(0, 0, 0, 0),
        CompetitionMahjongFanView(0, false, 0, 0, false, emptyList()),
        (0..3).map { CompetitionMahjongSeatView(it, 13, false, emptyList(), emptyList(), emptyList(), emptyList()) })
}
