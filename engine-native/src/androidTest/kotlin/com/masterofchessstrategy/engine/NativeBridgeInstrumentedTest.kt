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
        }
    }
}
