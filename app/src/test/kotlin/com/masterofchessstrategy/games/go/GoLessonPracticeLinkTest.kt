package com.masterofchessstrategy.games.go

import org.junit.Assert.*
import org.junit.Test
import com.masterofchessstrategy.engine.GoPhase
import com.masterofchessstrategy.engine.GoPlayer

class GoLessonPracticeLinkTest {
    @Test fun reviewedBasicAndAdvancedLinksPreserveLessonOrder() {
        val links = GoLearningLibrary.lessons.flatMap { GoLessonPracticeLink.forLesson(it.id) }
        assertEquals(9, links.size)
        links.forEach { link ->
            assertNotNull(GoLearningLibrary.lesson(link.lessonId))
            assertNotNull(GoEndgameLibrary.puzzle(link.puzzleId))
            assertEquals(link, GoLessonPracticeLink.resolve(link.lessonId, link.puzzleId))
        }
        assertEquals(listOf("escape-atari"), GoLessonPracticeLink.forLesson("liberties").map { it.puzzleId })
        assertEquals(
            listOf("bridge-groups", "join-three-groups", "link-one-eye-groups"),
            GoLessonPracticeLink.forLesson("connection").map { it.puzzleId },
        )
        assertEquals(listOf("corner-capture", "shared-liberty-double-capture"), GoLessonPracticeLink.forLesson("capture").map { it.puzzleId })
        assertEquals(listOf("corner-counter-capture"), GoLessonPracticeLink.forLesson("suicide").map { it.puzzleId })
        assertEquals(listOf("corner-ko-capture", "corner-snapback"), GoLessonPracticeLink.forLesson("ko").map { it.puzzleId })
        assertNull(GoLessonPracticeLink.resolve("suicide", "corner-capture"))
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
        listOf("passes", "counting", "agreement", "dispute", "handicap").forEach {
            assertTrue(GoLessonPracticeLink.forLesson(it).isEmpty())
        }
    }

    @Test fun aDataClassCopyCannotForgeAnUnreviewedPair() {
        val link = GoLessonPracticeLink.forLesson("capture").first()
        try { link.copy(puzzleId = "bridge-groups"); fail("Forged link accepted") }
        catch (_: IllegalArgumentException) { }
    }

    @Test fun pendingFailedOrUnsavedLessonStatesCannotNavigate() {
        val lesson = checkNotNull(GoLearningLibrary.lesson("connection"))
        val exercise = GoLessonState(lesson, List(81) { 0 }, GoPhase.PLAYING, GoPlayer.BLACK,
            emptySet(), emptySet(), false, lesson.instruction)
        val ready = GoLearningUiState(exercise = exercise, loading = false, progress = GoLearningProgress(lastLessonId = lesson.id))
        assertEquals(GoLessonPracticeLink.forLesson(lesson.id), ready.practiceLinks())
        listOf(ready.copy(loading = true), ready.copy(saving = true), ready.copy(needsSave = true),
            ready.copy(error = "写入失败"), ready.copy(exercise = null),
            ready.copy(progress = GoLearningProgress(lastLessonId = "capture"))).forEach {
            assertTrue(it.practiceLinks().isEmpty())
        }
    }
}
