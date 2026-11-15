package com.masterofchessstrategy.games.go

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.masterofchessstrategy.engine.ActionResult
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.GoAction
import com.masterofchessstrategy.engine.GoStone
import com.masterofchessstrategy.engine.NativeGoEngine
import com.masterofchessstrategy.engine.RestoreResult
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GoEndgameNativeInstrumentedTest {
    @Test fun allAuthoredSetupsAndAnswersRunThroughRealNativeRules() {
        for (puzzle in GoEndgameLibrary.puzzles) {
            val plan = GoEndgameLibrary.open(puzzle)
            val session = GoEndgameSession(plan)
            val initial = session.state()
            val first = session.answer(puzzle.answer)
            val solved = when (puzzle.maxBlackMoves) {
                1 -> first
                2 -> {
                    assertFalse(first.completed)
                    assertTrue(first.feedback.contains("剩余 1 手"))
                    session.answer(BoardPosition(8, 7))
                }
                else -> {
                    assertFalse(first.completed)
                    assertTrue(first.feedback.contains("剩余 2 手"))
                    val second = session.answer(BoardPosition(8, 7))
                    assertFalse(second.completed)
                    assertTrue(second.feedback.contains("剩余 1 手"))
                    session.answer(BoardPosition(7, 8))
                }
            }
            assertFalse(initial.completed); assertTrue(puzzle.id, solved.completed)
            puzzle.captured.forEach { assertEquals(GoStone.EMPTY.code,
                solved.stones[it.y * puzzle.boardSize + it.x]) }
            assertEquals(initial.stones, session.reset().stones)
        }
    }

    @Test fun authoredTacticsAcceptExactlyTheVerifiedPlacements() {
        val expected = mapOf(
            "corner-counter-capture" to setOf(BoardPosition(0, 1)),
            "connect-between-white-groups" to setOf(BoardPosition(9, 8), BoardPosition(9, 9)),
            "corner-separate-eyes" to setOf(BoardPosition(1, 0)),
            "link-one-eye-groups" to setOf(BoardPosition(2, 0)),
        )
        for ((id, solutions) in expected) {
            val puzzle = checkNotNull(GoEndgameLibrary.puzzle(id))
            val plan = GoEndgameLibrary.open(puzzle)
            val moves = NativeGoEngine(puzzle.boardSize).use { engine ->
                assertEquals(RestoreResult.Restored, engine.restore(plan.copyInitialBytes()))
                engine.legalActions().filterIsInstance<GoAction.Place>()
            }
            val exercise = GoEndgameSession(plan)
            val accepted = moves.mapNotNull { action ->
                action.point.takeIf { exercise.answer(it).completed }?.also { exercise.reset() }
            }
            assertEquals(id, solutions, accepted.toSet())
        }
    }

    @Test fun eachCompletedLifeGroupRejectsImmediateWhitePlayInBothEyes() {
        val eyesById = mapOf(
            "corner-separate-eyes" to listOf(BoardPosition(0, 0), BoardPosition(2, 0)),
            "link-one-eye-groups" to listOf(BoardPosition(0, 0), BoardPosition(4, 0)),
        )
        for ((id, eyes) in eyesById) {
            val puzzle = checkNotNull(GoEndgameLibrary.puzzle(id))
            val plan = GoEndgameLibrary.open(puzzle)
            NativeGoEngine(puzzle.boardSize).use { engine ->
                assertEquals(RestoreResult.Restored, engine.restore(plan.copyInitialBytes()))
                assertEquals(ActionResult.Accepted, engine.apply(GoAction.Place(puzzle.answer)))
                for (eye in eyes) {
                    assertEquals(GoStone.EMPTY, engine.stoneAt(eye))
                    assertFalse(engine.apply(GoAction.Place(eye)) == ActionResult.Accepted)
                }
            }
        }
    }
}
