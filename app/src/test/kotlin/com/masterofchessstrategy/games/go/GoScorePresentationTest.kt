package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GoAreaScore
import org.junit.Assert.assertEquals
import org.junit.Test

class GoScorePresentationTest {
    @Test fun normalKomiIsAppliedAfterHalfBoardComparison() {
        val score = GoAreaScore(126, 198, 15, GameResult.SECOND_PLAYER_WIN, 0)
        assertEquals(-51, score.blackMarginQuarters())
        assertEquals("12.75 子", goScoreText(51, GoScoreDisplayMode.STONES))
        assertEquals("25.5 目", goScoreText(51, GoScoreDisplayMode.POINTS))
    }

    @Test fun handicapReturnIsHalvedInStoneMargin() {
        val score = GoAreaScore(180, 144, 0, GameResult.FIRST_PLAYER_WIN, 8)
        assertEquals(14, score.blackMarginQuarters())
        assertEquals("3.5 子", goScoreText(14, GoScoreDisplayMode.STONES))
        assertEquals("7 目", goScoreText(14, GoScoreDisplayMode.POINTS))
    }

    @Test fun evenAreaWithNoKomiIsADraw() {
        assertEquals(0, GoAreaScore(162, 162, 0, GameResult.DRAW, 0).blackMarginQuarters())
        assertEquals("0 子", goScoreText(0, GoScoreDisplayMode.STONES))
    }
}
