package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.*
import org.junit.Assert.*
import org.junit.Test

class GoTacticalCaptureTest {
    private val puzzle = GoTacticalCaptureLibrary.twoCorners
    private val factory: (Int, Int, Int) -> GoRecordEngine = { size, _, _ -> GoEndgameLibraryTest.GridRules(size) }

    @Test fun firstMoveProofEnumeratesEveryLegalWhiteDefenseIncludingPass() {
        val plan = GoTacticalCaptureLibrary.open(engineFactory = factory)
        val legal = factory(9, 15, 0).use { engine ->
            assertEquals(RestoreResult.Restored, engine.restore(plan.copyInitialBytes()))
            assertEquals(ActionResult.Accepted, engine.apply(GoAction.Place(puzzle.firstMove)))
            engine.legalActions()
        }
        assertTrue(GoAction.Pass in legal)
        assertEquals(GoCaptureProofResult.PROVEN, plan.firstMoveProof.result)
        assertEquals(legal.size, plan.firstMoveProof.defenses)
        assertTrue(plan.firstMoveProof.defenses > 60)
    }

    @Test fun acceptedFirstMoveAndComputedDefenseDoNotGrantCompletionBeforeSecondCapture() {
        val plan = GoTacticalCaptureLibrary.open(engineFactory = factory)
        val session = GoTacticalCaptureSession(plan, factory)
        val first = session.answer(puzzle.firstMove)
        assertFalse(first.completed); assertEquals(1, first.blackMoves); assertNotNull(first.defense)
        factory(9, 15, 0).use { engine ->
            assertEquals(RestoreResult.Restored, engine.restore(session.copyCheckpointBytes()))
            assertEquals(GoPlayer.BLACK.code, engine.currentPlayer.value)
            assertEquals(puzzle.setup.size + 2, engine.eventHistory().size)
            assertEquals(first.defense, engine.eventHistory().last())
        }
        val solved = session.answer(BoardPosition(8, 7))
        assertTrue(solved.completed); assertEquals(2, solved.blackMoves)
        assertEquals(0, solved.stones[0]); assertEquals(0, solved.stones[80])
        val beforeRepeat = session.copyCheckpointBytes()
        assertEquals(solved, session.answer(BoardPosition(4, 0)))
        assertArrayEquals(beforeRepeat, session.copyCheckpointBytes())
    }

    @Test fun alternativeWinningFirstCaptureIsAcceptedWithoutScriptCoordinateMatching() {
        val session = GoTacticalCaptureSession(GoTacticalCaptureLibrary.open(engineFactory = factory), factory)
        val first = session.answer(BoardPosition(8, 7))
        assertFalse(first.completed); assertEquals(1, first.blackMoves)
        assertTrue(session.answer(BoardPosition(0, 1)).completed)
    }

    @Test fun refutedLegalOrIllegalMovesKeepCurrentCheckpointAndPlayerBudget() {
        val session = GoTacticalCaptureSession(GoTacticalCaptureLibrary.open(engineFactory = factory), factory)
        val initial = session.copyCheckpointBytes(); val stones = session.state().stones
        val wrong = session.answer(BoardPosition(4, 0))
        assertFalse(wrong.completed); assertEquals(0, wrong.blackMoves); assertEquals(stones, wrong.stones)
        assertTrue(wrong.feedback.contains("防守")); assertArrayEquals(initial, session.copyCheckpointBytes())
        val illegal = session.answer(puzzle.setup.first())
        assertEquals(0, illegal.blackMoves); assertEquals(2, illegal.attempts)
        assertArrayEquals(initial, session.copyCheckpointBytes())
    }

