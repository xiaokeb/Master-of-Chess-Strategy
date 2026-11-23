package com.masterofchessstrategy.games.tiger_goat

import com.masterofchessstrategy.engine.games.tiger_goat.TigerGoatSearchBridge
import com.masterofchessstrategy.engine.games.tiger_goat.TigerGoatSearchLimits
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class TigerGoatAiSearchCoordinatorTest {
    private val limits = TigerGoatSearchLimits(turnDepth = 1)
    // Shape-only bridge fixtures; Native replay/legality are covered in Native tests.
    private fun bytes(history: Boolean = false) = ByteArray(if (history) 56 else 53).also {
        it[0] = 77; it[1] = 79; it[2] = 67; it[3] = 84; it[4] = 2; it[5] = 1
        if (history) { it[47] = 1; it[49] = 4; it[50] = -1; it[51] = -1 }
    }
    private fun position(revision: Long = 0, data: ByteArray = bytes(), id: String = "session") =
        TigerGoatAiPositionSnapshot(TigerGoatAiPositionStamp(id, revision), data)
    private fun frame() = longArrayOf(1, 0, 1, 0, 7, -1, 1, 12, 1, 1, 0)
    private fun await(latch: CountDownLatch) = assertTrue("latch timed out", latch.await(5, TimeUnit.SECONDS))
    private fun eventually(condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "condition timed out" }
            Thread.sleep(1)
        }
    }

    @Test fun completeHistoryIsCopiedAndSearchRunsOnWorkerAndConsumedOnce() {
        val caller = Thread.currentThread()
        val seen = AtomicReference<ByteArray>()
        val original = bytes(true)
        val saved = original.copyOf()
        val source = position(data = original)
        original.fill(0)
        source.copyBytes().fill(0)
        val owner = TigerGoatAiSearchCoordinator(TigerGoatSearchBridge { snapshot, _, _, _, _ ->
            assertNotSame(caller, Thread.currentThread())
            seen.set(snapshot.copyOf()); snapshot.fill(0); frame()
        })
        try {
            assertTrue(owner.setPosition(source)); assertTrue(owner.start(limits))
            eventually { owner.state is TigerGoatAiSearchState.Ready }
            assertArrayEquals(saved, seen.get())
            val suggestion = owner.consume(position(data = saved))!!
            assertEquals(7, suggestion.action!!.from)
            assertEquals(12, suggestion.score)
            assertNull(owner.consume(source))
        } finally { owner.closeAndJoinBlocking() }
    }

    @Test fun cancellationIsImmediateButAdmissionWaitsForNativeFinally() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val token = AtomicReference<AtomicBoolean>()
        val owner = TigerGoatAiSearchCoordinator(TigerGoatSearchBridge { _, _, _, _, cancelled ->
            token.set(cancelled); entered.countDown(); await(release); frame()
        })
        try {
            val source = position(); owner.setPosition(source); owner.start(limits); await(entered)
            owner.cancel()
            assertTrue(token.get().get())
            assertFalse(owner.start(limits))
            assertNull(owner.consume(source))
            release.countDown()
        } finally { release.countDown(); owner.closeAndJoinBlocking() }
        assertEquals(TigerGoatAiSearchState.Closed, owner.state)
    }

    @Test fun sameStampRestoreInvalidatesPrivateGenerationEvenWithIdenticalBytes() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val owner = TigerGoatAiSearchCoordinator(TigerGoatSearchBridge { _, _, _, _, _ ->
            entered.countDown(); await(release); frame()
        })
        try {
            val source = position(); owner.setPosition(source); owner.start(limits); await(entered)
            owner.setPosition(position(1))
            owner.setPosition(position()) // ABA public stamp + bytes.
            release.countDown()
            eventually { !owner.isSearchRunning }
            assertEquals(TigerGoatAiSearchState.Idle, owner.state)
            assertNull(owner.consume(source))
        } finally { release.countDown(); owner.closeAndJoinBlocking() }
    }

    @Test fun consumptionChecksFullBytesSessionAndRevision() {
        for (other in listOf(position(1), position(id = "other"), position(data = bytes().also { it[40] = 1 }))) {
            val owner = TigerGoatAiSearchCoordinator(TigerGoatSearchBridge { _, _, _, _, _ -> frame() })
            try {
                owner.setPosition(position()); owner.start(limits)
                eventually { owner.state is TigerGoatAiSearchState.Ready }
                assertNull(owner.consume(other))
                assertNull(owner.consume(position()))
            } finally { owner.closeAndJoinBlocking() }
        }
    }

    @Test fun readyResultIsRevokedByCancelAndReplacement() {
        val owner = TigerGoatAiSearchCoordinator(TigerGoatSearchBridge { _, _, _, _, _ -> frame() })
        try {
            val source = position(); owner.setPosition(source); owner.start(limits)
            eventually { owner.state is TigerGoatAiSearchState.Ready }
            owner.cancel(); assertNull(owner.consume(source))
            eventually { owner.start(limits) }
            eventually { owner.state is TigerGoatAiSearchState.Ready }
            owner.setPosition(source); assertNull(owner.consume(source))
        } finally { owner.closeAndJoinBlocking() }
    }

    @Test fun closeSealsBeforeWaitingAndWaitsThroughBridgeFinally() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val cleanupEntered = CountDownLatch(1); val cleanupRelease = CountDownLatch(1)
        val done = CountDownLatch(1); val failed = AtomicReference<Throwable>()
        val owner = TigerGoatAiSearchCoordinator(TigerGoatSearchBridge { _, _, _, _, _ ->
            try { entered.countDown(); await(release); frame() }
            finally { cleanupEntered.countDown(); await(cleanupRelease) }
        })
        owner.setPosition(position()); owner.start(limits); await(entered)
        val closer = Thread {
            try { owner.closeAndJoinBlocking() } catch (error: Throwable) { failed.set(error) }
            finally { done.countDown() }
        }
        try {
            closer.start()
            eventually { owner.state == TigerGoatAiSearchState.Closed }
            assertFalse(owner.setPosition(position())); assertFalse(owner.start(limits))
            closer.interrupt() // Drain remains mandatory, then restores interrupted status.
            release.countDown(); await(cleanupEntered)
            assertEquals(1L, done.count)
            cleanupRelease.countDown(); await(done)
            assertNull(failed.get()); assertTrue(closer.isInterrupted)
            assertNull(owner.consume(position()))
        } finally {
            release.countDown(); cleanupRelease.countDown(); closer.join(5000)
            owner.closeAndJoinBlocking()
        }
    }

    private class NoSuppression(message: String) : RuntimeException(message, null, false, false)

    @Test fun allOriginalFailuresSurviveNewEnvelopeIncludingSuppressionDisabledThrowable() {
        val first = NoSuppression("first"); val second = NoSuppression("second")
        var calls = 0
        val owner = TigerGoatAiSearchCoordinator(TigerGoatSearchBridge { _, _, _, _, _ ->
            throw if (calls++ == 0) first else second
        })
        owner.setPosition(position()); owner.start(limits)
        eventually { owner.state is TigerGoatAiSearchState.Failed }
        eventually { owner.start(limits) }
        eventually { owner.failures.size == 2 }
        val error = assertThrows(TigerGoatAiDrainException::class.java) { owner.closeAndJoinBlocking() }
        assertSame(first, error.cause); assertSame(second, error.suppressed.single())
        assertEquals(listOf(first, second), owner.failures)
        assertTrue(first.suppressed.isEmpty()); assertTrue(second.suppressed.isEmpty())
        assertEquals(TigerGoatAiSearchState.Closed, owner.state)
    }

    @Test fun staleFailureAfterSealIsObservableAndDrainFails() {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val failure = AssertionError("late native failure")
        val owner = TigerGoatAiSearchCoordinator(TigerGoatSearchBridge { _, _, _, _, _ ->
            entered.countDown(); await(release); throw failure
        })
        try {
            owner.setPosition(position()); owner.start(limits); await(entered)
            owner.seal(); release.countDown()
            val error = assertThrows(TigerGoatAiDrainException::class.java) { owner.closeAndJoinBlocking() }
            assertSame(failure, error.cause); assertSame(failure, owner.failures.single())
            assertNull(owner.consume(position()))
        } finally { release.countDown(); runCatching { owner.closeAndJoinBlocking() } }
    }

    @Test fun noPositionAndClosedOwnerRejectAdmissionAndBadMoctCannotReachBridge() {
        val reached = AtomicBoolean(false)
        val owner = TigerGoatAiSearchCoordinator(TigerGoatSearchBridge { _, _, _, _, _ ->
            reached.set(true); frame()
        })
        try {
            assertFalse(owner.start(limits))
            owner.setPosition(position(data = byteArrayOf(1)))
            assertThrows(IllegalArgumentException::class.java) { owner.start(limits) }
            assertFalse(reached.get())
        } finally { owner.closeAndJoinBlocking() }
        assertFalse(owner.start(limits))
    }
}
