package com.masterofchessstrategy.engine.games.tiger_goat

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.masterofchessstrategy.engine.internal.NativeBindings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Real packaged JNI and its handle table; no mock rules or Android app persistence. */
@RunWith(AndroidJUnit4::class)
class NativeTigerGoatSessionInstrumentedTest {
    @Test fun openingPlacementStepUndoAndCorruptionAreAtomic() {
        NativeTigerGoatSession().use { game ->
            assertEquals(37, game.geometry.points.size)
            assertEquals(76, game.geometry.edges.size)
            val initial = game.serialize()
            assertEquals(2, initial[4].toInt())
            assertEquals(8, game.legalActions().size)
            assertEquals(0, game.apply(TigerGoatAction(TigerGoatActionKind.REMOVE_GOAT, 6)))
            assertEquals(2, game.snapshot().side)
            assertEquals(7, game.snapshot().board.count { it == 2 })
            assertEquals(14, game.snapshot().reserve)
            assertEquals(0, game.apply(TigerGoatAction(TigerGoatActionKind.PLACE_GOAT, to = 0)))
            val placed = game.serialize()
            assertEquals(13, game.snapshot().reserve)
            assertEquals(0, game.apply(TigerGoatAction(TigerGoatActionKind.STEP, 10, 5)))
            assertTrue(game.undo())
            assertArrayEquals(placed, game.serialize())
            for (bad in listOf(placed.copyOf().also { it[12] = 3 }, ByteArray(52), ByteArray(196_659))) {
                assertFalse(game.restore(bad))
                assertArrayEquals(placed, game.serialize())
            }
            game.reset()
            assertArrayEquals(initial, game.serialize())
            assertFalse(game.undo())
        }
    }

    @Test fun legacyBlockedGoatsPassWritesV2AndRestoresUndo() {
        NativeTigerGoatSession().use { game ->
            val pass = TigerGoatAction(TigerGoatActionKind.PASS_GOAT)
            assertEquals(2, game.apply(pass)) // Opening cannot yield freely.
            assertTrue(game.restore(seed(1, 2, setOf(10, 14), (25..36).toSet())))
            val blocked = game.serialize()
            assertEquals(listOf(pass), game.legalActions())
            assertEquals(0, game.apply(pass))
            val passed = game.serialize()
            assertEquals(2, passed[4].toInt())
            assertEquals(1, game.snapshot().side)
            assertEquals(1, game.snapshot().actionCount)
            NativeTigerGoatSession().use { restored ->
                assertTrue(restored.restore(passed))
                assertTrue(restored.undo())
                assertArrayEquals(blocked, restored.serialize())
                assertEquals(listOf(pass), restored.legalActions())
            }
            // A valid checksum does not let a v1 save smuggle action 6.
            val mislabeled = passed.copyOf().also { it[4] = 1; checksum(it) }
            assertFalse(game.restore(mislabeled))
            assertArrayEquals(passed, game.serialize())
            assertTrue(game.undo())
            assertEquals(listOf(pass), game.legalActions())
        }
    }

    @Test fun captureChainReplayPreservesVisitedSetAndOptionalEnd() {
        NativeTigerGoatSession().use { game ->
            assertTrue(game.restore(seed(2, 1, setOf(10, 14), setOf(11, 17, 1, 2, 3, 5, 6, 7, 8, 35, 36))))
            assertEquals(0, game.apply(TigerGoatAction(TigerGoatActionKind.CAPTURE, 10, 12)))
            val chain = game.serialize()
            assertEquals(TigerGoatPhase.CAPTURE_CHAIN, game.snapshot().phase)
            assertEquals(setOf(10, 12), game.snapshot().visited)
            assertTrue(game.legalActions().contains(TigerGoatAction(TigerGoatActionKind.CAPTURE, 12, 22)))
            NativeTigerGoatSession().use { restored ->
                assertTrue(restored.restore(chain))
                assertEquals(game.snapshot(), restored.snapshot())
                assertEquals(0, restored.apply(TigerGoatAction(TigerGoatActionKind.END_CHAIN)))
                assertEquals(2, restored.snapshot().side)
                assertTrue(restored.undo())
                assertArrayEquals(chain, restored.serialize())
                assertEquals(0, restored.apply(TigerGoatAction(TigerGoatActionKind.CAPTURE, 12, 22)))
                assertTrue(restored.undo())
                assertArrayEquals(chain, restored.serialize())
            }
            assertEquals(0, game.apply(TigerGoatAction(TigerGoatActionKind.RESIGN)))
            assertEquals(TigerGoatOutcome.GOAT_WINS, game.snapshot().outcome)
            assertTrue(game.legalActions().isEmpty())
            assertTrue(game.undo())
            assertArrayEquals(chain, game.serialize())
        }
    }

