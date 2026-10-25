package com.masterofchessstrategy.engine.games.lanzhou_fangqi

import com.masterofchessstrategy.engine.GameResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class NativeLanzhouFangqiSessionTest {
    @Test fun snapshotAndLegalCaptureDecodeTheStableProtocol() {
        val bridge = FakeBridge().apply {
            state[48] = 2
            state[49] = 0
            state[50] = 1
            legal = intArrayOf(1, 6, 6, 0, 0)
        }
        NativeLanzhouFangqiSession(bridge).use { session ->
            val snapshot = session.snapshot()
            assertEquals(FangqiSide.RED, snapshot.currentPlayer)
            assertEquals(1, snapshot.pendingCaptures)
            assertEquals(GameResult.ONGOING, snapshot.result)
            assertEquals(2, snapshot.board[48])
            assertEquals(listOf(FangqiAction.Capture(FangqiPoint(6, 6))), session.legalActions())
            assertTrue(session.apply(FangqiAction.Capture(FangqiPoint(6, 6))).accepted)
            assertEquals(listOf(1, 6, 6, 0, 0), bridge.lastApply)
        }
        assertEquals(1, bridge.releaseCount)
    }

    @Test fun malformedNativePayloadsAndClosedHandleAreRejected() {
        val bridge = FakeBridge().apply { state[0] = 3 }
        val session = NativeLanzhouFangqiSession(bridge)
        expectFailure { session.snapshot() }
        bridge.state[0] = 0
        bridge.legal = intArrayOf(0, 3, 3, 6)
        expectFailure { session.legalActions() }
        session.close()
        session.close()
        assertEquals(1, bridge.releaseCount)
        expectFailure { session.snapshot() }
    }

    @Test fun rejectedActionHasStableErrorAndRestoreCopiesInput() {
        val bridge = FakeBridge().apply { applyCode = FangqiRejection.REPEATED_POSITION.code }
        NativeLanzhouFangqiSession(bridge).use { session ->
            val result = session.apply(FangqiAction.Move(FangqiPoint(0, 5), FangqiPoint(0, 4)))
            assertFalse(result.accepted)
            assertEquals(FangqiRejection.REPEATED_POSITION, result.rejection)
            val bytes = byteArrayOf(1, 2, 3)
            assertTrue(session.restore(bytes))
            bytes[0] = 9
            assertEquals(1, bridge.restored?.first()?.toInt())
        }
    }

    private fun expectFailure(action: () -> Unit) {
        try {
            action()
            fail("Expected malformed or closed native session to be rejected")
        } catch (_: IllegalStateException) {
            // Expected protocol guard.
        }
    }

    private class FakeBridge : FangqiBridge {
        val state = IntArray(53)
        var legal = intArrayOf()
        var applyCode = 0
        var lastApply: List<Int>? = null
        var restored: ByteArray? = null
        var releaseCount = 0

        override fun create() = 7L
        override fun createFromPosition(board: IntArray, sideCode: Int) = 7L
        override fun release(handle: Long) { releaseCount++ }
        override fun snapshot(handle: Long) = state.copyOf()
        override fun apply(handle: Long, kind: Int, x: Int, y: Int, toX: Int, toY: Int): Int {
            lastApply = listOf(kind, x, y, toX, toY)
            return applyCode
        }
        override fun legalActions(handle: Long) = legal.copyOf()
        override fun undo(handle: Long) = false
        override fun reset(handle: Long) = Unit
        override fun serialize(handle: Long) = byteArrayOf(1)
        override fun restore(handle: Long, data: ByteArray): Boolean {
            restored = data
            return true
        }
        override fun chooseAction(handle: Long, difficultyCode: Int) = intArrayOf()
    }
}
