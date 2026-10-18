package com.masterofchessstrategy.engine.games.xiangqi

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.masterofchessstrategy.engine.ActionResult
import com.masterofchessstrategy.engine.BoardMove
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.ChineseChessFenCodec
import com.masterofchessstrategy.engine.ChineseChessRuleRevision
import com.masterofchessstrategy.engine.EngineError
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.NativeChineseChessEngine
import com.masterofchessstrategy.engine.NaturalLimitClaimOutcome
import com.masterofchessstrategy.engine.RestoreResult
import java.util.zip.CRC32
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exercises the real JNI judge, not a Kotlin reconstruction of move nature. */
@RunWith(AndroidJUnit4::class)
class NativeXiangqiRuleRevisionInstrumentedTest {
    @Test
    fun displayedRuleMetadataTracksRealRestoreAndResetButNotRejectedRevision() {
        NativeChineseChessEngine().use { engine ->
            assertEquals(ChineseChessRuleRevision.CONTINUOUS_CHECKING_MATE, engine.ruleRevision)
            val current = engine.serialize()
            assertEquals(RestoreResult.Restored, engine.restore(asV3(current)))
            assertEquals(ChineseChessRuleRevision.LEGACY_COMPATIBILITY, engine.ruleRevision)
            val unknown = current.copyOf().apply { this[size - 5] = 2 }
            reseal(unknown)
            assertEquals(RestoreResult.Rejected(EngineError.UNSUPPORTED), engine.restore(unknown))
            assertEquals(ChineseChessRuleRevision.LEGACY_COMPATIBILITY, engine.ruleRevision)
            val oldV4 = current.copyOf().apply { this[size - 5] = 0 }
            reseal(oldV4)
            assertEquals(RestoreResult.Restored, engine.restore(oldV4))
            assertEquals(ChineseChessRuleRevision.LEGACY_COMPATIBILITY, engine.ruleRevision)
            assertEquals(ActionResult.Accepted, engine.apply(BoardMove(BoardPosition(0, 6), BoardPosition(0, 5))))
            assertEquals(ChineseChessRuleRevision.LEGACY_COMPATIBILITY, engine.ruleRevision)
            assertTrue(engine.undo())
            assertEquals(ChineseChessRuleRevision.LEGACY_COMPATIBILITY, engine.ruleRevision)
            engine.reset()
            assertEquals(ChineseChessRuleRevision.CONTINUOUS_CHECKING_MATE, engine.ruleRevision)
        }
    }

    @Test
    fun continuousKillCycleLosesOnlyUnderCurrentRevisionAndSurvivesReopenAndUndo() {
        for (rotated in listOf(false, true)) for (oldRules in listOf(true, false)) {
            val fen = if (rotated) {
                "9/9/3k5/9/9/3npn3/2c2cp2/5A3/4K4/6B2 b - - 0 1"
            } else {
                "2b6/4k4/3a5/2PC2C2/3NPN3/9/9/5K3/9/9 w - - 0 1"
            }
            NativeChineseChessEngine().use { engine ->
                assertEquals(RestoreResult.Restored, engine.restore(ChineseChessFenCodec.parse(fen).toEngineState()))
                if (oldRules) {
                    assertEquals(RestoreResult.Restored, engine.restore(asV3(engine.serialize())))
                }
                val moves = CYCLE.map { coordinates ->
                    fun position(x: Int, y: Int) = if (rotated) BoardPosition(8 - x, 9 - y) else BoardPosition(x, y)
                    BoardMove(position(coordinates[0], coordinates[1]), position(coordinates[2], coordinates[3]))
                }
                moves.forEach { assertEquals(ActionResult.Accepted, engine.apply(it)) }
                assertEquals(GameResult.ONGOING, engine.gameResult())
                val live = engine.serialize()
                NativeChineseChessEngine().use { reopened ->
                    assertEquals(RestoreResult.Restored, reopened.restore(live))
                    assertArrayEquals(live, reopened.serialize())
                    moves.forEach { assertEquals(ActionResult.Accepted, reopened.apply(it)) }
                    val expected = if (oldRules) GameResult.DRAW else if (rotated) {
                        GameResult.FIRST_PLAYER_WIN
                    } else {
                        GameResult.SECOND_PLAYER_WIN
                    }
                    assertEquals(expected, reopened.gameResult())
                    val terminal = reopened.serialize()
                    assertEquals(if (oldRules) 3 else 4, terminal[4].toInt())
                    if (!oldRules) assertEquals(1, terminal[terminal.size - 5].toInt())
                    assertEquals(RestoreResult.Restored, engine.restore(terminal))
                    assertArrayEquals(terminal, engine.serialize())
                    assertEquals(expected, engine.gameResult())
                    // A valid CRC cannot authorize changing a completed
                    // game to the other revision while keeping its verdict.
                    val otherRevision = if (oldRules) {
                        terminal.copyOf(terminal.size + 1).apply {
                            this[4] = 4
                            this[size - 5] = 1
                        }
                    } else {
                        terminal.copyOf().apply { this[size - 5] = 0 }
                    }
                    reseal(otherRevision)
                    assertEquals(RestoreResult.Rejected(EngineError.CORRUPTED_DATA), engine.restore(otherRevision))
                    assertArrayEquals(terminal, engine.serialize())
                    assertTrue(engine.undo())
                    assertEquals(GameResult.ONGOING, engine.gameResult())
                    assertEquals(ActionResult.Accepted, engine.apply(moves.last()))
                    assertArrayEquals(terminal, engine.serialize())
                    assertTrue(engine.apply(moves.first()) is ActionResult.Rejected)
                }
            }
        }
    }

