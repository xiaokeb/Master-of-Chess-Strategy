package com.masterofchessstrategy.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** Value boundaries only; real request ownership is verified separately with native NN tests. */
class GoSearchRuntimeContractTest {
    private val metrics = GoSearchMetrics(1, 0.0, 0.5, 0.0)

    @Test
    fun budgetAcceptsBothBounds() {
        assertEquals(1, GoSearchBudget(1, 1).maxVisits)
        assertEquals(20_000L, GoSearchBudget(512, 20_000).maxTimeMillis)
    }

    @Test
    fun budgetRejectsOutOfRangeVisits() {
        listOf(Int.MIN_VALUE, -1, 0, 513, Int.MAX_VALUE).forEach { visits ->
            assertThrows(IllegalArgumentException::class.java) { GoSearchBudget(visits, 1) }
        }
    }

    @Test
    fun budgetRejectsOutOfRangeTime() {
        listOf(Long.MIN_VALUE, -1L, 0L, 20_001L, Long.MAX_VALUE).forEach { time ->
            assertThrows(IllegalArgumentException::class.java) { GoSearchBudget(1, time) }
        }
    }

    @Test
    fun completedSearchRequiresPositiveBoundedVisits() {
        assertEquals(512, metrics.copy(visits = 512).visits)
        listOf(Int.MIN_VALUE, -1, 0, 513, Int.MAX_VALUE).forEach { visits ->
            assertThrows(IllegalArgumentException::class.java) { metrics.copy(visits = visits) }
        }
    }

    @Test
    fun elapsedTimeRejectsNegativeAndNonFiniteValues() {
        listOf(-0.1, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY).forEach {
            assertThrows(IllegalArgumentException::class.java) { metrics.copy(elapsedMillis = it) }
        }
    }

    @Test
    fun softDeadlineDoesNotRejectAnOverrun() {
        val budget = GoSearchBudget(1, 1)
        val measured = metrics.copy(elapsedMillis = 114.7)
        assertEquals(1L, budget.maxTimeMillis)
        assertEquals(114.7, measured.elapsedMillis, 0.0)
    }

    @Test
    fun probabilityAcceptsBothBounds() {
        assertEquals(0.0, metrics.copy(whiteWinProbability = 0.0).whiteWinProbability, 0.0)
        assertEquals(1.0, metrics.copy(whiteWinProbability = 1.0).whiteWinProbability, 0.0)
    }

    @Test
    fun probabilityRejectsOutOfRangeAndNonFiniteValues() {
        listOf(-0.01, 1.01, Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY).forEach {
            assertThrows(IllegalArgumentException::class.java) { metrics.copy(whiteWinProbability = it) }
        }
    }

    @Test
    fun scoreEstimateAcceptsEitherSignButNotNonFiniteValues() {
        assertEquals(-7.5, metrics.copy(whiteMinusBlackPoints = -7.5).whiteMinusBlackPoints, 0.0)
        assertEquals(7.5, metrics.copy(whiteMinusBlackPoints = 7.5).whiteMinusBlackPoints, 0.0)
        listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY).forEach {
            assertThrows(IllegalArgumentException::class.java) { metrics.copy(whiteMinusBlackPoints = it) }
        }
    }

    @Test
    fun resultAllowsOnlyPlacementOrPass() {
        val place = GoAction.Place(BoardPosition(18, 18))
        assertEquals(place, GoSearchResult.Move(place, metrics).action)
        assertEquals(GoAction.Pass, GoSearchResult.Move(GoAction.Pass, metrics).action)
        listOf(
            GoAction.ToggleDeadGroup(BoardPosition(0, 0), GoPlayer.BLACK),
            GoAction.ConfirmScore(GoPlayer.WHITE, 1),
            GoAction.ResumePlay(GoPlayer.BLACK),
        ).forEach { action ->
            assertThrows(IllegalArgumentException::class.java) { GoSearchResult.Move(action, metrics) }
        }
    }

    @Test
    fun exhaustiveOutcomesExposeAnActionOnlyForMove() {
        fun candidate(result: GoSearchResult): GoAction? = when (result) {
            is GoSearchResult.Move -> result.action
            GoSearchResult.Cancelled, GoSearchResult.Busy, GoSearchResult.BudgetExhausted -> null
        }
        assertEquals(GoAction.Pass, candidate(GoSearchResult.Move(GoAction.Pass, metrics)))
        listOf(GoSearchResult.Cancelled, GoSearchResult.Busy, GoSearchResult.BudgetExhausted).forEach {
            assertEquals(null, candidate(it))
        }
    }

    @Test
    fun admissionLimitsMatchTheNativeRuntime() {
        assertEquals(1_000_000, GoSearchRuntime.MAX_SNAPSHOT_BYTES)
        assertEquals(16, GoSearchRuntime.MAX_PREPARED_REQUESTS)
    }
}
