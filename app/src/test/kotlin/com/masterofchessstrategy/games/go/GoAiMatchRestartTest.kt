package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class GoAiMatchRestartTest {
    @Test fun unfinishedRestartUsesNewIdentitySamePolicyAndResetsAllowances() = runTest {
        val repository = repository()
        val session = session(repository)
        try {
            runCurrent(); val old = repository.saved!!
            assertTrue(session.restart()); runCurrent()
            val fresh = repository.saved!!
            assertNotEquals(old.matchId, fresh.matchId)
            assertEquals(old.config, fresh.config)
            assertArrayEquals(byteArrayOf(9, 0), fresh.copyBytes())
            assertEquals(0, fresh.undoUsed); assertEquals(0, fresh.hintUsed)
            assertTrue(session.state.value.paused)
            assertEquals(0, session.state.value.eventCount)
            assertFalse(session.undo())
        } finally { session.closeAndJoin() }
    }

    @Test fun newSaveFailureKeepsOldBoardIdentityAndAllowances() = runTest {
        val repository = repository()
        val session = session(repository)
        try {
            runCurrent(); val old = repository.saved!!; repository.fail = true
            assertTrue(session.restart()); runCurrent()
            assertSame(old, repository.saved)
            assertEquals(1, session.state.value.undoUsed); assertEquals(2, session.state.value.hintUsed)
            assertEquals(1, session.state.value.game.stones[0])
            assertNotNull(session.state.value.game.error)
            assertFalse(session.state.value.game.saving)
            repository.fail = false
            assertTrue(session.restart()); runCurrent()
            assertNotEquals(old.matchId, repository.saved!!.matchId)
        } finally { session.closeAndJoin() }
    }

    @Test fun restartJoinsCancelledSearchBeforePublishingNewPositionAndReusesRuntime() = runTest {
        val repository = GoAiMatchSessionTest.Repository()
        val release = CompletableDeferred<Unit>()
        val requests = mutableListOf<GoAiSearchRequest>()
        val search = GoAiSearchEngine {
            requests += it
            withContext(NonCancellable) { release.await() }
            GoAiSearchOutcome.Move(GoAiSuggestion(GoAction.Pass, 1, 1.0, 0.5, 0.0))
        }
        val config = GoAiMatchConfig(9, humanPlayer = GoPlayer.WHITE)
        val session = GoAiMatchSession(this, repository, config, search, StandardTestDispatcher(testScheduler)) { size, _, handicap ->
            GoAiMatchSessionTest.Rules(size, handicap)
        }
        try {
            runCurrent(); session.resume(); runCurrent()
            assertEquals(1, requests.size)
            val oldId = repository.saved!!.matchId
            assertTrue(session.restart()); runCurrent()
            assertTrue(requests.first().cancellation.isCancelled)
            assertTrue(session.state.value.game.saving)
            assertEquals(oldId, repository.saved!!.matchId)
            release.complete(Unit); runCurrent()
            assertNotEquals(oldId, repository.saved!!.matchId)
            assertEquals(0, session.state.value.eventCount)
            session.resume(); runCurrent()
            assertEquals(2, requests.size)
            assertTrue(requests[1].position.stamp.revision > requests[0].position.stamp.revision)
            assertEquals(1, session.state.value.eventCount)
        } finally { release.complete(Unit); session.closeAndJoin() }
    }

    @Test fun savingRejectsRepeatedRestartAndPreservesSpeedChange() = runTest {
        val repository = repository()
        val session = session(repository)
        try {
            runCurrent(); repository.gate = CompletableDeferred()
            assertTrue(session.restart()); runCurrent()
            assertFalse(session.restart()); assertFalse(session.humanAction(GoAction.Pass))
            session.setSpeed(4f); session.pause()
            repository.gate!!.complete(Unit); runCurrent()
            assertEquals(4f, session.state.value.speed)
            assertTrue(session.state.value.paused)
            assertEquals(1, repository.history.size)
        } finally { session.closeAndJoin() }
    }

    @Test fun finishedWithoutCompletionStoreCannotOverwriteOrReset() = runTest {
        val repository = repository(finished = true)
        val session = session(repository)
        try {
            runCurrent(); val old = repository.saved!!
            assertFalse(session.restart()); runCurrent()
            assertSame(old, repository.saved)
            assertTrue(repository.history.isEmpty())
            assertEquals(GoPhase.FINISHED, session.state.value.game.phase)
            assertFalse(session.state.value.completionReserved)
        } finally { session.closeAndJoin() }
    }

    @Test fun failedSealNeverCallsCompletedStoreAndLeavesTerminalUndoAvailable() = runTest {
        val repository = repository(finished = true)
        val session = session(repository)
        var stores = 0
        try {
            runCurrent(); repository.fail = true
            assertTrue(session.restart(GoAiCompletedMatchSink { stores++ })); runCurrent()
            assertEquals(0, stores)
            assertFalse(repository.saved!!.completionReserved)
            assertFalse(session.state.value.completionReserved)
            assertTrue(session.state.value.canUndo)
        } finally { session.closeAndJoin() }
    }

    @Test fun completedStoreFailurePersistsSealAndRestoringCannotUndoIntoAnotherResult() = runTest {
        val repository = repository(finished = true)
        val oldBytes = repository.saved!!.copyBytes()
        val session = session(repository)
        runCurrent()
        assertTrue(session.restart(GoAiCompletedMatchSink { throw IOException("Room rejected") })); runCurrent()
        assertTrue(repository.saved!!.completionReserved)
        assertTrue(session.state.value.completionReserved)
        assertFalse(session.undo())
        assertArrayEquals(oldBytes, repository.saved!!.copyBytes())
        session.closeAndJoin()
        val restored = session(repository)
        try {
            runCurrent(); assertTrue(restored.state.value.completionReserved)
            assertFalse(restored.undo())
            assertEquals(GoPhase.FINISHED, restored.state.value.game.phase)
        } finally { restored.closeAndJoin() }
    }

    @Test fun archiveSuccessThenNewSaveFailureRetriesSameIdentityWithoutDuplicateAward() = runTest {
        val repository = repository(finished = true)
        val old = repository.saved!!
        val session = session(repository)
        val stored = linkedMapOf<String, ByteArray>()
        var calls = 0
        val sink = GoAiCompletedMatchSink { match ->
            calls++
            assertEquals(old.matchId, match.matchId)
            assertTrue(match.completionReserved)
            val previous = stored.putIfAbsent(match.matchId, match.copyBytes())
            if (previous != null) assertArrayEquals(previous, match.copyBytes())
            repository.fail = true
        }
        try {
            runCurrent(); assertTrue(session.restart(sink)); runCurrent()
            assertEquals(1, calls); assertEquals(1, stored.size)
            assertEquals(old.matchId, repository.saved!!.matchId)
            assertTrue(session.state.value.completionReserved)
            repository.fail = false
            val successfulSink = GoAiCompletedMatchSink { match ->
                calls++; assertEquals(old.matchId, match.matchId)
                assertArrayEquals(stored[match.matchId], match.copyBytes())
            }
            assertTrue(session.restart(successfulSink)); runCurrent()
            assertEquals(2, calls); assertEquals(1, stored.size)
            assertNotEquals(old.matchId, repository.saved!!.matchId)
            assertFalse(repository.saved!!.completionReserved)
            assertEquals(0, session.state.value.undoUsed); assertEquals(0, session.state.value.hintUsed)
        } finally { session.closeAndJoin() }
    }

    @Test fun invalidSealOnPlayingRecordIsRejectedWithoutRepairingFile() = runTest {
        val repository = repository()
        val old = repository.saved!!
        repository.saved = GoAiSavedMatch(old.matchId, old.config, old.copyBytes(), completionReserved = true)
        val session = session(repository)
        try {
            runCurrent(); assertFalse(session.state.value.game.canOperate)
            assertNotNull(session.state.value.game.error)
            assertTrue(repository.history.isEmpty())
            assertFalse(session.restart())
        } finally { session.closeAndJoin() }
    }

    @Test fun closingDuringRestartWriteJoinsAndClosesEveryCandidate() = runTest {
        val repository = repository()
        val engines = mutableListOf<GoAiMatchSessionTest.Rules>()
        val session = session(repository, engines)
        runCurrent(); repository.gate = CompletableDeferred()
        val oldId = repository.saved!!.matchId
        session.restart(); runCurrent()
        val closing = async { session.closeAndJoin() }; runCurrent()
        assertFalse(closing.isCompleted)
        repository.gate!!.complete(Unit); runCurrent(); closing.await()
        assertNotEquals(oldId, repository.saved!!.matchId)
        assertTrue(engines.all { it.closed })
        assertTrue(session.state.value.closed)
    }

    private fun repository(finished: Boolean = false) = GoAiMatchSessionTest.Repository().apply {
        saved = GoAiSavedMatch("old-match", GoAiMatchConfig(9, difficulty = GoAiDifficulty.MEDIUM),
            if (finished) byteArrayOf(9, 0, 81, 81, 82, 83) else byteArrayOf(9, 0, 0, 1), 1, 2)
    }
    private fun TestScope.session(repository: GoAiMatchSessionTest.Repository,
        engines: MutableList<GoAiMatchSessionTest.Rules> = mutableListOf()) =
        GoAiMatchSession(this, repository, GoAiMatchConfig(9), GoAiMatchSessionTest.Search(), StandardTestDispatcher(testScheduler)) { size, _, handicap ->
            GoAiMatchSessionTest.Rules(size, handicap).also(engines::add)
        }
}
