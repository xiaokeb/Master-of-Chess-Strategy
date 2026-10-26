package com.masterofchessstrategy.engine

import org.junit.Assert.*
import org.junit.Test

class LanzhouMahjongBridgeDecoderTest {
    @Test fun fourStopCodesAndLimitsDecode() {
        for (stop in LanzhouMahjongBotStop.entries) {
            val steps = if (stop == LanzhouMahjongBotStop.STEP_LIMIT) 512 else 0
            assertEquals(LanzhouMahjongBotAdvance(stop, steps),
                LanzhouMahjongBridgeDecoder.advance(intArrayOf(1, stop.code, steps), 512))
        }
    }

    @Test fun malformedStopFramesAreRejected() {
        for (data in listOf(intArrayOf(), intArrayOf(1, 0), intArrayOf(1, 0, 0, 0),
            intArrayOf(2, 0, 0), intArrayOf(1, 4, 0), intArrayOf(1, 0, -1),
            intArrayOf(1, 0, 513), intArrayOf(1, 2, 1)))
            assertThrows(IllegalStateException::class.java) { LanzhouMahjongBridgeDecoder.advance(data, 512) }
    }

    @Test fun onlyTheFixedViewerHasConcealedTiles() {
        for (viewer in 0..3) for (seat in 0..3) {
            val tiles = if (seat == viewer) intArrayOf(0, 1) else intArrayOf()
            val decoded = LanzhouMahjongBridgeDecoder.seat(seat, viewer,
                intArrayOf(2, 0, 0, 0, 0, tiles.size) + tiles)
            assertEquals(tiles.toList(), decoded.concealedTiles)
        }
        rejected(intArrayOf(2, 0, 0, 0, 0, 2, 0, 1))
    }

    @Test fun concealedKongValuesAreVisibleOnlyToTheirOwner() {
        for (viewer in 0..3) for (seat in 0..3) {
            val tile = if (seat == viewer) 26 else -1
            val decoded = LanzhouMahjongBridgeDecoder.seat(seat, viewer,
                intArrayOf(0, 0, 0, 1, 2, tile, seat, 0, 0))
            assertEquals(tile, decoded.melds.single().tile)
            assertEquals(seat != viewer, decoded.melds.single().isTileHidden)
        }
        rejected(intArrayOf(0, 0, 0, 1, 2, 26, 1, 0, 0))
        assertThrows(IllegalStateException::class.java) {
            LanzhouMahjongBridgeDecoder.seat(0, 0, intArrayOf(0, 0, 0, 1, 2, -1, 0, 0, 0))
        }
    }

    @Test fun visibleMeldsStayVisibleAndCannotUseHiddenSentinel() {
        for (kind in 0..1) {
            val good = intArrayOf(0, 0, 0, 1, kind, 26, 0, 0, 0)
            assertEquals(26, LanzhouMahjongBridgeDecoder.seat(1, 0, good).melds.single().tile)
            rejected(good.also { it[5] = -1 })
        }
        rejected(intArrayOf(0, 0, 0, 1, 2, -1, 2, 0, 0))
    }

    @Test fun truncationTrailingDataAndOverflowCountsAreRejected() {
        val good = intArrayOf(0, 0, 0, 1, 2, -1, 1, 1, 26, 0)
        for (size in good.indices) rejected(good.copyOf(size))
        rejected(good + 0)
        for ((index, value) in listOf(0 to 15, 1 to 2, 2 to -1, 3 to Int.MAX_VALUE, 7 to Int.MAX_VALUE))
            rejected(good.copyOf().also { it[index] = value })
        rejected(IntArray(141))
    }

    @Test fun invalidTilesAndMeldCodesAreRejected() {
        for (data in listOf(intArrayOf(0, 0, 0, 0, 1, 27, 0),
            intArrayOf(0, 0, 0, 1, 3, 0, 0, 0, 0),
            intArrayOf(0, 0, 0, 1, 0, 0, 4, 0, 0))) rejected(data)
    }

    private fun rejected(data: IntArray) {
        assertThrows(IllegalStateException::class.java) { LanzhouMahjongBridgeDecoder.seat(1, 0, data) }
    }
}
