package com.masterofchessstrategy.games.lanzhou_mahjong.settlement

import java.io.IOException
import java.math.BigInteger
import org.junit.Assert.assertEquals
import org.junit.Test

class LanzhouMahjongStatisticsTest {
    @Test fun countsAllSeatWinsAndKeepsDrawsAndFalseDeclarationsSeparate() {
        val statistics = summarizeLanzhouMahjongSettlements(listOf(
            LanzhouMahjongSettlementSummaryRow("WIN", 0, null, 6, -2, -2, -2),
            LanzhouMahjongSettlementSummaryRow("LOSS", 1, 0, -4, 4, 0, 0),
            LanzhouMahjongSettlementSummaryRow("FALSE_DECLARATION", null, 2, 24, 24, -72, 24),
            LanzhouMahjongSettlementSummaryRow("EXHAUSTIVE_DRAW", null, null, 0, 0, 0, 0),
        ))
        assertEquals(4, statistics.completedRounds)
        assertEquals(1, statistics.exhaustiveDraws)
        assertEquals(listOf(1, 1, 0, 0), statistics.seats.map { it.wins })
        assertEquals(listOf(0, 0, 1, 0), statistics.seats.map { it.falseDeclarations })
        assertEquals(listOf(26L, 26L, -74L, 22L).map(BigInteger::valueOf), statistics.seats.map { it.netScore })
    }

    @Test fun multipleRoundTotalsCanExceedTheNativeIntegerRange() {
        val row = LanzhouMahjongSettlementSummaryRow("LOSS", 1, 0, -Long.MAX_VALUE, Long.MAX_VALUE, 0, 0)
        val statistics = summarizeLanzhouMahjongSettlements(listOf(row, row))
        assertEquals(BigInteger.valueOf(Long.MAX_VALUE).shiftLeft(1), statistics.seats[1].netScore)
    }

    @Test(expected = IOException::class)
    fun invalidSeatDoesNotBecomeAPartialSummary() {
        summarizeLanzhouMahjongSettlements(listOf(
            LanzhouMahjongSettlementSummaryRow("WIN", 4, null, 0, 0, 0, 0),
        ))
    }
}
