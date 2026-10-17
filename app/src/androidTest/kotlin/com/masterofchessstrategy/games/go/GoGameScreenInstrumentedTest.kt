package com.masterofchessstrategy.games.go

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.GoAreaScore
import com.masterofchessstrategy.engine.GoPhase
import com.masterofchessstrategy.engine.GoPlayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GoGameScreenInstrumentedTest {
    @get:Rule val composeRule = createComposeRule()

    @Test fun accessibleIntersectionReturnsItsBoardCoordinate() {
        var tapped: Pair<Int, Int>? = null
        show(GoGameUiState(boardSize = 13, loading = false, sessionReady = true),
            onTap = { x, y -> tapped = x to y })
        composeRule.onNodeWithTag("go_intersection_12_12").performClick()
        assertEquals(12 to 12, tapped)
    }

    @Test fun scoringHasSeparatePlayerConfirmationsAndDisputeTurn() {
        val confirmations = mutableListOf<GoPlayer>()
        var firstPlayer: GoPlayer? = null
        show(
            GoGameUiState(
                boardSize = 9, loading = false, sessionReady = true,
                phase = GoPhase.SCORING,
                displayMode = GoScoreDisplayMode.POINTS,
                previewScore = GoAreaScore(126, 198, 15, GameResult.SECOND_PLAYER_WIN, 0),
            ),
            onConfirm = confirmations::add,
            onResume = { firstPlayer = it },
        )
        composeRule.onNodeWithText("预览：白领先 25.5 目").assertExists()
        composeRule.onNodeWithText("黑方确认").performScrollTo().performClick()
        composeRule.onNodeWithText("白方确认").performScrollTo().performClick()
        assertEquals(listOf(GoPlayer.BLACK, GoPlayer.WHITE), confirmations)
        composeRule.onNodeWithText("白方主张死子，续弈").performScrollTo().performClick()
        assertEquals(GoPlayer.WHITE, firstPlayer)
    }

    @Test fun finishedGameStillOffersUndo() {
        var undone = false
        show(
            GoGameUiState(
                boardSize = 9, loading = false, sessionReady = true,
                phase = GoPhase.FINISHED, result = GameResult.FIRST_PLAYER_WIN,
            ),
            onUndo = { undone = true },
        )
        composeRule.onNodeWithText("黑方获胜").assertExists()
        composeRule.onNodeWithText("悔棋").performScrollTo().performClick()
        assertTrue(undone)
    }

    private fun show(
        state: GoGameUiState,
        onTap: (Int, Int) -> Unit = { _, _ -> },
        onUndo: () -> Unit = {},
        onConfirm: (GoPlayer) -> Unit = {},
        onResume: (GoPlayer) -> Unit = {},
    ) {
        composeRule.setContent {
            MaterialTheme {
                GoGameScreen(
                    state = state,
                    onIntersectionTap = onTap,
                    onPass = {},
                    onUndo = onUndo,
                    onConfirmScore = onConfirm,
                    onResumePlay = onResume,
                    onScoringActorSelect = {},
                    onScoreModeToggle = {},
                    onLegalPointsToggle = {},
                    onRetrySave = {},
                    onNewGame = {},
                    onBack = {},
                )
            }
        }
    }
}
