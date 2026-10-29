package com.masterofchessstrategy.games.lanzhou_mahjong

import androidx.lifecycle.ViewModelStore
import com.masterofchessstrategy.engine.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LanzhouMahjongGameViewModelTest {
    @Test fun viewModelResumesAdmittedFullRecordAndLeavesAfterNativeRelease() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(worker)
        val f = HostFixture(worker)
        val record = f.record()
        f.store.saved = com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongStoredSession(record, 321)
        lateinit var engine: HostFakeSession
        val vm = LanzhouMahjongGameViewModel(f.storage(), LanzhouMahjongLaunchConfig(Difficulty.HARD, 91),
            sessionFactory = { HostFakeSession(it).also { created -> engine = created } }, dispatcher = worker,
            openMode = LanzhouMahjongOpenMode.RESUME)
        try {
            advanceUntilIdle()
            assertEquals(Difficulty.EASY, engine.config.difficulty)
            assertEquals(7L, engine.config.seed)
            assertEquals(321L, f.store.saved!!.updatedAtEpochMillis)
            assertTrue(vm.leave())
            assertEquals(1, engine.closeCalls)
            assertTrue(vm.retirementCompletion.isCompleted)
        } finally { clear(vm); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun repeatedInputWhileSavingDoesNotCommitTwice() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(worker)
        val f = HostFixture(worker)
        lateinit var engine: HostFakeSession
        val vm = LanzhouMahjongGameViewModel(f.storage(), LanzhouMahjongLaunchConfig(Difficulty.EASY, 7),
            sessionFactory = { HostFakeSession(it).also { created -> engine = created } }, dispatcher = worker)
        try {
            advanceUntilIdle()
            val gate = CompletableDeferred<Unit>()
            f.store.saveGate = gate
            vm.apply(HostFakeSession.action)
            runCurrent()
            assertTrue(vm.state.value.table!!.isBusy)
            vm.apply(HostFakeSession.action)
            assertEquals(1, engine.applied)
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(1, engine.applied)
            assertFalse(vm.state.value.table!!.isBusy)
        } finally { clear(vm); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun onClearedRetirementWaitsForAcceptedSaveAndClosesExactlyOnce() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(worker)
        val f = HostFixture(worker)
        lateinit var engine: HostFakeSession
        val vm = LanzhouMahjongGameViewModel(f.storage(), LanzhouMahjongLaunchConfig(Difficulty.EASY, 7),
            sessionFactory = { HostFakeSession(it).also { created -> engine = created } }, dispatcher = worker)
        try {
            advanceUntilIdle()
            val gate = CompletableDeferred<Unit>()
            f.store.saveGate = gate
            vm.apply(HostFakeSession.action)
            runCurrent()
            clear(vm)
            runCurrent()
            assertFalse(vm.retirementCompletion.isCompleted)
            assertEquals(0, engine.closeCalls)
            assertTrue(vm.state.value.retired)
            gate.complete(Unit)
            advanceUntilIdle()
            vm.retirementCompletion.await()
            assertEquals(1, engine.closeCalls)
            vm.apply(HostFakeSession.action)
            advanceUntilIdle()
            assertEquals(1, engine.applied)
        } finally { clear(vm); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun corruptLoadDisablesRestartAndKeepsErrorUntilSafeExit() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(worker)
        val f = HostFixture(worker)
        f.store.loadError = IOException("retained corrupt row")
        var creates = 0
        val vm = LanzhouMahjongGameViewModel(f.storage(), LanzhouMahjongLaunchConfig(Difficulty.EASY, 7),
            sessionFactory = { creates++; HostFakeSession(it) }, dispatcher = worker)
        try {
            advanceUntilIdle()
            vm.restart()
            advanceUntilIdle()
            assertEquals(0, creates)
            assertTrue(vm.state.value.restoreFailed)
            assertEquals("对局恢复失败，原存档已保留，请返回后重试", vm.state.value.error)
            assertTrue(f.store.attempts.isEmpty())
            assertTrue(vm.leave())
        } finally { clear(vm); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    @Test fun unavailableEngineReportsRetainedDataAndNoStoreWrite() = runTest {
        val worker = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(worker)
        val f = HostFixture(worker)
        val vm = LanzhouMahjongGameViewModel(f.storage(), LanzhouMahjongLaunchConfig(Difficulty.EASY, 7),
            sessionFactory = { throw UnsatisfiedLinkError("unavailable") }, dispatcher = worker)
        try {
            advanceUntilIdle()
            assertFalse(vm.state.value.isLoading)
            assertTrue(vm.state.value.restoreFailed)
            assertTrue(f.store.attempts.isEmpty())
            assertTrue(vm.leave())
        } finally { clear(vm); advanceUntilIdle(); Dispatchers.resetMain() }
    }

    private fun clear(vm: LanzhouMahjongGameViewModel) {
        ViewModelStore().apply { put("game", vm); clear() }
    }
}
