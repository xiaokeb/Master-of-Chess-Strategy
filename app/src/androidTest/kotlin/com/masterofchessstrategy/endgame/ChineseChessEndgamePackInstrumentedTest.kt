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
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChineseChessEndgamePackInstrumentedTest {
    @Test
    fun twoMoveLevelsWinAgainstEveryDefenseWithExactlyOneFirstMove() {
        val assets = InstrumentationRegistry.getInstrumentation().targetContext.assets
        val levels = ChineseChessEndgamePackParser.loadBundled(assets).levels
            .filter { it.theme == "两步强制胜" }
        assertEquals(2, levels.size)
        var cooperativeOnlyMoves = 0
        levels.forEach { level ->
            assertEquals(2, level.maxPlayerMoves)
            NativeChineseChessEngine().use { engine ->
                assertTrue(engine.restore(level.initialEngineState) is RestoreResult.Restored)
                val before = engine.serialize()
                // Independent fixed-depth traversal of the actual APK asset;
                // a valid sample PV alone does not establish a forced win.
                val winningFirstMoves = engine.legalActions().filter { first ->
                    assertTrue(engine.apply(first) is ActionResult.Accepted)
                    assertTrue("${level.id}: must not already win in one move",
                        engine.gameResult() != GameResult.FIRST_PLAYER_WIN)
                    var everyReplyLoses = engine.gameResult() == GameResult.ONGOING
                    var someReplyLoses = false
                    if (everyReplyLoses) {
                        val replies = engine.legalActions()
                        assertTrue(replies.isNotEmpty())
                        replies.forEach { reply ->
                            assertTrue(engine.apply(reply) is ActionResult.Accepted)
                            val canWin = when (engine.gameResult()) {
                                GameResult.FIRST_PLAYER_WIN -> true
                                GameResult.ONGOING -> engine.legalActions().any { finish ->
                                    assertTrue(engine.apply(finish) is ActionResult.Accepted)
                                    val wins = engine.gameResult() == GameResult.FIRST_PLAYER_WIN
                                    assertTrue(engine.undo())
                                    wins
                                }
                                else -> false
                            }
                            everyReplyLoses = everyReplyLoses && canWin
                            someReplyLoses = someReplyLoses || canWin
                            assertTrue(engine.undo())
                        }
                    }
                    if (someReplyLoses && !everyReplyLoses) cooperativeOnlyMoves++
                    assertTrue(engine.undo())
                    everyReplyLoses
                }
                assertEquals(level.id, listOf(level.principalVariation.first()), winningFirstMoves)
                assertArrayEquals(level.id, before, engine.serialize())
            }
        }
        assertTrue("Fixtures must distinguish cooperative PVs from forced wins", cooperativeOnlyMoves > 0)
    }

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
