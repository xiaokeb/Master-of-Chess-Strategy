package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GoAiMatchModeTest {
    @Test fun switchingKeepsSameIdentityFullHistoryOriginalWhiteAndSeparateAllowances() = runTest {
        val repository = repository(); val search = GoAiMatchSessionTest.Search()
        val engines = mutableListOf<GoAiMatchSessionTest.Rules>()
        val session = session(repository, search, engines)
        try {
            runCurrent(); val old = repository.saved!!
            assertTrue(session.setAutomatic(true)); runCurrent()
            assertTrue(session.state.value.config.automatic); assertTrue(session.state.value.paused)
            assertEquals(GoPlayer.WHITE, session.state.value.rememberedHuman)
            assertTrue(session.state.value.automaticUsed)
            assertEquals(old.matchId, repository.saved!!.matchId)
            assertArrayEquals(old.copyBytes(), repository.saved!!.copyBytes())
            assertEquals(1, session.state.value.undoUsed); assertEquals(2, session.state.value.hintUsed)
            assertEquals(2, session.state.value.eventCount)
            assertEquals(1, engines.size) // No substitute Native or reset during metadata changes.
            advanceTimeBy(10_000); runCurrent(); assertTrue(search.requests.isEmpty())
            assertFalse(session.humanAction(GoAction.Pass)); assertFalse(session.requestHint())
            assertTrue(session.setAutomatic(false)); runCurrent()
            assertEquals(old.config, session.state.value.config)
            assertTrue(session.state.value.automaticUsed); assertTrue(repository.saved!!.automaticUsed)
            assertEquals(GoPlayer.WHITE, repository.saved!!.rememberedHuman)
            assertArrayEquals(old.copyBytes(), repository.saved!!.copyBytes())
        } finally { session.closeAndJoin() }
    }

    @Test fun autoResumeSearchesTheExistingPositionWithExistingBudget() = runTest {
        val repository = repository(); val search = GoAiMatchSessionTest.Search()
        val session = session(repository, search)
        try {
            runCurrent(); val old = repository.saved!!
            session.setAutomatic(true); runCurrent()
            session.resume(); runCurrent(); advanceTimeBy(1_000); runCurrent()
            val request = search.requests.single()
            assertArrayEquals(old.copyBytes(), request.position.copyBytes())
            assertEquals(old.config.difficulty.budget, request.budget)
            assertFalse(request.cancellation.isCancelled)
            session.pause(); runCurrent(); assertTrue(request.cancellation.isCancelled)
        } finally { session.closeAndJoin() }
    }

    @Test fun identicalModeRequestDoesNotInterruptAnExistingSearchOrSave() = runTest {
        val repository = repository(); val search = GoAiMatchSessionTest.Search()
        val session = session(repository, search)
        try {
            runCurrent(); session.resume(); runCurrent()
            val request = search.requests.single()
            assertFalse(session.setAutomatic(false)); runCurrent()
            assertTrue(repository.history.isEmpty()); assertFalse(request.cancellation.isCancelled)
            assertFalse(session.state.value.paused)
        } finally { session.closeAndJoin() }
    }

    @Test fun failedSavePreservesModeHistoryCountersAndEligibilityThenAllowsRetry() = runTest {
        val repository = repository(); val session = session(repository)
        try {
            runCurrent(); val old = repository.saved!!; repository.fail = true
            assertTrue(session.setAutomatic(true)); runCurrent()
            assertSame(old, repository.saved); assertEquals(old.config, session.state.value.config)
            assertFalse(session.state.value.automaticUsed); assertTrue(session.state.value.paused)
            assertEquals(1, session.state.value.undoUsed); assertEquals(2, session.state.value.hintUsed)
            assertNotNull(session.state.value.game.error); assertFalse(session.state.value.game.saving)
            repository.fail = false; assertTrue(session.setAutomatic(true)); runCurrent()
            assertTrue(repository.saved!!.automaticUsed); assertEquals(old.matchId, repository.saved!!.matchId)
        } finally { session.closeAndJoin() }
    }

    @Test fun savingGatesOtherMutationsAndKeepsChosenSpeed() = runTest {
        val repository = repository(); val session = session(repository)
        try {
            runCurrent(); repository.gate = CompletableDeferred()
            assertTrue(session.setAutomatic(true)); runCurrent()
            assertFalse(session.setAutomatic(true)); assertFalse(session.restart())
            assertFalse(session.undo()); assertFalse(session.humanAction(GoAction.Pass)); assertFalse(session.requestHint())
            session.setSpeed(4f); session.resume(); session.pause()
            repository.gate!!.complete(Unit); runCurrent()
            assertEquals(4f, session.state.value.speed); assertTrue(session.state.value.paused)
            assertEquals(1, repository.history.size)
        } finally { session.closeAndJoin() }
    }

    @Test fun switchingJoinsCancelledSearchAndDoesNotConsumeLateSuggestion() = runTest {
        val repository = repository(); val release = CompletableDeferred<Unit>()
        val requests = mutableListOf<GoAiSearchRequest>()
        val search = GoAiSearchEngine {
            requests += it; withContext(NonCancellable) { release.await() }
            GoAiSearchOutcome.Move(GoAiSuggestion(GoAction.Place(BoardPosition(2, 0)), 1, 1.0, 0.5, 0.0))
        }
        val session = session(repository, search)
        try {
            runCurrent(); val old = repository.saved!!; session.resume(); runCurrent()
            session.setAutomatic(true); runCurrent()
            assertTrue(requests.single().cancellation.isCancelled)
            assertTrue(session.state.value.game.saving); assertTrue(repository.history.isEmpty())
            release.complete(Unit); runCurrent()
            assertTrue(repository.saved!!.config.automatic)
            assertArrayEquals(old.copyBytes(), repository.saved!!.copyBytes())
            session.resume(); advanceTimeBy(1_000); runCurrent()
            assertEquals(2, requests.size)
            assertTrue(requests[1].position.stamp.revision > requests[0].position.stamp.revision)
            assertEquals(3, session.state.value.eventCount)
            session.pause()
        } finally { release.complete(Unit); session.closeAndJoin() }
    }

    @Test fun restoreReturnsToOriginalWhiteAndMovesUndoHintsKeepMixedProvenance() = runTest {
        val repository = repository().apply {
            val old = saved!!
            saved = GoAiSavedMatch(old.matchId, old.config.copy(humanPlayer = null), old.copyBytes(), 1, 2,
                rememberedHuman = GoPlayer.WHITE, automaticUsed = true)
        }
        val search = GoAiMatchSessionTest.Search()
        val session = session(repository, search)
        try {
            runCurrent(); assertTrue(session.state.value.paused); assertTrue(search.requests.isEmpty())
            session.setAutomatic(false); runCurrent()
            assertEquals(GoPlayer.WHITE, session.state.value.config.humanPlayer)
            // Black AI moves first in this restored even-length, non-handicap fixture.
            search.answers.trySend(GoAiSearchOutcome.Move(GoAiSuggestion(GoAction.Place(BoardPosition(2, 0)), 1, 1.0, 0.5, 0.0)))
            session.resume(); runCurrent(); session.pause()
            search.answers.trySend(GoAiSearchOutcome.Move(GoAiSuggestion(GoAction.Pass, 1, 1.0, 0.5, 0.0)))
            assertTrue(session.requestHint()); runCurrent()
            assertEquals(3, repository.saved!!.hintUsed)
            assertTrue(session.humanAction(GoAction.Place(BoardPosition(3, 0)))); runCurrent()
            assertTrue(session.undo()); runCurrent()
            assertEquals(2, repository.saved!!.undoUsed)
            assertTrue(repository.history.all { it.automaticUsed && it.rememberedHuman == GoPlayer.WHITE })
        } finally { session.closeAndJoin() }
    }

    @Test fun restartCreatesFreshEligibilityForCurrentModeAndRemembersOriginalSide() = runTest {
        for (automatic in listOf(false, true)) {
            val repository = repository(); val session = session(repository)
            try {
                runCurrent(); session.setAutomatic(true); runCurrent()
                if (!automatic) { session.setAutomatic(false); runCurrent() }
                val oldId = repository.saved!!.matchId
                session.restart(); runCurrent()
                assertNotEquals(oldId, repository.saved!!.matchId)
                assertEquals(GoPlayer.WHITE, repository.saved!!.rememberedHuman)
                assertEquals(automatic, repository.saved!!.automaticUsed)
                assertEquals(automatic, session.state.value.automaticUsed)
                assertEquals(0, session.state.value.undoUsed); assertEquals(0, session.state.value.hintUsed)
            } finally { session.closeAndJoin() }
        }
    }

    @Test fun scoringFinishedAndReservedMatchesRejectModeChangesWithoutWriting() = runTest {
        for (record in listOf(byteArrayOf(9, 0, 81, 81), byteArrayOf(9, 0, 81, 81, 82, 83))) {
            for (reserved in listOf(false, true)) {
                if (reserved && record.size == 4) continue
                val repository = GoAiMatchSessionTest.Repository().apply {
                    saved = GoAiSavedMatch("terminal", GoAiMatchConfig(9), record, completionReserved = reserved)
                }
                val session = session(repository)
                try {
                    runCurrent(); assertFalse(session.state.value.canSwitchMode)
                    assertFalse(session.setAutomatic(true)); runCurrent(); assertTrue(repository.history.isEmpty())
                } finally { session.closeAndJoin() }
            }
        }
    }

    @Test fun closeJoinsAcceptedMetadataWriteAndClosesNativeWithoutReplacingIt() = runTest {
        val repository = repository(); val engines = mutableListOf<GoAiMatchSessionTest.Rules>()
        val session = session(repository, engines = engines)
        runCurrent(); val old = repository.saved!!; repository.gate = CompletableDeferred()
        session.setAutomatic(true); runCurrent()
        val closing = async { session.closeAndJoin() }; runCurrent(); assertFalse(closing.isCompleted)
        repository.gate!!.complete(Unit); runCurrent(); closing.await()
        assertEquals(old.matchId, repository.saved!!.matchId); assertArrayEquals(old.copyBytes(), repository.saved!!.copyBytes())
        assertTrue(repository.saved!!.automaticUsed); assertTrue(repository.saved!!.config.automatic)
        assertEquals(1, engines.size); assertTrue(engines.single().closed); assertFalse(session.setAutomatic(false))
    }

    private fun repository() = GoAiMatchSessionTest.Repository().apply {
        saved = GoAiSavedMatch("mode-match", GoAiMatchConfig(9, humanPlayer = GoPlayer.WHITE, difficulty = GoAiDifficulty.MEDIUM),
            byteArrayOf(9, 0, 0, 1), 1, 2)
    }
    private fun TestScope.session(repository: GoAiMatchSessionTest.Repository,
        search: GoAiSearchEngine = GoAiMatchSessionTest.Search(), engines: MutableList<GoAiMatchSessionTest.Rules> = mutableListOf()) =
        GoAiMatchSession(this, repository, GoAiMatchConfig(9), search, StandardTestDispatcher(testScheduler)) { size, _, handicap ->
            GoAiMatchSessionTest.Rules(size, handicap).also(engines::add)
        }
}
