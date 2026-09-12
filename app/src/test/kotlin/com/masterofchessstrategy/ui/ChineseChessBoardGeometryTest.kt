package com.masterofchessstrategy.ui

import androidx.compose.ui.geometry.Offset
import com.masterofchessstrategy.engine.BoardPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChineseChessBoardGeometryTest {
    @Test
    fun intersectionCenterMapsToBoardPosition() {
        val geometry = calculateBoardGeometry(width = 600f, height = 600f)
        val position = BoardPosition(4, 5)

        assertEquals(
            position,
            boardPositionAt(geometry.center(position), width = 600f, height = 600f),
        )
    }

    @Test
    fun tapBetweenIntersectionsIsIgnored() {
        val geometry = calculateBoardGeometry(width = 600f, height = 600f)
        val first = geometry.center(BoardPosition(4, 5))
        val between = Offset(first.x + geometry.cellSize * 0.5f, first.y)

        assertNull(boardPositionAt(between, width = 600f, height = 600f))
    }

    @Test
    fun tapOutsideBoardIsIgnored() {
        assertNull(boardPositionAt(Offset.Zero, width = 600f, height = 600f))
    }
}
