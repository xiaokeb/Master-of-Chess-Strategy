package com.masterofchessstrategy.engine

import org.junit.Assert.*
import org.junit.Test

class CompetitionMahjongAutoAdvanceDecoderTest {
    @Test fun readsLiveFullBatchAndTerminalShortBatch() {
        assertEquals(CompetitionMahjongAutoAdvance(32, false), decodeCompetitionMahjongAutoAdvance(intArrayOf(1, 32, 0), 32))
        assertEquals(CompetitionMahjongAutoAdvance(2, true), decodeCompetitionMahjongAutoAdvance(intArrayOf(1, 2, 1), 32))
    }
    @Test fun readsAlreadyTerminalWithoutImplicitContinuation() {
        assertEquals(CompetitionMahjongAutoAdvance(0, true), decodeCompetitionMahjongAutoAdvance(intArrayOf(1, 0, 1), 1))
    }
    @Test fun rejectsUnknownVersionOrLength() {
        for (frame in listOf(intArrayOf(), intArrayOf(1, 1), intArrayOf(1, 1, 0, 0), intArrayOf(2, 1, 0))) {
            assertThrows(IllegalStateException::class.java) { decodeCompetitionMahjongAutoAdvance(frame, 1) }
        }
    }
    @Test fun rejectsInvalidCountOrBoolean() {
        for (frame in listOf(intArrayOf(1, -1, 0), intArrayOf(1, 2, 0), intArrayOf(1, 1, 2), intArrayOf(1, 1, -1), intArrayOf(1, 0, 0))) {
            assertThrows(IllegalStateException::class.java) { decodeCompetitionMahjongAutoAdvance(frame, 1) }
        }
    }
    @Test fun rejectsInvalidRequestedBudget() {
        for (budget in listOf(0, 33)) {
            assertThrows(IllegalArgumentException::class.java) { decodeCompetitionMahjongAutoAdvance(intArrayOf(1, 1, 0), budget) }
        }
    }
}
