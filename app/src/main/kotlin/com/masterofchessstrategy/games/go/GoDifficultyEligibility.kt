package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameType
import java.util.Collections

/** Eligibility is a persisted-progress fact, not proof that the corresponding AI is available. */
data class GoDifficultyCondition(
    val difficulty: Difficulty,
    val tutorialCompleted: Boolean,
    val precedingDifficulty: Difficulty?,
    val qualifyingWins: Int,
    val requiredWins: Int,
) {
    val isUnlocked: Boolean get() = tutorialCompleted && qualifyingWins >= requiredWins
}

/** Read-only snapshot: never turns ten Go lessons into the legacy four-step tutorial record. */
class GoDifficultyEligibility private constructor(
    completed: Set<String>,
    val lastLessonId: String,
    wins: Map<Difficulty, Int>,
) {
    val completedLessonIds: Set<String> = Collections.unmodifiableSet(completed.toSet())
    val winsByDifficulty: Map<Difficulty, Int> = Collections.unmodifiableMap(wins.toMap())
    val completedLessonCount: Int get() = completedLessonIds.size
    val totalLessonCount: Int get() = GoLearningLibrary.lessons.size
    val tutorialCompleted: Boolean get() = GoLearningLibrary.lessons.all { it.id in completedLessonIds }
    val conditions: List<GoDifficultyCondition> = Collections.unmodifiableList(Difficulty.entries.map { difficulty ->
        val (preceding, required) = when (difficulty) {
            Difficulty.EASY -> null to 0
            Difficulty.MEDIUM -> Difficulty.EASY to 10
            Difficulty.HARD -> Difficulty.MEDIUM to 15
            Difficulty.MASTER -> Difficulty.HARD to 20
        }
        GoDifficultyCondition(difficulty, tutorialCompleted, preceding,
            preceding?.let { winsByDifficulty.getValue(it) } ?: 0, required)
    })

    fun condition(difficulty: Difficulty): GoDifficultyCondition = conditions.first { it.difficulty == difficulty }

    companion object {
        /** Caller supplies only the validated standard-ranked ledger, never local/imported/automatic records. */
        fun from(
            learning: GoLearningProgress,
            rankedWinsByGame: Map<GameType, Map<Difficulty, Int>> = emptyMap(),
        ): GoDifficultyEligibility {
            val completed = learning.completedLessonIds.toSet()
            // Revalidate a caller-owned mutable set before accepting it as an immutable fact.
            GoLearningProgress(completed, learning.lastLessonId)
            val goWins = rankedWinsByGame[GameType.GO].orEmpty().toMap()
            require(goWins.values.all { it >= 0 }) { "围棋有效胜场不能为负数" }
            return GoDifficultyEligibility(completed, learning.lastLessonId,
                Difficulty.entries.associateWith { goWins[it] ?: 0 })
        }
    }
}
