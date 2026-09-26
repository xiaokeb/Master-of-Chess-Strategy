package com.masterofchessstrategy.game

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.NativeChineseChessEngine
import com.masterofchessstrategy.engine.ActionResult
import com.masterofchessstrategy.engine.BoardMove
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.ChineseChessFenCodec
import com.masterofchessstrategy.engine.ChineseChessSide
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.RestoreResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PikafishMasterAiInstrumentedTest {
    @Test
    fun everyDifficultyUsesBundledNetworkAndPreservesThePosition() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val networkProvider = PikafishNetworkProvider(context)

        NativeChineseChessEngine(
            networkProvider::requireNetworkPath,
        ).use { engine ->
            val legalMoves = engine.legalActions()
            val before = engine.serialize()
            // Revisit EASY after MASTER/HARD to exercise profile/cache transitions.
            listOf(
                Difficulty.MASTER, Difficulty.EASY, Difficulty.MEDIUM,
                Difficulty.HARD, Difficulty.EASY,
            ).forEach { difficulty ->
                val selected = engine.chooseMove(difficulty)
                assertNotNull(selected)
                assertTrue(selected in legalMoves)
                assertTrue(before.contentEquals(engine.serialize()))
            }
        }
    }

    @Test
    fun hardPikafishConvertsTheRookWinAgainstEveryKingReply() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = PikafishNetworkProvider(context)
        val state = ChineseChessFenCodec.parse(
            "3k5/9/9/9/9/9/5r3/9/4K4/9 b - - 0 1",
        ).toEngineState()
        NativeChineseChessEngine(provider::requireNetworkPath).use { engine ->
            assertEquals(RestoreResult.Restored, engine.restore(state))
            assertConvertsWin(engine, remainingPlies = 11)
        }
    }

    @Test
    fun repeatedPositionHistorySurvivesRestoreAndUndoBeforeSearch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = PikafishNetworkProvider(context)
        val cycle = listOf(
            BoardMove(BoardPosition(7, 9), BoardPosition(6, 7)),
            BoardMove(BoardPosition(7, 0), BoardPosition(6, 2)),
            BoardMove(BoardPosition(6, 7), BoardPosition(7, 9)),
            BoardMove(BoardPosition(6, 2), BoardPosition(7, 0)),
        )
        NativeChineseChessEngine(provider::requireNetworkPath).use { engine ->
            cycle.forEach { assertEquals(ActionResult.Accepted, engine.apply(it)) }
            assertEquals(GameResult.ONGOING, engine.gameResult())
            val state = engine.serialize()
            NativeChineseChessEngine(provider::requireNetworkPath).use { restored ->
                assertEquals(RestoreResult.Restored, restored.restore(state))
                for (session in listOf(engine, restored)) {
                    val move = requireNotNull(session.chooseMove(Difficulty.MEDIUM))
                    assertTrue(move in session.legalActions())
                    assertTrue(state.contentEquals(session.serialize()))
                    assertEquals(ActionResult.Accepted, session.apply(move))
                    assertTrue(session.undo())
                    assertTrue(state.contentEquals(session.serialize()))
                }
            }
            cycle.forEach { assertEquals(ActionResult.Accepted, engine.apply(it)) }
            assertEquals(GameResult.DRAW, engine.gameResult())
            assertEquals(null, engine.chooseMove(Difficulty.EASY))
        }
    }

    @Test
    fun blackFirstCustomHistoryKeepsItsOriginalNoCaptureClock() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = PikafishNetworkProvider(context)
        NativeChineseChessEngine(provider::requireNetworkPath).use { engine ->
            assertEquals(RestoreResult.Restored, engine.restore(ChineseChessFenCodec.parse(
                "4k4/9/9/9/r8/4P4/9/9/9/4K4 b - - 118 1",
            ).toEngineState()))
            assertEquals(ActionResult.Accepted, engine.apply(
                BoardMove(BoardPosition(0, 4), BoardPosition(0, 5)),
            ))
            val state = engine.serialize()
            val selected = requireNotNull(engine.chooseMove(Difficulty.EASY))
            assertTrue(selected in engine.legalActions())
            assertTrue(state.contentEquals(engine.serialize()))
            assertEquals(ActionResult.Accepted, engine.apply(selected))
            assertEquals(GameResult.DRAW, engine.gameResult())
        }
    }

    @Test
    fun hardPikafishTakesImmediateStalemate() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val provider = PikafishNetworkProvider(context)
        NativeChineseChessEngine(provider::requireNetworkPath).use { engine ->
            assertEquals(RestoreResult.Restored, engine.restore(ChineseChessFenCodec.parse(
                "3k5/9/9/9/9/9/5r3/9/9/4K4 b - - 0 1",
            ).toEngineState()))
            assertEquals(ActionResult.Accepted, engine.apply(requireNotNull(engine.chooseMove(Difficulty.HARD))))
            assertEquals(GameResult.SECOND_PLAYER_WIN, engine.gameResult())
            assertTrue(engine.legalActions().isEmpty())
            assertTrue(!engine.isInCheck(ChineseChessSide.RED))
        }
    }

    private fun assertConvertsWin(engine: NativeChineseChessEngine, remainingPlies: Int) {
        if (engine.gameResult() != GameResult.ONGOING) {
            assertEquals(GameResult.SECOND_PLAYER_WIN, engine.gameResult())
            return
        }
        assertTrue("Pikafish failed to convert the rook win within 11 plies", remainingPlies > 0)
        val before = engine.serialize()
        val moves = if (engine.currentPlayer.value == ChineseChessSide.BLACK.code) {
            listOf(requireNotNull(engine.chooseMove(Difficulty.HARD)))
        } else {
            engine.legalActions()
        }
        assertTrue(before.contentEquals(engine.serialize()))
        moves.forEach { move ->
            assertEquals(ActionResult.Accepted, engine.apply(move))
            assertConvertsWin(engine, remainingPlies - 1)
            assertTrue(engine.undo())
        }
    }
}
