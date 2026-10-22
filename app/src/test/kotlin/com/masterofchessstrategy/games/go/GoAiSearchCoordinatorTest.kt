package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.BoardPosition
import com.masterofchessstrategy.engine.GoAction
import com.masterofchessstrategy.engine.GoPlayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Request/lifecycle tests with controlled completion; no neural strength claim. */
@OptIn(ExperimentalCoroutinesApi::class)
class GoAiSearchCoordinatorTest {
    private val budget = GoAiSearchBudget(8, 3000)
    private fun move(x: Int = 0) = GoAiSuggestion(GoAction.Place(BoardPosition(x, 0)), 1, 5.0, 0.5, 0.0)

    @Test fun copiesSnapshotsAndConsumesRecommendationOnlyOnce() = runTest {
        val stamp = GoAiPositionStamp("session", 1)
        val bytes = byteArrayOf(1, 2)
        val snapshot = GoAiPositionSnapshot(stamp, bytes)
        bytes[0] = 9
        val dispatcher = StandardTestDispatcher(testScheduler)
        val coordinator = GoAiSearchCoordinator(this, { request ->
            assertArrayEquals(byteArrayOf(1, 2), request.position.copyBytes())
            request.position.copyBytes()[0] = 8
            GoAiSearchOutcome.Move(move())
        }, { stamp }, dispatcher)
        assertTrue(coordinator.start(snapshot, budget))
        advanceUntilIdle()
        assertArrayEquals(byteArrayOf(1, 2), snapshot.copyBytes())
        assertEquals(move(), coordinator.consume(stamp))
        assertNull(coordinator.consume(stamp))
        coordinator.closeAndJoin()
    }

    @Test fun replacementCancelsOldRequestAndDiscardsItsLateResult() = runTest {
        val stamp = GoAiPositionStamp("session", 1)
        val firstGate = CompletableDeferred<Unit>()
        val requests = mutableListOf<GoAiSearchRequest>()
        val coordinator = GoAiSearchCoordinator(this, { request ->
            requests.add(request)
            if (requests.size == 1) withContext(NonCancellable) { firstGate.await() }
            GoAiSearchOutcome.Move(move(if (request === requests.first()) 0 else 1))
        }, { stamp }, StandardTestDispatcher(testScheduler))
        val snapshot = GoAiPositionSnapshot(stamp, byteArrayOf(1))
        coordinator.start(snapshot, budget)
        runCurrent()
        coordinator.start(snapshot, budget)
        runCurrent()
        val firstCancelled = requests.first().cancellation.isCancelled
        val secondCancelled = requests.last().cancellation.isCancelled
        val beforeLateResult = coordinator.state.value
        firstGate.complete(Unit)
        advanceUntilIdle()
        assertTrue(firstCancelled)
        assertFalse(secondCancelled)
        assertEquals(move(1), (beforeLateResult as GoAiSearchState.Ready).suggestion)
        assertEquals(move(1), coordinator.consume(stamp))
        coordinator.closeAndJoin()
    }

    @Test fun changingRevisionDuringSearchDropsResultWithoutApplying() = runTest {
        var stamp = GoAiPositionStamp("session", 1)
        val gate = CompletableDeferred<Unit>()
        val coordinator = GoAiSearchCoordinator(this, {
            gate.await()
            GoAiSearchOutcome.Move(move())
        }, { stamp }, StandardTestDispatcher(testScheduler))
        coordinator.start(GoAiPositionSnapshot(stamp, byteArrayOf(1)), budget)
        runCurrent()
        stamp = stamp.copy(revision = 2)
        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(GoAiSearchState.Idle, coordinator.state.value)
        assertNull(coordinator.consume(stamp))
        coordinator.closeAndJoin()
    }

