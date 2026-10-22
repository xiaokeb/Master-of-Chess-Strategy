package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class GoAiAutoContinuationTest {
    @Test fun configurationIsDurablePausedAndCanBeUpdatedOrDisabled() = runTest {
        val repository = GoAiMatchSessionTest.Repository()
        val session = session(repository, sink = GoAiCompletedMatchSink {})
        try {
            runCurrent(); assertTrue(session.configureAutoContinuation(true, 3)); runCurrent()
            assertTrue(repository.saved!!.autoContinueEnabled); assertEquals(3, repository.saved!!.autoContinueMaxGames)
            assertEquals(1, repository.saved!!.autoContinueGameNumber); assertTrue(session.state.value.paused)
            assertTrue(session.configureAutoContinuation(true, 5)); runCurrent()
            assertEquals(5, repository.saved!!.autoContinueMaxGames)
            assertTrue(session.configureAutoContinuation(false)); runCurrent()
            assertFalse(repository.saved!!.autoContinueEnabled); assertEquals(1, repository.saved!!.autoContinueMaxGames)
            assertEquals(1, repository.saved!!.autoContinueGameNumber)
        } finally { session.closeAndJoin() }
    }

    @Test fun configurationRequiresAutomaticModeCompletedSinkAndValidBound() = runTest {
        val automatic = session(GoAiMatchSessionTest.Repository())
        try { runCurrent(); assertFalse(automatic.configureAutoContinuation(true, 3)) }
        finally { automatic.closeAndJoin() }
        val human = session(GoAiMatchSessionTest.Repository(), sink = GoAiCompletedMatchSink {},
            config = GoAiMatchConfig(9, humanPlayer = GoPlayer.BLACK))
        try { runCurrent(); assertFalse(human.configureAutoContinuation(true, 3)) }
        finally { human.closeAndJoin() }
        val valid = session(GoAiMatchSessionTest.Repository(), sink = GoAiCompletedMatchSink {})
        try {
            runCurrent()
            for (limit in listOf(1, 101)) try {
                valid.configureAutoContinuation(true, limit); fail("Invalid limit accepted")
            } catch (_: IllegalArgumentException) { }
        } finally { valid.closeAndJoin() }
    }

    @Test fun settingSaveFailureKeepsOriginalPolicyAndBoard() = runTest {
        val repository = GoAiMatchSessionTest.Repository()
        val session = session(repository, sink = GoAiCompletedMatchSink {})
        try {
            runCurrent(); val original = repository.saved!!; repository.fail = true
            assertTrue(session.configureAutoContinuation(true, 3)); runCurrent()
            assertSame(original, repository.saved); assertFalse(session.state.value.autoContinueEnabled)
            assertEquals(1, session.state.value.autoContinueMaxGames); assertNotNull(session.state.value.game.error)
        } finally { session.closeAndJoin() }
    }

    @Test fun confirmedAutomaticTerminalArchivesThenResumesFreshSecondGame() = runTest {
        val repository = GoAiMatchSessionTest.Repository()
        val search = GoAiMatchSessionTest.Search().apply {
            answers.trySend(move(GoAction.Pass)); answers.trySend(move(GoAction.Pass))
        }
        val archived = mutableListOf<GoAiSavedMatch>()
        val session = session(repository, search, GoAiCompletedMatchSink(archived::add))
        try {
            runCurrent(); val firstId = repository.saved!!.matchId
            assertTrue(session.configureAutoContinuation(true, 3)); runCurrent()
            session.resume(); advanceTimeBy(1_000); runCurrent(); advanceTimeBy(1_000); runCurrent()
            assertEquals(GoPhase.SCORING, session.state.value.game.phase)
            assertTrue(session.humanAction(GoAction.ConfirmScore(GoPlayer.BLACK, 1))); runCurrent()
            assertTrue(session.humanAction(GoAction.ConfirmScore(GoPlayer.WHITE, 1))); runCurrent()

            val fresh = repository.saved!!
            assertEquals(1, archived.size); assertEquals(firstId, archived.single().matchId)
            assertTrue(archived.single().completionReserved); assertTrue(archived.single().autoContinueEnabled)
            assertNotEquals(firstId, fresh.matchId); assertEquals(2, fresh.autoContinueGameNumber)
            assertEquals(3, fresh.autoContinueMaxGames); assertTrue(fresh.autoContinueEnabled)
            assertEquals(GoPhase.PLAYING, session.state.value.game.phase); assertFalse(session.state.value.paused)
            assertEquals(2, session.state.value.autoContinueGameNumber)
        } finally { session.closeAndJoin() }
    }

    @Test fun restoredTerminalContinuesButStopsExactlyAtPersistedMaximum() = runTest {
        val terminal = byteArrayOf(9, 0, 81, 81, 82, 83)
        val continuingRepository = GoAiMatchSessionTest.Repository().apply {
            saved = GoAiSavedMatch("series-2", GoAiMatchConfig(9, humanPlayer = null), terminal,
                autoContinueEnabled = true, autoContinueMaxGames = 3, autoContinueGameNumber = 2)
        }
        val archived = mutableListOf<GoAiSavedMatch>()
        val continuing = session(continuingRepository, sink = GoAiCompletedMatchSink(archived::add))
        try {
            runCurrent(); assertEquals(3, continuingRepository.saved!!.autoContinueGameNumber)
            assertEquals(GoPhase.PLAYING, continuing.state.value.game.phase); assertEquals(1, archived.size)
        } finally { continuing.closeAndJoin() }

        val stoppedRepository = GoAiMatchSessionTest.Repository().apply {
            saved = GoAiSavedMatch("series-3", GoAiMatchConfig(9, humanPlayer = null), terminal,
                autoContinueEnabled = true, autoContinueMaxGames = 3, autoContinueGameNumber = 3)
        }
        val stoppedArchive = mutableListOf<GoAiSavedMatch>()
        val stopped = session(stoppedRepository, sink = GoAiCompletedMatchSink(stoppedArchive::add))
        try {
            runCurrent(); assertEquals("series-3", stoppedRepository.saved!!.matchId)
            assertEquals(GoPhase.FINISHED, stopped.state.value.game.phase)
            assertFalse(stopped.state.value.autoContinuePending); assertEquals(1, stoppedArchive.size)
            assertEquals("series-3", stoppedArchive.single().matchId)
            assertTrue(stoppedRepository.saved!!.completionReserved)
        } finally { stopped.closeAndJoin() }
    }

    @Test fun maximumGameArchiveFailureRetriesWithoutCreatingAnotherGame() = runTest {
        val terminal = byteArrayOf(9, 0, 81, 81, 82, 83)
        val repository = GoAiMatchSessionTest.Repository().apply {
            saved = GoAiSavedMatch("maximum-retry", GoAiMatchConfig(9, humanPlayer = null), terminal,
                autoContinueEnabled = true, autoContinueMaxGames = 2, autoContinueGameNumber = 2)
        }
        var attempts = 0
        val storedIds = mutableListOf<String>()
        val session = session(repository, sink = GoAiCompletedMatchSink { match ->
            attempts++
            if (attempts == 1) throw IOException("archive failed")
            storedIds += match.matchId
        })
        try {
            runCurrent()
            assertEquals("maximum-retry", repository.saved!!.matchId)
            assertTrue(repository.saved!!.completionReserved)
            assertEquals(GoPhase.FINISHED, session.state.value.game.phase)
            assertNotNull(session.state.value.game.error)
            assertEquals(1, attempts)

            assertTrue(session.retryAutoContinuation()); runCurrent()
            assertEquals(2, attempts); assertEquals(listOf("maximum-retry"), storedIds)
            assertEquals("maximum-retry", repository.saved!!.matchId)
            assertEquals(2, repository.saved!!.autoContinueGameNumber)
            assertEquals(GoPhase.FINISHED, session.state.value.game.phase)
            assertNull(session.state.value.game.error)
            assertFalse(session.continueAutomatically())
        } finally { session.closeAndJoin() }
    }

    @Test fun archiveFailureKeepsReservedTerminalAndExplicitRetryUsesSameIdentity() = runTest {
        val terminal = byteArrayOf(9, 0, 81, 81, 82, 83)
        val repository = GoAiMatchSessionTest.Repository().apply {
            saved = GoAiSavedMatch("retry-series", GoAiMatchConfig(9, humanPlayer = null), terminal,
                autoContinueEnabled = true, autoContinueMaxGames = 3)
        }
        var attempts = 0; val storedIds = mutableListOf<String>()
        val sink = GoAiCompletedMatchSink { match ->
            attempts++; if (attempts == 1) throw IOException("archive failed"); storedIds += match.matchId
        }
        val session = session(repository, sink = sink)
        try {
            runCurrent(); assertEquals("retry-series", repository.saved!!.matchId)
            assertTrue(repository.saved!!.completionReserved); assertEquals(GoPhase.FINISHED, session.state.value.game.phase)
            assertNotNull(session.state.value.game.error); assertEquals(1, attempts)
            assertTrue(session.continueAutomatically()); runCurrent()
            assertEquals(listOf("retry-series"), storedIds); assertEquals(2, attempts)
            assertNotEquals("retry-series", repository.saved!!.matchId)
            assertEquals(2, repository.saved!!.autoContinueGameNumber)
        } finally { session.closeAndJoin() }
    }

    @Test fun specifiedOpeningContinuationReturnsToExactEndpointAndPreservesTrainingSource() = runTest {
        val openingBytes = byteArrayOf(9, 0, 0, 1)
        val repository = GoAiMatchSessionTest.Repository().apply {
            saved = GoAiSavedMatch("opening-series", GoAiMatchConfig(9, humanPlayer = null),
                openingBytes + byteArrayOf(81, 81, 82, 83), openingBytes = openingBytes,
                autoContinueEnabled = true, autoContinueMaxGames = 2)
        }
        val session = session(repository, sink = GoAiCompletedMatchSink {})
        try {
            runCurrent(); val fresh = repository.saved!!
            assertArrayEquals(openingBytes, fresh.copyBytes()); assertArrayEquals(openingBytes, fresh.copyOpeningBytes())
            assertEquals(2, fresh.autoContinueGameNumber); assertEquals(2, session.state.value.openingEventCount)
            assertTrue(session.state.value.hasSpecifiedOpening); assertFalse(session.state.value.canUndo)
        } finally { session.closeAndJoin() }
    }

    @Test fun manualRestartResetsSeriesCountAndLeavingAutomaticModeDisablesPolicy() = runTest {
        val repository = GoAiMatchSessionTest.Repository()
        val session = session(repository, sink = GoAiCompletedMatchSink {})
        try {
            runCurrent(); assertTrue(session.configureAutoContinuation(true, 5)); runCurrent()
            assertTrue(session.restart()); runCurrent()
            assertTrue(repository.saved!!.autoContinueEnabled); assertEquals(1, repository.saved!!.autoContinueGameNumber)
            assertTrue(session.setAutomatic(false)); runCurrent()
            assertFalse(repository.saved!!.autoContinueEnabled); assertEquals(1, repository.saved!!.autoContinueMaxGames)
            assertEquals(GoPlayer.BLACK, repository.saved!!.config.humanPlayer)
        } finally { session.closeAndJoin() }
    }

    @Test fun restoredTerminalCreatesNextCheckpointPausedUntilExplicitResume() = runTest {
        val repository = GoAiMatchSessionTest.Repository().apply {
            saved = GoAiSavedMatch("restored-paused", GoAiMatchConfig(9, humanPlayer = null),
                byteArrayOf(9, 0, 81, 81, 82, 83), autoContinueEnabled = true, autoContinueMaxGames = 2)
        }
        val search = GoAiMatchSessionTest.Search()
        val session = session(repository, search, GoAiCompletedMatchSink {})
        try {
            runCurrent(); assertEquals(2, session.state.value.autoContinueGameNumber)
            assertTrue(session.state.value.paused)
            advanceTimeBy(2_000); runCurrent(); assertTrue(search.requests.isEmpty())
            session.resume(); advanceTimeBy(1_000); runCurrent(); assertEquals(1, search.requests.size)
        } finally { session.closeAndJoin() }
    }

    @Test fun pauseDuringInFlightArchivePreventsNextGameFromRestartingSearch() = runTest {
        val repository = GoAiMatchSessionTest.Repository()
        val search = GoAiMatchSessionTest.Search().apply {
            answers.trySend(move(GoAction.Pass)); answers.trySend(move(GoAction.Pass))
        }
        val archiveGate = CompletableDeferred<Unit>()
        val session = session(repository, search, GoAiCompletedMatchSink { archiveGate.await() })
        try {
            runCurrent(); assertTrue(session.configureAutoContinuation(true, 2)); runCurrent()
            session.resume(); advanceTimeBy(1_000); runCurrent(); advanceTimeBy(1_000); runCurrent()
            assertTrue(session.humanAction(GoAction.ConfirmScore(GoPlayer.BLACK, 1))); runCurrent()
            assertTrue(session.humanAction(GoAction.ConfirmScore(GoPlayer.WHITE, 1))); runCurrent()
            assertTrue(session.state.value.game.saving)
            session.pause(); archiveGate.complete(Unit); runCurrent()
            assertEquals(2, session.state.value.autoContinueGameNumber); assertTrue(session.state.value.paused)
            advanceTimeBy(2_000); runCurrent(); assertEquals(2, search.requests.size)
            session.resume(); advanceTimeBy(1_000); runCurrent(); assertEquals(3, search.requests.size)
        } finally { archiveGate.complete(Unit); session.closeAndJoin() }
    }

    private fun TestScope.session(
        repository: GoAiMatchSessionTest.Repository,
        search: GoAiSearchEngine = GoAiMatchSessionTest.Search(),
        sink: GoAiCompletedMatchSink? = null,
        config: GoAiMatchConfig = GoAiMatchConfig(9, humanPlayer = null),
    ) = GoAiMatchSession(this, repository, config, search, StandardTestDispatcher(testScheduler),
        completedSink = sink) { size, _, handicap -> GoAiMatchSessionTest.Rules(size, handicap) }

    private fun move(action: GoAction) = GoAiSearchOutcome.Move(GoAiSuggestion(action, 1, 1.0, 0.5, 0.0))
}
