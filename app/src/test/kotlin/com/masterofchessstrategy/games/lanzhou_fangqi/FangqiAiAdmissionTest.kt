package com.masterofchessstrategy.games.lanzhou_fangqi

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.games.lanzhou_fangqi.data.FangqiLearningProgressEntity
import com.masterofchessstrategy.games.lanzhou_fangqi.data.FangqiLearningSnapshot
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

/** Synthetic validated facts only; these tests do not certify a production ledger, Native or an AI entry. */
class FangqiAiAdmissionTest {
    @Test fun missingAndIncompatibleLearningNeverBecomeAnEmptyOrCompletedTutorial() {
        for ((read, reason) in listOf(FangqiLearningRead.Unavailable to FangqiAiBlockReason.LEARNING_UNAVAILABLE,
            FangqiLearningRead.Incompatible to FangqiAiBlockReason.LEARNING_INCOMPATIBLE)) {
            val eligibility = FangqiAiAdmission.eligibility(read, verified(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE))
            assertTrue(eligibility.conditions.all { !it.isUnlocked && it.blockedReason == reason && it.completedLessons == null })
        }
    }

    @Test fun practiceCompletionsDoNotReplaceAnyOfTheSixSavedLessons() {
        val practiceRows = LanzhouFangqiLearningContent.practices.map { row("practice:${it.id}") }
        val onlyPractices = FangqiLearningRead.Ready(FangqiLearningSnapshot(practiceRows))
        val missingOneLesson = FangqiLearningRead.Ready(FangqiLearningSnapshot(practiceRows +
            LanzhouFangqiLearningContent.lessons.dropLast(1).map { row("lesson:${it.id}") }))
        for (read in listOf(onlyPractices, missingOneLesson)) {
            val eligibility = FangqiAiAdmission.eligibility(read, verified(100, 100, 100))
            assertTrue(eligibility.conditions.all { it.blockedReason == FangqiAiBlockReason.TUTORIAL_INCOMPLETE })
        }
    }

    @Test fun sixSavedLessonsUnlockEasyEvenWhenHigherTierWinSourcesAreUnavailableOrIncompatible() {
        for (wins in listOf(FangqiQualifiedWinsRead.Unavailable, FangqiQualifiedWinsRead.Incompatible)) {
            val eligibility = FangqiAiAdmission.eligibility(learned(), wins)
            assertTrue(eligibility.condition(Difficulty.EASY).isUnlocked)
            assertEquals(0, eligibility.condition(Difficulty.EASY).requiredWins)
            assertNull(eligibility.condition(Difficulty.EASY).precedingDifficulty)
            assertTrue(eligibility.conditions.drop(1).all { !it.isUnlocked && it.qualifyingWins == null })
        }
    }

    @Test fun precedingTierThresholdsAreExactAtTenFifteenAndTwenty() {
        for ((difficulty, required) in listOf(Difficulty.MEDIUM to 10, Difficulty.HARD to 15, Difficulty.MASTER to 20)) {
            fun condition(count: Int): FangqiAiDifficultyCondition {
                val wins = when (difficulty) {
                    Difficulty.MEDIUM -> verified(easy = count)
                    Difficulty.HARD -> verified(medium = count)
                    else -> verified(hard = count)
                }
                return FangqiAiAdmission.eligibility(learned(), wins).condition(difficulty)
            }
            assertFalse(condition(required - 1).isUnlocked)
            assertEquals(1, condition(required - 1).remainingWins)
            assertTrue(condition(required).isUnlocked)
            assertEquals(0, condition(required).remainingWins)
            assertTrue(condition(required + 1).isUnlocked)
        }
    }

    @Test fun otherDifficultyWinsCannotSubstituteForTheRequiredPrecedingDifficulty() {
        val eligibility = FangqiAiAdmission.eligibility(learned(), verified(easy = 100, hard = 100))
        assertTrue(eligibility.condition(Difficulty.MEDIUM).isUnlocked)
        assertFalse(eligibility.condition(Difficulty.HARD).isUnlocked)
        assertTrue(eligibility.condition(Difficulty.MASTER).isUnlocked)
        assertEquals(Difficulty.MEDIUM, eligibility.condition(Difficulty.HARD).precedingDifficulty)
    }

