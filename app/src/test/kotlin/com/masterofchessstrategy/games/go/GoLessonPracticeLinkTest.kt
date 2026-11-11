package com.masterofchessstrategy.games.go

import org.junit.Assert.*
import org.junit.Test
import com.masterofchessstrategy.engine.GoPhase
import com.masterofchessstrategy.engine.GoPlayer

class GoLessonPracticeLinkTest {
    @Test fun onlyThreeRealReviewedPairsAreCurrentlyLinked() {
        val links = GoLearningLibrary.lessons.mapNotNull { GoLessonPracticeLink.forLesson(it.id) }
        assertEquals(3, links.size)
        links.forEach { link ->
            assertNotNull(GoLearningLibrary.lesson(link.lessonId))
            assertNotNull(GoEndgameLibrary.puzzle(link.puzzleId))
            assertEquals(link, GoLessonPracticeLink.resolve(link.lessonId, link.puzzleId))
        }
        assertEquals("escape-atari", GoLessonPracticeLink.forLesson("liberties")!!.puzzleId)
        assertEquals("bridge-groups", GoLessonPracticeLink.forLesson("connection")!!.puzzleId)
        assertEquals("corner-capture", GoLessonPracticeLink.forLesson("capture")!!.puzzleId)
    }

    @Test fun unknownMissingAndMismatchedArgumentsAreRejected() {
        assertNull(GoLessonPracticeLink.resolve(null, "corner-capture"))
        assertNull(GoLessonPracticeLink.resolve("capture", null))
        assertNull(GoLessonPracticeLink.resolve("unknown", "corner-capture"))
        assertNull(GoLessonPracticeLink.resolve("capture", "unknown"))
        assertNull(GoLessonPracticeLink.resolve("capture", "bridge-groups"))
        assertNull(GoLessonPracticeLink.resolve("capture/../", "corner-capture"))
    }

    @Test fun unrelatedLessonsDoNotAcquireFakeCorrespondingPuzzles() {
        listOf("suicide", "ko", "passes", "counting", "agreement", "dispute", "handicap").forEach {
            assertNull(GoLessonPracticeLink.forLesson(it))
        }
    }

    @Test fun aDataClassCopyCannotForgeAnUnreviewedPair() {
        val link = GoLessonPracticeLink.forLesson("capture")!!
        try { link.copy(puzzleId = "bridge-groups"); fail("Forged link accepted") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun pendingFailedOrUnsavedLessonStatesCannotNavigate() {
        val lesson = GoLearningLibrary.lessons.first()
        val exercise = GoLessonState(lesson, List(81) { 0 }, GoPhase.PLAYING, GoPlayer.BLACK,
            emptySet(), emptySet(), false, lesson.instruction)
        val ready = GoLearningUiState(exercise = exercise, loading = false)
        assertEquals(GoLessonPracticeLink.forLesson(lesson.id), ready.practiceLink())
        listOf(ready.copy(loading = true), ready.copy(saving = true), ready.copy(needsSave = true),
            ready.copy(error = "写入失败"), ready.copy(exercise = null),
            ready.copy(progress = GoLearningProgress(lastLessonId = "capture"))).forEach {
            assertNull(it.practiceLink())
        }
    }
}
