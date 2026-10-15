package com.masterofchessstrategy.engine.games.tiger_goat

import org.junit.Assert.*
import org.junit.Test

class NativeTigerGoatSessionTest {
    @Test
    fun snapshotDecodesOpeningAndNativeArraysCannotMutateTheResult() {
        val bridge = FakeBridge()
        val session = NativeTigerGoatSession(bridge)
        val snapshot = session.snapshot()
        assertEquals(TigerGoatPhase.OPENING_REMOVAL, snapshot.phase)
        assertEquals(14, snapshot.reserve)
        assertEquals(2, snapshot.board.count { it == 1 })
        assertEquals(8, snapshot.board.count { it == 2 })
        bridge.fields[10] = 0
        assertEquals(1, snapshot.board[10])
        assertEquals(37, session.geometry.points.size)
        assertEquals(76, session.geometry.edges.size)
    }

    @Test
    fun malformedSnapshotsAndChainsAreRejected() {
        val bridge = FakeBridge()
        val session = NativeTigerGoatSession(bridge)
        val opening = bridge.fields.copyOf()
        for (field in listOf(10, 37, 38, 39, 40, 41, 42, 43, 44)) {
            bridge.fields = opening.copyOf().also { it[field] = 99 }
            assertThrows(IllegalStateException::class.java) { session.snapshot() }
        }
        bridge.fields = opening.copyOf(80)
        assertThrows(IllegalStateException::class.java) { session.snapshot() }
        bridge.fields = opening.copyOf().also {
            it[37] = 2; it[42] = 10; it[43] = 1; it[44 + 10] = 1
        }
        assertThrows(IllegalStateException::class.java) { session.snapshot() }
        bridge.fields[44 + 12] = 1
        bridge.fields[6] = 0; bridge.fields[40] = 1
        assertEquals(setOf(10, 12), session.snapshot().visited)
        bridge.fields[42] = 12 // An empty point cannot be the active tiger.
        assertThrows(IllegalStateException::class.java) { session.snapshot() }
    }

    @Test
    fun topologyMismatchAndInvalidCreateDoNotLeakHandles() {
        val bridge = FakeBridge()
        bridge.geometryFields[3 + 25 * 2] = 0
        assertThrows(IllegalStateException::class.java) { NativeTigerGoatSession(bridge) }
        assertEquals(0, bridge.createCalls)
        bridge.geometryFields = geometryFields()
        bridge.newHandle = 0
        assertThrows(IllegalStateException::class.java) { NativeTigerGoatSession(bridge) }
    }

    @Test
    fun actionShapesKindsAndRuleErrorsAreValidated() {
        val bridge = FakeBridge()
        val session = NativeTigerGoatSession(bridge)
        assertEquals(listOf(TigerGoatAction(TigerGoatActionKind.REMOVE_GOAT, 6)), session.legalActions())
        val remove = TigerGoatAction(TigerGoatActionKind.REMOVE_GOAT, 6)
        assertEquals(0, session.apply(remove))
        assertEquals(listOf(7L, 0L, 6L, -1L), bridge.lastApply)
        for (fields in listOf(intArrayOf(0), intArrayOf(9, 0, 0), intArrayOf(0, 6, 0),
            intArrayOf(1, 0, 8), intArrayOf(2, 37, 0), intArrayOf(3, 8, 8),
            intArrayOf(4, 0, -1), intArrayOf(0, 6, -1, 0, 6, -1))) {
            bridge.actions = fields
            assertThrows(IllegalStateException::class.java) { session.legalActions() }
        }
        bridge.error = 90
        assertThrows(IllegalStateException::class.java) { session.apply(remove) }
    }

    @Test
    fun forcedPassKeepsCodeSixAndRejectsAllPointPayloads() {
        val bridge = FakeBridge()
        val session = NativeTigerGoatSession(bridge)
        bridge.actions = intArrayOf(6, -1, -1)
        val pass = TigerGoatAction(TigerGoatActionKind.PASS_GOAT)
        assertEquals(listOf(pass), session.legalActions())
        assertEquals(0, session.apply(pass))
        assertEquals(listOf(7L, 6L, -1L, -1L), bridge.lastApply)
        for (fields in listOf(intArrayOf(6, 0, -1), intArrayOf(6, -1, 0))) {
            bridge.actions = fields
            assertThrows(IllegalStateException::class.java) { session.legalActions() }
        }
        assertThrows(IllegalStateException::class.java) {
            session.apply(pass.copy(from = 0))
        }
        assertEquals(listOf(7L, 6L, -1L, -1L), bridge.lastApply)
    }