    @Test
    fun unsupportedOrMalformedRuleRevisionRejectsWithoutReplacingTheLiveGame() {
        NativeChineseChessEngine().use { engine ->
            assertEquals(ActionResult.Accepted, engine.apply(BoardMove(BoardPosition(0, 6), BoardPosition(0, 5))))
            val current = engine.serialize()
            assertEquals(4, current[4].toInt())
            for (revision in listOf(2, 255)) {
                val unknown = current.copyOf().apply { this[size - 5] = revision.toByte() }
                reseal(unknown)
                assertEquals(RestoreResult.Rejected(EngineError.UNSUPPORTED), engine.restore(unknown))
                assertArrayEquals(current, engine.serialize())
            }
            for (malformed in listOf(current.copyOf(current.size - 1), current.copyOf(current.size + 1))) {
                reseal(malformed)
                assertEquals(RestoreResult.Rejected(EngineError.CORRUPTED_DATA), engine.restore(malformed))
                assertArrayEquals(current, engine.serialize())
            }
            val forged = current.copyOf().apply { this[9] = 3 }
            reseal(forged)
            assertEquals(RestoreResult.Rejected(EngineError.CORRUPTED_DATA), engine.restore(forged))
            assertArrayEquals(current, engine.serialize())
            assertEquals(RestoreResult.Restored, engine.restore(asV3(current)))
            assertEquals(3, engine.serialize()[4].toInt())
            engine.reset()
            val fresh = engine.serialize()
            assertEquals(4, fresh[4].toInt())
            assertEquals(1, fresh[fresh.size - 5].toInt())
        }
    }

    @Test
    fun ruleMarkerDoesNotDisplaceNaturalLimitClaimEvents() {
        NativeChineseChessEngine().use { engine ->
            assertEquals(ActionResult.Accepted, engine.apply(BoardMove(BoardPosition(0, 6), BoardPosition(0, 5))))
            assertEquals(NaturalLimitClaimOutcome.FIRST_FALSE_CLAIM, engine.claimNaturalLimit())
            val firstClaim = engine.serialize()
            assertEquals(4, firstClaim[4].toInt())
            assertEquals(1, firstClaim[firstClaim.size - 5].toInt())
            NativeChineseChessEngine().use { reopened ->
                assertEquals(RestoreResult.Restored, reopened.restore(firstClaim))
                assertArrayEquals(firstClaim, reopened.serialize())
                assertEquals(NaturalLimitClaimOutcome.SECOND_FALSE_CLAIM_LOSS, reopened.claimNaturalLimit())
                assertEquals(GameResult.FIRST_PLAYER_WIN, reopened.gameResult())
                val terminal = reopened.serialize()
                assertEquals(RestoreResult.Restored, engine.restore(terminal))
                assertArrayEquals(terminal, engine.serialize())
                assertEquals(GameResult.FIRST_PLAYER_WIN, engine.gameResult())
                // legalActions remains a geometric query; apply is the gate
                // that rejects play after an adjudicated terminal result.
                assertEquals(
                    ActionResult.Rejected(EngineError.INVALID_STATE),
                    engine.apply(BoardMove(BoardPosition(0, 3), BoardPosition(0, 4))),
                )
            }
        }
    }

    private fun asV3(current: ByteArray): ByteArray {
        require(current[4].toInt() == 4)
        return current.copyOf(current.size - 1).apply {
            this[4] = 3
            reseal(this)
        }
    }

    private fun reseal(data: ByteArray) {
        val crc = CRC32().apply { update(data, 0, data.size - 4) }.value
        repeat(4) { byte -> data[data.size - 4 + byte] = (crc ushr (8 * byte)).toByte() }
    }

    private companion object {
        val CYCLE = listOf(
            intArrayOf(3, 3, 4, 3), intArrayOf(4, 1, 3, 1),
            intArrayOf(4, 3, 3, 3), intArrayOf(3, 1, 4, 1),
        )
    }
}
