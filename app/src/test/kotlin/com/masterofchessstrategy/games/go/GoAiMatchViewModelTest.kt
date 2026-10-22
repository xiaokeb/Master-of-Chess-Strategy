package com.masterofchessstrategy.games.go

import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.settings.LocalDataWriterFailure
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class GoAiMatchViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun teardown() { Dispatchers.resetMain() }

    @Test fun loaderFailurePreservesStorageAndCanRetry() = runTest(dispatcher) {
        val repository = Repository()
        val owner = Owner()
        var attempts = 0
        val model = model(repository) { if (++attempts == 1) throw IOException("load failed"); owner }
        val store = ViewModelStore().apply { put("qa", model) }
        try {
            runCurrent()
            assertFalse(model.state.value.loadingModel)
            assertNotNull(model.state.value.error)
            assertEquals(0, repository.saves)
            model.retryModel(); runCurrent()
            assertEquals(2, attempts)
            assertTrue(model.state.value.match!!.game.canOperate)
            assertEquals(1, repository.saves)
        } finally { store.clear(); runCurrent() }
        assertEquals(1, owner.closed)
    }

    @Test fun closingDuringModelConstructionClosesLateOwnerWithoutStartingMatch() = runTest(dispatcher) {
        val repository = Repository()
        val gate = CompletableDeferred<GoAiMatchEngineOwner>()
        val owner = Owner()
        val model = model(repository) { gate.await() }
        val store = ViewModelStore().apply { put("qa", model) }
        try {
            runCurrent(); store.clear(); runCurrent()
            assertEquals(0, owner.closed)
            gate.complete(owner); runCurrent()
            assertEquals(1, owner.closed)
            assertEquals(0, repository.saves)
            assertNull(model.state.value.match)
        } finally { gate.complete(owner); store.clear(); runCurrent() }
    }

    @Test fun runtimeCloseWaitsForSearchCancellationAndJoin() = runTest(dispatcher) {
        val repository = Repository()
        val release = CompletableDeferred<Unit>()
        val owner = Owner().apply { gate = release }
        val model = model(repository) { owner }
        val store = ViewModelStore().apply { put("qa", model) }
        try {
            runCurrent(); model.resume()
            assertTrue(model.humanAction(GoAction.Place(BoardPosition(0, 0))))
            runCurrent()
            assertNotNull(owner.request)
            store.clear(); runCurrent()
            assertTrue(owner.request!!.cancellation.isCancelled)
            assertTrue(owner.running)
            assertEquals(0, owner.closed)
            release.complete(Unit); runCurrent()
            assertFalse(owner.running)
            assertEquals(1, owner.closed)
        } finally { release.complete(Unit); store.clear(); runCurrent() }
    }

    @Test fun repeatedRetryDuringLoadingDoesNotCreateMoreOwners() = runTest(dispatcher) {
        val gate = CompletableDeferred<GoAiMatchEngineOwner>()
        val owner = Owner()
        var attempts = 0
        val model = model(Repository()) { attempts++; gate.await() }
        val store = ViewModelStore().apply { put("qa", model) }
        try {
            runCurrent()
            repeat(4) { model.retryModel() }
            runCurrent()
            assertEquals(1, attempts)
            gate.complete(owner); runCurrent()
            assertTrue(model.state.value.match!!.game.canOperate)
        } finally { gate.complete(owner); store.clear(); runCurrent() }
        assertEquals(1, owner.closed)
    }

    @Test fun specifiedOpeningIsForwardedToTheOwnedSession() = runTest(dispatcher) {
        val opening = GoAiOpening.fromSnapshot(byteArrayOf(1)) { size, _, _ -> Rules(size) }
        val repository = Repository(); val owner = Owner()
        val model = model(repository, opening) { owner }
        val store = ViewModelStore().apply { put("qa", model) }
        try {
            runCurrent(); val match = checkNotNull(model.state.value.match)
            assertTrue(match.hasSpecifiedOpening); assertEquals(1, match.openingEventCount)
            assertEquals(1, match.eventCount); assertFalse(match.canUndo)
            assertEquals(1, repository.saves)
        } finally { store.clear(); runCurrent() }
        assertEquals(1, owner.closed)
    }

    @Test fun automaticContinuationConfigurationIsForwardedOnlyWithCompletedStorage() = runTest(dispatcher) {
        val repository = Repository(); val owner = Owner()
        val model = model(repository, config = GoAiMatchConfig(9, humanPlayer = null),
            completedSink = GoAiCompletedMatchSink {}) { owner }
        val store = ViewModelStore().apply { put("qa", model) }
        try {
            runCurrent(); assertTrue(model.state.value.completedStorageReady)
            assertTrue(model.configureAutoContinuation(true, 3)); runCurrent()
            val match = checkNotNull(model.state.value.match)
            assertTrue(match.autoContinueEnabled); assertEquals(3, match.autoContinueMaxGames)
            assertTrue(checkNotNull(repository.saved).autoContinueEnabled)
        } finally { store.clear(); runCurrent() }
        assertEquals(1, owner.closed)
    }

    @Test fun repeatedRetirementAndOnClearedShareOneCompletionUntilModelReleaseFinishes() = runTest(dispatcher) {
        val owner = Owner().apply { closeGate = CompletableDeferred() }
        val model = model(Repository()) { owner }
        val store = ViewModelStore().apply { put("qa", model) }
        runCurrent()
        val completion = model.retirementCompletion
        assertFalse(completion.isCompleted)
        assertSame(completion, model.beginRetirement())
        assertTrue(model.state.value.match!!.closed)
        assertSame(completion, model.beginRetirement())
        runCurrent()
        assertEquals(1, owner.closeAttempts)
        assertFalse(completion.isCompleted)
        store.clear(); runCurrent()
        assertTrue(model.viewModelScope.coroutineContext[Job]!!.isCompleted)
        assertFalse(completion.isCompleted)
        owner.closeGate!!.complete(Unit); runCurrent()
        completion.await()
        assertEquals(1, owner.closed)
        assertSame(completion, model.beginRetirement())
        assertEquals(1, owner.closeAttempts)
    }

    @Test fun retiringDuringConstructionWaitsForLateCandidateCleanupAndNeverStartsStorage() = runTest(dispatcher) {
        val repository = Repository()
        val loading = CompletableDeferred<GoAiMatchEngineOwner>()
        val owner = Owner().apply { closeGate = CompletableDeferred() }
        val model = model(repository) { loading.await() }
        val store = ViewModelStore().apply { put("qa", model) }
        runCurrent()
        val completion = model.beginRetirement()
        store.clear(); runCurrent()
        assertFalse(completion.isCompleted)
        loading.complete(owner); runCurrent()
        assertEquals(1, owner.closeAttempts)
        assertFalse(completion.isCompleted)
        assertEquals(0, repository.saves)
        assertNull(model.state.value.match)
        owner.closeGate!!.complete(Unit); runCurrent()
        completion.await()
        assertEquals(1, owner.closed)
    }

    @Test fun retirementWaitsForPendingDurableSaveAndRejectsEveryNewOperation() = runTest(dispatcher) {
        val repository = Repository()
        val owner = Owner()
        val model = model(repository) { owner }
        val store = ViewModelStore().apply { put("qa", model) }
        runCurrent()
        repository.saveGate = CompletableDeferred()
        assertTrue(model.humanAction(GoAction.Place(BoardPosition(0, 0))))
        runCurrent()
        val completion = model.beginRetirement()
        runCurrent()
        assertFalse(completion.isCompleted)
        assertEquals(0, owner.closed)
        assertFalse(model.humanAction(GoAction.Pass))
        assertFalse(model.undo()); assertFalse(model.requestHint()); assertFalse(model.restart())
        assertFalse(model.setAutomatic(true)); assertFalse(model.configureAutoContinuation(true, 3))
        assertFalse(model.retryAutoContinuation())
        model.retryModel(); model.retrySearch(); model.resume(); model.pause(); model.setSpeed(Float.NaN)
        repository.saveGate!!.complete(Unit); runCurrent()
        completion.await()
        assertEquals(2, repository.saves)
        assertEquals(1, owner.closed)
        assertTrue(model.state.value.match!!.closed)
        assertFalse(model.state.value.match!!.game.sessionReady)
        assertNull(owner.request)
        store.clear(); runCurrent()
    }

    @Test fun completionCannotSucceedWhileCancelledSearchStillOwnsItsRuntime() = runTest(dispatcher) {
        val release = CompletableDeferred<Unit>()
        val owner = Owner().apply { gate = release }
        val model = model(Repository()) { owner }
        val store = ViewModelStore().apply { put("qa", model) }
        runCurrent(); model.resume()
        assertTrue(model.humanAction(GoAction.Place(BoardPosition(0, 0))))
        runCurrent()
        val completion = model.beginRetirement()
        assertTrue(owner.request!!.cancellation.isCancelled)
        runCurrent()
        assertFalse(completion.isCompleted)
        assertEquals(0, owner.closeAttempts)
        release.complete(Unit); runCurrent()
        completion.await()
        assertFalse(owner.running)
        assertEquals(1, owner.closed)
        store.clear(); runCurrent()
    }

    @Test fun rulesAndModelCleanupFailuresRemainObservableAndDoNotSkipModelClose() = runTest(dispatcher) {
        val ownerFailure = IOException("model close fault")
        val rulesFailure = IOException("rules close fault")
        val owner = Owner().apply { closeFailure = ownerFailure }
        val model = GoAiMatchViewModel(Repository(), GoAiMatchConfig(9), { owner }, dispatcher,
            engineFactory = { size, _, _ ->
                val rules = Rules(size)
                object : GoRecordEngine by rules {
                    override fun close() { rules.close(); throw rulesFailure }
                }
            })
        val store = ViewModelStore().apply { put("qa", model) }
        runCurrent()
        val completion = model.beginRetirement()
        runCurrent()
        assertTrue(completion.isCompleted)
        assertTrue(completion.isCancelled)
        try { completion.await(); fail("Cleanup failure was swallowed") }
        catch (failure: LocalDataWriterFailure) {
            assertEquals(2, failure.failures.size)
            assertSame(rulesFailure, originalCoroutineFailure(failure.cause!!))
            assertSame(ownerFailure, originalCoroutineFailure(failure.failures[1]))
            assertSame(failure.failures[1], failure.suppressed.single())
            // A second await must not lose errors attached between suspension boundaries.
            val repeated = runCatching { completion.await() }.exceptionOrNull()
            assertSame(failure, repeated)
        }
        assertEquals(1, owner.closeAttempts)
        assertSame(completion, model.beginRetirement())
        store.clear(); runCurrent()
        assertEquals(1, owner.closeAttempts)
    }

    @Test fun aLateCandidateCleanupFailureIsRetainedByTheStableCompletion() = runTest(dispatcher) {
        val loading = CompletableDeferred<GoAiMatchEngineOwner>()
        val failure = IOException("late model close fault")
        val owner = Owner().apply { closeFailure = failure }
        val model = model(Repository()) { loading.await() }
        val store = ViewModelStore().apply { put("qa", model) }
        runCurrent()
        val completion = model.beginRetirement()
        loading.complete(owner); runCurrent()
        assertTrue(completion.isCompleted)
        try { completion.await(); fail("Late cleanup failure was swallowed") }
        catch (actual: IOException) { assertSame(failure, originalCoroutineFailure(actual)) }
        assertEquals(1, owner.closeAttempts)
        assertNull(model.state.value.match)
        assertSame(completion, model.beginRetirement())
        store.clear(); runCurrent()
    }

    // Preserve exact failure identity while allowing debug-mode coroutine recovery copies.
    private fun originalCoroutineFailure(error: Throwable): Throwable = generateSequence(error) { current ->
        current.cause?.takeIf { it.javaClass == current.javaClass && it.message == current.message }
    }.last()

    private fun model(repository: Repository, opening: GoAiOpening? = null,
        config: GoAiMatchConfig = GoAiMatchConfig(9), completedSink: GoAiCompletedMatchSink? = null,
        loader: suspend () -> GoAiMatchEngineOwner) =
        GoAiMatchViewModel(repository, config, loader, dispatcher, completedSink,
            specifiedOpening = opening) { size, _, _ -> Rules(size) }

    private class Owner : GoAiMatchEngineOwner {
        var closed = 0
        var closeAttempts = 0
        var closeGate: CompletableDeferred<Unit>? = null
        var closeFailure: Exception? = null
        var running = false
        var request: GoAiSearchRequest? = null
        var gate: CompletableDeferred<Unit>? = null
        override suspend fun search(request: GoAiSearchRequest): GoAiSearchOutcome {
            this.request = request
            running = true
            try { withContext(NonCancellable) { gate?.await() } }
            finally { running = false }
            return GoAiSearchOutcome.Cancelled
        }
        override suspend fun closeAndJoin() {
            check(!running)
            closeAttempts++
            closeGate?.await()
            closeFailure?.let { throw it }
            closed++
        }
    }
    private class Repository : GoAiMatchRepository {
        var saves = 0
        var saved: GoAiSavedMatch? = null
        var saveGate: CompletableDeferred<Unit>? = null
        override suspend fun load(): GoAiSavedMatch? = null
        override suspend fun save(match: GoAiSavedMatch) { saveGate?.await(); saves++; saved = match }
    }
    private class Rules(override val boardSize: Int) : GoRecordEngine {
        private var count = 0
        override val gameType = GameType.GO
        override val currentPlayer get() = PlayerId(count % 2)
        override val handicapStones = 0
        override val blackKomiQuarters = 15
        override val phase = GoPhase.PLAYING
        override fun reset() { count = 0 }
        override fun apply(action: GoAction): ActionResult { count++; return ActionResult.Accepted }
        override fun undo(): Boolean = if (count > 0) { count--; true } else false
        override fun serialize() = byteArrayOf(count.toByte())
        override fun restore(data: ByteArray): RestoreResult { count = data[0].toInt(); return RestoreResult.Restored }
        override fun stoneAt(point: BoardPosition) = if (count > 0 && point == BoardPosition(0, 0)) GoStone.BLACK else GoStone.EMPTY
        override fun eventHistory() = if (count > 0) listOf(GoAction.Place(BoardPosition(0, 0))) else emptyList()
        override fun scoringState() = GoScoringState(phase, 0, false, false, emptyList())
        override fun gameResult() = GameResult.ONGOING
        override fun legalActions() = emptyList<GoAction>()
        override fun previewScore(): GoAreaScore? = null
        override fun finalScore(): GoAreaScore? = null
        override fun exportSgf() = "(;GM[1]SZ[$boardSize])"
        override fun restoreSgf(text: String) = RestoreResult.Rejected(EngineError.UNSUPPORTED)
        override fun close() = Unit
    }
}
