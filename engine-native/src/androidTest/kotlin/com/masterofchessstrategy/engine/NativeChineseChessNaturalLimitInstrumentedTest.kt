package com.masterofchessstrategy.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeChineseChessNaturalLimitInstrumentedTest {
    @Test
    fun completeNoCaptureHistoryCanBeClaimedThroughJniAndRestored() {
        val initialFen = "5kr2/9/9/9/5P3/9/R8/9/9/5K3 w - - 0 1"
        val redPath = listOf(
            0 to 6, 1 to 6, 2 to 6, 3 to 6, 3 to 7, 3 to 8,
            3 to 9, 2 to 9, 1 to 9, 0 to 9, 0 to 8, 0 to 7,
        )
        val blackPath = listOf(
            6 to 0, 7 to 0, 8 to 0, 8 to 1, 8 to 2,
            8 to 3, 7 to 3, 6 to 3, 6 to 2, 6 to 1,
        )
        NativeChineseChessEngine().use { engine ->
            assertEquals(
                RestoreResult.Restored,
                engine.restore(ChineseChessFenCodec.parse(initialFen).toEngineState()),
            )
            repeat(60) { round ->
                assertAccepted(
                    engine, redPath[round % redPath.size], redPath[(round + 1) % redPath.size],
                )
                assertAccepted(
                    engine, blackPath[round % blackPath.size], blackPath[(round + 1) % blackPath.size],
                )
            }
            assertEquals(GameResult.ONGOING, engine.gameResult())
            val review = engine.naturalLimitReview(ChineseChessSide.RED)
            assertEquals(120, review.noCapturePlies)
            assertEquals(120, review.recordedPlies)
            assertEquals(120, review.effectivePlies)
            assertTrue(review.completeRecord)
            assertTrue(review.eligible)

            // Replaying a saved long game must preserve the right to apply.
            NativeChineseChessEngine().use { restored ->
                assertEquals(RestoreResult.Restored, restored.restore(engine.serialize()))
                assertEquals(review, restored.naturalLimitReview(ChineseChessSide.RED))
                assertEquals(NaturalLimitClaimOutcome.DRAW, restored.claimNaturalLimit())
                assertEquals(GameResult.DRAW, restored.gameResult())
            }
            assertEquals(GameResult.ONGOING, engine.gameResult())
        }
    }

    @Test
    fun eleventhCheckIsDeductedOnlyForTheClaimantAcrossJniRestore() {
        val attackerPath = listOf(
            0 to 6, 1 to 6, 2 to 6, 3 to 6, 3 to 7, 3 to 8,
            3 to 9, 2 to 9, 1 to 9, 0 to 9, 0 to 8, 0 to 7,
        )
        val defenderPath = listOf(
            6 to 0, 7 to 0, 8 to 0, 8 to 1, 8 to 2,
            8 to 3, 7 to 3, 6 to 3, 6 to 2, 6 to 1,
        )
        ChineseChessSide.entries.forEach { claimant ->
            val rotated = claimant == ChineseChessSide.BLACK
            val defender = if (rotated) ChineseChessSide.RED else ChineseChessSide.BLACK
            val fen = if (rotated) {
                "3k5/9/9/8r/9/3p5/9/9/9/2R1K4 b - - 0 1"
            } else {
                "4k1r2/9/9/9/5P3/9/R8/9/9/5K3 w - - 0 1"
            }
            fun move(fx: Int, fy: Int, tx: Int, ty: Int): BoardMove {
                fun position(x: Int, y: Int) = if (rotated) {
                    BoardPosition(8 - x, 9 - y)
                } else {
                    BoardPosition(x, y)
                }
                return BoardMove(position(fx, fy), position(tx, ty))
            }
            fun play(engine: NativeChineseChessEngine, fx: Int, fy: Int, tx: Int, ty: Int) {
                assertEquals(ActionResult.Accepted, engine.apply(move(fx, fy, tx, ty)))
            }
            NativeChineseChessEngine().use { engine ->
                assertEquals(RestoreResult.Restored, engine.restore(ChineseChessFenCodec.parse(fen).toEngineState()))
                var rookX = 0
                var rookY = 6
                var defenderIndex = 0
                listOf(6, 7, 8, 9, 5).forEach { rank ->
                    if (rank != rookY) {
                        play(engine, rookX, rookY, rookX, rank)
                        val from = defenderPath[defenderIndex++]
                        val to = defenderPath[defenderIndex]
                        play(engine, from.first, from.second, to.first, to.second)
                        rookY = rank
                    }
                    play(engine, rookX, rookY, 4, rank)
                    assertTrue(engine.isInCheck(defender))
                    play(engine, 4, 0, 3, 0)
                    play(engine, 4, rank, 3, rank)
                    assertTrue(engine.isInCheck(defender))
                    play(engine, 3, 0, 4, 0)
                    rookX = 3
                }
                val tenth = engine.naturalLimitReview(claimant)
                assertEquals(10, tenth.claimantChecks)
                assertEquals(28, tenth.effectivePlies)
                play(engine, 3, 5, 4, 5)
                play(engine, 4, 0, 3, 0)
                val eleventh = engine.naturalLimitReview(claimant)
                assertEquals(11, eleventh.claimantChecks)
                assertEquals(30, eleventh.noCapturePlies)
                assertEquals(28, eleventh.effectivePlies)

                // Enter quiet circuits without repeating the checking cycle.
                play(engine, 4, 5, 0, 5)
                play(engine, 3, 0, 4, 0)
                play(engine, 0, 5, 0, 6)
                play(engine, 4, 0, 5, 0)
                val quietRounds = (120 - 34) / 2
                repeat(quietRounds) { round ->
                    val from = attackerPath[round % attackerPath.size]
                    val to = attackerPath[(round + 1) % attackerPath.size]
                    play(engine, from.first, from.second, to.first, to.second)
                    val replyFrom = defenderPath[(4 + round) % defenderPath.size]
                    val replyTo = defenderPath[(5 + round) % defenderPath.size]
                    play(engine, replyFrom.first, replyFrom.second, replyTo.first, replyTo.second)
                    assertEquals(GameResult.ONGOING, engine.gameResult())
                }
                val review = engine.naturalLimitReview(claimant)
                assertEquals(120, review.noCapturePlies)
                assertEquals(120, review.recordedPlies)
                assertEquals(11, review.claimantChecks)
                assertEquals(118, review.effectivePlies)
                assertTrue(review.completeRecord)
                assertFalse(review.eligible)
                val other = engine.naturalLimitReview(defender)
                assertEquals(0, other.claimantChecks)
                assertEquals(120, other.effectivePlies)
                assertTrue(other.eligible)
                assertEquals(NaturalLimitClaimOutcome.FIRST_FALSE_CLAIM, engine.claimNaturalLimit())

                NativeChineseChessEngine().use { restored ->
                    val saved = engine.serialize()
                    assertEquals(RestoreResult.Restored, restored.restore(saved))
                    assertArrayEquals(saved, restored.serialize())
                    assertEquals(review, restored.naturalLimitReview(claimant))
                    // The same complete history also verifies capture-stage
                    // reset: old checks and false requests cannot leak through.
                    NativeChineseChessEngine().use { captured ->
                        assertEquals(RestoreResult.Restored, captured.restore(saved))
                        val currentAttacker = attackerPath[quietRounds % attackerPath.size]
                        val currentDefender = defenderPath[(4 + quietRounds) % defenderPath.size]
                        play(captured, currentAttacker.first, currentAttacker.second, 2, 3)
                        play(captured, currentDefender.first, currentDefender.second, 2, 3)
                        val newStage = captured.naturalLimitReview(claimant)
                        assertEquals(0, newStage.noCapturePlies)
                        assertEquals(0, newStage.recordedPlies)
                        assertEquals(0, newStage.claimantChecks)
                        assertTrue(newStage.completeRecord)
                        assertEquals(NaturalLimitClaimOutcome.FIRST_FALSE_CLAIM, captured.claimNaturalLimit())
                        assertEquals(GameResult.ONGOING, captured.gameResult())
                    }
                    val from = attackerPath[quietRounds % attackerPath.size]
                    val to = attackerPath[(quietRounds + 1) % attackerPath.size]
                    play(restored, from.first, from.second, to.first, to.second)
                    val replyFrom = defenderPath[(4 + quietRounds) % defenderPath.size]
                    val replyTo = defenderPath[(5 + quietRounds) % defenderPath.size]
                    val lastReply = move(replyFrom.first, replyFrom.second, replyTo.first, replyTo.second)
                    assertEquals(ActionResult.Accepted, restored.apply(lastReply))
                    val eligible = restored.naturalLimitReview(claimant)
                    assertEquals(122, eligible.noCapturePlies)
                    assertEquals(120, eligible.effectivePlies)
                    assertTrue(eligible.eligible)
                    assertEquals(NaturalLimitClaimOutcome.DRAW, restored.claimNaturalLimit())
                    assertEquals(GameResult.DRAW, restored.gameResult())
                    assertTrue(restored.undo())
                    assertEquals(GameResult.ONGOING, restored.gameResult())
                    assertEquals(119, restored.naturalLimitReview(claimant).effectivePlies)
                    assertEquals(ActionResult.Accepted, restored.apply(lastReply))
                    assertEquals(NaturalLimitClaimOutcome.DRAW, restored.claimNaturalLimit())
                }
                assertEquals(GameResult.ONGOING, engine.gameResult())
            }
        }
    }

    private fun assertAccepted(
        engine: NativeChineseChessEngine,
        from: Pair<Int, Int>,
        to: Pair<Int, Int>,
    ) {
        assertEquals(
            ActionResult.Accepted,
            engine.apply(
                BoardMove(
                    BoardPosition(from.first, from.second),
                    BoardPosition(to.first, to.second),
                ),
            ),
        )
    }
}
