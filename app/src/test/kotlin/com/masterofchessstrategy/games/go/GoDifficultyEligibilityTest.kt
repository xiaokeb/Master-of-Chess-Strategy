package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameType
import org.junit.Assert.*
import org.junit.Test

class GoDifficultyEligibilityTest {
    private fun completed() = GoLearningProgress(GoLearningLibrary.lessons.map { it.id }.toSet())
    private fun facts(wins: Map<Difficulty, Int>) = GoDifficultyEligibility.from(completed(), mapOf(GameType.GO to wins))

    @Test fun rankedWinsNeverBypassTheTenLessonRequirement() {
        val progress = GoLearningProgress(GoLearningLibrary.lessons.take(4).map { it.id }.toSet())
        val facts = GoDifficultyEligibility.from(progress,
            mapOf(GameType.GO to Difficulty.entries.associateWith { 100 }))
        assertEquals(4, facts.completedLessonCount)
        assertEquals(10, facts.totalLessonCount)
        assertFalse(facts.tutorialCompleted)
        assertTrue(facts.conditions.none { it.isUnlocked })
    }

    @Test fun allTenLessonsUnlockOnlyEasyWithoutRankedWins() {
        val facts = GoDifficultyEligibility.from(completed())
        assertTrue(facts.tutorialCompleted)
        assertEquals(listOf(Difficulty.EASY), facts.conditions.filter { it.isUnlocked }.map { it.difficulty })
        assertEquals(0, facts.condition(Difficulty.EASY).requiredWins)
    }

    @Test fun eachHigherDifficultyUsesItsOwnPrecedingRankedWinThreshold() {
        for ((target, preceding, threshold) in listOf(
            Triple(Difficulty.MEDIUM, Difficulty.EASY, 10),
            Triple(Difficulty.HARD, Difficulty.MEDIUM, 15),
            Triple(Difficulty.MASTER, Difficulty.HARD, 20),
        )) {
            assertFalse(facts(mapOf(preceding to threshold - 1)).condition(target).isUnlocked)
            val reached = facts(mapOf(preceding to threshold)).condition(target)
            assertTrue(reached.isUnlocked)
            assertEquals(preceding, reached.precedingDifficulty)
            assertEquals(threshold, reached.qualifyingWins)
            assertEquals(threshold, reached.requiredWins)
        }
    }

    @Test fun otherGamesCannotUnlockGoDifficulty() {
        val facts = GoDifficultyEligibility.from(completed(),
            mapOf(GameType.CHINESE_CHESS to Difficulty.entries.associateWith { 1_000 }))
        assertTrue(facts.condition(Difficulty.EASY).isUnlocked)
        assertFalse(facts.condition(Difficulty.MEDIUM).isUnlocked)
        assertEquals(0, facts.winsByDifficulty.getValue(Difficulty.EASY))
    }

    @Test fun lastLessonSelectionDoesNotInventCompletion() {
        val facts = GoDifficultyEligibility.from(GoLearningProgress(
            lastLessonId = GoLearningLibrary.lessons.last().id))
        assertEquals(GoLearningLibrary.lessons.last().id, facts.lastLessonId)
        assertEquals(0, facts.completedLessonCount)
        assertTrue(facts.conditions.none { it.isUnlocked })
    }

    @Test fun snapshotDoesNotAliasCallerCollections() {
        val lessons = GoLearningLibrary.lessons.map { it.id }.toMutableSet()
        val wins = mutableMapOf(Difficulty.EASY to 10)
        val games = mutableMapOf<GameType, Map<Difficulty, Int>>(GameType.GO to wins)
        val facts = GoDifficultyEligibility.from(GoLearningProgress(lessons), games)
        lessons.clear()
        wins.clear()
        games.clear()
        assertEquals(10, facts.completedLessonCount)
        assertTrue(facts.condition(Difficulty.MEDIUM).isUnlocked)
    }

    @Test fun negativeGoWinCountsAreRejectedInsteadOfSilentlyUnlocking() {
        assertThrows(IllegalArgumentException::class.java) { facts(mapOf(Difficulty.EASY to -1)) }
    }

    @Test fun aSetMutatedAfterProgressConstructionIsRevalidated() {
        val ids = mutableSetOf(GoLearningLibrary.lessons.first().id)
        val progress = GoLearningProgress(ids)
        ids.add("not-a-real-lesson")
        assertThrows(IllegalArgumentException::class.java) { GoDifficultyEligibility.from(progress) }
    }
}
