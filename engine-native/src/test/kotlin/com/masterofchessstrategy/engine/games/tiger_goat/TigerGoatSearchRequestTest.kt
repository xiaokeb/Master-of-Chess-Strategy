package com.masterofchessstrategy.engine.games.tiger_goat

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class TigerGoatSearchRequestTest {
    @Test fun limitsHaveExplicitBoundsNotDifficultyClaims() {
        assertEquals(TigerGoatSearchLimits(3, 10_000, 1_500), TigerGoatSearchLimits())
        for (depth in listOf(Int.MIN_VALUE, 0, 17, Int.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { TigerGoatSearchLimits(turnDepth = depth) }
        }
        assertThrows(IllegalArgumentException::class.java) { TigerGoatSearchLimits(nodeLimit = -1) }
        for (time in listOf(Long.MIN_VALUE, -1L, 60_001L, Long.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { TigerGoatSearchLimits(timeLimitMillis = time) }
        }
        assertEquals(0L, TigerGoatSearchLimits(16, 0, 60_000).nodeLimit)
        assertEquals(Long.MAX_VALUE, TigerGoatSearchLimits(nodeLimit = Long.MAX_VALUE).nodeLimit)
    }

    @Test fun prepareRejectsMalformedShapeBeforeCallingTheBridge() {
        val bridge = fixedBridge(frame())
        val malformed = listOf(
            ByteArray(52), ByteArray(196_659), ByteArray(53),
            snapshot().also { it[0] = 0 }, snapshot().also { it[4] = 0 },
            snapshot().also { it[4] = 3 }, snapshot().also { it[5] = 2 },
            snapshot().also { it[47] = 1 }, snapshot().copyOf(56),
        )
        malformed.forEach { bytes ->
            assertThrows(IllegalArgumentException::class.java) {
                TigerGoatSearchRequest.prepare(bytes, bridge = bridge)
            }
        }
        // Checksums, seed pieces and replay are deliberately not validated in Kotlin.
        for (version in listOf(1, 2)) {
            TigerGoatSearchRequest.prepare(snapshot().also { it[4] = version.toByte() }, bridge = bridge)
        }
    }

    @Test fun callerBridgeRequestAndResultCannotMutateOwnedSnapshot() {
        val original = snapshot()
        val expected = original.copyOf()
        var bridgeInput: ByteArray? = null
        val request = TigerGoatSearchRequest.prepare(original, bridge = TigerGoatSearchBridge { bytes, depth, nodes, time, _ ->
            assertArrayEquals(expected, bytes)
            assertEquals(3, depth); assertEquals(10_000L, nodes); assertEquals(1_500L, time)
            bridgeInput = bytes
            bytes[10] = 99
            frame()
        })
        original[0] = 0
        request.sourceSnapshot[1] = 0
        val result = request.executeBlocking()
        assertArrayEquals(expected, request.sourceSnapshot)
        assertArrayEquals(expected, result.sourceSnapshot)
        result.sourceSnapshot[2] = 0
        bridgeInput!![3] = 0
        assertArrayEquals(expected, result.sourceSnapshot)
    }

    @Test fun stopCodesAreExplicitAndFailureMetricsRemainDiagnostic() {
        val expected = listOf(
            TigerGoatSearchStop.DEPTH_COMPLETED, TigerGoatSearchStop.NO_LEGAL_ACTION,
            TigerGoatSearchStop.NODE_LIMIT, TigerGoatSearchStop.TIME_LIMIT,
            TigerGoatSearchStop.CANCELLED, TigerGoatSearchStop.INVALID_LIMITS, TigerGoatSearchStop.RULE_REJECTED,
        )
        expected.forEachIndexed { code, stop ->
            val fields = when (stop) {
                TigerGoatSearchStop.DEPTH_COMPLETED -> frame()
                TigerGoatSearchStop.NODE_LIMIT, TigerGoatSearchStop.TIME_LIMIT -> frame(stop = code, depth = 1)
                else -> emptyFrame(stop = code, depth = 1, nodes = 3)
            }
            val result = execute(fields)
            assertEquals(code, stop.code)
            assertEquals(stop, result.stop)
            assertEquals(3L, result.visitedNodes)
            assertEquals(7L, result.elapsedMillis)
        }
    }

    @Test fun allGeneratedActionShapesIncludingPassDecodeWithoutNarrowing() {
        val cases = listOf(
            TigerGoatAction(TigerGoatActionKind.REMOVE_GOAT, 6),
            TigerGoatAction(TigerGoatActionKind.PLACE_GOAT, to = 6),
            TigerGoatAction(TigerGoatActionKind.STEP, 10, 5),
            TigerGoatAction(TigerGoatActionKind.CAPTURE, 12, 14),
            TigerGoatAction(TigerGoatActionKind.END_CHAIN),
            TigerGoatAction(TigerGoatActionKind.PASS_GOAT),
        )
        cases.forEach { action ->
            val fields = frame().also {
                it[3] = action.kind.code.toLong(); it[4] = action.from.toLong(); it[5] = action.to.toLong()
            }
            assertEquals(action, execute(fields).action)
        }
        for (score in listOf(-100_000L, 0L, 100_000L)) {
            assertEquals(score.toInt(), execute(frame().also { it[7] = score }).score)
        }
    }

    @Test fun malformedWireFramesFailClosed() {
        val bad = mutableListOf(LongArray(0), frame().copyOf(10), frame().copyOf(12))
        fun changed(index: Int, value: Long) { bad += frame().also { it[index] = value } }
        changed(0, 2); changed(1, -1); changed(1, 99)
        changed(2, 2); changed(6, -1)
        changed(3, Long.MAX_VALUE); changed(3, 5) // Resign is never an AI search action.
        changed(4, -2); changed(4, 4_294_967_302); changed(5, 37)
        changed(7, -100_001); changed(7, 100_001); changed(7, Long.MAX_VALUE)
        changed(8, -1); changed(8, 4); changed(8, Long.MAX_VALUE)
        changed(9, -1); changed(9, 10_001); changed(10, -1)
        bad.forEach { fields ->
            assertThrows(IllegalStateException::class.java) { execute(fields) }
        }
    }

    @Test fun absentPayloadAndIterationSemanticsAreValidated() {
        val bad = listOf(
            emptyFrame(1).also { it[3] = 0 },
            emptyFrame(1).also { it[4] = 0 },
            emptyFrame(1).also { it[5] = 0 },
            emptyFrame(1).also { it[7] = 1 },
            frame().also { it[6] = 0 }, // Absent score cannot carry a number.
            frame(depth = 0),
            frame(depth = 2), // Completion must reach the requested depth of three.
            emptyFrame(0),
            emptyFrame(2, depth = 1),
            frame(stop = 3, depth = 0),
        )
        bad.forEach { assertThrows(IllegalStateException::class.java) { execute(it) } }
        for (code in listOf(1, 4, 5, 6)) {
            assertThrows(IllegalStateException::class.java) { execute(frame(stop = code)) }
        }
    }

    @Test fun budgetCanReturnAnUnscoredFallbackOrAPreviousCompleteIteration() {
        val fallback = execute(frame(stop = 2, depth = 0).also { it[6] = 0; it[7] = 0 })
        assertNotNull(fallback.action)
        assertNull(fallback.score)
        assertEquals(0, fallback.completedTurnDepth)
        val completed = execute(frame(stop = 3, depth = 1))
        assertEquals(123, completed.score)
        assertEquals(1, completed.completedTurnDepth)
        val none = execute(emptyFrame(2))
        assertNull(none.action)
    }

    @Test fun cancelBeforeRunDoesNotLoadOrInvokeNativeAndStillConsumesTheRequest() {
        var calls = 0
        val request = TigerGoatSearchRequest.prepare(snapshot(), bridge = TigerGoatSearchBridge { _, _, _, _, _ ->
            calls++; frame()
        })
        request.cancel(); request.cancel()
        val result = request.executeBlocking()
        assertEquals(0, calls)
        assertEquals(TigerGoatSearchStop.CANCELLED, result.stop)
        assertNull(result.action); assertNull(result.score)
        assertEquals(0L, result.visitedNodes)
        assertThrows(IllegalStateException::class.java) { request.executeBlocking() }
        // Also works without the fake: no attempt to load the Android shared library on the JVM.
        TigerGoatSearchRequest.prepare(snapshot()).also { it.cancel() }.executeBlocking()
    }

    @Test fun cancellationAfterResultRevokesItsLiveSuggestionButPreservesDiagnostics() {
        val request = TigerGoatSearchRequest.prepare(snapshot(), bridge = fixedBridge(frame()))
        val result = request.executeBlocking()
        assertNotNull(result.action)
        assertEquals(123, result.score)
        request.cancel()
        assertTrue(request.isCancellationRequested)
        assertEquals(TigerGoatSearchStop.CANCELLED, result.stop)
        assertNull(result.action); assertNull(result.score)
        assertEquals(3, result.completedTurnDepth)
        assertEquals(3L, result.visitedNodes)
        assertEquals(7L, result.elapsedMillis)
    }

    @Test fun cancelDoesNotReleaseAStillExecutingBridgeAndLateSuccessIsRevoked() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        var token: AtomicBoolean? = null
        val request = TigerGoatSearchRequest.prepare(snapshot(), bridge = TigerGoatSearchBridge { _, _, _, _, cancellation ->
            token = cancellation
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            frame()
        })
        try {
            val running = executor.submit<TigerGoatSearchResult> { request.executeBlocking() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            request.cancel()
            assertTrue(token!!.get())
            assertFalse(running.isDone) // No fake close/release or detached completion.
            release.countDown()
            val result = running.get(5, TimeUnit.SECONDS)
            assertEquals(TigerGoatSearchStop.CANCELLED, result.stop)
            assertNull(result.action); assertNull(result.score)
        } finally {
            request.cancel(); release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun concurrentSecondExecuteIsRejectedWithoutSecondNativeCall() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val request = TigerGoatSearchRequest.prepare(snapshot(), bridge = TigerGoatSearchBridge { _, _, _, _, _ ->
            calls.incrementAndGet(); entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            frame()
        })
        try {
            val first = executor.submit<TigerGoatSearchResult> { request.executeBlocking() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            assertThrows(IllegalStateException::class.java) { request.executeBlocking() }
            assertEquals(1, calls.get())
            release.countDown()
            assertEquals(TigerGoatSearchStop.DEPTH_COMPLETED, first.get(5, TimeUnit.SECONDS).stop)
        } finally {
            request.cancel(); release.countDown()
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test fun bridgeExceptionPropagatesAndCannotBeRetriedOnTheSameRequest() {
        val failure = IllegalArgumentException("Native rejected the replay")
        val request = TigerGoatSearchRequest.prepare(snapshot(), bridge = TigerGoatSearchBridge { _, _, _, _, _ ->
            throw failure
        })
        val actual = assertThrows(IllegalArgumentException::class.java) { request.executeBlocking() }
        assertSame(failure, actual)
        assertArrayEquals(snapshot(), request.sourceSnapshot)
        assertThrows(IllegalStateException::class.java) { request.executeBlocking() }
    }

    @Test fun mutationsToReturnedBridgeFrameDoNotChangeTheDecodedResult() {
        val fields = frame()
        val request = TigerGoatSearchRequest.prepare(snapshot(), bridge = fixedBridge(fields))
        val result = request.executeBlocking()
        fields.fill(Long.MAX_VALUE)
        assertEquals(TigerGoatSearchStop.DEPTH_COMPLETED, result.stop)
        assertEquals(TigerGoatAction(TigerGoatActionKind.REMOVE_GOAT, 6), result.action)
        assertEquals(123, result.score)
    }

    private fun execute(fields: LongArray) = TigerGoatSearchRequest.prepare(
        snapshot(), bridge = fixedBridge(fields),
    ).executeBlocking()

    private fun fixedBridge(fields: LongArray) = TigerGoatSearchBridge { _, _, _, _, _ -> fields }

    private fun frame(stop: Int = 0, depth: Int = 3) = longArrayOf(
        1, stop.toLong(), 1, 0, 6, -1, 1, 123, depth.toLong(), 3, 7,
    )

    private fun emptyFrame(stop: Int, depth: Int = 0, nodes: Long = 3) = longArrayOf(
        1, stop.toLong(), 0, -1, -1, -1, 0, 0, depth.toLong(), nodes, 7,
    )

    /** Valid shape, intentionally no checksum: only Native can admit a real game. */
    private fun snapshot() = ByteArray(53).also {
        byteArrayOf(77, 79, 67, 84, 2, 1).copyInto(it)
    }
}
