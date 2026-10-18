package com.masterofchessstrategy.engine.games.tiger_goat

import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual packaged JNI, private replay and live rules; no App navigation or persistence claims. */
@RunWith(AndroidJUnit4::class)
class NativeTigerGoatSearchInstrumentedTest {
    private val oneTurn = TigerGoatSearchLimits(1, 100_000, 10_000)

    @Test fun openingSearchIsLegalRepeatableAndDoesNotMutateTheLiveSession() {
        NativeTigerGoatSession().use { game ->
            val original = game.serialize()
            val first = TigerGoatSearchRequest.prepare(original, oneTurn).executeBlocking()
            assertEquals(TigerGoatSearchStop.DEPTH_COMPLETED, first.stop)
            assertEquals(1, first.completedTurnDepth)
            assertNotNull(first.score)
            assertEquals(TigerGoatActionKind.REMOVE_GOAT, first.action!!.kind)
            assertTrue(first.action in game.legalActions())
            assertArrayEquals(original, game.serialize())
            assertArrayEquals(original, first.sourceSnapshot)
            val repeat = TigerGoatSearchRequest.prepare(original, oneTurn).executeBlocking()
            assertEquals(first.action, repeat.action)
            assertEquals(first.score, repeat.score)
            assertEquals(first.visitedNodes, repeat.visitedNodes)
            assertEquals(0, game.apply(first.action!!))
            assertTrue(game.undo())
            assertArrayEquals(original, game.serialize())
        }
    }

    @Test fun placementAndExistingCaptureChainUseTheActualRules() {
        NativeTigerGoatSession().use { game ->
            assertEquals(0, game.apply(TigerGoatAction(TigerGoatActionKind.REMOVE_GOAT, 6)))
            val placing = game.serialize()
            val placement = TigerGoatSearchRequest.prepare(placing, oneTurn).executeBlocking()
            assertEquals(TigerGoatSearchStop.DEPTH_COMPLETED, placement.stop)
            assertEquals(TigerGoatActionKind.PLACE_GOAT, placement.action!!.kind)
            assertTrue(placement.action in game.legalActions())
            assertArrayEquals(placing, game.serialize())

            assertTrue(game.restore(seed(1, setOf(12, 0), setOf(13, 19, 1, 2, 3, 4, 5, 6, 9))))
            assertEquals(0, game.apply(TigerGoatAction(TigerGoatActionKind.CAPTURE, 12, 14)))
            assertEquals(TigerGoatPhase.CAPTURE_CHAIN, game.snapshot().phase)
            val chain = game.serialize()
            val continuation = TigerGoatSearchRequest.prepare(chain, oneTurn).executeBlocking()
            assertEquals(TigerGoatSearchStop.DEPTH_COMPLETED, continuation.stop)
            assertEquals(TigerGoatAction(TigerGoatActionKind.CAPTURE, 14, 24), continuation.action)
            assertEquals(100_000, continuation.score)
            assertArrayEquals(chain, game.serialize())
            assertEquals(0, game.apply(continuation.action!!))
            assertEquals(TigerGoatOutcome.TIGER_WINS, game.snapshot().outcome)
        }
    }

    @Test fun forcedGoatPassAndBothTerminalOutcomesAreNotGuessedByTheBridge() {
        NativeTigerGoatSession().use { game ->
            assertTrue(game.restore(seed(2, setOf(10, 14), (25..36).toSet())))
            val before = game.serialize()
            val pass = TigerGoatSearchRequest.prepare(before, oneTurn).executeBlocking()
            assertEquals(TigerGoatAction(TigerGoatActionKind.PASS_GOAT), pass.action)
            assertArrayEquals(before, game.serialize())
            for (goats in listOf(setOf(1, 2, 3, 5, 9, 10, 14), setOf(1, 2, 3, 5, 9, 10, 14, 15, 16, 17))) {
                assertTrue(game.restore(seed(1, setOf(0, 4), goats)))
                assertNotEquals(TigerGoatOutcome.ONGOING, game.snapshot().outcome)
                val result = TigerGoatSearchRequest.prepare(game.serialize(), oneTurn).executeBlocking()
                assertEquals(TigerGoatSearchStop.NO_LEGAL_ACTION, result.stop)
                assertNull(result.action); assertNull(result.score)
                assertEquals(0L, result.visitedNodes)
            }
        }
    }

    @Test fun zeroBudgetsAreExplicitAndElapsedIsNonnegative() {
        NativeTigerGoatSession().use { game ->
            val bytes = game.serialize()
            val noNodes = TigerGoatSearchRequest.prepare(bytes, oneTurn.copy(nodeLimit = 0)).executeBlocking()
            assertEquals(TigerGoatSearchStop.NODE_LIMIT, noNodes.stop)
            assertNull(noNodes.action); assertNull(noNodes.score)
            assertEquals(0L, noNodes.visitedNodes)
            val noTime = TigerGoatSearchRequest.prepare(bytes, oneTurn.copy(timeLimitMillis = 0)).executeBlocking()
            assertEquals(TigerGoatSearchStop.TIME_LIMIT, noTime.stop)
            assertNull(noTime.action); assertNull(noTime.score)
            assertTrue(noNodes.elapsedMillis >= 0 && noTime.elapsedMillis >= 0)
            assertArrayEquals(bytes, game.serialize())
        }
    }

