package com.masterofchessstrategy.games.lanzhou_mahjong

import androidx.lifecycle.ViewModelStore
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongLearningAdvance
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongLearningRepository
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongLearningSnapshot
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

/** Repository doubles verify lifecycle/dispatch only; real SQLite is checked separately. */
@OptIn(ExperimentalCoroutinesApi::class)
class LanzhouMahjongLearningViewModelTest {
    private val main = StandardTestDispatcher()
    private val worker = StandardTestDispatcher(main.scheduler)
    @Before fun setMain() { Dispatchers.setMain(main) }
    @After fun resetMain() { Dispatchers.resetMain() }

    @Test fun databaseCallsUseWorkerAndCompletionWaitsForRepositoryReturn() = runTest(main) {
        val repository = Repository().apply { release = CompletableDeferred() }
        val vm = LanzhouMahjongLearningViewModel(repository, worker) { 91L }
        runCurrent()
        assertTrue(vm.state.value.loaded)
        assertSame(worker, repository.loadDispatcher)
        vm.submit(1); runCurrent()
        assertSame(worker, repository.saveDispatcher)
        assertTrue(vm.state.value.isBusy)
        assertEquals(0, vm.state.value.completedCount)
        repository.release!!.complete(Unit); runCurrent()
        assertEquals(1, vm.state.value.completedCount)
        vm.beginRetirement().await()
    }

    @Test fun readFailureIsRetainedUntilExplicitRetry() = runTest(main) {
        val failure = IOException("private database location")
        val repository = Repository().apply { readFailure = failure }
        val vm = LanzhouMahjongLearningViewModel(repository, worker)
        runCurrent()
        assertFalse(vm.state.value.loaded)
        assertSame(failure, vm.lastFailure)
        assertFalse(vm.state.value.feedback!!.contains("private"))
        vm.submit(1); runCurrent()
        assertTrue(repository.requests.isEmpty())
        repository.readFailure = null
        repository.progress = LanzhouMahjongLearningSnapshot(2, 4, 10L)
        vm.reload(); runCurrent()
        assertEquals(4, vm.state.value.completedCount)
        vm.beginRetirement().await()
    }

    @Test fun explicitRetryUsesTheOriginalAnswerRequest() = runTest(main) {
        val repository = Repository().apply { saveFailure = IOException("disk full") }
        var clockCalls = 0
        val vm = LanzhouMahjongLearningViewModel(repository, worker) { clockCalls++; 37L }
        runCurrent(); vm.submit(1); runCurrent()
        val original = repository.requests.single()
        assertEquals(0, vm.state.value.completedCount)
        vm.selectLesson(1); runCurrent(); vm.submit(0); runCurrent()
        assertEquals(0, vm.state.value.lessonIndex)
        repository.saveFailure = null
        vm.retrySave(); runCurrent()
        assertSame(original, repository.requests.last())
        assertEquals(1, clockCalls)
        assertEquals(1, vm.state.value.completedCount)
        vm.beginRetirement().await()
    }

    @Test fun epochChangeRetiresIdlePageWithoutAnotherUserAction() = runTest(main) {
        val repository = Repository()
        val vm = LanzhouMahjongLearningViewModel(repository, worker)
        runCurrent()
        repository.epoch.value++
        runCurrent()
        assertTrue(vm.state.value.retired)
        vm.submit(1); runCurrent()
        assertTrue(repository.requests.isEmpty())
        vm.retirementCompletion.await()
    }

    @Test fun clearingViewModelWaitsForAcceptedWriteAndRejectsLateMastery() = runTest(main) {
        val repository = Repository().apply { release = CompletableDeferred() }
        val vm = LanzhouMahjongLearningViewModel(repository, worker)
        val owner = ViewModelStore().apply { put("learning", vm) }
        runCurrent(); vm.submit(1); runCurrent()
        owner.clear()
        assertFalse(vm.retirementCompletion.isCompleted)
        assertTrue(vm.state.value.retired)
        repository.release!!.complete(Unit); runCurrent()
        vm.retirementCompletion.await()
        assertEquals(1, repository.progress!!.completedCount)
        assertEquals(0, vm.state.value.completedCount)
        vm.retrySave(); runCurrent()
        assertEquals(1, repository.requests.size)
    }

    private class Repository : LanzhouMahjongLearningRepository {
        override val ownerEpoch = 0L
        override val epoch = MutableStateFlow(0L)
        var progress: LanzhouMahjongLearningSnapshot? = null
        var readFailure: IOException? = null
        var saveFailure: IOException? = null
        var release: CompletableDeferred<Unit>? = null
        var loadDispatcher: ContinuationInterceptor? = null
        var saveDispatcher: ContinuationInterceptor? = null
        val requests = mutableListOf<LanzhouMahjongLearningAdvance>()
        override suspend fun load(): LanzhouMahjongLearningSnapshot? {
            loadDispatcher = currentCoroutineContext()[ContinuationInterceptor]
            readFailure?.let { throw it }
            return progress
        }
        override suspend fun advance(request: LanzhouMahjongLearningAdvance): LanzhouMahjongLearningSnapshot {
            saveDispatcher = currentCoroutineContext()[ContinuationInterceptor]
            requests += request
            release?.await()
            saveFailure?.let { throw it }
            progress = request.after
            return request.after
        }
    }
}
