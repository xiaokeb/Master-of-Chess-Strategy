package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.settings.LocalDataWriterFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class GoAiRuntimeOwnerAdapterTest {
    @Test fun mapsAllTypedResultsAndClosesEveryRequest() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        for (result in listOf(validMove(), GoSearchResult.Cancelled, GoSearchResult.Busy, GoSearchResult.BudgetExhausted)) {
            val runtime = Runtime().apply { native.result = result }
            val owner = GoAiRuntimeOwnerAdapter(runtime, worker)
            val outcome = owner.search(request())
            when (result) {
                is GoSearchResult.Move -> assertEquals(GoAction.Pass, (outcome as GoAiSearchOutcome.Move).suggestion.action)
                GoSearchResult.Cancelled -> assertEquals(GoAiSearchOutcome.Cancelled, outcome)
                GoSearchResult.Busy -> assertEquals(GoAiSearchOutcome.Busy, outcome)
                GoSearchResult.BudgetExhausted -> assertEquals(GoAiSearchOutcome.BudgetExhausted, outcome)
            }
            assertEquals(1, runtime.native.closed)
            assertEquals(GoSearchBudget(8, 3_000), runtime.budget)
            assertArrayEquals(byteArrayOf(1, 2, 3), runtime.bytes)
            owner.closeAndJoin()
        }
    }

    @Test fun preCancelledDoesNotPrepareOrRunNative() = runTest {
        val runtime = Runtime()
        val owner = GoAiRuntimeOwnerAdapter(runtime, StandardTestDispatcher(testScheduler))
        val request = request().also { it.cancellation.cancel() }
        assertEquals(GoAiSearchOutcome.Cancelled, owner.search(request))
        assertEquals(0, runtime.prepared)
        assertEquals(0, runtime.native.runs)
        owner.closeAndJoin()
    }

    @Test fun prepareFailureReleasesAdmissionAndLaterModelFailureStaysObservable() = runTest {
        val prepareFailure = IOException("prepare failed before request acquisition")
        val modelFailure = IOException("model close failed")
        val runtime = Runtime().apply {
            preparing = { throw prepareFailure }
            disposing = { throw modelFailure }
        }
        val owner = GoAiRuntimeOwnerAdapter(runtime, StandardTestDispatcher(testScheduler))
        val observed = runCatching { owner.search(request()) }.exceptionOrNull()!!
        assertTrue(generateSequence(observed) { it.cause }.any { it === prepareFailure })
        assertEquals(0, runtime.native.runs)
        assertEquals(0, runtime.native.closed)
        repeat(2) {
            val closed = runCatching { owner.closeAndJoin() }.exceptionOrNull()!!
            assertTrue(generateSequence(closed) { it.cause }.any { it === modelFailure })
        }
        assertEquals(1, runtime.closeAttempts)
    }

    @Test fun cancellationDuringPrepareIsDeliveredAtAttachmentBeforeRun() = runTest {
        val request = request()
        val runtime = Runtime().apply { preparing = { request.cancellation.cancel() } }
        val owner = GoAiRuntimeOwnerAdapter(runtime, StandardTestDispatcher(testScheduler))
        assertEquals(GoAiSearchOutcome.Cancelled, owner.search(request))
        assertEquals(1, runtime.native.cancels)
        assertEquals(0, runtime.native.runs)
        assertEquals(1, runtime.native.closed)
        owner.closeAndJoin()
    }

    @Test fun runFailureDetachesCallbackThenReleasesRequest() = runTest {
        val request = request()
        val runtime = Runtime().apply { native.running = { throw IOException("run failed") } }
        val owner = GoAiRuntimeOwnerAdapter(runtime, StandardTestDispatcher(testScheduler))
        try { owner.search(request); fail("Run failure hidden") } catch (_: IOException) { }
        assertEquals(1, runtime.native.closed)
        request.cancellation.cancel()
        assertEquals(0, runtime.native.cancels)
        owner.closeAndJoin()
    }

    @Test fun lateCancellationDoesNotExposeOtherwiseValidMove() = runTest {
        val request = request()
        val runtime = Runtime().apply { native.running = { request.cancellation.cancel() } }
        val owner = GoAiRuntimeOwnerAdapter(runtime, StandardTestDispatcher(testScheduler))
        assertEquals(GoAiSearchOutcome.Cancelled, owner.search(request))
        assertEquals(1, runtime.native.cancels)
        assertEquals(1, runtime.native.closed)
        owner.closeAndJoin()
    }

    @Test fun requestCloseFailureDoesNotHidePrimarySearchFailure() = runTest {
        val primary = IllegalStateException("primary search failure")
        val secondary = IOException("request cleanup failure")
        val runtime = Runtime().apply {
            native.running = { throw primary }
            native.closing = { throw secondary }
        }
        val owner = GoAiRuntimeOwnerAdapter(runtime, StandardTestDispatcher(testScheduler))
        try {
            owner.search(request()); fail("Primary failure hidden")
        } catch (failure: LocalDataWriterFailure) {
            assertSame(primary, failure.cause)
            assertEquals(listOf(primary, secondary), failure.failures)
            assertSame(secondary, failure.suppressed.single())
        }
        assertEquals(1, runtime.native.closed)
        try { owner.closeAndJoin(); fail("Request cleanup failure was forgotten") }
        catch (failure: IOException) { assertTrue(generateSequence<Throwable>(failure) { it.cause }.any { it === secondary }) }
        assertEquals(1, runtime.closed)
    }

    @Test fun requestAndRuntimeCleanupFailuresSurviveRepeatedCloseAwaits() = runTest {
        val requestFailure = IOException("request close")
        val runtimeFailure = IOException("runtime close")
        val runtime = Runtime().apply {
            native.closing = { throw requestFailure }
            disposing = { throw runtimeFailure }
        }
        val owner = GoAiRuntimeOwnerAdapter(runtime, StandardTestDispatcher(testScheduler))
        val searchFailure = runCatching { owner.search(request()) }.exceptionOrNull()!!
        assertTrue(generateSequence(searchFailure) { it.cause }.any { it === requestFailure })
        val first = runCatching { owner.closeAndJoin() }.exceptionOrNull() as LocalDataWriterFailure
        assertSame(requestFailure, first.cause)
        assertEquals(listOf(requestFailure, runtimeFailure), first.failures)
        assertSame(runtimeFailure, first.suppressed.single())
        assertSame(first, runCatching { owner.closeAndJoin() }.exceptionOrNull())
        assertEquals(1, runtime.closeAttempts)
    }

    @Test fun rejectsOverBudgetMetricsAndOversizedInputAndClosedOwner() = runTest {
        val runtime = Runtime().apply { native.result = GoSearchResult.Move(GoAction.Pass, GoSearchMetrics(9, 1.0, 0.5, 0.0)) }
        val owner = GoAiRuntimeOwnerAdapter(runtime, StandardTestDispatcher(testScheduler))
        try { owner.search(request()); fail("Excess visits accepted") } catch (_: IllegalArgumentException) { }
        assertEquals(1, runtime.native.closed)
        val large = request(ByteArray(GoSearchRuntime.MAX_SNAPSHOT_BYTES + 1))
        try { owner.search(large); fail("Oversized snapshot accepted") } catch (_: IllegalArgumentException) { }
        assertEquals(1, runtime.prepared)
        owner.closeAndJoin(); owner.closeAndJoin()
        assertEquals(1, runtime.closed)
        try { owner.search(request()); fail("Closed owner accepted search") } catch (_: IllegalStateException) { }
    }

    @Test fun concurrentCloseWaitsForFirstRuntimeDisposal() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runtime = Runtime().apply { disposing = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
        val owner = GoAiRuntimeOwnerAdapter(runtime)
        try {
            val first = async(Dispatchers.Default) { owner.closeAndJoin() }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val secondStarted = CompletableDeferred<Unit>()
            val second = async(Dispatchers.Default) { secondStarted.complete(Unit); owner.closeAndJoin() }
            secondStarted.await()
            assertFalse(first.isCompleted)
            assertFalse(second.isCompleted)
            release.countDown()
            withTimeout(5_000) { first.await(); second.await() }
            assertEquals(1, runtime.closed)
        } finally { release.countDown(); owner.closeAndJoin() }
    }

    @Test fun closeDuringPrepareWaitsForTheLateRequestWithoutRunningIt() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val runtime = Runtime().apply { preparing = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) } }
        val owner = GoAiRuntimeOwnerAdapter(runtime)
        val request = request()
        try {
            val searching = async(Dispatchers.Default) { owner.search(request) }
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val closing = async(start = CoroutineStart.UNDISPATCHED) { owner.closeAndJoin() }
            assertTrue(request.cancellation.isCancelled)
            assertFalse(closing.isCompleted)
            assertEquals(0, runtime.closed)
            release.countDown()
            withTimeout(5_000) {
                assertEquals(GoAiSearchOutcome.Cancelled, searching.await())
                closing.await()
            }
            assertEquals(0, runtime.native.runs)
            assertEquals(1, runtime.native.closed)
            assertEquals(1, runtime.closed)
        } finally { release.countDown(); owner.closeAndJoin() }
    }

    @Test fun closeCancelsRunButWaitsForRequestFinallyBeforeDisposingRuntime() = runBlocking {
        val running = CountDownLatch(1)
        val releaseRun = CountDownLatch(1)
        val closingRequest = CountDownLatch(1)
        val releaseRequest = CountDownLatch(1)
        val runtime = Runtime().apply {
            native.running = { running.countDown(); check(releaseRun.await(5, TimeUnit.SECONDS)) }
            native.closing = { closingRequest.countDown(); check(releaseRequest.await(5, TimeUnit.SECONDS)) }
        }
        val owner = GoAiRuntimeOwnerAdapter(runtime)
        val request = request()
        try {
            val searching = async(Dispatchers.Default) { owner.search(request) }
            assertTrue(running.await(5, TimeUnit.SECONDS))
            val closing = async(start = CoroutineStart.UNDISPATCHED) { owner.closeAndJoin() }
            assertTrue(request.cancellation.isCancelled)
            assertFalse(closing.isCompleted)
            releaseRun.countDown()
            assertTrue(closingRequest.await(5, TimeUnit.SECONDS))
            assertFalse(closing.isCompleted)
            assertEquals(0, runtime.closed)
            releaseRequest.countDown()
            withTimeout(5_000) {
                assertEquals(GoAiSearchOutcome.Cancelled, searching.await())
                closing.await()
            }
            request.cancellation.cancel() // Detached callback cannot touch the closed request.
            assertEquals(1, runtime.native.cancels)
            assertEquals(1, runtime.closed)
        } finally { releaseRun.countDown(); releaseRequest.countDown(); owner.closeAndJoin() }
    }

    @Test fun everyConcurrentAndFutureCloseObservesTheSameRuntimeFailure() = runTest {
        val runtime = Runtime().apply { disposing = { throw IOException("model close fault") } }
        val owner = GoAiRuntimeOwnerAdapter(runtime, StandardTestDispatcher(testScheduler))
        val first = async { runCatching { owner.closeAndJoin() } }
        val second = async { runCatching { owner.closeAndJoin() } }
        runCurrent()
        for (result in listOf(first.await(), second.await(), runCatching { owner.closeAndJoin() })) {
            assertEquals("model close fault", result.exceptionOrNull()!!.message)
        }
        assertEquals(1, runtime.closeAttempts)
        try { owner.search(request()); fail("Failed close reopened admission") }
        catch (_: IllegalStateException) { }
    }

    @Test fun completeSnapshotAndItsPositionIdentityAreNeverTruncatedOrMutated() = runTest {
        val input = ByteArray(GoSearchRuntime.MAX_SNAPSHOT_BYTES) { (it % 251).toByte() }
        val expected = input.copyOf()
        val position = GoAiPositionSnapshot(GoAiPositionStamp("full-history-identity", 917), input)
        input.fill(0)
        val runtime = Runtime()
        val owner = GoAiRuntimeOwnerAdapter(runtime, StandardTestDispatcher(testScheduler))
        val request = GoAiSearchRequest(position, GoAiSearchBudget(512, 20_000), GoAiCancellation())
        assertTrue(owner.search(request) is GoAiSearchOutcome.Move)
        assertArrayEquals(expected, runtime.bytes)
        assertEquals(GoSearchBudget(512, 20_000), runtime.budget)
        assertEquals(GoAiPositionStamp("full-history-identity", 917), position.stamp)
        assertArrayEquals(expected, position.copyBytes())
        owner.closeAndJoin()
    }

    private fun request(bytes: ByteArray = byteArrayOf(1, 2, 3)) = GoAiSearchRequest(
        GoAiPositionSnapshot(GoAiPositionStamp("qa", 1), bytes), GoAiSearchBudget(8, 3_000), GoAiCancellation())
    private fun validMove() = GoSearchResult.Move(GoAction.Pass, GoSearchMetrics(1, 1.0, 0.5, 0.0))
    private class Runtime : GoSearchRuntime {
        val native = Request()
        var prepared = 0
        @Volatile var closed = 0
        @Volatile var closeAttempts = 0
        var bytes: ByteArray? = null
        var budget: GoSearchBudget? = null
        var preparing: (() -> Unit)? = null
        var disposing: (() -> Unit)? = null
        override fun prepare(snapshot: ByteArray, budget: GoSearchBudget): GoSearchRequest {
            prepared++; bytes = snapshot.copyOf(); this.budget = budget
            preparing?.invoke()
            return native
        }
        override fun cancelAll() = Unit
        override fun close() { closeAttempts++; disposing?.invoke(); closed++ }
    }
    private class Request : GoSearchRequest {
        var result: GoSearchResult = GoSearchResult.Move(GoAction.Pass, GoSearchMetrics(1, 1.0, 0.5, 0.0))
        @Volatile var cancels = 0
        @Volatile var runs = 0
        @Volatile var closed = 0
        var running: (() -> Unit)? = null
        var closing: (() -> Unit)? = null
        override fun run(): GoSearchResult { runs++; running?.invoke(); return result }
        override fun cancel() { check(closed == 0); cancels++ }
        override fun close() { closed++; closing?.invoke() }
    }
}
