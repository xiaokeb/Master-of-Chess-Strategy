package com.masterofchessstrategy.games.lanzhou_fangqi

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiSide
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.NativeLanzhouFangqiSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class LanzhouFangqiScreenTest {
    @get:Rule val compose = createComposeRule()
    private var session: NativeLanzhouFangqiSession? = null

    @After fun releaseSession() { session?.close() }

    @Test fun accessiblePointsMoveAndUndoOpeningThroughNativeSession() {
        val engine = NativeLanzhouFangqiSession().also { session = it }
        compose.setContent { MaterialTheme { LanzhouFangqiScreen(engine, onBack = {}) } }
        compose.onNodeWithTag("lanzhou_fangqi_point_0_5").performClick().assertIsSelected()
        compose.onNodeWithTag("lanzhou_fangqi_point_0_4").performClick()
        compose.runOnIdle {
            assertEquals(FangqiSide.BLACK, engine.snapshot().currentPlayer)
            assertEquals(1, engine.snapshot().board[4 * 7])
        }
        compose.onNodeWithText("悔棋").performClick()
        compose.runOnIdle {
            assertEquals(FangqiSide.RED, engine.snapshot().currentPlayer)
            assertEquals(1, engine.snapshot().board[5 * 7])
            assertEquals(0, engine.snapshot().board[4 * 7])
        }
    }

    @Test fun twoNewSquaresRequireTwoExplicitTargetsAndUndoEntireTurn() {
        val board = MutableList(49) { 0 }
        listOf(0, 2, 14, 8, 9, 15, 23).forEach { board[it] = 1 }
        listOf(40, 41, 48).forEach { board[it] = 2 }
        val engine = NativeLanzhouFangqiSession.fromPosition(board, FangqiSide.RED).also { session = it }
        compose.setContent { MaterialTheme { LanzhouFangqiScreen(engine, onBack = {}) } }
        compose.onNodeWithTag("lanzhou_fangqi_point_2_3").performClick()
        compose.onNodeWithTag("lanzhou_fangqi_point_2_2").performClick()
        compose.runOnIdle {
            assertEquals(2, engine.snapshot().pendingCaptures)
            assertEquals(FangqiSide.RED, engine.snapshot().currentPlayer)
        }
        compose.onNodeWithTag("lanzhou_fangqi_point_6_6").performClick()
        compose.runOnIdle {
            assertEquals(1, engine.snapshot().pendingCaptures)
            assertEquals(0, engine.snapshot().board[48])
            assertEquals(2, engine.snapshot().board[40])
        }
        compose.onNodeWithTag("lanzhou_fangqi_point_5_5").performClick()
        compose.runOnIdle {
            assertEquals(0, engine.snapshot().pendingCaptures)
            assertEquals(FangqiSide.BLACK, engine.snapshot().currentPlayer)
        }
        compose.onNodeWithText("悔棋").performClick()
        compose.runOnIdle {
            assertEquals(board, engine.snapshot().board)
            assertEquals(0, engine.snapshot().pendingCaptures)
        }
    }
}
