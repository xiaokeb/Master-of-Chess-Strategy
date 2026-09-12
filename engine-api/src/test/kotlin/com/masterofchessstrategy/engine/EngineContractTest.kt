package com.masterofchessstrategy.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EngineContractTest {
    @Test
    fun difficultyCodesRemainStableAcrossJni() {
        assertEquals(listOf(0, 1, 2, 3), Difficulty.entries.map { it.code })
    }

    @Test
    fun gameTypeCodesAreUnique() {
        assertEquals(6, GameType.entries.map { it.code }.toSet().size)
    }

    @Test
    fun boardPositionsRejectNegativeCoordinates() {
        assertThrows(IllegalArgumentException::class.java) {
            BoardPosition(x = -1, y = 0)
        }
    }
}
