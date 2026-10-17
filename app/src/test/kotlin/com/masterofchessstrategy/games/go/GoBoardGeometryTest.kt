package com.masterofchessstrategy.games.go

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GoBoardGeometryTest {
    @Test
    fun tapsMapToAllFourCornersAndCenterAtEveryBoardSize() {
        for (size in listOf(9, 13, 19)) {
            val side = 342f
            val cell = side / size
            assertEquals(GoBoardPoint(0, 0), goBoardPointAt(0f, 0f, side, size))
            assertEquals(GoBoardPoint(size - 1, 0),
                goBoardPointAt(side - 0.1f, cell / 2f, side, size))
            assertEquals(GoBoardPoint(0, size - 1),
                goBoardPointAt(cell / 2f, side - 0.1f, side, size))
            assertEquals(GoBoardPoint(size - 1, size - 1),
                goBoardPointAt(side - 0.1f, side - 0.1f, side, size))
            assertEquals(GoBoardPoint(size / 2, size / 2),
                goBoardPointAt(side / 2f, side / 2f, side, size))
        }
    }

    @Test
    fun tapsOutsideTheSquareCannotSelectAnIntersection() {
        assertNull(goBoardPointAt(-0.1f, 20f, 342f, 19))
        assertNull(goBoardPointAt(20f, 342f, 342f, 19))
        assertNull(goBoardPointAt(342f, 20f, 342f, 19))
        assertNull(goBoardPointAt(Float.NaN, 20f, 342f, 19))
        assertNull(goBoardPointAt(20f, 20f, 0f, 19))
    }

    @Test
    fun starPointsMatchTheProjectHandicapGrid() {
        val expected = mapOf(
            9 to setOf(20, 22, 24, 38, 40, 42, 56, 58, 60),
            13 to setOf(42, 45, 48, 81, 84, 87, 120, 123, 126),
            19 to setOf(60, 66, 72, 174, 180, 186, 288, 294, 300),
        )
        for ((size, indices) in expected) {
            assertEquals(indices, goBoardStarPoints(size))
        }
    }

    @Test
    fun semanticDescriptionIncludesPositionAndStatus() {
        assertEquals(
            "第2行第3列，白子，最后落点，待确认死子，高亮点",
            goBoardPointDescription(2, 1, 2, true, true, true),
        )
    }
}
