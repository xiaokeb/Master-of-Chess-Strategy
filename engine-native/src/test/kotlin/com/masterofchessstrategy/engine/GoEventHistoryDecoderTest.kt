package com.masterofchessstrategy.engine

import org.junit.Assert.*
import org.junit.Test

class GoEventHistoryDecoderTest {
    @Test fun emptyAndAllFiveKindsDecodeForEveryBoardSize() {
        for (size in listOf(9, 13, 19)) {
            assertTrue(GoEventHistoryDecoder.decode(intArrayOf(1, 0), size).isEmpty())
            assertEquals(listOf(
                GoAction.Place(BoardPosition(size - 1, 0)), GoAction.Pass,
                GoAction.ToggleDeadGroup(BoardPosition(0, size - 1), GoPlayer.WHITE),
                GoAction.ConfirmScore(GoPlayer.BLACK, Int.MAX_VALUE),
                GoAction.ResumePlay(GoPlayer.WHITE),
            ), GoEventHistoryDecoder.decode(intArrayOf(1, 5,
                1, size - 1, 0, 0, 0, 2, 0, 0, 0, 0,
                3, 0, size - 1, 1, 0, 4, 0, Int.MAX_VALUE, 0, 0, 5, 1, 0, 0, 0), size))
        }
    }

    @Test fun truncationTrailingFieldsVersionsAndCountsAreRejected() {
        val valid = frame(1, 0, 0, 0, 0)
        for (size in valid.indices) rejected(valid.copyOf(size))
        rejected(valid + 0)
        for (count in listOf(-1, Int.MAX_VALUE, GoEventHistoryDecoder.MAX_EVENTS + 1))
            rejected(intArrayOf(1, count))
        rejected(valid.copyOf().also { it[0] = 2 })
    }

    @Test fun invalidKindsCoordinatesActorsAndRevisionAreRejected() {
        for (kind in listOf(0, 6, Int.MAX_VALUE)) rejected(frame(kind, 0, 0, 0, 0))
        for (bad in listOf(-1, 9, Int.MAX_VALUE)) {
            rejected(frame(1, bad, 0, 0, 0))
            rejected(frame(3, 0, bad, 0, 0))
        }
        for (bad in listOf(-1, 2, Int.MAX_VALUE)) {
            rejected(frame(3, 0, 0, bad, 0))
            rejected(frame(4, bad, 0, 0, 0))
            rejected(frame(5, bad, 0, 0, 0))
        }
        rejected(frame(4, 0, -1, 0, 0))
    }

    @Test fun allUnusedArgumentSlotsMustBeZero() {
        for (kind in 1..5) rejected(frame(kind, 0, 0, 0, 1))
        rejected(frame(1, 0, 0, 1, 0))
        for (offset in 3..5) rejected(frame(2, 0, 0, 0, 0).also { it[offset] = 1 })
        rejected(frame(4, 0, 0, 1, 0))
        rejected(frame(5, 0, 1, 0, 0))
        rejected(frame(5, 0, 0, 1, 0))
    }

    @Test fun nativeUpperBoundIncludesReservedFinalConfirmations() {
        val data = IntArray(2 + GoEventHistoryDecoder.MAX_EVENTS * 5)
        data[0] = 1
        data[1] = GoEventHistoryDecoder.MAX_EVENTS
        repeat(data[1]) { data[2 + it * 5] = 2 }
        assertEquals(data[1], GoEventHistoryDecoder.decode(data, 19).size)
    }

    private fun frame(vararg event: Int) = intArrayOf(1, 1) + event
    private fun rejected(data: IntArray) {
        assertThrows(IllegalStateException::class.java) { GoEventHistoryDecoder.decode(data, 9) }
    }
}
