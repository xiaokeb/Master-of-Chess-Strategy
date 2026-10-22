package com.masterofchessstrategy.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalDataRestoreBarrierTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun prepare() = Dispatchers.setMain(dispatcher)
    @After fun cleanup() = Dispatchers.resetMain()

    @Test fun allOwnersAreCancelledBeforeWaitingAndFinalWritesPrecedeRestore() = runTest(dispatcher) {
        val owners = listOf(object : ViewModel() {}, object : ViewModel() {})
        val events = mutableListOf<String>()
        val jobs = owners.mapIndexed { index, owner ->
            owner.viewModelScope.launch {
                try { awaitCancellation() } finally {
                    // Models a database operation already past its cancellation point.
                    withContext(NonCancellable) { delay(100L); events += "write$index" }
                }
            }
        }
        runCurrent()
        var restored = false
        launch {
            awaitLocalDataWriters(owners) { events += "stop" }
            events += "restore"
            restored = true
        }
        runCurrent()
        assertTrue(jobs.all { it.isCancelled })
        assertFalse(restored)
        advanceTimeBy(99L)
        assertFalse(restored)
        advanceUntilIdle()
        assertEquals(listOf("stop", "write0", "write1", "restore"), events)
        // The owners remain usable for loading the new data after replacement.
        owners.first().viewModelScope.launch { events += "reload" }
        advanceUntilIdle()
        assertEquals("reload", events.last())
    }

    @Test fun allRetirementsBeginBeforeNavigationAndResourceReleasePrecedesStorage() = runTest(dispatcher) {
        val events = mutableListOf<String>()
        val owners = (0..1).map { index -> RetiringOwner { events += "seal$index" } }
        var storageStarted = false
        val operation = launch {
            awaitLocalDataWriters(owners) { events += "navigate" }
            storageStarted = true
        }
        runCurrent()
        assertEquals(listOf("seal0", "seal1", "navigate"), events)
        owners[0].completion.complete(Unit)
        runCurrent()
        assertFalse(storageStarted)
        owners[1].completion.complete(Unit)
        operation.join()
        assertTrue(storageStarted)
    }

    @Test fun cleanupFailureStillDrainsOtherOwnersAndNeverStartsStorage() = runTest(dispatcher) {
        val owners = listOf(RetiringOwner {}, RetiringOwner {})
        val first = IllegalStateException("first close failed")
        val second = IllegalArgumentException("second close failed")
        var observed: Throwable? = null
        var storageStarted = false
        val operation = launch {
            try {
                awaitLocalDataWriters(owners) {}
                storageStarted = true
            } catch (error: Throwable) { observed = error }
        }
        runCurrent()
        owners[0].completion.completeExceptionally(first)
        runCurrent()
        assertFalse(operation.isCompleted)
        owners[1].completion.completeExceptionally(second)
        operation.join()
        val aggregate = observed as LocalDataWriterFailure
        assertEquals(2, aggregate.failures.size)
        assertTrue(generateSequence(aggregate.cause) { it.cause }.any { it === first })
        assertTrue(generateSequence(aggregate.failures[1]) { it.cause }.any { it === second })
        assertTrue(aggregate.suppressed.single() === aggregate.failures[1])
        assertFalse(storageStarted)
    }

    @Test fun explicitFailurePayloadSurvivesRepeatedSuspensionAndNestedAggregation() = runTest(dispatcher) {
        val first = IllegalStateException("first")
        val second = IllegalArgumentException("second")
        val third = java.io.IOException("third")
        assertTrue(LocalDataWriterFailure.combine(listOf(first, first)) === first)
        assertEquals(null, LocalDataWriterFailure.combine(emptyList()))
        val errors = mutableListOf<Throwable>(first, second, first)
        val nested = LocalDataWriterFailure.combine(errors) as LocalDataWriterFailure
        errors.clear()
        assertEquals(listOf(first, second), nested.failures)
        try {
            (nested.failures as MutableList<Throwable>).clear()
            org.junit.Assert.fail("Failure payload was mutable")
        } catch (_: UnsupportedOperationException) { }
        val combined = LocalDataWriterFailure.combine(listOf(nested, third)) as LocalDataWriterFailure
        val receipt = CompletableDeferred<Unit>().apply { completeExceptionally(combined) }
        repeat(3) {
            val actual = try {
                withContext(dispatcher) { receipt.await() }
                error("Missing failure")
            } catch (error: LocalDataWriterFailure) { error }
            assertTrue(actual === combined)
            assertTrue(actual.cause === nested)
            assertEquals(listOf(first, second), (actual.cause as LocalDataWriterFailure).failures)
            assertTrue(actual.failures[1] === third)
            assertTrue(first.suppressed.isEmpty())
            assertTrue(second.suppressed.isEmpty())
        }
    }

    @Test fun sealAndNavigationFailuresStillAwaitEveryNestedCleanup() = runTest(dispatcher) {
        val sealFailure = IllegalStateException("seal")
        val navigationFailure = IllegalArgumentException("navigation")
        val cleanupFailure = java.io.IOException("cleanup")
        val delayed = RetiringOwner {}
        val seal = RetiringOwner { throw sealFailure }
        var observed: Throwable? = null
        var storageStarted = false
        val operation = launch {
            try {
                awaitLocalDataWriters(listOf(seal, delayed)) { throw navigationFailure }
                storageStarted = true
            } catch (error: Throwable) { observed = error }
        }
        runCurrent()
        assertFalse(operation.isCompleted)
        delayed.completion.completeExceptionally(cleanupFailure)
        operation.join()
        val failures = (observed as LocalDataWriterFailure).failures
        assertTrue(failures[0] === sealFailure)
        assertTrue(failures[1] === navigationFailure)
        assertTrue(generateSequence(failures[2]) { it.cause }.any { it === cleanupFailure })
        assertFalse(storageStarted)
    }

    @Test fun callerCancellationDoesNotAbandonAnIndependentCleanup() = runTest(dispatcher) {
        val owner = RetiringOwner {}
        var storageStarted = false
        val operation = launch {
            awaitLocalDataWriters(listOf(owner)) {}
            storageStarted = true
        }
        runCurrent()
        operation.cancel()
        runCurrent()
        assertFalse(operation.isCompleted)
        owner.completion.complete(Unit)
        operation.join()
        assertTrue(operation.isCancelled)
        assertFalse(storageStarted)
    }

    @Test fun navigationFailureStillCancelsAndDrainsExistingWrites() = runTest(dispatcher) {
        val owner = object : ViewModel() {}
        val events = mutableListOf<String>()
        owner.viewModelScope.launch {
            try { awaitCancellation() }
            finally { withContext(NonCancellable) { delay(100); events += "write-finished" } }
        }
        runCurrent()
        var failed = false
        val operation = launch {
            try { awaitLocalDataWriters(listOf(owner)) { error("navigation failed") } }
            catch (_: IllegalStateException) { failed = true }
        }
        runCurrent()
        assertFalse(failed)
        advanceUntilIdle()
        operation.join()
        assertEquals(listOf("write-finished"), events)
        assertTrue(failed)
    }

    private class RetiringOwner(private val seal: () -> Unit) : ViewModel(), LocalDataWriterRetirement {
        val completion = CompletableDeferred<Unit>()
        override val retirementCompletion = completion
        override fun beginRetirement(): kotlinx.coroutines.Deferred<Unit> {
            seal()
            return completion
        }
    }
}
