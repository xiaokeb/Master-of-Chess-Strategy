package com.masterofchessstrategy.engine.games.xiangqi

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.masterofchessstrategy.engine.ActionResult
import com.masterofchessstrategy.engine.BoardMove
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.ChineseChessFenCodec
import com.masterofchessstrategy.engine.ChineseChessSide
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.NativeChineseChessEngine
import com.masterofchessstrategy.engine.NaturalLimitClaimOutcome
import com.masterofchessstrategy.engine.RestoreResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeXiangqiCounterCheckInstrumentedTest {
    @Test
    fun bothSidesExceedTenChecksWithoutCombiningTheirDeductionsOrFalseRequests() {
        for (rotated in listOf(false, true)) {
            val first = if (rotated) ChineseChessSide.BLACK else ChineseChessSide.RED
            val second = if (rotated) ChineseChessSide.RED else ChineseChessSide.BLACK
            NativeChineseChessEngine().use { engine ->
                restoreInitial(engine, rotated, counterCheck = false)
                CHECKING_ROUNDS.forEach { row ->
                    play(engine, rotated, row[0], row[1], row[2], row[3])
                    play(engine, rotated, row[4], row[5], row[6], row[7])
                }
                listOf(first, second).forEach { side -> assertReview(engine, side, 70) }
                repeat(25) { round ->
                    val from = RED_PATH[round % RED_PATH.size]
                    val to = RED_PATH[(round + 1) % RED_PATH.size]
                    play(engine, rotated, from.first, from.second, to.first, to.second)
                    val replyFrom = BLACK_PATH[(5 + round) % BLACK_PATH.size]
                    val replyTo = BLACK_PATH[(6 + round) % BLACK_PATH.size]
                    play(engine, rotated, replyFrom.first, replyFrom.second, replyTo.first, replyTo.second)
                }
                listOf(first, second).forEach { side -> assertReview(engine, side, 120) }
                assertEquals(NaturalLimitClaimOutcome.FIRST_FALSE_CLAIM, engine.claimNaturalLimit())
                val saved = engine.serialize()
                NativeChineseChessEngine().use { restored ->
                    assertEquals(RestoreResult.Restored, restored.restore(saved))
                    assertArrayEquals(saved, restored.serialize())
                    // Both sides checked eleven times, but each review excludes
                    // only its own excess: 120 - 2, never 120 - 4.
                    val from = RED_PATH[25 % RED_PATH.size]
                    val to = RED_PATH[26 % RED_PATH.size]
                    play(restored, rotated, from.first, from.second, to.first, to.second)
                    assertReview(restored, second, 121)
                    assertEquals(NaturalLimitClaimOutcome.FIRST_FALSE_CLAIM, restored.claimNaturalLimit())
                    assertEquals(RestoreResult.Restored, engine.restore(restored.serialize()))
                    val replyFrom = BLACK_PATH[30 % BLACK_PATH.size]
                    val replyTo = BLACK_PATH[31 % BLACK_PATH.size]
                    play(engine, rotated, replyFrom.first, replyFrom.second, replyTo.first, replyTo.second)
                    listOf(first, second).forEach { side -> assertReview(engine, side, 122) }
                    assertEquals(NaturalLimitClaimOutcome.DRAW, engine.claimNaturalLimit())
                    assertEquals(GameResult.DRAW, engine.gameResult())
                }
            }
        }
    }

    @Test
    fun aCounterCheckBelongsToTheRespondingSideAndSurvivesRestoreAndUndo() {
        for (rotated in listOf(false, true)) {
            val first = if (rotated) ChineseChessSide.BLACK else ChineseChessSide.RED
            val second = if (rotated) ChineseChessSide.RED else ChineseChessSide.BLACK
            NativeChineseChessEngine().use { engine ->
                restoreInitial(engine, rotated, counterCheck = true)
                play(engine, rotated, 0, 6, 4, 6)
                assertTrue(engine.isInCheck(second))
                // The cannon interposes and reveals its rook's counter-check.
                play(engine, rotated, 5, 2, 4, 2)
                assertFalse(engine.isInCheck(second))
                assertTrue(engine.isInCheck(first))
                listOf(first, second).forEach { side ->
                    val review = engine.naturalLimitReview(side)
                    assertEquals(1, review.claimantChecks)
                    assertEquals(2, review.recordedPlies)
                    assertEquals(2, review.effectivePlies)
                    assertTrue(review.completeRecord)
                }
                NativeChineseChessEngine().use { restored ->
                    assertEquals(RestoreResult.Restored, restored.restore(engine.serialize()))
                    assertEquals(engine.naturalLimitReview(second), restored.naturalLimitReview(second))
                    assertTrue(restored.undo())
                    assertEquals(1, restored.naturalLimitReview(first).claimantChecks)
                    assertEquals(0, restored.naturalLimitReview(second).claimantChecks)
                    play(restored, rotated, 5, 2, 4, 2)
                    assertEquals(1, restored.naturalLimitReview(second).claimantChecks)
                }
            }
        }
    }

    private fun assertReview(engine: NativeChineseChessEngine, side: ChineseChessSide, raw: Int) {
        val review = engine.naturalLimitReview(side)
        assertEquals(raw, review.noCapturePlies)
        assertEquals(raw, review.recordedPlies)
        assertEquals(11, review.claimantChecks)
        assertEquals(raw - 2, review.effectivePlies)
        assertTrue(review.completeRecord)
        assertEquals(raw >= 122, review.eligible)
    }

    private fun restoreInitial(engine: NativeChineseChessEngine, rotated: Boolean, counterCheck: Boolean) {
        val fen = when {
            counterCheck && rotated -> "3k5/9/9/8r/9/9/9/3C5/9/3RK4 b - - 0 1"
            counterCheck -> "4kr3/9/5c3/9/9/9/R8/9/9/5K3 w - - 0 1"
            rotated -> "3k5/9/9/8r/9/3p5/9/9/9/2R1K4 b - - 0 1"
            else -> "4k1r2/9/9/9/5P3/9/R8/9/9/5K3 w - - 0 1"
        }
        assertEquals(RestoreResult.Restored, engine.restore(ChineseChessFenCodec.parse(fen).toEngineState()))
    }

    private fun play(engine: NativeChineseChessEngine, rotated: Boolean, fx: Int, fy: Int, tx: Int, ty: Int) {
        fun position(x: Int, y: Int) = if (rotated) BoardPosition(8 - x, 9 - y) else BoardPosition(x, y)
        assertEquals(ActionResult.Accepted, engine.apply(BoardMove(position(fx, fy), position(tx, ty))))
        assertEquals(GameResult.ONGOING, engine.gameResult())
    }

    private companion object {
        val RED_PATH = listOf(
            0 to 6, 1 to 6, 2 to 6, 3 to 6, 3 to 7, 3 to 8,
            3 to 9, 2 to 9, 1 to 9, 0 to 9, 0 to 8, 0 to 7,
        )
        val BLACK_PATH = listOf(
            6 to 0, 7 to 0, 8 to 0, 8 to 1, 8 to 2,
            8 to 3, 7 to 3, 6 to 3, 6 to 2, 6 to 1,
        )
        val CHECKING_ROUNDS = listOf(
            intArrayOf(0, 6, 4, 6, 4, 0, 3, 0), intArrayOf(4, 6, 3, 6, 3, 0, 4, 0),
            intArrayOf(3, 6, 3, 7, 6, 0, 7, 0), intArrayOf(3, 7, 4, 7, 4, 0, 3, 0),
            intArrayOf(4, 7, 3, 7, 3, 0, 4, 0), intArrayOf(3, 7, 3, 8, 7, 0, 8, 0),
            intArrayOf(3, 8, 4, 8, 4, 0, 3, 0), intArrayOf(4, 8, 3, 8, 3, 0, 4, 0),
            intArrayOf(3, 8, 3, 9, 8, 0, 8, 1), intArrayOf(3, 9, 4, 9, 4, 0, 3, 0),
            intArrayOf(4, 9, 3, 9, 3, 0, 4, 0), intArrayOf(3, 9, 3, 5, 8, 1, 8, 2),
            intArrayOf(3, 5, 4, 5, 4, 0, 3, 0), intArrayOf(4, 5, 3, 5, 3, 0, 4, 0),
            intArrayOf(3, 5, 4, 5, 4, 0, 3, 0), intArrayOf(4, 5, 0, 5, 3, 0, 4, 0),
            intArrayOf(0, 5, 0, 6, 4, 0, 5, 0), intArrayOf(5, 9, 4, 9, 8, 2, 4, 2),
            intArrayOf(4, 9, 3, 9, 4, 2, 3, 2), intArrayOf(3, 9, 4, 9, 3, 2, 3, 1),
            intArrayOf(0, 6, 1, 6, 3, 1, 4, 1), intArrayOf(4, 9, 3, 9, 4, 1, 3, 1),
            intArrayOf(3, 9, 4, 9, 3, 1, 3, 0), intArrayOf(1, 6, 2, 6, 3, 0, 4, 0),
            intArrayOf(4, 9, 3, 9, 4, 0, 3, 0), intArrayOf(3, 9, 4, 9, 3, 0, 3, 3),
            intArrayOf(2, 6, 2, 7, 3, 3, 4, 3), intArrayOf(4, 9, 3, 9, 4, 3, 3, 3),
            intArrayOf(3, 9, 4, 9, 3, 3, 3, 4), intArrayOf(2, 7, 1, 7, 3, 4, 4, 4),
            intArrayOf(4, 9, 3, 9, 4, 4, 3, 4), intArrayOf(3, 9, 4, 9, 3, 4, 4, 4),
            intArrayOf(4, 9, 5, 9, 4, 4, 4, 2), intArrayOf(1, 7, 0, 7, 4, 2, 8, 2),
            intArrayOf(0, 7, 0, 6, 8, 2, 8, 3),
        )
    }
}
