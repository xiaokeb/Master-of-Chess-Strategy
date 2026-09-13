package com.masterofchessstrategy.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

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
            val aiMove = requireNotNull(engine.chooseMove(Difficulty.EASY))
            assertTrue(aiMove in engine.legalActions())
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
}