    @Test fun rawBadParametersAndInvalidReplayAreRejectedWithoutTouchingTheLiveGame() {
        NativeTigerGoatSession().use { game ->
            val bytes = game.serialize()
            val token = AtomicBoolean(false)
            for (depth in listOf(0, 17, Int.MAX_VALUE)) {
                assertThrows(IllegalArgumentException::class.java) {
                    TigerGoatSearchBindings.search(bytes, depth, 100, 1_000, token)
                }
            }
            for (time in listOf(-1L, 60_001L, Long.MAX_VALUE)) {
                assertThrows(IllegalArgumentException::class.java) {
                    TigerGoatSearchBindings.search(bytes, 1, 100, time, token)
                }
            }
            assertThrows(IllegalArgumentException::class.java) {
                TigerGoatSearchBindings.search(bytes, 1, -1, 1_000, token)
            }
            for (bad in listOf(ByteArray(52), ByteArray(196_659), bytes.copyOf().also { it[12] = 99 })) {
                assertThrows(IllegalArgumentException::class.java) {
                    TigerGoatSearchBindings.search(bad, 1, 100, 1_000, token)
                }
            }
            val badChecksum = bytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
            // Kotlin admits shape only. The real JNI replay, not a fake, must reject this.
            val request = TigerGoatSearchRequest.prepare(badChecksum, oneTurn)
            assertThrows(IllegalArgumentException::class.java) { request.executeBlocking() }
            // Correct checksum and header, but placing a goat before opening removal is illegal.
            val badReplay = bytes.copyOf(56).also {
                it[47] = 1; it[48] = 0
                it[49] = 1; it[50] = 255.toByte(); it[51] = 0
                checksum(it)
            }
            val replayRequest = TigerGoatSearchRequest.prepare(badReplay, oneTurn)
            assertThrows(IllegalArgumentException::class.java) { replayRequest.executeBlocking() }
            assertArrayEquals(bytes, game.serialize())
        }
    }

    @Test fun rawPrecancelledTokenSkipsReplayButDoesNotBypassParameterBounds() {
        val token = AtomicBoolean(true)
        val fields = TigerGoatSearchBindings.search(ByteArray(53), 1, 100, 1_000, token)
        assertEquals(11, fields.size)
        assertEquals(listOf(1L, 4L, 0L, -1L, -1L, -1L, 0L, 0L, 0L, 0L), fields.take(10))
        assertTrue(fields[10] >= 0)
        assertThrows(IllegalArgumentException::class.java) {
            TigerGoatSearchBindings.search(ByteArray(52), 1, 100, 1_000, token)
        }
    }

    @Test fun concurrentCancellationRevokesAResultAndNeverBorrowsAClosedSessionHandle() {
        val bytes = NativeTigerGoatSession().use { it.serialize() }
        val enteringBridge = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val request = TigerGoatSearchRequest.prepare(
            bytes, TigerGoatSearchLimits(16, 10_000_000, 5_000),
            TigerGoatSearchBridge { source, depth, nodes, time, token ->
                enteringBridge.countDown()
                TigerGoatSearchBindings.search(source, depth, nodes, time, token)
            },
        )
        try {
            val running = executor.submit<TigerGoatSearchResult> { request.executeBlocking() }
            assertTrue(enteringBridge.await(5, TimeUnit.SECONDS))
            request.cancel()
            val result = running.get(10, TimeUnit.SECONDS)
            assertEquals(TigerGoatSearchStop.CANCELLED, result.stop)
            assertNull(result.action); assertNull(result.score)
            assertArrayEquals(bytes, result.sourceSnapshot)
            // The latch proves bridge dispatch, not an exact internal native poll boundary.
        } finally {
            request.cancel()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    @Test fun cancellingACompletedRealSuggestionRevokesItWithoutASecondNativeCall() {
        val bytes = NativeTigerGoatSession().use { it.serialize() }
        val request = TigerGoatSearchRequest.prepare(bytes, oneTurn)
        val result = request.executeBlocking()
        assertNotNull(result.action)
        request.cancel()
        assertEquals(TigerGoatSearchStop.CANCELLED, result.stop)
        assertNull(result.action); assertNull(result.score)
        assertThrows(IllegalStateException::class.java) { request.executeBlocking() }
    }

    private fun seed(side: Int, tigers: Set<Int>, goats: Set<Int>): ByteArray {
        require(tigers.size == 2 && tigers.intersect(goats).isEmpty() && goats.size in 7..21)
        val bytes = ByteArray(53)
        byteArrayOf(77, 79, 67, 84, 2, 1, 1, side.toByte(), 0, (22 - goats.size).toByte()).copyInto(bytes)
        tigers.forEach { bytes[10 + it] = 1 }
        goats.forEach { bytes[10 + it] = 2 }
        checksum(bytes)
        return bytes
    }

    private fun checksum(bytes: ByteArray) {
        var value = 0x811c9dc5.toInt()
        for (index in 0 until bytes.size - 4) value = (value xor (bytes[index].toInt() and 255)) * 16777619
        repeat(4) { bytes[bytes.size - 4 + it] = (value ushr (8 * it)).toByte() }
    }
}
