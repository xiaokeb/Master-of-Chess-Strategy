package com.masterofchessstrategy.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.masterofchessstrategy.engine.internal.NativeBindings
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeGoRecordsInstrumentedTest {
    @Test fun handicapAndBoardSizeImportUseRealParser() {
        for (size in listOf(9, 13, 19)) for (handicap in 2..9) {
            NativeGoEngine(size, 0, handicap).use { source ->
                assertEquals(ActionResult.Accepted, source.apply(GoAction.Place(BoardPosition(0, 0))))
                val sgf = source.exportSgf()
                val result = NativeGoSgfImporter.importSgf(sgf)
                assertTrue(result is GoSgfImportResult.Imported)
                (result as GoSgfImportResult.Imported).engine.use { candidate ->
                    assertEquals(size, candidate.boardSize)
                    assertEquals(handicap, candidate.handicapStones)
                    assertEquals(0, candidate.blackKomiQuarters)
                    assertEquals(GoStone.WHITE, candidate.stoneAt(BoardPosition(0, 0)))
                    assertEquals(source.currentPlayer, candidate.currentPlayer)
                    assertEquals(source.eventHistory(), candidate.eventHistory())
                    assertEquals(sgf, candidate.exportSgf())
                }
            }
        }
        // A misleading property inside a comment must not choose the board size.
        val imported = NativeGoSgfImporter.importSgf("(;FF[4]GM[1]SZ[13]C[文本 SZ\\[9\\] 😀];B[mm])")
        assertTrue(imported is GoSgfImportResult.Imported)
        (imported as GoSgfImportResult.Imported).engine.use {
            assertEquals(13, it.boardSize)
            assertEquals(GoStone.BLACK, it.stoneAt(BoardPosition(12, 12)))
            assertEquals(15, it.blackKomiQuarters)
        }
    }

    @Test fun fullEventsPreserveAgreementButSgfCannotRestoreAFinalResult() {
        NativeGoEngine(9).use { game ->
            val events = mutableListOf<GoAction>()
            fun apply(action: GoAction) {
                assertEquals(ActionResult.Accepted, game.apply(action))
                events.add(action)
                assertEquals(events, game.eventHistory())
            }
            apply(GoAction.Place(BoardPosition(0, 0)))
            apply(GoAction.Pass)
            apply(GoAction.Pass)
            apply(GoAction.ToggleDeadGroup(BoardPosition(0, 0), GoPlayer.WHITE))
            apply(GoAction.ResumePlay(GoPlayer.WHITE))
            apply(GoAction.Place(BoardPosition(1, 1)))
            apply(GoAction.Pass)
            apply(GoAction.Pass)
            val revision = game.scoringState().proposalRevision
            apply(GoAction.ConfirmScore(GoPlayer.BLACK, revision))
            apply(GoAction.ConfirmScore(GoPlayer.WHITE, revision))
            assertEquals(GoPhase.FINISHED, game.phase)
            NativeGoEngine(9).use { full ->
                assertEquals(RestoreResult.Restored, full.restore(game.serialize()))
                assertEquals(events, full.eventHistory())
                assertEquals(game.finalScore(), full.finalScore())
            }
            val sgf = game.exportSgf()
            assertTrue(sgf.contains(";PL[W];W[bb]"))
            NativeGoEngine(9).use { moves ->
                assertEquals(RestoreResult.Restored, moves.restoreSgf(sgf))
                assertEquals(GoPhase.SCORING, moves.phase)
                assertNull(moves.finalScore())
                assertEquals(GameResult.ONGOING, moves.gameResult())
                assertFalse(moves.scoringState().blackConfirmed)
                assertFalse(moves.scoringState().whiteConfirmed)
                assertEquals(events.filter { it !is GoAction.ToggleDeadGroup && it !is GoAction.ConfirmScore },
                    moves.eventHistory())
                assertTrue(moves.undo())
                assertEquals(GoAction.Pass, moves.eventHistory().last())
            }
            assertTrue(game.undo())
            events.removeAt(events.lastIndex)
            assertEquals(events, game.eventHistory())
            assertEquals(GoPhase.SCORING, game.phase)
        }
    }

    @Test fun rejectedImportsAreAtomicAndResetClearsHistory() {
        NativeGoEngine(9).use { game ->
            assertEquals(ActionResult.Accepted, game.apply(GoAction.Place(BoardPosition(0, 0))))
            val save = game.serialize()
            val events = game.eventHistory()
            val sgf = game.exportSgf()
            for (bad in listOf("", "(;FF[4]GM[1]SZ[13];B[aa])", "(;FF[4]GM[1]SZ[9];W[aa])",
                "(;FF[4]GM[1]SZ[9];B[aa];W[aa])", "(;FF[4]GM[1]SZ[9](;B[aa])(;B[bb]))",
                "(;FF[4]GM[1]SZ[9]RE[B+R])", "\uD800", "a".repeat(1_000_001))) {
                assertEquals(RestoreResult.Rejected(EngineError.CORRUPTED_DATA), game.restoreSgf(bad))
                assertArrayEquals(save, game.serialize())
                assertEquals(events, game.eventHistory())
                assertEquals(sgf, game.exportSgf())
            }
            game.reset()
            assertTrue(game.eventHistory().isEmpty())
            assertFalse(game.undo())
        }
    }

    @Test fun closedAndWrongGameHandlesCannotReadOrReplaceRecords() {
        val game = NativeGoEngine(9)
        game.close()
        game.close()
        assertThrows(IllegalStateException::class.java) { game.exportSgf() }
        assertThrows(IllegalStateException::class.java) { game.eventHistory() }
        assertThrows(IllegalStateException::class.java) { game.restoreSgf("") }
        val chess = NativeBindings.createChineseChessEngine()
        try {
            assertThrows(IllegalStateException::class.java) { NativeBindings.exportGoSgf(chess) }
            assertThrows(IllegalStateException::class.java) { NativeBindings.goEventHistory(chess) }
            assertThrows(IllegalStateException::class.java) {
                NativeBindings.restoreGoSgf(chess, "(;SZ[9])".toByteArray())
            }
            assertTrue(NativeBindings.serializeChineseChess(chess).isNotEmpty())
        } finally {
            NativeBindings.releaseChineseChessEngine(chess)
        }
    }
}
