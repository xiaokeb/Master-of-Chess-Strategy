package com.masterofchessstrategy.games.go

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.*
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class GoDifficultyProgressViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun prepare() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private fun facts(complete: Boolean) = GoDifficultyEligibility.from(GoLearningProgress(
        if (complete) GoLearningLibrary.lessons.map { it.id }.toSet() else emptySet()))
    private fun source(current: () -> Boolean = { true }, read: suspend () -> GoDifficultyEligibility) =
        object : GoDifficultyEligibilityRepository {
            override val isCurrent: Boolean get() = current()
            override suspend fun load(): GoDifficultyEligibility = read()
        }

    @Test fun initialLoadPublishesOnlyRepositoryFacts() = runTest(dispatcher) {
        val stored = facts(false)
        val model = GoDifficultyProgressViewModel(source { stored }, dispatcher)
        assertTrue(model.state.value.loading)
        assertNull(model.state.value.eligibility)
        advanceUntilIdle()
        assertSame(stored, model.state.value.eligibility)
        assertFalse(model.state.value.loading)
        assertNull(model.state.value.error)
    }

    @Test fun firstResumeDoesNotDuplicateTheInitialRead() = runTest(dispatcher) {
        var reads = 0
        val model = GoDifficultyProgressViewModel(source { reads++; facts(false) }, dispatcher)
        model.resume()
        advanceUntilIdle()
        assertEquals(1, reads)
    }

    @Test fun returningFromLessonsRefreshesEvenWhenAnOlderReadIsStillInFlight() = runTest(dispatcher) {
        val oldRead = CompletableDeferred<GoDifficultyEligibility>()
        var reads = 0
        val model = GoDifficultyProgressViewModel(source {
            if (++reads == 1) withContext(NonCancellable) { oldRead.await() } else facts(true)
        }, dispatcher)
        model.resume()
        runCurrent()
        model.resume()
        runCurrent()
        assertEquals(2, reads)
        assertTrue(model.state.value.eligibility!!.tutorialCompleted)
        oldRead.complete(facts(false))
        advanceUntilIdle()
        assertTrue(model.state.value.eligibility!!.tutorialCompleted)
    }

    @Test fun failureCannotReuseAnEarlierUnlockedSnapshotAndRetryCanRecover() = runTest(dispatcher) {
        var failure = false
        val model = GoDifficultyProgressViewModel(source { if (failure) throw IOException("bad progress") else facts(true) }, dispatcher)
        advanceUntilIdle()
        assertTrue(model.state.value.eligibility!!.tutorialCompleted)
        failure = true
        model.reload()
        assertNull(model.state.value.eligibility)
        advanceUntilIdle()
        assertEquals("bad progress", model.state.value.error)
        assertNull(model.state.value.eligibility)
        failure = false
        model.reload()
        advanceUntilIdle()
        assertTrue(model.state.value.eligibility!!.tutorialCompleted)
    }

    @Test fun aLateCancelledReadCannotUnlockANewerPage() = runTest(dispatcher) {
        val late = CompletableDeferred<GoDifficultyEligibility>()
        var reads = 0
        val current = facts(false)
        val model = GoDifficultyProgressViewModel(source {
            if (++reads == 1) withContext(NonCancellable) { late.await() } else current
        }, dispatcher)
        runCurrent()
        model.reload()
        runCurrent()
        assertSame(current, model.state.value.eligibility)
        late.complete(facts(true))
        advanceUntilIdle()
        assertSame(current, model.state.value.eligibility)
        assertFalse(model.state.value.eligibility!!.tutorialCompleted)
    }

    @Test fun aClearedOwnerCannotPublishOrStartAnotherRead() = runTest(dispatcher) {
        val late = CompletableDeferred<GoDifficultyEligibility>()
        var reads = 0
        val model = GoDifficultyProgressViewModel(source { reads++; withContext(NonCancellable) { late.await() } }, dispatcher)
        val store = ViewModelStore().apply { put("go-progress", model) }
        runCurrent()
        store.clear()
        model.reload()
        late.complete(facts(true))
        advanceUntilIdle()
        assertEquals(1, reads)
        assertNull(model.state.value.eligibility)
    }

    @Test fun aLateFailedReadCannotReplaceANewerSuccess() = runTest(dispatcher) {
        val late = CompletableDeferred<Unit>()
        var reads = 0
        val current = facts(false)
        val model = GoDifficultyProgressViewModel(source {
            if (++reads == 1) withContext(NonCancellable) { late.await(); throw IOException("retired failure") }
            else current
        }, dispatcher)
        runCurrent()
        model.reload()
        runCurrent()
        late.complete(Unit)
        advanceUntilIdle()
        assertSame(current, model.state.value.eligibility)
        assertNull(model.state.value.error)
    }

    @Test fun replacementAfterReadButBeforePublicationCannotUnlockTheRetiredOwner() = runTest(dispatcher) {
        var current = true
        val model = GoDifficultyProgressViewModel(source(current = { current }) {
            val read = facts(true)
            current = false
            read
        }, dispatcher)
        advanceUntilIdle()
        assertNull(model.state.value.eligibility)
        assertTrue(model.state.value.error!!.contains("数据已替换"))
        assertFalse(model.state.value.loading)
    }
}
