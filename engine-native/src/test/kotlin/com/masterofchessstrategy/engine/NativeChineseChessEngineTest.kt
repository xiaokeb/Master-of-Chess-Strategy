package com.masterofchessstrategy.engine

import com.masterofchessstrategy.engine.internal.ChineseChessBridge
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeChineseChessEngineTest {
    @Test
    fun bridgeValuesMapToTypedGameState() {
        val bridge = FakeChineseChessBridge()
        NativeChineseChessEngine(bridge).use { engine ->
            assertEquals(GameType.CHINESE_CHESS, engine.gameType)
            assertEquals(PlayerId(0), engine.currentPlayer)
            assertEquals(
                ChineseChessPiece(
                    ChineseChessPieceType.CHARIOT,
                    ChineseChessSide.BLACK,
                ),
                engine.pieceAt(BoardPosition(0, 0)),
            )
            assertEquals(
                listOf(
                    BoardMove(
                        BoardPosition(0, 6),
                        BoardPosition(0, 5),
                    ),
                ),
                engine.legalActions(),
            )
            assertEquals(GameResult.ONGOING, engine.gameResult())
            assertEquals(
                BoardMove(BoardPosition(0, 3), BoardPosition(0, 4)),
                engine.chooseMove(Difficulty.EASY),
            )
        }
    }

    @Test
    fun moveAndRestoreCodesMapWithoutOrdinalCoupling() {
        val bridge = FakeChineseChessBridge()
        NativeChineseChessEngine(bridge).use { engine ->
            bridge.applyResult = 1
            assertEquals(
                ActionResult.Rejected(EngineError.ILLEGAL_ACTION),
                engine.apply(
                    BoardMove(
                        BoardPosition(0, 6),
                        BoardPosition(1, 6),
                    ),
                ),
            )

            bridge.restoreResult = 3
            assertEquals(
                RestoreResult.Rejected(EngineError.CORRUPTED_DATA),
                engine.restore(byteArrayOf(1, 2, 3)),
            )
            assertArrayEquals(byteArrayOf(1, 2, 3), bridge.lastRestore)
        }
    }

    @Test
    fun closeIsIdempotentAndClosedSessionRejectsCalls() {
        val bridge = FakeChineseChessBridge()
        val engine = NativeChineseChessEngine(bridge)

        engine.close()
        engine.close()

        assertEquals(listOf(42L), bridge.releasedHandles)
        val error = assertThrows(IllegalStateException::class.java) {
            engine.undo()
        }
        assertEquals("Engine session is closed", error.message)
    }

    @Test
    fun malformedNativeCollectionsAreRejected() {
        val bridge = FakeChineseChessBridge().apply {
            moves = intArrayOf(0, 6, 0)
        }
        NativeChineseChessEngine(bridge).use { engine ->
            val error = assertThrows(IllegalStateException::class.java) {
                engine.legalActions()
            }
            assertEquals("Native engine returned malformed moves", error.message)
        }
    }

    @Test
    fun malformedAiMoveAndMissingMasterNetworkAreRejected() {
        val bridge = FakeChineseChessBridge().apply {
            aiMove = intArrayOf(0, 3, 0)
        }
        NativeChineseChessEngine(bridge).use { engine ->
            assertThrows(IllegalStateException::class.java) {
                engine.chooseMove(Difficulty.EASY)
            }
            assertThrows(IllegalStateException::class.java) {
                engine.chooseMove(Difficulty.MASTER)
            }
        }
    }

    @Test
    fun masterMoveUsesTheProvidedNetworkPath() {
        val bridge = FakeChineseChessBridge()
        NativeChineseChessEngine(
            bridge = bridge,
            masterNetworkPathProvider = { "private/pikafish.nnue" },
        ).use { engine ->
            assertEquals(
                BoardMove(BoardPosition(0, 3), BoardPosition(0, 4)),
                engine.chooseMove(Difficulty.MASTER),
            )
        }

        assertEquals(Difficulty.MASTER.code, bridge.lastDifficultyCode)
        assertEquals("private/pikafish.nnue", bridge.lastNetworkPath)
    }

    @Test
    fun malformedNativeScalarCodesAreRejected() {
        val bridge = FakeChineseChessBridge()
        bridge.currentPlayerCode = 2
        NativeChineseChessEngine(bridge).use { engine ->
            assertThrows(IllegalStateException::class.java) {
                engine.currentPlayer
            }

            bridge.currentPlayerCode = 0
            bridge.pieceCode = 0x105
            val error = assertThrows(IllegalStateException::class.java) {
                engine.pieceAt(BoardPosition(0, 0))
            }
            assertEquals("Native engine returned malformed piece", error.message)
        }
    }

    @Test
    fun commandsUseTheOwnedHandle() {
        val bridge = FakeChineseChessBridge()
        NativeChineseChessEngine(bridge).use { engine ->
            engine.reset()
            assertTrue(engine.undo())
            assertEquals(ActionResult.Accepted, engine.apply(validMove()))
            assertEquals(RestoreResult.Restored, engine.restore(byteArrayOf(7)))
            assertFalse(engine.serialize().isEmpty())
        }

        assertTrue(bridge.observedHandles.all { it == 42L })
    }

    private fun validMove() =
        BoardMove(
            from = BoardPosition(0, 6),
            to = BoardPosition(0, 5),
        )
}

private class FakeChineseChessBridge : ChineseChessBridge {
    val observedHandles = mutableListOf<Long>()
    val releasedHandles = mutableListOf<Long>()
    var applyResult = 0
    var restoreResult = 0
    var lastRestore = byteArrayOf()
    var moves = intArrayOf(0, 6, 0, 5)
    var aiMove = intArrayOf(0, 3, 0, 4)
    var currentPlayerCode = 0
    var pieceCode = 0x80 or ChineseChessPieceType.CHARIOT.code
    var lastDifficultyCode: Int? = null
    var lastNetworkPath: String? = null

    override fun create(): Long = 42L

    override fun release(handle: Long) {
        releasedHandles += handle
    }

    override fun currentPlayer(handle: Long): Int =
        observe(handle) { currentPlayerCode }

    override fun reset(handle: Long) = observe(handle) { Unit }

    override fun applyMove(
        handle: Long,
        fromX: Int,
        fromY: Int,
        toX: Int,
        toY: Int,
    ): Int = observe(handle) { applyResult }

    override fun undo(handle: Long): Boolean = observe(handle) { true }

    override fun legalMoves(handle: Long): IntArray =
        observe(handle) { moves.copyOf() }

    override fun bestMove(
        handle: Long,
        difficultyCode: Int,
        networkPath: String?,
    ): IntArray =
        observe(handle) {
            lastDifficultyCode = difficultyCode
            lastNetworkPath = networkPath
            aiMove.copyOf()
        }

    override fun gameResult(handle: Long): Int = observe(handle) { 0 }

    override fun serialize(handle: Long): ByteArray =
        observe(handle) { byteArrayOf(1) }

    override fun restore(handle: Long, data: ByteArray): Int =
        observe(handle) {
            lastRestore = data.copyOf()
            restoreResult
        }

    override fun pieceAt(handle: Long, x: Int, y: Int): Int =
        observe(handle) { pieceCode }

    private inline fun <T> observe(handle: Long, value: () -> T): T {
        observedHandles += handle
        return value()
    }
}
