package com.masterofchessstrategy.engine

import org.junit.Assert.*
import org.junit.Test

class GoSearchResultDecoderTest {
    private fun move() = doubleArrayOf(1.0, 0.0, 1.0, 8.0, 12.0, 7.0, 42.5, 0.4, -3.25)
    @Test fun placementPreservesWhitePerspectiveMetrics() {
        val result = GoSearchResultDecoder.decode(move()) as GoSearchResult.Move
        assertEquals(GoAction.Place(BoardPosition(8, 12)), result.action)
        assertEquals(GoSearchMetrics(7, 42.5, 0.4, -3.25), result.metrics)
    }
    @Test fun passHasNoBoardCoordinate() {
        val frame = move().apply { this[2] = 2.0; this[3] = -1.0; this[4] = -1.0 }
        assertEquals(GoAction.Pass, (GoSearchResultDecoder.decode(frame) as GoSearchResult.Move).action)
        invalid { GoSearchResultDecoder.decode(frame.copyOf().apply { this[3] = 0.0 }) }
    }
    @Test fun nonMoveStatusesNeverCarryAnActionOrMetrics() {
        for ((status, expected) in listOf(GoSearchResult.Cancelled, GoSearchResult.Busy,
            GoSearchResult.BudgetExhausted).withIndex()) {
            val frame = doubleArrayOf(1.0, (status + 1).toDouble(), 0.0, -1.0, -1.0, 0.0, 0.0, 0.0, 0.0)
            assertEquals(expected, GoSearchResultDecoder.decode(frame))
            for (index in 2..8) invalid { GoSearchResultDecoder.decode(frame.copyOf().apply { this[index] += 1.0 }) }
        }
    }
    @Test fun wrongVersionLengthAndStatusAreRejected() {
        for (size in listOf(0, 8, 10)) invalid { GoSearchResultDecoder.decode(move().copyOf(size)) }
        for ((index, value) in listOf(0 to 2.0, 1 to -1.0, 1 to 4.0, 1 to 0.5))
            invalid { GoSearchResultDecoder.decode(move().apply { this[index] = value }) }
    }
    @Test fun scoringAndFractionalActionFieldsAreRejected() {
        for (kind in listOf(0.0, 3.0, 4.0, 5.0, 1.5))
            invalid { GoSearchResultDecoder.decode(move().apply { this[2] = kind }) }
        for (index in 3..5) for (value in listOf(-1.0, 0.5, 513.0))
            invalid { GoSearchResultDecoder.decode(move().apply { this[index] = value }) }
        invalid { GoSearchResultDecoder.decode(move().apply { this[5] = 0.0 }) }
        invalid { GoSearchResultDecoder.decode(move().apply { this[3] = 19.0 }) }
    }
    @Test fun nonFiniteAndOutOfRangeEstimatesAreRejected() {
        for (index in 0..8) for (value in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY))
            invalid { GoSearchResultDecoder.decode(move().apply { this[index] = value }) }
        for ((index, value) in listOf(6 to -1.0, 7 to -0.1, 7 to 1.1))
            invalid { GoSearchResultDecoder.decode(move().apply { this[index] = value }) }
    }
    private fun invalid(block: () -> Unit) {
        try { block(); fail("Malformed frame accepted") } catch (_: IllegalStateException) { }
    }
}
