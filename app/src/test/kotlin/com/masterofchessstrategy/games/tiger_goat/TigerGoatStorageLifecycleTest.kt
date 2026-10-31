package com.masterofchessstrategy.games.tiger_goat

import androidx.lifecycle.ViewModelStore
import com.masterofchessstrategy.games.tiger_goat.data.GatedTigerGoatSessionStore
import com.masterofchessstrategy.games.tiger_goat.data.TigerGoatStorageEpochGate
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Lifecycle fixtures exercise the actual frozen gate/store, without claiming Native/Room QA. */
@OptIn(ExperimentalCoroutinesApi::class)
class TigerGoatStorageLifecycleTest {
    private val dispatcher = StandardTestDispatcher()
    private val owners = mutableListOf<TigerGoatGameViewModel>()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() {
        owners.forEach { it.beginRetirement() }
        dispatcher.scheduler.advanceUntilIdle()
        Dispatchers.resetMain()
    }

    @Test fun repeatedRetirementSharesReceiptSealsControlsAndClosesExactlyOnce() = runTest(dispatcher) {
        val raw = MemoryStore()
        val rules = TrackedRules()
        val vm = create(raw, rules)
        advanceUntilIdle()
        val receipt = vm.beginRetirement()
        assertSame(receipt, vm.beginRetirement())
        assertTrue(vm.uiState.value.retired)
        vm.passGoat(); vm.undo(); vm.restart(); vm.resign(); vm.retrySave(); vm.retryLoad()
        advanceUntilIdle()
        receipt.await()
        assertTrue(vm.leave())
        assertSame(receipt, vm.retirementCompletion)
        assertEquals(1, rules.closes)
        assertEquals(0, rules.actionCount)
        assertEquals(0, raw.saves)
        assertFalse(vm.uiState.value.canRetrySave)
        assertTrue(vm.uiState.value.game!!.busy)
    }

    @Test fun retirementBeforeScheduledLoadDoesNotCreateOrTouchAnyResource() = runTest(dispatcher) {
        val raw = MemoryStore()
        var creations = 0
        val vm = TigerGoatGameViewModel(raw, { creations++; TrackedRules() }).also(owners::add)
        val receipt = vm.beginRetirement()
        advanceUntilIdle()
        receipt.await()
        assertEquals(0, creations)
        assertEquals(0, raw.loads)
        assertEquals(0, raw.saves)
        assertNull(vm.uiState.value.game)
    }

    @Test fun alreadyReplacedFixedOwnerCannotStartLoadOrAcquireANewIdentity() = runTest(dispatcher) {
        val raw = MemoryStore()
        val gate = TigerGoatStorageEpochGate.forDatabase(Any())
        val store = GatedTigerGoatSessionStore(raw, gate)
        gate.exclusive(replacing = true) { }
        val rules = TrackedRules()
        val vm = create(store, rules)
        vm.retryLoad(); vm.restartAfterRecoveryFailure()
        advanceUntilIdle()
        vm.retirementCompletion.await()
        assertEquals(0L, store.ownerEpoch)
        assertEquals(0, raw.loads)
        assertEquals(0, raw.saves)
        assertEquals(0, rules.closes)
        assertTrue(vm.uiState.value.retired)
    }

    @Test fun replacementAfterGatedLoadBeforeWatcherCannotRestoreLoadedBytes() = runTest(dispatcher) {
        val raw = MemoryStore()
        val gate = TigerGoatStorageEpochGate.forDatabase(Any())
        val rules = TrackedRules()
        var replacement: Job? = null
        raw.onLoad = {
            // The independent caller waits for load's lock; it runs at unlock before the watcher.
            replacement = launch(UnconfinedTestDispatcher(testScheduler)) { gate.exclusive(replacing = true) { } }
        }
        val vm = create(GatedTigerGoatSessionStore(raw, gate), rules)
        advanceUntilIdle()
        assertTrue(requireNotNull(replacement).isCompleted)
        vm.retirementCompletion.await()
        assertEquals(0, rules.restores)
        assertEquals(0, raw.saves)
        assertTrue(vm.uiState.value.retired)
        assertTrue(vm.uiState.value.game == null || vm.uiState.value.game!!.busy)
        assertEquals(1, rules.closes)
    }

