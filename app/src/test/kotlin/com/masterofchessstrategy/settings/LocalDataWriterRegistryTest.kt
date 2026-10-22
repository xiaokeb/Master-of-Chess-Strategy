package com.masterofchessstrategy.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LocalDataWriterRegistryTest {
    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val registry = LocalDataWriterRegistry()
    @Before fun prepare() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { store.clear(); Dispatchers.resetMain() }

    @Test fun sameDatabaseSharesTheRegistryButOtherDatabasesRemainIndependent() {
        val first = Any()
        assertSame(LocalDataWriterRegistry.forDatabase(first), LocalDataWriterRegistry.forDatabase(first))
        assertNotSame(LocalDataWriterRegistry.forDatabase(first), LocalDataWriterRegistry.forDatabase(Any()))
    }

    @Test fun destinationRegistrationIsIdempotentAndCompletedOwnersAreRemoved() = runTest(dispatcher) {
        val owner = object : ViewModel() {}
        store.put("game", owner)
        registry.register(owner); registry.register(owner)
        assertEquals(listOf(owner), registry.snapshot())
        store.clear()
        advanceUntilIdle()
        assertTrue(registry.snapshot().isEmpty())
        registry.register(owner)
        assertTrue(registry.snapshot().isEmpty())
    }

    @Test fun leavingPageDoesNotForgetCancelledWriteCleanupBeforeRestore() = runTest(dispatcher) {
        val owner = object : ViewModel() {}
        store.put("game", owner)
        val cleanupStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var oldWriteFinished = false
        var importStarted = false
        owner.viewModelScope.launch {
            try { awaitCancellation() }
            finally {
                withContext(NonCancellable) {
                    cleanupStarted.complete(Unit)
                    release.await()
                    oldWriteFinished = true
                }
            }
        }
        registry.register(owner)
        advanceUntilIdle()
        store.clear() // Simulates a NavBackStackEntry removed before opening Settings.
        advanceUntilIdle()
        assertTrue(cleanupStarted.isCompleted)
        assertEquals(listOf(owner), registry.snapshot())
        val restore = launch {
            awaitLocalDataWriters(registry.snapshot()) {}
            assertTrue(oldWriteFinished)
            importStarted = true
        }
        advanceUntilIdle()
        assertFalse(importStarted)
        release.complete(Unit)
        advanceUntilIdle()
        restore.join()
        assertTrue(importStarted)
        assertTrue(registry.snapshot().isEmpty())
    }

    @Test fun completedViewModelScopeDoesNotForgetIndependentCleanup() = runTest(dispatcher) {
        val owner = RetiringOwner()
        store.put("game", owner)
        registry.register(owner)
        store.clear()
        advanceUntilIdle()
        assertTrue(owner.viewModelScope.coroutineContext[kotlinx.coroutines.Job]!!.isCompleted)
        assertEquals(listOf(owner), registry.snapshot())
        owner.completion.complete(Unit)
        assertTrue(registry.snapshot().isEmpty())
    }

    @Test fun failedIndependentCleanupRemainsVisibleToTheNextRestore() = runTest(dispatcher) {
        val owner = RetiringOwner()
        store.put("game", owner)
        registry.register(owner)
        store.clear()
        advanceUntilIdle()
        owner.completion.completeExceptionally(IllegalStateException("model close failed"))
        assertEquals(listOf(owner), registry.snapshot())
        var failed = false
        try { awaitLocalDataWriters(registry.snapshot()) {} }
        catch (_: IllegalStateException) { failed = true }
        assertTrue(failed)
        assertEquals(listOf(owner), registry.snapshot())
    }

    @Test fun registrationAfterScopeCompletionStillTracksPendingRetirement() = runTest(dispatcher) {
        val owner = RetiringOwner()
        store.put("game", owner)
        store.clear()
        advanceUntilIdle()
        registry.register(owner)
        registry.register(owner)
        assertEquals(listOf(owner), registry.snapshot())
        owner.completion.complete(Unit)
        assertTrue(registry.snapshot().isEmpty())
        registry.register(owner)
        assertTrue(registry.snapshot().isEmpty())
    }

    @Test fun successfulRetirementStillWaitsForScopeCancellationCleanup() = runTest(dispatcher) {
        val owner = RetiringOwner()
        store.put("game", owner)
        val release = CompletableDeferred<Unit>()
        owner.viewModelScope.launch {
            try { awaitCancellation() } finally { withContext(NonCancellable) { release.await() } }
        }
        registry.register(owner)
        advanceUntilIdle()
        owner.completion.complete(Unit)
        store.clear()
        advanceUntilIdle()
        assertEquals(listOf(owner), registry.snapshot())
        release.complete(Unit)
        advanceUntilIdle()
        assertTrue(registry.snapshot().isEmpty())
    }

    private class RetiringOwner : ViewModel(), LocalDataWriterRetirement {
        val completion = CompletableDeferred<Unit>()
        override val retirementCompletion = completion
        override fun beginRetirement() = completion
    }
}