    @Test fun foreignClosedHandlesAndUnnarrowedJavaIntegersAreRejected() {
        val other = NativeBindings.createChineseChessEngine()
        val raw = TigerGoatBindings.create()
        try {
            TigerGoatBindings.release(other)
            assertEquals(0, NativeBindings.chineseChessCurrentPlayer(other))
            NativeBindings.releaseChineseChessEngine(raw)
            val initial = TigerGoatBindings.serialize(raw)
            assertEquals(81, TigerGoatBindings.snapshot(raw).size)
            assertThrows(IllegalStateException::class.java) { TigerGoatBindings.snapshot(other) }
            for (kind in listOf(-1, 7, 256, Int.MAX_VALUE)) {
                assertEquals(2, TigerGoatBindings.apply(raw, kind, 6, -1))
                assertArrayEquals(initial, TigerGoatBindings.serialize(raw))
            }
            for (point in listOf(-2, 37, 256, Int.MAX_VALUE)) {
                assertEquals(3, TigerGoatBindings.apply(raw, 0, point, -1))
                assertArrayEquals(initial, TigerGoatBindings.serialize(raw))
            }
            TigerGoatBindings.release(raw)
            assertThrows(IllegalStateException::class.java) { TigerGoatBindings.snapshot(raw) }
            TigerGoatBindings.release(raw)
        } finally {
            TigerGoatBindings.release(raw)
            NativeBindings.releaseChineseChessEngine(other)
        }
        val closed = NativeTigerGoatSession()
        closed.close(); closed.close()
        assertThrows(IllegalStateException::class.java) { closed.snapshot() }
    }

    @Test fun concurrentRawReadsAndReleaseRemainMemorySafe() {
        val handle = TigerGoatBindings.create()
        val ready = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val readers = (0..1).map {
                executor.submit {
                    repeat(100) {
                        try {
                            assertEquals(81, TigerGoatBindings.snapshot(handle).size)
                            ready.countDown()
                        } catch (_: IllegalStateException) {
                            // Calls that begin after removal must fail, never use a stale pointer.
                        }
                    }
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            TigerGoatBindings.release(handle)
            readers.forEach { it.get(5, TimeUnit.SECONDS) }
            assertThrows(IllegalStateException::class.java) { TigerGoatBindings.serialize(handle) }
        } finally {
            TigerGoatBindings.release(handle)
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    private fun seed(version: Int, side: Int, tigers: Set<Int>, goats: Set<Int>): ByteArray {
        require(tigers.size == 2 && tigers.intersect(goats).isEmpty() && goats.size in 8..21)
        val bytes = ByteArray(53)
        byteArrayOf(77, 79, 67, 84, version.toByte(), 1, 1, side.toByte(), 0, (22 - goats.size).toByte())
            .copyInto(bytes)
        tigers.forEach { bytes[10 + it] = 1 }
        goats.forEach { bytes[10 + it] = 2 }
        checksum(bytes)
        return bytes
    }

    private fun checksum(bytes: ByteArray) {
        var hash = 0x811c9dc5.toInt()
        for (index in 0 until bytes.size - 4) hash = (hash xor (bytes[index].toInt() and 255)) * 16777619
        repeat(4) { bytes[bytes.size - 4 + it] = (hash ushr (it * 8)).toByte() }
    }
}
