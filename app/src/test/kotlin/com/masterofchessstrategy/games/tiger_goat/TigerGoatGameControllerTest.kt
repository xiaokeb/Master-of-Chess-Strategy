package com.masterofchessstrategy.games.tiger_goat

import org.junit.Assert.*
import org.junit.Test

class TigerGoatGameControllerTest {
    @Test
    fun openingAndPlacementUseTheLegalActionWithoutSelectingAPiece() {
        val session = FakeSession()
        val controller = TigerGoatGameController(session)
        assertEquals(setOf(1), controller.uiState.value.legalDestinations)
        controller.onPointTap(0)
        assertTrue(session.applied.isEmpty())
        controller.onPointTap(1)
        assertEquals(TigerGoatAction(TigerGoatActionKind.REMOVE_GOAT, 1), session.applied.single())
        assertEquals(setOf(3), controller.uiState.value.legalDestinations)
        controller.onPointTap(3)
        assertEquals(TigerGoatAction(TigerGoatActionKind.PLACE_GOAT, to = 3), session.applied.last())
        assertNull(controller.uiState.value.selectedPoint)
        assertTrue(controller.uiState.value.canUndo)
    }

    @Test
    fun normalMoveRequiresSelectionAndAnAdvertisedDestination() {
        val session = FakeSession(stage = 2)
        val controller = TigerGoatGameController(session)
        controller.onPointTap(1)
        assertTrue(session.applied.isEmpty())
        controller.onPointTap(0)
        assertEquals(setOf(1), controller.uiState.value.legalDestinations)
        controller.onPointTap(3) // An opposing goat cannot become the selected tiger.
        assertTrue(session.applied.isEmpty())
        controller.onPointTap(1)
        assertEquals(TigerGoatAction(TigerGoatActionKind.STEP, 0, 1), session.applied.single())
        assertNull(controller.uiState.value.selectedPoint)
    }

    @Test
    fun pendingChainKeepsTheActiveTigerAndAllowsEndingTheTurn() {
        val session = FakeSession(stage = 3)
        val controller = TigerGoatGameController(session)
        assertEquals(1, controller.uiState.value.selectedPoint)
        assertEquals(setOf(0, 1), controller.uiState.value.chainVisited)
        assertEquals(setOf(3), controller.uiState.value.legalDestinations)
        controller.onPointTap(0)
        assertTrue(session.applied.isEmpty())
        controller.endChain()
        assertEquals(TigerGoatAction(TigerGoatActionKind.END_CHAIN), session.applied.single())
    }

    @Test
    fun rejectedActionAndRestoreKeepTheExistingPositionAndSelection() {
        val session = FakeSession(stage = 2)
        val controller = TigerGoatGameController(session)
        controller.onPointTap(0)
        session.rejection = 7
        controller.onPointTap(1)
        assertEquals(0, controller.uiState.value.selectedPoint)
        assertEquals("老虎不能斜向移动", controller.uiState.value.feedback)
        assertTrue(session.applied.isEmpty())
        assertFalse(controller.restore(byteArrayOf(99)))
        assertEquals(0, controller.uiState.value.selectedPoint)
        assertEquals(session.snapshot().pieces, controller.uiState.value.pieces)
        assertEquals("存档损坏或版本不兼容", controller.uiState.value.feedback)
    }

    @Test
    fun undoRestartAndRestoreClearSelectionAndOwnByteCopies() {
        val session = FakeSession(stage = 2)
        val controller = TigerGoatGameController(session)
        controller.onPointTap(0)
        controller.onPointTap(1)
        controller.undo()
        assertNull(controller.uiState.value.selectedPoint)
        controller.restart()
        assertEquals(TigerGoatPhase.OPENING_REMOVAL, controller.uiState.value.phase)
        val saved = byteArrayOf(2)
        assertTrue(controller.restore(saved))
        assertNotSame(saved, session.receivedSave)
        saved[0] = 99
        assertEquals(2, session.receivedSave!![0].toInt())
        val exported = controller.serialize()
        exported[0] = 99
        assertEquals(2, session.saveBuffer[0].toInt())
        controller.close()
        assertTrue(session.closed)
    }

    private class FakeSession(var stage: Int = 0) : TigerGoatRulesSession {
        override val spec = TigerGoatBoardSpec(
            List(4) { TigerGoatPoint(it, it, 0) },
            List(3) { TigerGoatEdge(it, it + 1) },
        )
        val applied = mutableListOf<TigerGoatAction>()
        private val priorStages = mutableListOf<Int>()
        var rejection = 0
        var receivedSave: ByteArray? = null
        val saveBuffer = byteArrayOf(2)
        var closed = false
        override val actionCount: Int get() = applied.size
        override fun snapshot() = TigerGoatSnapshot(
            pieces = if (stage == 0) listOf(1, 2, 1, 0) else listOf(1, 0, 1, 2),
            phase = when (stage) {
                0 -> TigerGoatPhase.OPENING_REMOVAL
                3 -> TigerGoatPhase.CAPTURE_CHAIN
                else -> TigerGoatPhase.PLAY
            },
            toMove = if (stage == 1 || stage == 4) 2 else 1,
            reserve = if (stage < 2) 14 else 0,
            captured = if (stage == 0) 0 else 1,
            winner = TigerGoatWinner.NONE,
            chainTiger = if (stage == 3) 1 else -1,
            chainVisited = if (stage == 3) setOf(0, 1) else emptySet(),
        )
        override fun legalActions() = when (stage) {
            0 -> listOf(TigerGoatAction(TigerGoatActionKind.REMOVE_GOAT, 1))
            1 -> listOf(TigerGoatAction(TigerGoatActionKind.PLACE_GOAT, to = 3))
            2 -> listOf(TigerGoatAction(TigerGoatActionKind.STEP, 0, 1))
            3 -> listOf(
                TigerGoatAction(TigerGoatActionKind.CAPTURE, 1, 3),
                TigerGoatAction(TigerGoatActionKind.END_CHAIN),
            )
            else -> emptyList()
        }
        override fun apply(action: TigerGoatAction): Int {
            if (rejection != 0) return rejection
            priorStages += stage
            applied += action
            stage = if (stage == 2 || action.kind == TigerGoatActionKind.END_CHAIN) 4 else stage + 1
            return 0
        }
        override fun undo(): Boolean {
            if (applied.isEmpty()) return false
            applied.removeAt(applied.lastIndex)
            stage = priorStages.removeAt(priorStages.lastIndex)
            return true
        }
        override fun reset() { stage = 0; applied.clear(); priorStages.clear() }
        override fun serialize() = saveBuffer
        override fun restore(bytes: ByteArray): Boolean {
            if (!bytes.contentEquals(byteArrayOf(2))) return false
            receivedSave = bytes
            stage = 2
            applied.clear()
            priorStages.clear()
            return true
        }
        override fun close() { closed = true }
    }
}
