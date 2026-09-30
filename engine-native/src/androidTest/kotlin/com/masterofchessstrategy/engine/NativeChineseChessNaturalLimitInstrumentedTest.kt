package com.masterofchessstrategy.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
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
