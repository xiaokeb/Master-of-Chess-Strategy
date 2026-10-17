package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.NativeGoEngine
import com.masterofchessstrategy.engine.RestoreResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoLearningNativeInstrumentedTest {
    @Test fun allAuthoredLessonsAndAnswersRunOnProductionRules() {
        for (lesson in GoLearningLibrary.lessons) {
            val engine = NativeGoEngine(lesson.boardSize, if (lesson.handicap == 0) 15 else 0, lesson.handicap)
            GoLessonSession(lesson, engine).use { session ->
                val before = engine.serialize()
                val wrong = session.answer(GoLessonAnswer.Result(GameResult.DRAW))
                assertFalse("${lesson.id}: wrong answer completed lesson", wrong.completed)
                assertArrayEquals("${lesson.id}: wrong answer changed board", before, engine.serialize())
                val answers = when (val goal = lesson.goal) {
                    is GoLessonGoal.FindLiberties -> goal.points.map { GoLessonAnswer.Point(it) }
                    is GoLessonGoal.Place -> listOf(GoLessonAnswer.Point(goal.point))
                    is GoLessonGoal.ForbiddenPoint -> listOf(GoLessonAnswer.Point(goal.point))
                    GoLessonGoal.SecondPass -> listOf(GoLessonAnswer.Pass)
                    is GoLessonGoal.Confirm -> listOf(GoLessonAnswer.Confirm(goal.actor))
                    is GoLessonGoal.Resume -> listOf(GoLessonAnswer.Resume(goal.actor))
                    is GoLessonGoal.PredictResult -> listOf(GoLessonAnswer.Result(goal.result))
                }
                answers.forEach(session::answer)
                assertTrue("${lesson.id}: correct answer incomplete", session.state().completed)
                val complete = engine.serialize()
                session.answer(GoLessonAnswer.Pass)
                assertArrayEquals("${lesson.id}: repeated completion changed rules", complete, engine.serialize())
                NativeGoEngine(lesson.boardSize).use { restored ->
                    assertEquals("${lesson.id}: final exercise cannot restore", RestoreResult.Restored,
                        restored.restore(complete))
                    assertArrayEquals(complete, restored.serialize())
                }
            }
        }
    }
}