    @Test fun negativeCountsAndAnotherGameAreRejectedBeforeAnyEligibilityProjection() {
        try { FangqiQualifiedWinFacts.fromVerifiedLedgerCounts(GameType.LANZHOU_SQUARE_CHESS,
            mapOf(Difficulty.EASY to -1)); fail("negative wins") } catch (_: IllegalArgumentException) {}
        GameType.entries.filter { it != GameType.LANZHOU_SQUARE_CHESS }.forEach { other ->
            try { FangqiQualifiedWinFacts.fromVerifiedLedgerCounts(other, mapOf(Difficulty.EASY to 10));
                fail("foreign game accepted") } catch (_: IllegalArgumentException) {}
        }
    }

    @Test fun countsAndConditionsOwnTheirCollectionsAndLargeCountsDoNotOverflow() {
        val source = mutableMapOf(Difficulty.EASY to 10, Difficulty.MEDIUM to 15, Difficulty.HARD to Int.MAX_VALUE)
        val facts = FangqiQualifiedWinFacts.fromVerifiedLedgerCounts(GameType.LANZHOU_SQUARE_CHESS, source)
        source.clear()
        val eligibility = FangqiAiAdmission.eligibility(learned(), FangqiQualifiedWinsRead.Verified(facts))
        assertTrue(eligibility.conditions.all { it.isUnlocked })
        assertEquals(0, eligibility.condition(Difficulty.MASTER).remainingWins)
        try { (facts.winsByDifficulty as MutableMap).clear(); fail("mutable facts") } catch (_: UnsupportedOperationException) {}
        try { (eligibility.conditions as MutableList).clear(); fail("mutable conditions") } catch (_: UnsupportedOperationException) {}
    }

