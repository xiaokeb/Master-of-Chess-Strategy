package com.masterofchessstrategy.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.zip.CRC32

@RunWith(AndroidJUnit4::class)
class NativeBridgeInstrumentedTest {
    @Test
    fun bundledLibraryReturnsExpectedProtocol() {
        val status = NativeEngineStatusProvider.check()

        assertTrue(status is NativeEngineStatus.Available)
        assertEquals(
            "MasterofChessStrategy Engine/1",
            (status as NativeEngineStatus.Available).protocol,
        )
    }

    @Test
    fun nativeChineseChessSessionSupportsMoveUndoAndRestore() {
        NativeChineseChessEngine().use { engine ->
            assertEquals(PlayerId(0), engine.currentPlayer)
            assertFalse(engine.isInCheck(ChineseChessSide.RED))
            assertFalse(engine.isInCheck(ChineseChessSide.BLACK))
            assertEquals(
                ChineseChessPiece(
                    ChineseChessPieceType.GENERAL,
                    ChineseChessSide.RED,
                ),
                engine.pieceAt(BoardPosition(4, 9)),
            )

            val initial = engine.serialize()
            assertEquals('M'.code.toByte(), initial[0])
            assertEquals(2, initial[4].toInt())
            assertEquals(GameType.CHINESE_CHESS.code, initial[5].toInt())
            assertEquals(
                ActionResult.Accepted,
                engine.apply(
                    BoardMove(
                        BoardPosition(0, 6),
                        BoardPosition(0, 5),
                    ),
                ),
            )
            assertTrue(engine.undo())
            assertTrue(initial.contentEquals(engine.serialize()))
            assertEquals(RestoreResult.Restored, engine.restore(initial))

            val corrupted = initial.copyOf()
            corrupted[20] = (corrupted[20].toInt() xor 1).toByte()
            assertEquals(
                RestoreResult.Rejected(EngineError.CORRUPTED_DATA),
                engine.restore(corrupted),
            )
            assertTrue(initial.contentEquals(engine.serialize()))
        }
    }

    @Test
    fun aiNeverFallsBackWhenTheNetworkIsMissing() {
        NativeChineseChessEngine().use { engine ->
            Difficulty.entries.forEach { difficulty ->
                org.junit.Assert.assertThrows(IllegalStateException::class.java) {
                    engine.chooseMove(difficulty)
                }
            }
        }
    }

    @Test
    fun nativeCheckQueryTracksMoveAndUndo() {
        NativeChineseChessEngine().use { engine ->
            assertEquals(RestoreResult.Restored, engine.restore(customPosition(
                side = ChineseChessSide.RED,
                pieces = listOf(
                    PositionedPiece(4, 9, ChineseChessPieceType.GENERAL, ChineseChessSide.RED),
                    PositionedPiece(5, 0, ChineseChessPieceType.GENERAL, ChineseChessSide.BLACK),
                    PositionedPiece(4, 7, ChineseChessPieceType.CHARIOT, ChineseChessSide.BLACK),
                ),
            )))
            assertTrue(engine.isInCheck(ChineseChessSide.RED))
            assertFalse(engine.isInCheck(ChineseChessSide.BLACK))
            assertAccepted(engine, 4, 9, 3, 9)
            assertFalse(engine.isInCheck(ChineseChessSide.RED))
            assertTrue(engine.undo())
            assertTrue(engine.isInCheck(ChineseChessSide.RED))
        }
    }

    @Test
    fun nativeChineseChessSessionAdjudicatesRepeatedJointChase() {
        NativeChineseChessEngine().use { engine ->
            val restored = engine.restore(
                customPosition(
                    side = ChineseChessSide.BLACK,
                    pieces = listOf(
                        PositionedPiece(4, 9, ChineseChessPieceType.GENERAL, ChineseChessSide.RED),
                        PositionedPiece(4, 0, ChineseChessPieceType.GENERAL, ChineseChessSide.BLACK),
                        PositionedPiece(4, 8, ChineseChessPieceType.ADVISOR, ChineseChessSide.RED),
                        PositionedPiece(0, 5, ChineseChessPieceType.CHARIOT, ChineseChessSide.RED),
                        PositionedPiece(3, 5, ChineseChessPieceType.CANNON, ChineseChessSide.RED),
                        PositionedPiece(4, 3, ChineseChessPieceType.HORSE, ChineseChessSide.BLACK),
                        PositionedPiece(5, 1, ChineseChessPieceType.CHARIOT, ChineseChessSide.BLACK),
                    ),
                ),
            )
            assertEquals(RestoreResult.Restored, restored)

            repeat(2) {
                assertAccepted(engine, 5, 1, 3, 1)
                assertAccepted(engine, 3, 5, 5, 5)
                assertAccepted(engine, 3, 1, 5, 1)
                assertAccepted(engine, 5, 5, 3, 5)
            }

            assertEquals(GameResult.FIRST_PLAYER_WIN, engine.gameResult())
        }
    }

    @Test
    fun fenImportPreservesNoCaptureClockInNativeRulesAndRestore() {
        val fen = "4k4/1N5N1/R8/9/9/4P4/9/9/9/4K4 w - - 119 38"
        val state = ChineseChessFenCodec.parse(fen).toEngineState()

        NativeChineseChessEngine().use { engine ->
            assertEquals(RestoreResult.Restored, engine.restore(state))
            assertEquals(GameResult.ONGOING, engine.gameResult())
            assertAccepted(engine, 0, 2, 4, 2)
            assertEquals(GameResult.FIRST_PLAYER_WIN, engine.gameResult())
            val finished = engine.serialize()

            NativeChineseChessEngine().use { restored ->
                assertEquals(RestoreResult.Restored, restored.restore(finished))
                assertEquals(GameResult.FIRST_PLAYER_WIN, restored.gameResult())
            }
        }
    }

    private fun assertAccepted(
        engine: NativeChineseChessEngine,
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
    ) {
        assertEquals(
            ActionResult.Accepted,
            engine.apply(
                BoardMove(
                    BoardPosition(fromX, fromY),
                    BoardPosition(toX, toY),
                ),
            ),
        )
    }

    private fun customPosition(
        side: ChineseChessSide,
        pieces: List<PositionedPiece>,
    ): ByteArray {
        val data = ByteArray(POSITION_HEADER_SIZE + POSITION_BOARD_SIZE + CHECKSUM_SIZE)
        "MOCX".encodeToByteArray().copyInto(data)
        data[4] = 2
        data[5] = GameType.CHINESE_CHESS.code.toByte()
        data[6] = side.code.toByte()
        pieces.forEach { piece ->
            val sideBit = if (piece.side == ChineseChessSide.BLACK) 0x80 else 0
            data[POSITION_HEADER_SIZE + piece.y * ChineseChessBoard.WIDTH + piece.x] =
                (sideBit or piece.type.code).toByte()
        }
        val crc = CRC32().apply {
            update(data, 0, data.size - CHECKSUM_SIZE)
        }.value
        repeat(CHECKSUM_SIZE) { byteIndex ->
            data[data.size - CHECKSUM_SIZE + byteIndex] =
                ((crc shr (byteIndex * Byte.SIZE_BITS)) and 0xff).toByte()
        }
        return data
    }

    private data class PositionedPiece(
        val x: Int,
        val y: Int,
        val type: ChineseChessPieceType,
        val side: ChineseChessSide,
    )

    private companion object {
        const val POSITION_HEADER_SIZE = 12
        const val POSITION_BOARD_SIZE = ChineseChessBoard.WIDTH * ChineseChessBoard.HEIGHT
        const val CHECKSUM_SIZE = 4
    }
}