    @Test fun sessionReplacementInvalidatesAnAlreadyReadyMoveAndStaleStart() = runTest {
        var stamp = GoAiPositionStamp("first", 1)
        val snapshot = GoAiPositionSnapshot(stamp, byteArrayOf(1))
        val coordinator = GoAiSearchCoordinator(this, { GoAiSearchOutcome.Move(move()) },
            { stamp }, StandardTestDispatcher(testScheduler))
        coordinator.start(snapshot, budget)
        advanceUntilIdle()
        stamp = GoAiPositionStamp("replacement", 1)
        coordinator.positionChanged()
        assertEquals(GoAiSearchState.Idle, coordinator.state.value)
        assertFalse(coordinator.start(snapshot, budget))
        assertNull(coordinator.consume(snapshot.stamp))
        coordinator.closeAndJoin()
    }

    @Test fun cancellationSuppressesDelayedSuccess() = runTest {
        val stamp = GoAiPositionStamp("session", 1)
        val gate = CompletableDeferred<Unit>()
        var request: GoAiSearchRequest? = null
        val coordinator = GoAiSearchCoordinator(this, {
            request = it
            withContext(NonCancellable) { gate.await() }
            GoAiSearchOutcome.Move(move())
        }, { stamp }, StandardTestDispatcher(testScheduler))
        coordinator.start(GoAiPositionSnapshot(stamp, byteArrayOf(1)), budget)
        runCurrent()
        coordinator.cancel()
        val cancellationWasSignalled = request!!.cancellation.isCancelled
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(cancellationWasSignalled)
        assertEquals(GoAiSearchState.Idle, coordinator.state.value)
        coordinator.closeAndJoin()
    }

    @Test fun rejectsAgreementActionsAndInvalidMetrics() = runTest {
        val stamp = GoAiPositionStamp("session", 1)
        val invalid = listOf(
            move().copy(action = GoAction.ConfirmScore(GoPlayer.BLACK, 1)),
            move().copy(visits = 9), move().copy(elapsedMillis = -1.0),
            move().copy(whiteWinProbability = Double.NaN), move().copy(whiteMinusBlackPoints = Double.POSITIVE_INFINITY),
        )
        for (suggestion in invalid) {
            val coordinator = GoAiSearchCoordinator(this, { GoAiSearchOutcome.Move(suggestion) },
                { stamp }, StandardTestDispatcher(testScheduler))
            coordinator.start(GoAiPositionSnapshot(stamp, byteArrayOf(1)), budget)
            advanceUntilIdle()
            assertTrue(coordinator.state.value is GoAiSearchState.Failed)
            assertNull(coordinator.consume(stamp))
            coordinator.closeAndJoin()
        }
    }

    @Test fun busyBudgetAndDriverFailureNeverPublishMove() = runTest {
        val stamp = GoAiPositionStamp("session", 1)
        for (outcome in listOf(GoAiSearchOutcome.Busy, GoAiSearchOutcome.BudgetExhausted, null)) {
            val coordinator = GoAiSearchCoordinator(this, {
                outcome ?: throw IllegalStateException("private driver detail")
            }, { stamp }, StandardTestDispatcher(testScheduler))
            coordinator.start(GoAiPositionSnapshot(stamp, byteArrayOf(1)), budget)
            advanceUntilIdle()
            assertTrue(coordinator.state.value is GoAiSearchState.Failed)
            assertNull(coordinator.consume(stamp))
            assertFalse((coordinator.state.value as GoAiSearchState.Failed).message.contains("private"))
            coordinator.closeAndJoin()
        }
    }

    @Test fun closeWaitsForCancelledNativeCallBeforeEngineCanBeFreed() = runTest {
        val stamp = GoAiPositionStamp("session", 1)
        val gate = CompletableDeferred<Unit>()
        val coordinator = GoAiSearchCoordinator(this, {
            withContext(NonCancellable) { gate.await() }
            GoAiSearchOutcome.Move(move())
        }, { stamp }, StandardTestDispatcher(testScheduler))
        val snapshot = GoAiPositionSnapshot(stamp, byteArrayOf(1))
        coordinator.start(snapshot, budget)
        runCurrent()
        val closing = launch { coordinator.closeAndJoin() }
        runCurrent()
        val waitedForNative = !closing.isCompleted
        val rejectedNewRequest = !coordinator.start(snapshot, budget)
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(waitedForNative)
        assertTrue(rejectedNewRequest)
        assertTrue(closing.isCompleted)
        assertEquals(GoAiSearchState.Idle, coordinator.state.value)
    }

