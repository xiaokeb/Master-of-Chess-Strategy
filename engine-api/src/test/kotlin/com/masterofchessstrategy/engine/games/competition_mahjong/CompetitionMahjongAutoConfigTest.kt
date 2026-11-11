package com.masterofchessstrategy.engine

import org.junit.Assert.*
import org.junit.Test

class CompetitionMahjongAutoConfigTest {
    @Test fun defaultsAreOneObserverAndFourBaselineSeats() {
        val config = CompetitionMahjongAutoConfig(925)
        assertEquals(0, config.viewerSeat)
        assertEquals(listOf(0, 0, 0, 0), config.botLevels)
        assertEquals(925L, config.decisionSeed)
    }

    @Test fun constructorDoesNotRetainMutableLevels() {
        val levels = mutableListOf(0, 1, 2, 3)
        val config = CompetitionMahjongAutoConfig(1, botLevels = levels)
        levels[0] = 3
        assertEquals(listOf(0, 1, 2, 3), config.botLevels)
        (config.botLevels as MutableList<Int>)[1] = 3
        assertEquals(listOf(0, 1, 2, 3), config.botLevels)
    }

    @Test fun rejectsInvalidSeedsAndSeats() {
        assertThrows(IllegalArgumentException::class.java) { CompetitionMahjongAutoConfig(-1) }
        assertThrows(IllegalArgumentException::class.java) { CompetitionMahjongAutoConfig(0, decisionSeed = -1) }
        for (value in listOf(-1, 4)) {
            assertThrows(IllegalArgumentException::class.java) { CompetitionMahjongAutoConfig(0, dealerSeat = value) }
            assertThrows(IllegalArgumentException::class.java) { CompetitionMahjongAutoConfig(0, roundWind = value) }
            assertThrows(IllegalArgumentException::class.java) { CompetitionMahjongAutoConfig(0, viewerSeat = value) }
        }
    }

    @Test fun rejectsWrongStrategyCountOrRange() {
        for (levels in listOf(emptyList(), listOf(0, 1, 2), listOf(0, 0, 0, 0, 0), listOf(-1, 0, 0, 0), listOf(0, 0, 0, 4))) {
            assertThrows(IllegalArgumentException::class.java) { CompetitionMahjongAutoConfig(0, botLevels = levels) }
        }
    }

    @Test fun acceptsBoundaryConfiguration() {
        val config = CompetitionMahjongAutoConfig(Long.MAX_VALUE, 3, 3, 3, listOf(3, 2, 1, 0), Long.MAX_VALUE)
        assertEquals(3, config.viewerSeat)
        assertEquals(listOf(3, 2, 1, 0), config.botLevels)
    }

    @Test fun advanceResultCannotClaimAStalledLiveHand() {
        assertEquals(0, CompetitionMahjongAutoAdvance(0, true).acceptedActions)
        assertEquals(32, CompetitionMahjongAutoAdvance(32, false).acceptedActions)
        for (count in listOf(-1, 33)) {
            assertThrows(IllegalArgumentException::class.java) { CompetitionMahjongAutoAdvance(count, true) }
        }
        assertThrows(IllegalArgumentException::class.java) { CompetitionMahjongAutoAdvance(0, false) }
    }
}