    @Test fun lateMissingLoadAfterReplacementCannotCreateAnOpeningCheckpoint() = runTest(dispatcher) {
        val raw = MemoryStore().apply { result = TigerGoatLoadResult.Missing }
        val gate = TigerGoatStorageEpochGate.forDatabase(Any())
        raw.onLoad = {
            launch(UnconfinedTestDispatcher(testScheduler)) { gate.exclusive(replacing = true) { } }
        }
        val vm = create(GatedTigerGoatSessionStore(raw, gate), TrackedRules())
        advanceUntilIdle()
        vm.retirementCompletion.await()
        assertEquals(0, raw.saves)
        assertTrue(vm.uiState.value.retired)
        assertFalse(vm.uiState.value.canRetrySave)
    }

    @Test fun acceptedNonCancellableSaveFinishesBeforeCloseWithoutLateReadyPublication() = runTest(dispatcher) {
        val raw = MemoryStore()
        val gate = TigerGoatStorageEpochGate.forDatabase(Any())
        val rules = TrackedRules()
        val vm = create(GatedTigerGoatSessionStore(raw, gate), rules)
        advanceUntilIdle()
        raw.saveBarrier = CompletableDeferred()
        vm.passGoat()
        runCurrent()
        assertEquals(1, raw.saves)
        val receipt = vm.beginRetirement()
        runCurrent()
        assertFalse(receipt.isCompleted)
        assertEquals(0, rules.closes)
        raw.saveBarrier!!.complete(Unit)
        advanceUntilIdle()
        receipt.await()
        assertEquals(1, rules.closes)
        assertEquals(1, raw.persisted!!.actionCount)
        assertTrue(vm.uiState.value.retired)
        assertTrue(vm.uiState.value.game!!.busy)
        vm.retrySave(); vm.passGoat()
        assertEquals(1, raw.saves)
    }

