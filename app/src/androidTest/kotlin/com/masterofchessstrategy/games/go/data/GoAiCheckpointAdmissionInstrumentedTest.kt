package com.masterofchessstrategy.games.go.data

import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.games.go.*
import org.junit.Assert.*
import org.junit.Test

/** Actual native restoration/score and owned AtomicFile; no NN search, Room rewards or strength claims. */
class GoAiCheckpointAdmissionInstrumentedTest {
    @Test fun threeSizesAndHandicapsKeepPlayingReviewAndAgreedTerminalDistinct() {
        for (size in listOf(9, 13, 19)) for (handicap in listOf(0, 5)) {
            val config = GoAiMatchConfig(size, handicap, GoPlayer.WHITE)
            NativeGoEngine(size, if (handicap == 0) 15 else 0, handicap).use { engine ->
                accepted(engine, GoAction.Place(BoardPosition(0, 0)))
                accepted(engine, GoAction.Place(BoardPosition(size - 1, size - 1)))
                accepted(engine, GoAction.Pass)
                val opening = engine.serialize()
                accepted(engine, GoAction.Place(BoardPosition(1, 0)))
                fun saved(reserved: Boolean = false) = GoAiSavedMatch("native-$size-$handicap", config,
                    engine.serialize(), completionReserved = reserved, openingBytes = opening)
                val playing = admitGoAiCheckpoint(saved())
                assertEquals(GoPhase.PLAYING, playing.phase)
                assertEquals(3, playing.openingEventCount)
                assertEquals(4, playing.moveCount)
                accepted(engine, GoAction.Pass); accepted(engine, GoAction.Pass)
                assertEquals(GoPhase.SCORING, admitGoAiCheckpoint(saved()).phase)
                rejected { admitGoAiCheckpoint(saved(reserved = true)) }
                val revision = engine.scoringState().proposalRevision
                accepted(engine, GoAction.ConfirmScore(GoPlayer.BLACK, revision))
                assertEquals(GameResult.ONGOING, admitGoAiCheckpoint(saved()).result)
                accepted(engine, GoAction.ConfirmScore(GoPlayer.WHITE, revision))
                val finished = admitGoAiCheckpoint(saved(reserved = true))
                assertEquals(GoPhase.FINISHED, finished.phase)
                assertEquals(engine.finalScore()!!.result, finished.result)
                assertEquals(6, finished.moveCount)
                assertEquals(8, finished.eventCount)
                assertEquals(config, finished.match().config)
                assertArrayEquals(engine.serialize(), finished.match().copyBytes())
            }
        }
    }

    @Test fun theSameFinalBoardCannotReplaceTheSpecifiedHistoryPrefix() {
        val original = listOf(BoardPosition(0, 0), BoardPosition(8, 8), BoardPosition(1, 0), BoardPosition(7, 8))
        val alternative = listOf(original[2], original[3], original[0], original[1])
        val opening = NativeGoEngine(9).use { engine ->
            original.forEach { accepted(engine, GoAction.Place(it)) }
            engine.serialize()
        }
        val current = NativeGoEngine(9).use { engine ->
            alternative.forEach { accepted(engine, GoAction.Place(it)) }
            NativeGoEngine(9).use { reference ->
                assertEquals(RestoreResult.Restored, reference.restore(opening))
                for (y in 0..8) for (x in 0..8) assertEquals(reference.stoneAt(BoardPosition(x, y)), engine.stoneAt(BoardPosition(x, y)))
            }
            engine.serialize()
        }
        rejected { admitGoAiCheckpoint(GoAiSavedMatch("prefix", GoAiMatchConfig(9), current, openingBytes = opening)) }
    }

    @Test fun aValidOuterEnvelopeCannotHideCorruptNativeBytesOrChangedConfiguration() {
        val valid = NativeGoEngine(9).use { it.serialize() }
        val corrupted = valid.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }
        val forged = GoAiSavedMatch("bad-native", GoAiMatchConfig(9), corrupted)
        assertNotNull(GoAiMatchFileCodec.decode(GoAiMatchFileCodec.encode(forged)))
        rejected { admitGoAiCheckpoint(forged) }
        rejected { admitGoAiCheckpoint(GoAiSavedMatch("wrong-size", GoAiMatchConfig(13), valid)) }
        rejected { admitGoAiCheckpoint(GoAiSavedMatch("wrong-handicap", GoAiMatchConfig(9, 5), valid)) }
        rejected { admitGoAiCheckpoint(GoAiSavedMatch("false-finish", GoAiMatchConfig(9), valid, completionReserved = true)) }
    }

    private fun accepted(engine: GoRuleEngine, action: GoAction) { assertEquals(ActionResult.Accepted, engine.apply(action)) }
    private fun rejected(block: () -> Unit) {
        try { block(); fail("Invalid native AI checkpoint accepted") } catch (_: IllegalArgumentException) { }
    }
}
