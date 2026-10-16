package com.masterofchessstrategy.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeGoEngineInstrumentedTest {
    @Test
    fun agreementRequiresBothPlayersAndSurvivesRestore() {
        NativeGoEngine(boardSize = 9).use { engine ->
            assertEquals(GameType.GO, engine.gameType)
            assertEquals(GoStone.EMPTY, engine.stoneAt(BoardPosition(0, 0)))
            assertEquals(GoPhase.PLAYING, engine.phase)
            assertNull(engine.previewScore())
            assertNull(engine.finalScore())
            assertTrue(GoAction.Place(BoardPosition(0, 0)) in engine.legalActions())

            assertEquals(ActionResult.Accepted, engine.apply(GoAction.Place(BoardPosition(0, 0))))
            assertEquals(GoStone.BLACK, engine.stoneAt(BoardPosition(0, 0)))
            assertEquals(PlayerId(1), engine.currentPlayer)
            assertEquals(ActionResult.Accepted, engine.apply(GoAction.Pass))
            assertEquals(ActionResult.Accepted, engine.apply(GoAction.Pass))
            assertEquals(GoPhase.SCORING, engine.phase)
            assertNull(engine.finalScore())
            val proposal = engine.scoringState().proposalRevision
            assertTrue(proposal > 0)

            assertEquals(ActionResult.Accepted,
                engine.apply(GoAction.ToggleDeadGroup(BoardPosition(0, 0), GoPlayer.WHITE)))
            val changed = engine.scoringState()
            assertTrue(changed.proposalRevision > proposal)
            assertEquals(listOf(BoardPosition(0, 0)), changed.deadStones)
            assertEquals(ActionResult.Rejected(EngineError.ILLEGAL_ACTION),
                engine.apply(GoAction.ConfirmScore(GoPlayer.BLACK, proposal)))
            assertEquals(ActionResult.Accepted,
                engine.apply(GoAction.ConfirmScore(GoPlayer.BLACK, changed.proposalRevision)))
            assertNull(engine.finalScore())
            assertEquals(ActionResult.Accepted,
                engine.apply(GoAction.ConfirmScore(GoPlayer.WHITE, changed.proposalRevision)))
            assertEquals(GoPhase.FINISHED, engine.phase)
            assertEquals(engine.previewScore(), engine.finalScore())

            val saved = engine.serialize()
            NativeGoEngine(boardSize = 9).use { restored ->
                assertEquals(RestoreResult.Restored, restored.restore(saved))
                assertEquals(engine.scoringState(), restored.scoringState())
                assertEquals(engine.finalScore(), restored.finalScore())
                assertTrue(restored.undo())
                assertEquals(GoPhase.SCORING, restored.phase)
                assertNull(restored.finalScore())
                assertFalse(restored.scoringState().whiteConfirmed)
                assertEquals(ActionResult.Accepted,
                    restored.apply(GoAction.ResumePlay(GoPlayer.WHITE)))
                assertEquals(GoPhase.PLAYING, restored.phase)
                assertEquals(PlayerId(1), restored.currentPlayer)
            }
        }
    }

    @Test
    fun goAndChineseChessSessionsRemainIndependent() {
        NativeGoEngine(boardSize = 13, initialBlackKomiQuarters = 0).use { go ->
            NativeChineseChessEngine().use { chess ->
                assertEquals(ActionResult.Accepted,
                    go.apply(GoAction.Place(BoardPosition(12, 12))))
                assertEquals(GoStone.BLACK, go.stoneAt(BoardPosition(12, 12)))
                assertEquals(ChineseChessPieceType.GENERAL,
                    chess.pieceAt(BoardPosition(4, 9))?.type)
            }
            assertEquals(PlayerId(1), go.currentPlayer)
            assertEquals(GoStone.BLACK, go.stoneAt(BoardPosition(12, 12)))
        }
    }

    @Test
    fun fixedHandicapUsesWhiteFirstAndKeepsScoringAdjustment() {
        NativeGoEngine(
            boardSize = 9,
            initialBlackKomiQuarters = 0,
            initialHandicapStones = 2,
        ).use { game ->
            assertEquals(2, game.handicapStones)
            assertEquals(0, game.blackKomiQuarters)
            assertEquals(PlayerId(1), game.currentPlayer)
            assertEquals(GoStone.BLACK, game.stoneAt(BoardPosition(2, 6)))
            assertEquals(GoStone.BLACK, game.stoneAt(BoardPosition(6, 2)))
            assertEquals(ActionResult.Accepted, game.apply(GoAction.Pass))
            assertEquals(ActionResult.Accepted, game.apply(GoAction.Pass))
            assertEquals(8, game.previewScore()?.handicapAdjustmentQuarters)

            val saved = game.serialize()
            NativeGoEngine(boardSize = 9).use { restored ->
                assertEquals(RestoreResult.Restored, restored.restore(saved))
                assertEquals(2, restored.handicapStones)
                assertEquals(0, restored.blackKomiQuarters)
                assertEquals(game.scoringState(), restored.scoringState())
            }
        }
    }
}
