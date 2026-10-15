package com.masterofchessstrategy.games.tiger_goat

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TigerGoatGameViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setUp() { Dispatchers.setMain(dispatcher) }
    @After fun tearDown() { Dispatchers.resetMain() }

    @Test fun failedPassSavePreservesCheckpointAndLocksActionsUntilExactRetry() = runTest(dispatcher) {
        val store = MemoryStore()
        val session = TigerGoatPassFixture()
        val vm = TigerGoatGameViewModel(store, { session }, { 7 }, { "test-session" })
        advanceUntilIdle()
        val committed = store.saved!!.bytes.copyOf()
        store.fail = true
        vm.passGoat()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.canRetrySave)
        assertTrue(vm.uiState.value.game!!.busy)
        assertArrayEquals(committed, store.saved!!.bytes)
        assertEquals(1, session.actionCount)
        vm.undo()
        vm.passGoat()
        assertEquals(1, session.actionCount)
        store.fail = false
        vm.retrySave()
        advanceUntilIdle()
        assertEquals(1, store.saved!!.actionCount)
        assertArrayEquals(session.serialize(), store.saved!!.bytes)
        assertFalse(vm.uiState.value.game!!.busy)
        vm.undo()
        advanceUntilIdle()
        assertTrue(vm.uiState.value.game!!.canPassGoat)
        assertEquals(0, store.saved!!.actionCount)
    }

    @Test fun inputDuringCheckpointSaveIsDiscarded() = runTest(dispatcher) {
        val store = MemoryStore().apply { gate = CompletableDeferred() }
        val session = TigerGoatPassFixture()
        val vm = TigerGoatGameViewModel(store, { session }, { 7 }, { "session" })
        runCurrent()
        assertTrue(vm.uiState.value.busy)
        vm.passGoat()
        store.gate!!.complete(Unit)
        advanceUntilIdle()
        assertEquals(0, session.actionCount)
        assertTrue(vm.uiState.value.game!!.canPassGoat)
    }

    @Test fun mutationExceptionRollsBackBeforeReloadAndKeepsStoredBytes() = runTest(dispatcher) {
        val store = MemoryStore()
        val session = TigerGoatPassFixture()
        val vm = TigerGoatGameViewModel(store, { session }, { 7 }, { "session" })
        advanceUntilIdle()
        val old = store.saved!!.bytes.copyOf()
        session.throwAfterApply = true
        vm.passGoat()
        assertTrue(vm.uiState.value.recoveryFailed)
        assertEquals(0, session.actionCount)
        assertArrayEquals(old, store.saved!!.bytes)
        vm.retryLoad()
        advanceUntilIdle()
        assertFalse(vm.uiState.value.recoveryFailed)
        assertTrue(vm.uiState.value.game!!.canPassGoat)
    }

    @Test fun rejectedLoadNeverOverwritesOrExposesPlayableReplacement() = runTest(dispatcher) {
        val store = MemoryStore().apply { incompatible = true }
        val vm = TigerGoatGameViewModel(store, { TigerGoatPassFixture() })
        advanceUntilIdle()
        assertTrue(vm.uiState.value.recoveryFailed)
        assertTrue(vm.uiState.value.game!!.busy)
        assertNull(store.saved)
        vm.passGoat()
        assertNull(store.saved)
    }

    private class MemoryStore : TigerGoatSessionStore {
        var saved: TigerGoatSavedSession? = null
        var fail = false
        var incompatible = false
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun load() = if (incompatible) TigerGoatLoadResult.Incompatible
            else saved?.let { TigerGoatLoadResult.Loaded(it.copyOwned()) } ?: TigerGoatLoadResult.Missing
        override suspend fun save(saved: TigerGoatSavedSession) {
            gate?.await()
            if (fail) error("Disk full")
            this.saved = saved.copyOwned()
        }
    }
}
