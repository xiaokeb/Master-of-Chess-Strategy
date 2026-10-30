package com.masterofchessstrategy.games.lanzhou_fangqi

import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.games.lanzhou_fangqi.data.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Repository doubles verify request/lifecycle ownership; they do not authenticate a real game. */
@OptIn(ExperimentalCoroutinesApi::class)
class FangqiAiEntryViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun main() { Dispatchers.setMain(dispatcher) }
    @After fun reset() { Dispatchers.resetMain() }

    @Test fun readingNeverSelectsOrCreatesADifficultyAndLockedChoiceDoesNotFallback() = runTest(dispatcher) {
        val repository = Repository(); val vm = model(repository); runCurrent()
        assertNull(vm.state.value.difficultyCode)
        assertEquals(0, repository.requests.size)
        vm.createNew(); runCurrent()
        assertNotNull(vm.state.value.error)
        vm.selectDifficulty(Difficulty.MASTER.code); vm.createNew(); runCurrent()
        assertEquals(Difficulty.MASTER.code, vm.state.value.difficultyCode)
        assertEquals(0, repository.requests.size)
        vm.beginRetirement().await()
    }

    @Test fun explicitEasyCreationIsAcknowledgedAndLaunchConsumedExactlyOnce() = runTest(dispatcher) {
        val repository = Repository(); val vm = model(repository); runCurrent()
        vm.selectDifficulty(Difficulty.EASY.code); vm.createNew(); runCurrent()
        val creation = repository.requests.single().first
        assertEquals("new-formal", creation.initial.identity.sessionId)
        assertEquals(100L, creation.initial.updatedAtEpochMillis)
        assertEquals(FangqiRankedLedger.standardInitialHistory(), creation.initial.history)
        assertEquals(FangqiAiEntryLaunch("new-formal", true), vm.consumeLaunch())
        assertNull(vm.consumeLaunch())
        assertFalse(vm.state.value.pendingCreation)
        vm.beginRetirement().await()
    }

    @Test fun failedAcknowledgementRetriesSameCreationAndCannotAllocateAnotherIdentity() = runTest(dispatcher) {
        val repository = Repository().apply { failCreate = true }
        var ids = 0; var times = 0
        val vm = FangqiAiEntryViewModel(repository, dispatcher, { "new-${++ids}" }, { (++times).toLong() })
        runCurrent(); vm.selectDifficulty(0); vm.createNew(); runCurrent()
        assertTrue(vm.state.value.pendingCreation)
        val original = repository.requests.single().first
        vm.selectDifficulty(3); vm.createNew(); runCurrent()
        assertEquals(0, vm.state.value.difficultyCode)
        assertEquals(1, repository.requests.size)
        repository.failCreate = false
        vm.retryCreate(); runCurrent()
        assertSame(original, repository.requests.last().first)
        assertEquals(1, ids); assertEquals(1, times)
        assertEquals(FangqiAiEntryLaunch("new-1", true), vm.consumeLaunch())
        vm.beginRetirement().await()
    }

    @Test fun consumedLaunchBlocksFurtherCreationUntilFreshMenuFactsAreRead() = runTest(dispatcher) {
        val repository = Repository(); val vm = model(repository); runCurrent()
        vm.selectDifficulty(0); vm.createNew(); runCurrent()
        assertNotNull(vm.consumeLaunch())
        assertTrue(vm.state.value.opening)
        vm.createNew(); vm.retryCreate(); vm.selectDifficulty(1); runCurrent()
        assertEquals(1, repository.requests.size)
        repository.facts = facts(saved("new-formal"), formal = true)
        vm.reload(); runCurrent()
        assertFalse(vm.state.value.opening)
        vm.selectDifficulty(0); vm.createNew(); runCurrent()
        assertEquals(1, repository.requests.size)
        vm.resumeActive()
        assertEquals(FangqiAiEntryLaunch("new-formal", true), vm.consumeLaunch())
        vm.beginRetirement().await()
    }

    @Test fun refreshQueuedDuringFailedCreationCannotLeaveRetryBehindAStaleCreatingFlag() = runTest(dispatcher) {
        val repository = Repository().apply { createRelease = CompletableDeferred(); failCreate = true }
        val vm = model(repository); runCurrent(); vm.selectDifficulty(0); vm.createNew(); runCurrent()
        val original = repository.requests.single().first
        vm.reload()
        repository.facts = facts(saved("new-formal"), formal = true)
        repository.createRelease!!.complete(Unit); runCurrent()
        assertFalse(vm.state.value.loading)
        assertFalse(vm.state.value.creating)
        assertTrue(vm.state.value.pendingCreation)
        assertNotNull(vm.state.value.facts)
        repository.failCreate = false
        vm.retryCreate(); runCurrent()
        assertSame(original, repository.requests.last().first)
        assertEquals(FangqiAiEntryLaunch("new-formal", true), vm.consumeLaunch())
        vm.beginRetirement().await()
    }

    @Test fun unfinishedLegacyAutoGameResumesAsLegacyAndCannotBeSilentlyReplaced() = runTest(dispatcher) {
        val prior = saved("old-auto", FangqiStoredMode.AI_AUTO_PLAY, Difficulty.HARD)
        val repository = Repository(facts(active = prior)); val vm = model(repository); runCurrent()
        vm.selectDifficulty(0); vm.createNew(); runCurrent()
        assertEquals(0, repository.requests.size)
        vm.resumeActive()
        assertEquals(FangqiAiEntryLaunch("old-auto", false), vm.consumeLaunch())
        assertEquals(Difficulty.HARD, vm.state.value.facts!!.active!!.difficulty)
        vm.beginRetirement().await()
    }

    @Test fun onlySettledFormalActivityIsExplicitlyPassedAsReplacement() = runTest(dispatcher) {
        val prior = saved("settled")
        val repository = Repository(facts(prior, formal = true, settled = true))
        val vm = model(repository); runCurrent(); vm.selectDifficulty(0); vm.createNew(); runCurrent()
        assertSame(prior, repository.requests.single().second)
        assertEquals(FangqiAiEntryLaunch("new-formal", true), vm.consumeLaunch())
        vm.beginRetirement().await()
    }

    @Test fun unfinishedFormalSessionCanResumeWithoutCreatingOrUpgradingItsDifficulty() = runTest(dispatcher) {
        val repository = Repository(facts(saved("formal", difficulty = Difficulty.MEDIUM), formal = true))
        val vm = model(repository); runCurrent(); vm.resumeActive()
        assertEquals(FangqiAiEntryLaunch("formal", true), vm.consumeLaunch())
        assertTrue(repository.requests.isEmpty())
        assertEquals(Difficulty.MEDIUM, vm.state.value.facts!!.active!!.difficulty)
        vm.beginRetirement().await()
    }

    @Test fun retirementWaitsForAnAcceptedCreationWithoutCancellingItsCommit() = runTest(dispatcher) {
        val repository = Repository().apply { createRelease = CompletableDeferred() }
        val vm = model(repository); runCurrent(); vm.selectDifficulty(0); vm.createNew(); runCurrent()
        assertTrue(vm.state.value.creating)
        val retirement = vm.beginRetirement(); runCurrent()
        assertFalse(retirement.isCompleted)
        assertNull(repository.committed)
        repository.createRelease!!.complete(Unit); runCurrent()
        retirement.await()
        assertNotNull(repository.committed)
        assertNull(vm.consumeLaunch())
        assertTrue(vm.state.value.retired)
    }

    @Test fun epochReplacementSuppressesLateReadAndWaitsForActualReaderToFinish() = runTest(dispatcher) {
        val repository = Repository().apply { readRelease = CompletableDeferred() }
        val vm = model(repository); runCurrent()
        repository.epoch.value = 1; runCurrent()
        assertTrue(vm.state.value.retired)
        assertFalse(vm.retirementCompletion.isCompleted)
        repository.readRelease!!.complete(Unit); runCurrent()
        vm.retirementCompletion.await()
        assertNull(vm.state.value.facts)
        assertNull(vm.consumeLaunch())
    }

    @Test fun duplicateClickDuringCreateDoesNotDispatchASecondRequest() = runTest(dispatcher) {
        val repository = Repository().apply { createRelease = CompletableDeferred() }
        val vm = model(repository); runCurrent(); vm.selectDifficulty(0); vm.createNew(); runCurrent()
        vm.createNew(); vm.retryCreate(); vm.resumeActive(); runCurrent()
        assertEquals(1, repository.requests.size)
        repository.createRelease!!.complete(Unit); runCurrent()
        assertNotNull(vm.consumeLaunch())
        vm.beginRetirement().await()
    }

    @Test fun readFailureStaysAnErrorAndRetryDoesNotInventProgress() = runTest(dispatcher) {
        val repository = Repository().apply { failRead = true }
        val vm = model(repository); runCurrent()
        assertNull(vm.state.value.facts)
        assertNotNull(vm.state.value.error)
        assertNotNull(vm.lastFailure)
        repository.failRead = false; vm.reload(); runCurrent()
        assertNotNull(vm.state.value.facts)
        assertNull(vm.lastFailure)
        assertTrue(repository.requests.isEmpty())
        vm.beginRetirement().await()
    }

    @Test fun laterLifecycleResumeRefreshesFactsAndPreservesExplicitSelection() = runTest(dispatcher) {
        val repository = Repository(); val vm = model(repository); runCurrent()
        vm.resume(); runCurrent(); assertEquals(1, repository.reads)
        vm.selectDifficulty(2)
        repository.facts = facts(saved("returned"), formal = true)
        vm.resume(); runCurrent()
        assertEquals(2, repository.reads)
        assertEquals(2, vm.state.value.difficultyCode)
        assertEquals("returned", vm.state.value.facts!!.active!!.sessionId)
        vm.beginRetirement().await()
    }

    @Test fun invalidRequestedCodeIsRetainedAndNeverSubstitutedWithEasy() = runTest(dispatcher) {
        val repository = Repository()
        val vm = FangqiAiEntryViewModel(repository, dispatcher, initialDifficultyCode = 999)
        runCurrent(); vm.createNew(); runCurrent()
        assertEquals(999, vm.state.value.difficultyCode)
        assertNotNull(vm.state.value.error)
        assertTrue(repository.requests.isEmpty())
        vm.beginRetirement().await()
    }

    private fun model(repository: Repository) = FangqiAiEntryViewModel(repository, dispatcher, { "new-formal" }, { 100L })

    private class Repository(var facts: FangqiAiEntryFacts = facts()) : FangqiAiEntryRepository {
        override val ownerEpoch = 0L
        override val epoch = MutableStateFlow(0L)
        var reads = 0; var failRead = false; var failCreate = false
        var readRelease: CompletableDeferred<Unit>? = null
        var createRelease: CompletableDeferred<Unit>? = null
        var committed: FangqiRankedCreation? = null
        val requests = mutableListOf<Pair<FangqiRankedCreation, FangqiStoredSession?>>()
        override suspend fun loadFacts(): FangqiAiEntryFacts {
            reads++; readRelease?.await()
            if (failRead) throw IOException("explicit read failure")
            return facts
        }
        override suspend fun create(creation: FangqiRankedCreation, expectedPrevious: FangqiStoredSession?): FangqiStoredSession {
            requests += creation to expectedPrevious
            createRelease?.await()
            committed = creation
            if (failCreate) throw IOException("explicit lost creation acknowledgement")
            val p = creation.initial
            return FangqiStoredSession(p.identity.mode, p.identity.difficulty, p.history.bytes, p.identity.sessionId,
                p.updatedAtEpochMillis, p.acceptedActionCount, p.undoUseCount, p.hintUseCount)
        }
    }

    companion object {
        private fun saved(id: String, mode: FangqiStoredMode = FangqiStoredMode.HUMAN_VS_AI,
            difficulty: Difficulty = Difficulty.EASY) = FangqiStoredSession(mode, difficulty,
            FangqiRankedLedger.standardInitialHistory().bytes, id, 10)
        private fun facts(active: FangqiStoredSession? = null, formal: Boolean = false, settled: Boolean = false): FangqiAiEntryFacts {
            val rows = FangqiLearningCatalog.lessonIds.map { FangqiLearningProgressEntity("lesson:$it", 1, 1) }
            return FangqiAiEntryFacts(FangqiAiAdmission.eligibility(FangqiLearningRead.Ready(FangqiLearningSnapshot(rows)),
                FangqiQualifiedWinsRead.Verified(FangqiQualifiedWinFacts.fromVerifiedLedgerCounts(GameType.LANZHOU_SQUARE_CHESS,
                    emptyMap()))), active, formal, settled)
        }
    }
}
