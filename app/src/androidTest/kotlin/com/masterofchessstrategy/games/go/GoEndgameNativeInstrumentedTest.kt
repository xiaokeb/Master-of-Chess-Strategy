package com.masterofchessstrategy.games.go

import androidx.test.ext.junit.runners.AndroidJUnit4
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
            val initial = session.state(); val solved = session.answer(puzzle.answer)
            assertFalse(initial.completed); assertTrue(puzzle.id, solved.completed)
            puzzle.captured.forEach { assertEquals(GoStone.EMPTY.code,
                solved.stones[it.y * puzzle.boardSize + it.x]) }
            assertEquals(initial.stones, session.reset().stones)
        }
    }
}
