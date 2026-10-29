package com.masterofchessstrategy.games.lanzhou_mahjong

import androidx.lifecycle.ViewModelStore
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongEntryFacts
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongEntryRepository
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongLearningSnapshot
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongStoredSession
import java.io.IOException
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LanzhouMahjongEntryViewModelTest {
    private val main = StandardTestDispatcher()
    private val worker = StandardTestDispatcher(main.scheduler)
    @Before fun setMain() { Dispatchers.setMain(main) }
    @After fun resetMain() { Dispatchers.resetMain() }

    @Test fun readsRealCountOnWorkerWithoutAuthorizingGames() = runTest(main) {
        val repository = Repository().apply { facts = facts(3) }
        val vm = LanzhouMahjongEntryViewModel(repository, worker)
        runCurrent()
        assertSame(worker, repository.readDispatcher)
        assertEquals(3, vm.state.value.facts!!.learning!!.completedCount)
        assertFalse(vm.state.value.facts!!.tutorialCompleted)
        assertEquals(0, repository.authorizations)
        vm.beginRetirement().await()
    }

    @Test fun readFailureKeepsOriginalCauseAndExplicitRetryLoadsFacts() = runTest(main) {
        val failure = IOException("private database location")
        val repository = Repository().apply { readFailure = failure }
        val vm = LanzhouMahjongEntryViewModel(repository, worker)
        runCurrent()
        assertNull(vm.state.value.facts)
        assertTrue(generateSequence(vm.lastFailure) { it.cause }.any { it === failure })
        assertEquals("菜单进度读取失败，请重试。原记录已保留", vm.state.value.error)
        repository.readFailure = null
        repository.facts = facts(10)
        vm.reload(); runCurrent()
        assertTrue(vm.state.value.facts!!.tutorialCompleted)
        assertNull(vm.lastFailure)
        vm.beginRetirement().await()
    }

    @Test fun failedRefreshDropsPreviouslyCompletedFacts() = runTest(main) {
        val repository = Repository().apply { facts = facts(10) }
        val vm = LanzhouMahjongEntryViewModel(repository, worker)
        runCurrent()
        repository.readFailure = IOException("locked")
        vm.reload()
        assertTrue(vm.state.value.loading)
        assertNull(vm.state.value.facts)
        runCurrent()
        assertNull(vm.state.value.facts)
        assertNotNull(vm.state.value.error)
        vm.beginRetirement().await()
    }

    @Test fun lifecycleReturnQueuesFreshReadAndNeverPublishesOlderCompletion() = runTest(main) {
        val release = CompletableDeferred<Unit>()
        val repository = Repository().apply { facts = facts(2); firstReadRelease = release }
        val vm = LanzhouMahjongEntryViewModel(repository, worker)
        vm.resume()
        runCurrent()
        assertEquals(1, repository.reads)
        repository.facts = facts(10)
        vm.resume()
        vm.reload()
        release.complete(Unit); runCurrent()
        assertEquals(2, repository.reads)
        assertEquals(10, vm.state.value.facts!!.learning!!.completedCount)
        vm.beginRetirement().await()
    }

    @Test fun retirementWaitsAcceptedReadAndRejectsLateFactsAndRefresh() = runTest(main) {
        val release = CompletableDeferred<Unit>()
        val repository = Repository().apply { facts = facts(10); firstReadRelease = release }
        val vm = LanzhouMahjongEntryViewModel(repository, worker)
        runCurrent()
        val completion = vm.beginRetirement()
        assertSame(completion, vm.beginRetirement())
        assertFalse(completion.isCompleted)
        assertTrue(vm.state.value.retired)
        vm.reload(); vm.resume(); vm.resume()
        release.complete(Unit); runCurrent()
        completion.await()
        assertNull(vm.state.value.facts)
        assertEquals(1, repository.reads)
        assertEquals(0, repository.authorizations)
    }

    @Test fun epochChangeRetiresIdlePageImmediately() = runTest(main) {
        val repository = Repository()
        val vm = LanzhouMahjongEntryViewModel(repository, worker)
        runCurrent()
        repository.epoch.value++
        runCurrent()
        assertTrue(vm.state.value.retired)
        assertNull(vm.state.value.facts)
        vm.reload(); runCurrent()
        assertEquals(1, repository.reads)
        vm.retirementCompletion.await()
    }

    @Test fun clearingViewModelWaitsForReadOutsideTheViewModelScope() = runTest(main) {
        val release = CompletableDeferred<Unit>()
        val repository = Repository().apply { firstReadRelease = release }
        val vm = LanzhouMahjongEntryViewModel(repository, worker)
        val owner = ViewModelStore().apply { put("entry", vm) }
        runCurrent()
        owner.clear()
        assertFalse(vm.retirementCompletion.isCompleted)
        release.complete(Unit); runCurrent()
        vm.retirementCompletion.await()
        assertTrue(vm.state.value.retired)
        assertNull(vm.state.value.facts)
    }

    @Test fun changedOwnerBeforeQueuedReadStartsNeverTouchesRepository() = runTest(main) {
        val repository = Repository()
        val vm = LanzhouMahjongEntryViewModel(repository, worker)
        repository.epoch.value++
        runCurrent()
        assertTrue(vm.state.value.retired)
        assertEquals(0, repository.reads)
        vm.retirementCompletion.await()
    }

    private fun facts(count: Int) = LanzhouMahjongEntryFacts(LanzhouMahjongLearningSnapshot(2, count, 30), null)

    private class Repository : LanzhouMahjongEntryRepository {
        override val ownerEpoch = 0L
        override val epoch = MutableStateFlow(0L)
        var facts = LanzhouMahjongEntryFacts(null, null)
        var readFailure: IOException? = null
        var firstReadRelease: CompletableDeferred<Unit>? = null
        var readDispatcher: ContinuationInterceptor? = null
        var reads = 0
        var authorizations = 0
        override suspend fun loadFacts(): LanzhouMahjongEntryFacts {
            readDispatcher = currentCoroutineContext()[ContinuationInterceptor]
            val captured = facts
            if (++reads == 1) firstReadRelease?.await()
            readFailure?.let { throw it }
            return captured
        }
        override suspend fun authorizeNew(difficulty: Difficulty, expectedCurrent: LanzhouMahjongStoredSession?) {
            authorizations++
            error("The read-only menu must never authorize a game")
        }
    }
}
