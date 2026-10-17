package com.masterofchessstrategy.games.go

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoLearningProgressTest {
    @Test fun everyLessonMustBeCompletedBeforeTutorialUnlockCondition() {
        val ids = GoLearningLibrary.lessons.map { it.id }.toSet()
        assertFalse(GoLearningProgress(ids - GoLearningLibrary.lessons.last().id).isCompleted)
        assertTrue(GoLearningProgress(ids).isCompleted)
    }

    @Test fun repeatingTheSameLessonDoesNotCompleteOtherTopics() {
        val one = GoLearningLibrary.lessons.first().id
        val repeated = GoLearningProgress(setOf(one) + one)
        assertFalse(repeated.isCompleted)
        assertTrue(repeated.completedLessonIds.size == 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownLessonCannotBeCountedAsCompleted() {
        GoLearningProgress(setOf("unknown-topic"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownResumePositionIsRejected() {
        GoLearningProgress(lastLessonId = "unknown-topic")
    }
}
