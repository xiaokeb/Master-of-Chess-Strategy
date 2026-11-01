package com.masterofchessstrategy.games.competition_mahjong.learning

import androidx.lifecycle.ViewModelStore
import java.io.IOException
import kotlin.coroutines.ContinuationInterceptor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Lifecycle tests use the real fixed-owner gate and an in-memory proof delegate. */
@OptIn(ExperimentalCoroutinesApi::class)
class CompetitionMahjongLearningViewModelTest {
    private val main = StandardTestDispatcher()
    private val worker = StandardTestDispatcher(main.scheduler)
    @Before fun setMain() { Dispatchers.setMain(main) }
    @After fun resetMain() { Dispatchers.resetMain() }

    @Test fun workerDispatchAndFinalAdvanceCreateOnlyOneWholeChapterConfirmation() = runTest(main) {
        val delegate = Store().apply { release = CompletableDeferred() }
        val vm = CompetitionMahjongLearningViewModel(gated(delegate), worker) { 71L }
        runCurrent()
        assertSame(worker, delegate.loadDispatcher)
        finishChapter(vm)
        assertSame(worker, delegate.saveDispatcher)
        assertEquals(1, delegate.requests.size)
        assertTrue(vm.state.value.busy)
        assertEquals(0, vm.state.value.completedChapterCount)
        delegate.release!!.complete(Unit); runCurrent()
        assertEquals(1, vm.state.value.completedChapterCount)
        assertEquals(71L, delegate.requests.single().confirmedAtEpochMillis)
        vm.beginRetirement().await()
    }

    @Test fun saveFailureAndAcknowledgementLossRetryTheSameRequest() = runTest(main) {
        val delegate = Store().apply { failure = IOException("private storage path") }
        var clockCalls = 0
        val vm = CompetitionMahjongLearningViewModel(gated(delegate), worker) { clockCalls++; 12L }
        runCurrent(); finishChapter(vm)
        val original = delegate.requests.single()
        assertFalse(vm.state.value.error!!.contains("private"))
        delegate.failure = null
        delegate.loseAcknowledgement = true
        vm.retrySave(); runCurrent()
        assertEquals(0, vm.state.value.completedChapterCount)
        vm.retrySave(); runCurrent()
        assertTrue(delegate.requests.all { it === original })
        assertEquals(1, clockCalls)
        assertEquals(1, delegate.saved.completedChapterCount)
        assertEquals(1, vm.state.value.completedChapterCount)
        vm.beginRetirement().await()
    }

    @Test fun idleEpochReplacementRetiresWithoutInputAndCannotRecaptureOwner() = runTest(main) {
        val delegate = Store()
        val gate = CompetitionMahjongStorageEpochGate()
        val vm = CompetitionMahjongLearningViewModel(gated(delegate, gate), worker)
        runCurrent()
        gate.exclusive(replacing = true) { }
        runCurrent()
        assertTrue(vm.state.value.retired)
        vm.load(); vm.acknowledgeReading(); vm.advance(); vm.retrySave(); runCurrent()
        assertTrue(delegate.requests.isEmpty())
        assertFalse(vm.state.value.tutorialCompleted)
        vm.retirementCompletion.await()
    }

    @Test fun viewModelClearWaitsForAcceptedCommitAndDoesNotPublishItsLateResult() = runTest(main) {
        val delegate = Store().apply { release = CompletableDeferred() }
        val vm = CompetitionMahjongLearningViewModel(gated(delegate), worker)
        val owner = ViewModelStore().apply { put("learning", vm) }
        runCurrent(); finishChapter(vm)
        owner.clear()
        assertTrue(vm.state.value.retired)
        assertFalse(vm.retirementCompletion.isCompleted)
        delegate.release!!.complete(Unit); runCurrent()
        vm.retirementCompletion.await()
        assertEquals(1, delegate.saved.completedChapterCount)
        assertEquals(0, vm.state.value.completedChapterCount)
        assertFalse(vm.state.value.busy)
        vm.retrySave(); runCurrent()
        assertEquals(1, delegate.requests.size)
    }

    @Test fun retirementDuringInitialLoadWaitsWithoutPublishingTheReturnedProgress() = runTest(main) {
        val delegate = Store().apply { loadRelease = CompletableDeferred() }
        val vm = CompetitionMahjongLearningViewModel(gated(delegate), worker)
        runCurrent()
        val completion = vm.beginRetirement()
        assertSame(completion, vm.beginRetirement())
        assertFalse(completion.isCompleted)
        delegate.loadRelease!!.complete(Unit); runCurrent()
        completion.await()
        assertFalse(vm.state.value.loaded)
        assertTrue(vm.state.value.retired)
        assertFalse(vm.state.value.busy)
    }

    @Test fun incompatibleProofKeepsCompletionHiddenUntilAnExplicitSuccessfulLoad() = runTest(main) {
        val delegate = Store().apply { incompatible = true }
        val vm = CompetitionMahjongLearningViewModel(gated(delegate), worker)
        runCurrent()
        assertFalse(vm.state.value.loaded)
        vm.advance(); runCurrent()
        assertTrue(delegate.requests.isEmpty())
        delegate.incompatible = false
        vm.load(); runCurrent()
        assertTrue(vm.state.value.loaded)
        assertEquals(0, vm.state.value.completedChapterCount)
        vm.beginRetirement().await()
    }

    private fun TestScope.finishChapter(vm: CompetitionMahjongLearningViewModel) {
        val chapter = vm.state.value.chapter
        chapter.cards.forEach { card ->
            assertEquals(card.id, vm.state.value.card.id)
            when (val task = card.task) {
                MahjongLessonTask.Reading -> vm.acknowledgeReading()
                is MahjongLessonTask.Choice -> { vm.selectOption(task.correctIndex); runCurrent(); vm.submit() }
                is MahjongLessonTask.Tiles -> {
                    task.acceptedSelections.first().forEach { vm.toggleTile(it); runCurrent() }
                    vm.submit()
                }
            }
            runCurrent()
            assertTrue(vm.state.value.solved)
            vm.advance(); runCurrent()
        }
    }

    private fun gated(delegate: Store, gate: CompetitionMahjongStorageEpochGate = CompetitionMahjongStorageEpochGate()) =
        GatedCompetitionMahjongLearningProofStore(delegate, gate)

    private class Store : CompetitionMahjongLearningProofStore {
        var saved = CompetitionMahjongLearningProof()
        var failure: IOException? = null
        var loseAcknowledgement = false
        var incompatible = false
        var release: CompletableDeferred<Unit>? = null
        var loadRelease: CompletableDeferred<Unit>? = null
        var loadDispatcher: ContinuationInterceptor? = null
        var saveDispatcher: ContinuationInterceptor? = null
        val requests = mutableListOf<CompetitionMahjongLearningProofRequest>()
        override suspend fun load(): CompetitionMahjongLearningProofRead {
            loadDispatcher = currentCoroutineContext()[ContinuationInterceptor]
            loadRelease?.await()
            return if (incompatible) CompetitionMahjongLearningProofRead.Incompatible else CompetitionMahjongLearningProofRead.Ready(saved)
        }
        override suspend fun confirm(request: CompetitionMahjongLearningProofRequest): CompetitionMahjongLearningProof {
            saveDispatcher = currentCoroutineContext()[ContinuationInterceptor]
            requests += request
            release?.await()
            failure?.let { throw it }
            saved = saved.confirm(request)
            if (loseAcknowledgement) { loseAcknowledgement = false; throw IOException("lost acknowledgement") }
            return saved
        }
    }
}