    @Test fun parentScopeCancellationSignalsBlockedNativeBeforeItReturns() = runTest {
        val stamp = GoAiPositionStamp("session", 1)
        val parent = SupervisorJob()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val gate = CompletableDeferred<Unit>()
        var request: GoAiSearchRequest? = null
        val coordinator = GoAiSearchCoordinator(CoroutineScope(parent + dispatcher), {
            request = it
            withContext(NonCancellable) { gate.await() }
            GoAiSearchOutcome.Move(move())
        }, { stamp }, dispatcher)
        coordinator.start(GoAiPositionSnapshot(stamp, byteArrayOf(1)), budget)
        runCurrent()
        parent.cancel()
        runCurrent()
        val cancellationWasSignalled = request!!.cancellation.isCancelled
        val waitedForNative = !parent.isCompleted
        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(cancellationWasSignalled)
        assertTrue(waitedForNative)
        assertTrue(parent.isCompleted)
        assertNull(coordinator.consume(stamp))
        coordinator.closeAndJoin()
    }

    @Test fun completedMoveCannotBeConsumedAfterOwnerScopeIsCancelled() = runTest {
        val stamp = GoAiPositionStamp("session", 1)
        val parent = SupervisorJob()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val coordinator = GoAiSearchCoordinator(CoroutineScope(parent + dispatcher), {
            GoAiSearchOutcome.Move(move())
        }, { stamp }, dispatcher)
        coordinator.start(GoAiPositionSnapshot(stamp, byteArrayOf(1)), budget)
        advanceUntilIdle()
        assertTrue(coordinator.state.value is GoAiSearchState.Ready)
        parent.cancel()
        assertNull(coordinator.consume(stamp))
        coordinator.closeAndJoin()
    }

    @Test fun nativeAttachmentAfterCancellationIsImmediatelySignalledOnce() {
        val token = GoAiCancellation()
        val calls = AtomicInteger()
        token.cancel()
        token.attachNativeCancellation { calls.incrementAndGet() }.use {
            assertEquals(1, calls.get())
            token.cancel()
            assertEquals(1, calls.get())
        }
    }

    @Test fun detachedNativeRequestIsNeverCalledByLaterCancellation() {
        val token = GoAiCancellation()
        val calls = AtomicInteger()
        token.attachNativeCancellation { calls.incrementAndGet() }.close()
        token.cancel()
        assertTrue(token.isCancelled)
        assertEquals(0, calls.get())
    }

    @Test fun duplicateNativeAttachmentCannotReplaceTheActiveRequest() {
        val token = GoAiCancellation()
        val calls = AtomicInteger()
        token.attachNativeCancellation { calls.incrementAndGet() }.use {
            try {
                token.attachNativeCancellation { fail("Second callback must never run") }
                fail("Duplicate attachment accepted")
            } catch (_: IllegalStateException) { }
            token.cancel()
            assertEquals(1, calls.get())
        }
    }

    @Test fun detachWaitsForInFlightCallbackBeforeNativeHandleRelease() {
        val token = GoAiCancellation()
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val detaching = CountDownLatch(1)
        val detached = CountDownLatch(1)
        val registration = token.attachNativeCancellation {
            entered.countDown()
            check(finish.await(5, TimeUnit.SECONDS))
        }
        val cancelling = Thread { token.cancel() }
        val detacher = Thread { detaching.countDown(); registration.close(); detached.countDown() }
        var callbackEntered = false
        var detachedTooEarly = false
        try {
            cancelling.start()
            callbackEntered = entered.await(2, TimeUnit.SECONDS)
            detacher.start()
            check(detaching.await(2, TimeUnit.SECONDS))
            detachedTooEarly = detached.await(30, TimeUnit.MILLISECONDS)
        } finally {
            finish.countDown()
            cancelling.join(2000)
            if (detacher.state != Thread.State.NEW) detacher.join(2000)
        }
        assertTrue(callbackEntered)
        assertFalse(detachedTooEarly)
        assertFalse(cancelling.isAlive)
        assertFalse(detacher.isAlive)
        assertEquals(0L, detached.count)
    }
}