    @Test fun failedRestoreRetiresFailedSaveAndRetryNeverRenewsItsFixedOwner() = runTest(dispatcher) {
        val raw = MemoryStore()
        val gate = TigerGoatStorageEpochGate.forDatabase(Any())
        val store = GatedTigerGoatSessionStore(raw, gate)
        val vm = create(store, TrackedRules())
        advanceUntilIdle()
        raw.saveFailure = IOException("owned disk failure")
        vm.passGoat()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.canRetrySave)
        val failure = IllegalStateException("owned restore rollback")
        assertSame(failure, originalFailure(runCatching { gate.exclusive(replacing = true) { throw failure } }.exceptionOrNull()))
        // No scheduler turn for the flow collector: the retry itself must detect the old epoch.
        vm.retrySave(); vm.retryLoad(); vm.restartAfterRecoveryFailure()
        advanceUntilIdle()
        vm.retirementCompletion.await()
        assertEquals(1, raw.saves)
        assertEquals(0L, store.ownerEpoch)
        assertTrue(vm.uiState.value.retired)
        assertFalse(vm.uiState.value.canRetrySave)
        assertFalse(vm.uiState.value.recoveryFailed)
    }

    @Test fun viewModelClearDrainsUncooperativeLoadOutsideItsCancelledScope() = runTest(dispatcher) {
        val raw = MemoryStore().apply { loadBarrier = CompletableDeferred() }
        val rules = TrackedRules()
        val vm = create(raw, rules)
        val viewModels = ViewModelStore().apply { put("tiger", vm) }
        runCurrent()
        assertEquals(1, raw.loads)
        viewModels.clear()
        runCurrent()
        assertFalse(vm.retirementCompletion.isCompleted)
        assertEquals(0, rules.closes)
        raw.loadBarrier!!.complete(Unit)
        advanceUntilIdle()
        vm.retirementCompletion.await()
        assertEquals(1, rules.closes)
        assertEquals(0, rules.restores)
        assertEquals(0, raw.saves)
    }

    @Test fun sameOwnerRecoveryClosesEachReplacedSessionOnce() = runTest(dispatcher) {
        val raw = MemoryStore().apply { result = TigerGoatLoadResult.Incompatible }
        val first = TrackedRules()
        val second = TrackedRules()
        var creations = 0
        val vm = TigerGoatGameViewModel(raw, { if (creations++ == 0) first else second }).also(owners::add)
        advanceUntilIdle()
        assertTrue(vm.uiState.value.recoveryFailed)
        raw.result = TigerGoatLoadResult.Loaded(saved())
        vm.retryLoad()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.recoveryFailed)
        assertEquals(1, first.closes)
        assertEquals(0, second.closes)
        vm.beginRetirement().await()
        assertEquals(1, first.closes)
        assertEquals(1, second.closes)
    }

    @Test fun closeFailureKeepsExceptionalReceiptRejectsLeaveAndNeverRetriesRelease() = runTest(dispatcher) {
        val failure = object : IllegalStateException("owned close failure") { }
        val rules = TrackedRules().apply { closeFailure = failure }
        val vm = create(MemoryStore(), rules)
        advanceUntilIdle()
        val receipt = vm.beginRetirement()
        advanceUntilIdle()
        assertSame(failure, originalFailure(runCatching { receipt.await() }.exceptionOrNull()))
        assertSame(receipt, vm.beginRetirement())
        assertFalse(vm.leave())
        assertFalse(vm.leave())
        assertEquals(1, rules.closes)
        assertTrue(vm.uiState.value.retired)
        assertNotNull(vm.uiState.value.error)
        assertFalse(vm.uiState.value.canRetrySave)
    }

    @Test fun recoveryCloseFailureRetiresWithoutOpeningAnotherSession() = runTest(dispatcher) {
        val failure = object : IllegalStateException("owned reload close failure") { }
        val rules = TrackedRules().apply { closeFailure = failure }
        val raw = MemoryStore().apply { result = TigerGoatLoadResult.Incompatible }
        var creations = 0
        val vm = TigerGoatGameViewModel(raw, { creations++; rules }).also(owners::add)
        advanceUntilIdle()
        vm.retryLoad()
        advanceUntilIdle()
        assertSame(failure, originalFailure(runCatching { vm.retirementCompletion.await() }.exceptionOrNull()))
        assertEquals(1, creations)
        assertEquals(1, rules.closes)
        assertEquals(1, raw.loads)
        assertTrue(vm.uiState.value.retired)
    }

    @Test fun cancellationShapedCloseFailureIsNotMistakenForOrdinaryJobCancellation() = runTest(dispatcher) {
        val failure = CancellationException("owned release cancellation")
        val rules = TrackedRules().apply { closeFailure = failure }
        val raw = MemoryStore().apply { result = TigerGoatLoadResult.Incompatible }
        val vm = create(raw, rules)
        advanceUntilIdle()
        vm.retryLoad()
        advanceUntilIdle()
        assertEquals(failure.message, runCatching { vm.retirementCompletion.await() }.exceptionOrNull()?.message)
        assertTrue(vm.uiState.value.retired)
        assertFalse(vm.leave())
        assertEquals(1, rules.closes)
    }

    private fun create(store: TigerGoatSessionStore, rules: TrackedRules) =
        TigerGoatGameViewModel(store, { rules }, { 7 }, { "owned-session" }).also(owners::add)

    private class TrackedRules(private val delegate: TigerGoatPassFixture = TigerGoatPassFixture()) :
        TigerGoatRulesSession by delegate {
        var closes = 0
        var restores = 0
        var closeFailure: Throwable? = null
        override fun restore(bytes: ByteArray): Boolean { restores++; return delegate.restore(bytes) }
        override fun close() { closes++; closeFailure?.let { throw it } }
    }

    private class MemoryStore : TigerGoatSessionStore {
        var result: TigerGoatLoadResult = TigerGoatLoadResult.Loaded(saved())
        var loads = 0
        var saves = 0
        var persisted: TigerGoatSavedSession? = null
        var onLoad: (() -> Unit)? = null
        var loadBarrier: CompletableDeferred<Unit>? = null
        var saveBarrier: CompletableDeferred<Unit>? = null
        var saveFailure: Throwable? = null
        override suspend fun load(): TigerGoatLoadResult {
            loads++
            onLoad?.invoke()
            withContext(NonCancellable) { loadBarrier?.await() }
            return result
        }
        override suspend fun save(saved: TigerGoatSavedSession) {
            saves++
            withContext(NonCancellable) { saveBarrier?.await() }
            saveFailure?.let { throw it }
            persisted = saved.copyOwned()
        }
    }

    companion object {
        private fun saved() = TigerGoatSavedSession("owned-session", byteArrayOf(0, 2), 0, 7)
    }
    // Coroutine debug recovery may copy an exception while preserving its original cause.
    private fun originalFailure(error: Throwable?): Throwable? = generateSequence(error) { it.cause }.lastOrNull()
}
