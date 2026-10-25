package com.masterofchessstrategy.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiAction
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiPoint
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiSide
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.NativeLanzhouFangqiSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeLanzhouFangqiInstrumentedTest {
    @Test
    fun openingMoveUndoAndAtomicRestoreUseNativeSession() {
        NativeLanzhouFangqiSession().use { game ->
            val initial = game.snapshot()
            assertEquals(FangqiSide.RED, initial.currentPlayer)
            assertEquals(14, initial.board.count { it == 1 })
            assertEquals(14, initial.board.count { it == 2 })
            assertFalse(initial.canUndo)
            val move = FangqiAction.Move(FangqiPoint(0, 5), FangqiPoint(0, 4))
            assertTrue(move in game.legalActions())
            assertTrue(game.apply(move).accepted)
            assertEquals(FangqiSide.BLACK, game.snapshot().currentPlayer)
            val saved = game.serialize()
            assertTrue(game.undo())
            assertEquals(initial, game.snapshot())
            assertTrue(game.restore(saved))
            assertEquals(FangqiSide.BLACK, game.snapshot().currentPlayer)
            val broken = saved.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            assertFalse(game.restore(broken))
            assertTrue(saved.contentEquals(game.serialize()))
            assertNotNull(game.chooseAction(Difficulty.EASY))
        }
    }

    @Test
    fun squareCaptureFinishesAndUndoRestoresWholeTurn() {
        val board = MutableList(49) { 0 }
        listOf(0 to 0, 1 to 0, 0 to 1, 1 to 2).forEach { (x, y) -> board[y * 7 + x] = 1 }
        board[5 * 7 + 5] = 2
        NativeLanzhouFangqiSession.fromPosition(board, FangqiSide.RED).use { game ->
            val initial = game.snapshot()
            assertTrue(game.apply(FangqiAction.Move(FangqiPoint(1, 2), FangqiPoint(1, 1))).accepted)
            assertEquals(1, game.snapshot().pendingCaptures)
            assertTrue(game.apply(FangqiAction.Capture(FangqiPoint(5, 5))).accepted)
            assertEquals(GameResult.FIRST_PLAYER_WIN, game.snapshot().result)
            assertTrue(game.undo())
            assertEquals(initial, game.snapshot())
        }
    }
}
