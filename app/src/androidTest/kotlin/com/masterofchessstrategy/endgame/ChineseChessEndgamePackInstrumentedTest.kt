package com.masterofchessstrategy.endgame

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.engine.ActionResult
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.NativeChineseChessEngine
import com.masterofchessstrategy.engine.RestoreResult
import com.masterofchessstrategy.engine.ChineseChessPieceType
import com.masterofchessstrategy.engine.ChineseChessSide
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChineseChessEndgamePackInstrumentedTest {
    @Test
    fun everyBundledPrincipalVariationWinsWithinItsLimit() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val pack = ChineseChessEndgamePackParser.loadBundled(assets)

        pack.levels.forEach { level ->
            NativeChineseChessEngine().use { engine ->
                assertTrue(
                    level.id,
                    engine.restore(level.initialEngineState) is RestoreResult.Restored,
                )
                assertEquals(level.id, GameResult.ONGOING, engine.gameResult())
                level.principalVariation.forEachIndexed { index, move ->
                    assertTrue(
                        "${level.id} step ${index + 1} was rejected",
                        engine.apply(move) is ActionResult.Accepted,
                    )
                }
                assertEquals(level.id, GameResult.FIRST_PLAYER_WIN, engine.gameResult())
                assertTrue(
                    level.id,
                    (level.principalVariation.size + 1) / 2 <= level.maxPlayerMoves,
                )
            }
        }
    }

    @Test
    fun everyUniqueMateSeedHasOneWinningFirstMove() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val pack = ChineseChessEndgamePackParser.loadBundled(assets)

        val uniqueLevels = pack.levels.filter { it.theme == "唯一一步杀" }
        assertEquals(9, uniqueLevels.size)
        uniqueLevels.forEach { level ->
            NativeChineseChessEngine().use { engine ->
                assertTrue(
                    level.id,
                    engine.restore(level.initialEngineState) is RestoreResult.Restored,
                )
                val winningMoves = engine.legalActions().filter { move ->
                    assertTrue(
                        "${level.id}: direct general capture is not a mate puzzle",
                        engine.pieceAt(move.to)?.type != ChineseChessPieceType.GENERAL,
                    )
                    val accepted = engine.apply(move) is ActionResult.Accepted
                    val wins = accepted &&
                        engine.gameResult() == GameResult.FIRST_PLAYER_WIN
                    if (wins) {
                        assertTrue(
                            "${level.id}: winning move must give checkmate, not stalemate",
                            engine.isInCheck(ChineseChessSide.BLACK),
                        )
                    }
                    if (accepted) assertTrue(level.id, engine.undo())
                    wins
                }
                assertEquals(
                    "${level.id}: ${winningMoves.joinToString()}",
                    1,
                    winningMoves.size,
                )
                assertEquals(level.id, level.principalVariation.single(), winningMoves.single())
            }
        }
    }
}
