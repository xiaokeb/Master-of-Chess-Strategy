package com.masterofchessstrategy.engine

import java.util.zip.CRC32
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChineseChessPositionCodecTest {
    @Test
    fun customPositionUsesMocxV2HeaderPiecesAndValidCrc32() {
        val encoded = ChineseChessPositionCodec.encode(
            ChineseChessSide.RED,
            listOf(
                piece(4, 9, ChineseChessPieceType.GENERAL, ChineseChessSide.RED),
                piece(4, 0, ChineseChessPieceType.GENERAL, ChineseChessSide.BLACK),
                piece(3, 1, ChineseChessPieceType.CHARIOT, ChineseChessSide.RED),
            ),
        )

        assertEquals(106, encoded.size)
        assertEquals("MOCX", encoded.copyOfRange(0, 4).toString(Charsets.US_ASCII))
        assertEquals(2, encoded[4].toInt())
        assertEquals(0, encoded[6].toInt())
        assertEquals(ChineseChessPieceType.CHARIOT.code, encoded[12 + 12].toInt())
        val expected = CRC32().apply { update(encoded, 0, encoded.size - 4) }.value
        val actual = (0..3).fold(0L) { value, byte ->
            value or ((encoded[encoded.size - 4 + byte].toLong() and 0xffL) shl (byte * 8))
        }
        assertEquals(expected, actual)
    }

    @Test
    fun duplicateSquareOrMissingGeneralIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            ChineseChessPositionCodec.encode(
                ChineseChessSide.RED,
                listOf(
                    piece(4, 9, ChineseChessPieceType.GENERAL, ChineseChessSide.RED),
                    piece(4, 9, ChineseChessPieceType.GENERAL, ChineseChessSide.BLACK),
                ),
            )
        }
        val failure = runCatching {
            ChineseChessPositionCodec.encode(
                ChineseChessSide.RED,
                listOf(
                    piece(4, 9, ChineseChessPieceType.GENERAL, ChineseChessSide.RED),
                    piece(0, 1, ChineseChessPieceType.CHARIOT, ChineseChessSide.RED),
                ),
            )
        }
        assertTrue(failure.isFailure)
    }

    private fun piece(
        x: Int,
        y: Int,
        type: ChineseChessPieceType,
        side: ChineseChessSide,
    ) = PositionedChineseChessPiece(
        BoardPosition(x, y),
        ChineseChessPiece(type, side),
    )
}
