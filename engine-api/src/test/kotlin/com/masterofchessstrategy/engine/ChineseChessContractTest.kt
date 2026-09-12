package com.masterofchessstrategy.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ChineseChessContractTest {
    @Test
    fun sideAndPieceCodesMatchTheNativeProtocol() {
        assertEquals(listOf(0, 1), ChineseChessSide.entries.map { it.code })
        assertEquals(
            (1..7).toList(),
            ChineseChessPieceType.entries.map { it.code },
        )
    }

    @Test
    fun boardRejectsCoordinatesOutsideNineByTenGrid() {
        assertThrows(IllegalArgumentException::class.java) {
            ChineseChessBoard.requireInside(BoardPosition(9, 0))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ChineseChessBoard.requireInside(BoardPosition(0, 10))
        }
    }
}