    @Test fun noSelectionAndInvalidCodesNeverFallBackToEasy() {
        val eligibility = FangqiAiAdmission.eligibility(learned(), verified(100, 100, 100))
        assertSame(FangqiAiSelection.NotSelected, eligibility.select(null))
        for (code in listOf(-1, 4, 99, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertEquals(FangqiAiSelection.InvalidCode(code), eligibility.select(code))
        }
    }

    @Test fun lockedRequestedDifficultyIsPreservedAndNeverReplacedByALowerTier() {
        val eligibility = FangqiAiAdmission.eligibility(learned(), verified())
        val decision = FangqiAiAdmission.newHumanGame(Difficulty.MASTER.code, eligibility)
        val locked = (decision as FangqiAiNewGameDecision.Blocked).selection as FangqiAiSelection.Locked
        assertEquals(Difficulty.MASTER, locked.condition.difficulty)
        assertEquals(20, locked.condition.requiredWins)
        assertEquals(FangqiAiBlockReason.INSUFFICIENT_WINS, locked.condition.blockedReason)
    }

    @Test fun additionalUnlocksNeverPromoteAnExplicitEasySelection() {
        val before = FangqiAiAdmission.eligibility(learned(), verified())
        val after = FangqiAiAdmission.eligibility(learned(), verified(100, 100, 100))
        assertEquals(FangqiAiSelection.Eligible(Difficulty.EASY), before.select(Difficulty.EASY.code))
        assertEquals(FangqiAiSelection.Eligible(Difficulty.EASY), after.select(Difficulty.EASY.code))
    }

    @Test fun eligibleNewGameProducesOnlyAnExplicitHumanRedIntent() {
        val eligibility = FangqiAiAdmission.eligibility(learned(), FangqiQualifiedWinsRead.Unavailable)
        val intent = FangqiAiAdmission.newHumanGame(Difficulty.EASY.code, eligibility) as FangqiAiNewGameDecision.NewHumanGameIntent
        assertEquals(Difficulty.EASY, intent.difficulty)
        assertEquals(FangqiStoredMode.HUMAN_VS_AI, intent.mode)
        assertEquals(GameType.LANZHOU_SQUARE_CHESS, intent.gameType)
        assertEquals(0, intent.humanPlayerIndex)
    }

    @Test fun anExistingLocalSessionRequiresExplicitResolutionAndIsNeverOverwrittenByAiSelection() {
        val active = active(FangqiStoredMode.LOCAL_TWO_PLAYER, null)
        val original = active.engineState
        val eligibility = FangqiAiAdmission.eligibility(learned(), verified(10))
        val decision = FangqiAiAdmission.newHumanGame(Difficulty.MEDIUM.code, eligibility, active)
            as FangqiAiNewGameDecision.ExistingSessionConflict
        assertEquals(Difficulty.MEDIUM, decision.requestedDifficulty)
        assertEquals(FangqiStoredMode.LOCAL_TWO_PLAYER, decision.active.mode)
        assertNull(decision.active.difficulty)
        assertEquals(active.sessionId, decision.active.sessionId)
        assertArrayEquals(original, active.engineState)
    }

    @Test fun explicitResumePreservesLocalHumanAiAndAutomaticModesWithAllSavedMetadata() {
        val variants = listOf(FangqiStoredMode.LOCAL_TWO_PLAYER to null) +
            listOf(FangqiStoredMode.HUMAN_VS_AI, FangqiStoredMode.AI_AUTO_PLAY).flatMap { mode ->
                Difficulty.entries.map { mode to it }
            }
        for ((mode, difficulty) in variants) {
            val saved = active(mode, difficulty)
            val summary = (FangqiAiAdmission.resumeExisting(saved) as FangqiAiResumeDecision.ResumeAdmittedSession).active
            assertEquals(mode, summary.mode)
            assertEquals(difficulty, summary.difficulty)
            assertEquals(saved.sessionId, summary.sessionId)
            assertEquals(saved.updatedAtEpochMillis, summary.updatedAtEpochMillis)
            assertEquals(saved.acceptedActionCount, summary.acceptedActionCount)
            assertEquals(saved.undoUseCount, summary.undoUseCount)
            assertEquals(saved.hintUseCount, summary.hintUseCount)
            assertTrue(summary.historySha256.matches(Regex("[0-9a-f]{64}")))
        }
    }

    @Test fun missingActiveSaveDoesNotCreateAnImplicitNewGameOnResume() {
        assertSame(FangqiAiResumeDecision.NoActiveSession, FangqiAiAdmission.resumeExisting(null))
    }

    @Test fun savedHumanAiDifficultyDoesNotOverrideALockedNewGameRequest() {
        val eligibility = FangqiAiAdmission.eligibility(learned(), verified())
        val saved = active(FangqiStoredMode.HUMAN_VS_AI, Difficulty.MASTER)
        val result = FangqiAiAdmission.newHumanGame(Difficulty.MASTER.code, eligibility, saved)
        assertTrue(result is FangqiAiNewGameDecision.Blocked)
        val resume = FangqiAiAdmission.resumeExisting(saved) as FangqiAiResumeDecision.ResumeAdmittedSession
        assertEquals(Difficulty.MASTER, resume.active.difficulty)
    }

    @Test fun incompatibleLearningRowsCannotBeConstructedAsReadyEvidence() {
        for (invalid in listOf(row("lesson:opening").copy(contentVersion = 2), row("lesson:unknown"),
            row("lesson:opening").copy(completedAtEpochMillis = -1))) {
            try { FangqiLearningRead.Ready(FangqiLearningSnapshot(listOf(invalid))); fail("bad learning admitted") }
            catch (_: IOException) {}
        }
        val blocked = FangqiAiAdmission.newHumanGame(Difficulty.EASY.code,
            FangqiAiAdmission.eligibility(FangqiLearningRead.Incompatible, verified(100, 100, 100)))
        assertTrue(blocked is FangqiAiNewGameDecision.Blocked)
    }

    @Test fun summaryIdentityDoesNotExposeOrDependOnCallerMutableByteArrays() {
        val original = active(FangqiStoredMode.HUMAN_VS_AI, Difficulty.EASY)
        val first = (FangqiAiAdmission.resumeExisting(original) as FangqiAiResumeDecision.ResumeAdmittedSession).active
        original.engineState.fill(0)
        val second = (FangqiAiAdmission.resumeExisting(original) as FangqiAiResumeDecision.ResumeAdmittedSession).active
        assertEquals(first, second)
    }

    companion object {
        private fun learned() = FangqiLearningRead.Ready(FangqiLearningSnapshot(
            LanzhouFangqiLearningContent.lessons.map { row("lesson:${it.id}") },
        ))
        private fun verified(easy: Int = 0, medium: Int = 0, hard: Int = 0) = FangqiQualifiedWinsRead.Verified(
            FangqiQualifiedWinFacts.fromVerifiedLedgerCounts(GameType.LANZHOU_SQUARE_CHESS,
                mapOf(Difficulty.EASY to easy, Difficulty.MEDIUM to medium, Difficulty.HARD to hard)),
        )
        private fun row(key: String) = FangqiLearningProgressEntity(key, 1, 123L)
        /** Synthetic admitted-save metadata; no test claims to have invoked Native admission. */
        private fun active(mode: FangqiStoredMode, difficulty: Difficulty?) = FangqiStoredSession(mode, difficulty,
            ("MOCS-LFQ|1|1|" + "2".repeat(14) + "0".repeat(21) + "1".repeat(14) + "|0|").toByteArray(),
            "retained-fangqi-session", 321L, acceptedActionCount = 0, undoUseCount = 1, hintUseCount = 2)
    }
}