    @Test fun proofBudgetExhaustionCannotPublishMoveOrCompletion() {
        val plan = GoTacticalCaptureLibrary.open(engineFactory = factory)
        val session = GoTacticalCaptureSession(plan, factory, nodeLimit = 1)
        val initial = session.copyCheckpointBytes()
        val result = session.answer(puzzle.firstMove)
        assertFalse(result.completed); assertEquals(0, result.blackMoves)
        assertTrue(result.feedback.contains("超过限额")); assertArrayEquals(initial, session.copyCheckpointBytes())
        factory(9, 15, 0).use { engine ->
            engine.restore(initial); engine.apply(GoAction.Place(puzzle.firstMove))
            val proof = GoCaptureSolver(puzzle, factory, 1).afterBlackMove(engine.serialize(), 1)
            assertEquals(GoCaptureProofResult.UNRESOLVED, proof.result); assertEquals(1, proof.nodes)
        }
    }

    @Test fun resetRestoresEntireInitialHistoryAndClearsAcceptedMoves() {
        val session = GoTacticalCaptureSession(GoTacticalCaptureLibrary.open(engineFactory = factory), factory)
        val initial = session.copyCheckpointBytes(); val initialState = session.state()
        session.answer(puzzle.firstMove)
        assertEquals(initialState, session.reset()); assertArrayEquals(initial, session.copyCheckpointBytes())
    }

    @Test fun invalidReferenceFirstMoveAndUnresolvedPlanAreRejectedWithAllResourcesClosed() {
        val engines = mutableListOf<GoEndgameLibraryTest.GridRules>()
        val tracked: (Int, Int, Int) -> GoRecordEngine = { size, _, _ ->
            GoEndgameLibraryTest.GridRules(size).also(engines::add)
        }
        val bad = GoTacticalCapturePuzzle("wrong-first", "错误首着", 9, puzzle.setup, puzzle.targets,
            BoardPosition(4, 0), 2)
        assertInvalid { GoTacticalCaptureLibrary.open(bad, tracked) }
        assertInvalid { GoTacticalCaptureLibrary.open(puzzle, tracked, nodeLimit = 1) }
        assertTrue(engines.isNotEmpty()); assertTrue(engines.all { it.closed })
    }

    @Test fun successfulAndRefutedAnalysisAlsoCloseEveryCandidate() {
        val engines = mutableListOf<GoEndgameLibraryTest.GridRules>()
        val tracked: (Int, Int, Int) -> GoRecordEngine = { size, _, _ -> GoEndgameLibraryTest.GridRules(size).also(engines::add) }
        val plan = GoTacticalCaptureLibrary.open(engineFactory = tracked)
        val session = GoTacticalCaptureSession(plan, tracked)
        session.answer(BoardPosition(4, 0)); session.answer(puzzle.firstMove); session.answer(BoardPosition(8, 7))
        assertTrue(engines.all { it.closed })
    }

    @Test fun planProofAndStateBytesAreDefensiveCopies() {
        val plan = GoTacticalCaptureLibrary.open(engineFactory = factory)
        val expected = plan.copyInitialBytes(); plan.copyInitialBytes()[0] = 99
        assertArrayEquals(expected, plan.copyInitialBytes())
        val defense = plan.firstMoveProof.copyContinuationBytes()!!; val saved = defense.copyOf(); defense[0] = 98
        assertArrayEquals(saved, plan.firstMoveProof.copyContinuationBytes())
        val session = GoTacticalCaptureSession(plan, factory); val old = session.state().stones
        (session.state().stones as MutableList)[0] = 99
        assertEquals(old, session.state().stones)
    }

    @Test fun corruptedHistoryCannotBecomeProvenAndRestoreFailureClosesCandidate() {
        val engines = mutableListOf<GoEndgameLibraryTest.GridRules>()
        val tracked: (Int, Int, Int) -> GoRecordEngine = { size, _, _ -> GoEndgameLibraryTest.GridRules(size).also(engines::add) }
        try {
            GoCaptureSolver(puzzle, tracked).afterBlackMove(byteArrayOf(0, 0), 1)
            fail("Corrupt history accepted as a proof")
        } catch (_: IllegalStateException) { }
        assertEquals(1, engines.size); assertTrue(engines.single().closed)
    }

    private fun assertInvalid(block: () -> Unit) {
        try { block(); fail("Unverified tactical plan accepted") } catch (_: IllegalArgumentException) { }
    }
}
