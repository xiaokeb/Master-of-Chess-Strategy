package com.masterofchessstrategy.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChineseChessFenCodecTest {
    @Test
    fun initialPositionRoundTripsWithCanonicalPikafishFen() {
        val parsed = ChineseChessFenCodec.parse(START_FEN)

        assertEquals(ChineseChessSide.RED, parsed.sideToMove)
        assertEquals(32, parsed.pieces.size)
        assertEquals(0, parsed.noCapturePlies)
        assertEquals(1, parsed.fullMoveNumber)
        assertEquals(START_FEN, ChineseChessFenCodec.format(parsed))
        assertEquals(106, parsed.toEngineState().size)
    }

    @Test
    fun countersAndSideRoundTripWithoutDroppingTheHalfmoveClock() {
        val fen = "4k4/9/9/9/9/4P4/9/9/9/4K4 b - - 119 38"
        val parsed = ChineseChessFenCodec.parse(fen)

        assertEquals(ChineseChessSide.BLACK, parsed.sideToMove)
        assertEquals(119, parsed.noCapturePlies)
        assertEquals(38, parsed.fullMoveNumber)
        assertEquals(fen, ChineseChessFenCodec.format(parsed))
        assertEquals(119, parsed.toEngineState()[7].toInt() and 0xff)
    }

    @Test
    fun malformedRanksPiecesFieldsAndCountersAreRejected() {
        val invalid = listOf(
            START_FEN.replace("rnbakabnr", "rnbakabn"),
            START_FEN.replace("rnbakabnr", "rnbakabnx"),
            START_FEN.replace("rnbakabnr", "rnbakaKnr"),
            START_FEN.replace("/9/9/", "/0/9/"),
            START_FEN.replace(" w - - ", " x - - "),
            START_FEN.replace(" w - - ", " w K - "),
            START_FEN.replace(" 0 1", " 121 1"),
            START_FEN.replace(" 0 1", " 0 0"),
            START_FEN + " extra",
        )
        invalid.forEach { fen ->
            assertTrue(fen, runCatching { ChineseChessFenCodec.parse(fen) }.isFailure)
        }
    }

    @Test
    fun duplicateOrMissingGeneralsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ChineseChessFenCodec.parse("9/9/9/9/9/9/9/9/9/4K4 w - - 0 1")
        }
    }

    private companion object {
        const val START_FEN =
            "rnbakabnr/9/1c5c1/p1p1p1p1p/9/9/" +
                "P1P1P1P1P/1C5C1/9/RNBAKABNR w - - 0 1"
    }
}