    @Test
    fun byteOwnershipAndCloseAreDeterministic() {
        val bridge = FakeBridge()
        val session = NativeTigerGoatSession(bridge)
        val saved = session.serialize()
        saved[0] = 99
        assertEquals(0, bridge.saved[0].toInt())
        val restore = ByteArray(53)
        assertTrue(session.restore(restore))
        assertNotSame(restore, bridge.receivedSave)
        restore[0] = 99
        assertEquals(0, bridge.receivedSave!![0].toInt())
        assertFalse(session.restore(ByteArray(52)))
        assertFalse(session.restore(ByteArray(196_659)))
        session.close(); session.close()
        assertEquals(listOf(7L), bridge.released)
        assertThrows(IllegalStateException::class.java) { session.snapshot() }
        assertThrows(IllegalStateException::class.java) { session.restore(ByteArray(53)) }
    }

    private class FakeBridge : TigerGoatBridge {
        var geometryFields = geometryFields()
        var fields = IntArray(81).also {
            it[10] = 1; it[14] = 1
            for (point in listOf(6, 7, 8, 11, 13, 16, 17, 18)) it[point] = 2
            it[38] = 1; it[39] = 14; it[42] = -1
        }
        var actions = intArrayOf(0, 6, -1)
        var newHandle = 7L
        var createCalls = 0
        var error = 0
        var lastApply: List<Long>? = null
        val saved = ByteArray(53)
        var receivedSave: ByteArray? = null
        val released = mutableListOf<Long>()
        override fun create(): Long { createCalls++; return newHandle }
        override fun release(handle: Long) { released += handle }
        override fun geometry() = geometryFields
        override fun snapshot(handle: Long) = fields
        override fun legalActions(handle: Long) = actions
        override fun apply(handle: Long, kind: Int, from: Int, to: Int): Int {
            lastApply = listOf(handle, kind.toLong(), from.toLong(), to.toLong())
            return error
        }
        override fun undo(handle: Long) = true
        override fun reset(handle: Long) = Unit
        override fun serialize(handle: Long) = saved
        override fun restore(handle: Long, bytes: ByteArray): Boolean { receivedSave = bytes; return true }
    }

    companion object {
        private fun geometryFields(): IntArray {
            // An explicit edge fixture checks the protocol against the documented board.
            val points = List(25) { it % 5 to it / 5 } + listOf(
                -1 to 1, -1 to 2, -1 to 3, -2 to 0, -2 to 2, -2 to 4,
                5 to 1, 5 to 2, 5 to 3, 6 to 0, 6 to 2, 6 to 4,
            )
            val edges = mutableListOf<Pair<Int, Int>>()
            for (y in 0..4) for (x in 0..3) edges += (y * 5 + x) to (y * 5 + x + 1)
            for (y in 0..3) for (x in 0..4) edges += (y * 5 + x) to ((y + 1) * 5 + x)
            edges += listOf(0 to 6, 6 to 12, 2 to 6, 6 to 10, 2 to 8, 8 to 14, 4 to 8, 8 to 12,
                10 to 16, 16 to 22, 12 to 16, 16 to 20, 12 to 18, 18 to 24, 14 to 18, 18 to 22,
                10 to 25, 25 to 28, 10 to 26, 26 to 29, 10 to 27, 27 to 30,
                25 to 26, 26 to 27, 28 to 29, 29 to 30,
                14 to 31, 31 to 34, 14 to 32, 32 to 35, 14 to 33, 33 to 36,
                31 to 32, 32 to 33, 34 to 35, 35 to 36)
            return (listOf(1, 37, 76) + points.flatMap { listOf(it.first, it.second) } +
                edges.flatMap { listOf(minOf(it.first, it.second), maxOf(it.first, it.second)) }).toIntArray()
        }
    }
}
