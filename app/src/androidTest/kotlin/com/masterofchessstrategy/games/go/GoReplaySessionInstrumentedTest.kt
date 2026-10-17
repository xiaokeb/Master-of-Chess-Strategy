package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.*
import org.junit.Assert.*
import org.junit.Test

/** Real Native replay only; no active session, Room or reward writes. */
class GoReplaySessionInstrumentedTest {
    private fun place(x: Int, y: Int) = GoAction.Place(BoardPosition(x, y))
    private fun apply(engine: GoRecordEngine, action: GoAction) { assertEquals(ActionResult.Accepted, engine.apply(action)) }

    @Test fun allSizesPreserveFixedSetupEveryPrefixAndFullExport() {
        for (size in listOf(9, 13, 19)) NativeGoEngine(size, 0, 5).use { original ->
            val setup = original.serialize()
            apply(original, place(0, 0))
            apply(original, place(size - 1, size - 1))
            apply(original, GoAction.Pass)
            val saved = original.serialize()
            val sgf = original.exportSgf()
            GoReplaySession.openSnapshot(saved).use { replay ->
                assertEquals(5, replay.frame().handicapStones)
                assertEquals(GoPlayer.WHITE, replay.frame().currentPlayer)
                NativeGoEngine(size).use { verifier ->
                    assertEquals(RestoreResult.Restored, verifier.restore(setup))
                    assertEquals(stones(verifier), replay.frame().stones)
                    for ((index, action) in original.eventHistory().withIndex()) {
                        apply(verifier, action)
                        val frame = replay.seek(index + 1)
                        assertEquals(stones(verifier), frame.stones)
                        assertEquals(verifier.currentPlayer.value, frame.currentPlayer.code)
                        assertEquals(verifier.phase, frame.phase)
                    }
                }
                replay.seek(1)
                assertEquals(sgf, replay.exportSgf())
                assertArrayEquals(saved, original.serialize())
            }
        }
    }

    @Test fun finishedRecordRetainsProposalConfirmationsAndFinalResult() {
        NativeGoEngine(9).use { original ->
            apply(original, place(0, 0))
            apply(original, place(1, 0))
            apply(original, GoAction.Pass)
            apply(original, GoAction.Pass)
            apply(original, GoAction.ToggleDeadGroup(BoardPosition(1, 0), GoPlayer.BLACK))
            val revision = original.scoringState().proposalRevision
            apply(original, GoAction.ConfirmScore(GoPlayer.BLACK, revision))
            apply(original, GoAction.ConfirmScore(GoPlayer.WHITE, revision))
            val bytes = original.serialize()
            GoReplaySession.openSnapshot(bytes).use { replay ->
                val proposal = replay.seek(5)
                assertEquals(GoPhase.SCORING, proposal.phase)
                assertEquals(setOf(1), proposal.deadStoneIndices)
                assertEquals(GameResult.ONGOING, proposal.result)
                assertNull(proposal.finalScore)
                val one = replay.seek(6)
                assertTrue(one.blackConfirmed)
                assertFalse(one.whiteConfirmed)
                val end = replay.seek(7)
                assertEquals(GoPhase.FINISHED, end.phase)
                assertEquals(original.finalScore(), end.finalScore)
                assertEquals(original.gameResult(), end.result)
                assertEquals(4, end.moveCount)
                assertArrayEquals(bytes, original.serialize())
            }
        }
    }

    @Test fun sgfDoesNotInventFinalResultAndReplayRetainsResumePlayer() {
        NativeGoEngine(9).use { original ->
            apply(original, place(0, 0))
            apply(original, GoAction.Pass)
            apply(original, GoAction.Pass)
            apply(original, GoAction.ResumePlay(GoPlayer.BLACK))
            apply(original, place(1, 0))
            val bytes = original.serialize()
            GoReplaySession.openSgf(original.exportSgf()).use { replay ->
                assertEquals(GoPlayer.BLACK, replay.seek(4).currentPlayer)
                assertEquals(GoPhase.PLAYING, replay.frame().phase)
                assertEquals(GameResult.ONGOING, replay.seek(5).result)
                assertEquals(stones(original), replay.frame().stones)
                assertArrayEquals(bytes, original.serialize())
            }
        }
    }

    private fun stones(engine: GoRecordEngine) = List(engine.boardSize * engine.boardSize) {
        engine.stoneAt(BoardPosition(it % engine.boardSize, it / engine.boardSize)).code
    }
}
