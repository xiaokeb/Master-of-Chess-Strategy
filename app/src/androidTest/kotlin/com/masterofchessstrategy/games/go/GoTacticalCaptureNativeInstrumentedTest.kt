package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.*
import org.junit.Assert.*
import org.junit.Test

class GoTacticalCaptureNativeInstrumentedTest {
    @Test fun nativeFullDefenseProofAndBothFirstCapturesCompleteInTwoBlackMoves() {
        val plan = GoTacticalCaptureLibrary.open()
        val puzzle = plan.puzzle
        NativeGoEngine(9).use { engine ->
            assertEquals(RestoreResult.Restored, engine.restore(plan.copyInitialBytes()))
            assertEquals(ActionResult.Accepted, engine.apply(GoAction.Place(puzzle.firstMove)))
            assertTrue(GoAction.Pass in engine.legalActions())
            assertEquals(engine.legalActions().size, plan.firstMoveProof.defenses)
            assertEquals(GoCaptureProofResult.PROVEN, plan.firstMoveProof.result)
        }
        for ((first, second) in listOf(puzzle.firstMove to BoardPosition(8, 7), BoardPosition(8, 7) to puzzle.firstMove)) {
            val session = GoTacticalCaptureSession(plan)
            val initial = session.copyCheckpointBytes()
            val rejected = session.answer(BoardPosition(4, 0))
            assertFalse(rejected.completed); assertArrayEquals(initial, session.copyCheckpointBytes())
            val step = session.answer(first)
            assertFalse(step.completed); assertEquals(1, step.blackMoves); assertNotNull(step.defense)
            NativeGoEngine(9).use { engine ->
                assertEquals(RestoreResult.Restored, engine.restore(session.copyCheckpointBytes()))
                assertEquals(GoPlayer.BLACK.code, engine.currentPlayer.value)
                assertEquals(puzzle.setup.size + 2, engine.eventHistory().size)
            }
            val solved = session.answer(second)
            assertTrue(solved.completed); assertEquals(2, solved.blackMoves)
            assertArrayEquals(initial, session.reset().let { session.copyCheckpointBytes() })
        }
    }
}
