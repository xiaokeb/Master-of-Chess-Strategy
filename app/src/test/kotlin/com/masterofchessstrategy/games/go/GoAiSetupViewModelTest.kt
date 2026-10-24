package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.*
import java.io.IOException
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GoAiSetupViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun prepare() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private val config = GoAiMatchConfig(9)
    private fun match(id: String, selected: GoAiMatchConfig = config, reserved: Boolean = false) =
        GoAiSavedMatch(id, selected, byteArrayOf(1), completionReserved = reserved)
    private fun facts(active: GoAiSavedMatch? = null, phase: GoPhase? = active?.let { GoPhase.PLAYING },
        stored: Boolean = false, learned: Boolean = true) = GoAiEntrySnapshot(active, phase, stored,
        GoDifficultyEligibility.from(GoLearningProgress(
            if (learned) GoLearningLibrary.lessons.map { it.id }.toSet() else emptySet())))

    private inner class Source(var snapshot: GoAiEntrySnapshot = facts()) : GoAiEntryRepository {
        override var isCurrent = true
        var reads = 0
        var creates = 0
        var expected: GoAiSavedMatch? = null
        var abandoned = false
        var read: suspend () -> GoAiEntrySnapshot = { snapshot }
        var write: suspend (GoAiMatchConfig) -> GoAiSavedMatch = { match("new", it) }
        override suspend fun load(): GoAiEntrySnapshot { reads++; return read() }
        override suspend fun create(config: GoAiMatchConfig, expectedActive: GoAiSavedMatch?, abandonOngoing: Boolean): GoAiSavedMatch {
            creates++; expected = expectedActive; abandoned = abandonOngoing
            return write(config)
        }
    }

    @Test fun initialResumeReadsOnceAndPublishesOnlyRepositorySnapshot() = runTest(dispatcher) {
        val source = Source()
        val model = GoAiSetupViewModel(source, dispatcher)
        model.resume()
        advanceUntilIdle()
        assertEquals(1, source.reads)
        assertSame(source.snapshot, model.state.value.snapshot)
        assertFalse(model.state.value.loading)
        model.beginRetirement(); advanceUntilIdle()
    }

    @Test fun incompleteTutorialAndLockedDifficultyCannotCreate() = runTest(dispatcher) {
        val source = Source(facts(learned = false))
        val model = GoAiSetupViewModel(source, dispatcher)
        advanceUntilIdle()
        model.requestCreate(config)
        assertNotNull(model.state.value.error)
        source.snapshot = facts()
        model.reload(); advanceUntilIdle()
        model.requestCreate(config.copy(difficulty = GoAiDifficulty.MEDIUM))
        advanceUntilIdle()
        assertEquals(0, source.creates)
        assertNull(model.state.value.readyMatch)
        model.beginRetirement(); advanceUntilIdle()
    }

    @Test fun ongoingNeedsExplicitConfirmationAndCancelDoesNotWrite() = runTest(dispatcher) {
        val active = match("old")
        val source = Source(facts(active))
        val model = GoAiSetupViewModel(source, dispatcher)
        advanceUntilIdle()
        model.requestCreate(config.copy(boardSize = 13))
        assertEquals(13, model.state.value.pendingConfig?.boardSize)
        assertEquals(0, source.creates)
        model.dismissAbandon()
        model.confirmAbandon(); advanceUntilIdle()
        assertEquals(0, source.creates)
        model.requestCreate(config.copy(boardSize = 13)); model.confirmAbandon()
        advanceUntilIdle()
        assertSame(active, source.expected)
        assertTrue(source.abandoned)
        assertEquals(13, model.state.value.readyMatch?.config?.boardSize)
        model.beginRetirement(); advanceUntilIdle()
    }

    @Test fun scoringAlsoNeedsExplicitAbandonRatherThanImplicitClear() = runTest(dispatcher) {
        val source = Source(facts(match("scoring"), GoPhase.SCORING))
        val model = GoAiSetupViewModel(source, dispatcher)
        advanceUntilIdle()
        model.requestCreate(config)
        advanceUntilIdle()
        assertNotNull(model.state.value.pendingConfig)
        assertEquals(0, source.creates)
        model.beginRetirement(); advanceUntilIdle()
    }

    @Test fun unfinishedCompletionCannotBeBypassedByNewGameOrAbandon() = runTest(dispatcher) {
        val source = Source(facts(match("finished"), GoPhase.FINISHED))
        val model = GoAiSetupViewModel(source, dispatcher)
        advanceUntilIdle()
        model.requestCreate(config); model.confirmAbandon()
        advanceUntilIdle()
        assertEquals(0, source.creates)
        assertNotNull(model.state.value.error)
        assertNull(model.state.value.pendingConfig)
        model.continueMatch()
        assertEquals("finished", model.state.value.readyMatch?.matchId)
        model.beginRetirement(); advanceUntilIdle()
    }

    @Test fun storedTerminalCanStartWithoutPretendingItWasAbandoned() = runTest(dispatcher) {
        val active = match("saved-result", reserved = true)
        val source = Source(facts(active, GoPhase.FINISHED, stored = true))
        val model = GoAiSetupViewModel(source, dispatcher)
        advanceUntilIdle()
        model.requestCreate(config); advanceUntilIdle()
        assertEquals(1, source.creates)
        assertSame(active, source.expected)
        assertFalse(source.abandoned)
        model.beginRetirement(); advanceUntilIdle()
    }

    @Test fun noNavigationOrSecondCreateBeforeFirstDurableAcknowledgement() = runTest(dispatcher) {
        val ack = CompletableDeferred<GoAiSavedMatch>()
        val source = Source().apply { write = { ack.await() } }
        val model = GoAiSetupViewModel(source, dispatcher)
        advanceUntilIdle()
        model.requestCreate(config); runCurrent()
        assertTrue(model.state.value.busy)
        assertNull(model.state.value.readyMatch)
        model.requestCreate(config.copy(boardSize = 13)); model.reload(); model.continueMatch()
        runCurrent()
        assertEquals(1, source.creates)
        assertEquals(1, source.reads)
        val committed = match("committed")
        ack.complete(committed); advanceUntilIdle()
        assertSame(committed, model.state.value.readyMatch)
        assertFalse(model.state.value.busy)
        assertSame(committed, model.takeReadyMatch())
        assertNull(model.takeReadyMatch())
        assertNull(model.state.value.readyMatch)
        model.beginRetirement(); advanceUntilIdle()
    }

    @Test fun lostAcknowledgementRefreshesTheActualNewUuidAndDoesNotBlindlyRetry() = runTest(dispatcher) {
        val actual = match("already-committed")
        val source = Source().apply { write = { snapshot = facts(actual); throw IOException("lost ack") } }
        val model = GoAiSetupViewModel(source, dispatcher)
        advanceUntilIdle()
        model.requestCreate(config); advanceUntilIdle()
        assertEquals(1, source.creates)
        assertEquals(2, source.reads)
        assertNull(model.state.value.readyMatch)
        assertSame(actual, model.state.value.snapshot?.active)
        assertEquals("lost ack", model.state.value.error)
        model.continueMatch()
        assertSame(actual, model.state.value.readyMatch)
        model.beginRetirement(); advanceUntilIdle()
    }

    @Test fun failedRefreshClearsStaleEligibilityAndActiveAfterCreationFailure() = runTest(dispatcher) {
        val source = Source().apply { write = { read = { throw IOException("reload failed") }; throw IOException("create failed") } }
        val model = GoAiSetupViewModel(source, dispatcher)
        advanceUntilIdle()
        model.requestCreate(config); advanceUntilIdle()
        assertNull(model.state.value.snapshot)
        assertNull(model.state.value.readyMatch)
        assertFalse(model.state.value.busy)
        model.beginRetirement(); advanceUntilIdle()
    }

    @Test fun continueKeepsStoredColorAndBoardRatherThanSelectedNewConfig() = runTest(dispatcher) {
        val active = match("resume", GoAiMatchConfig(13, humanPlayer = GoPlayer.WHITE))
        val source = Source(facts(active))
        val model = GoAiSetupViewModel(source, dispatcher)
        advanceUntilIdle()
        model.continueMatch()
        assertSame(active, model.state.value.readyMatch)
        assertEquals(0, source.creates)
        model.beginRetirement(); advanceUntilIdle()
    }

    @Test fun changedEpochBeforeCreateOrContinueDropsOldFacts() = runTest(dispatcher) {
        val source = Source(facts(match("old")))
        val model = GoAiSetupViewModel(source, dispatcher)
        advanceUntilIdle()
        source.isCurrent = false
        model.continueMatch(); model.requestCreate(config)
        assertNull(model.state.value.snapshot)
        assertNull(model.state.value.readyMatch)
        assertEquals(0, source.creates)
        model.beginRetirement(); advanceUntilIdle()
    }

    @Test fun changedEpochAfterWriteCannotNavigateToRetiredData() = runTest(dispatcher) {
        val source = Source().apply { write = { isCurrent = false; match("old-epoch") } }
        val model = GoAiSetupViewModel(source, dispatcher)
        advanceUntilIdle()
        model.requestCreate(config); advanceUntilIdle()
        assertEquals(1, source.creates)
        assertNull(model.state.value.readyMatch)
        assertNull(model.state.value.snapshot)
        model.beginRetirement(); advanceUntilIdle()
    }

    @Test fun retirementBeforeQueuedCreatePreventsAnyWrite() = runTest(dispatcher) {
        val source = Source()
        val model = GoAiSetupViewModel(source, dispatcher)
        advanceUntilIdle()
        model.requestCreate(config)
        val done = model.beginRetirement()
        advanceUntilIdle()
        assertTrue(done.isCompleted)
        assertEquals(0, source.creates)
        assertNull(model.state.value.readyMatch)
    }

    @Test fun retirementWaitsForEnteredWriteAndNeverNavigatesAfterwards() = runTest(dispatcher) {
        val ack = CompletableDeferred<GoAiSavedMatch>()
        val source = Source().apply { write = { ack.await() } }
        val model = GoAiSetupViewModel(source, dispatcher)
        advanceUntilIdle()
        model.requestCreate(config); runCurrent()
        assertEquals(1, source.creates)
        val done = model.beginRetirement()
        runCurrent()
        assertFalse(done.isCompleted)
        ack.complete(match("drained")); advanceUntilIdle()
        assertTrue(done.isCompleted)
        assertNull(model.state.value.readyMatch)
        assertSame(done, model.beginRetirement())
    }

    @Test fun returningReloadCannotPublishTheOlderNonCancellableRead() = runTest(dispatcher) {
        val late = CompletableDeferred<GoAiEntrySnapshot>()
        val source = Source().apply { read = { if (reads == 1) withContext(NonCancellable) { late.await() } else facts(learned = false) } }
        val model = GoAiSetupViewModel(source, dispatcher)
        model.resume(); runCurrent()
        model.resume(); runCurrent()
        assertEquals(2, source.reads)
        assertFalse(model.state.value.snapshot!!.eligibility.tutorialCompleted)
        late.complete(facts()); advanceUntilIdle()
        assertFalse(model.state.value.snapshot!!.eligibility.tutorialCompleted)
        model.beginRetirement(); advanceUntilIdle()
    }

    @Test fun alreadyReadyMatchCannotBeConsumedAfterRetirement() = runTest(dispatcher) {
        val source = Source(facts(match("ready-before-exit")))
        val model = GoAiSetupViewModel(source, dispatcher)
        advanceUntilIdle()
        model.continueMatch()
        assertNotNull(model.state.value.readyMatch)
        model.beginRetirement()
        assertNull(model.state.value.readyMatch)
        assertNull(model.takeReadyMatch())
        advanceUntilIdle()
    }

    @Test fun alreadyReadyMatchCannotBeConsumedAfterEpochChanges() = runTest(dispatcher) {
        val source = Source(facts(match("ready-before-restore")))
        val model = GoAiSetupViewModel(source, dispatcher)
        advanceUntilIdle()
        model.continueMatch()
        source.isCurrent = false
        assertNull(model.takeReadyMatch())
        assertNull(model.state.value.readyMatch)
        assertNull(model.state.value.snapshot)
        model.beginRetirement(); advanceUntilIdle()
    }
}
