package com.masterofchessstrategy.games.go

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.GoStone
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
            val solved = if (puzzle.maxBlackMoves == 1) first else {
                assertFalse(first.completed)
                assertTrue(first.feedback.contains("剩余 1 手"))
                session.answer(BoardPosition(8, 7))
            }
            assertFalse(initial.completed); assertTrue(puzzle.id, solved.completed)
            puzzle.captured.forEach { assertEquals(GoStone.EMPTY.code,
                solved.stones[it.y * puzzle.boardSize + it.x]) }
            assertEquals(initial.stones, session.reset().stones)
        }
    }
}
