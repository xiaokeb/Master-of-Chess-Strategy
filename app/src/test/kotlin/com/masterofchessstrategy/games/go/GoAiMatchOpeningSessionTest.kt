package com.masterofchessstrategy.games.go

import com.masterofchessstrategy.engine.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GoAiMatchOpeningSessionTest {
    @Test fun newSpecifiedOpeningIsSavedAndPublishedAtItsExactEndpoint() = runTest {
        val repository = GoAiMatchSessionTest.Repository()
        val opening = opening(byteArrayOf(9, 0, 0, 1))
        val session = session(repository, opening = opening)
        try {
            runCurrent(); val saved = checkNotNull(repository.saved)
            assertTrue(saved.hasSpecifiedOpening); assertArrayEquals(opening.copyBytes(), saved.copyOpeningBytes())
            assertArrayEquals(opening.copyBytes(), saved.copyBytes())
            assertTrue(session.state.value.hasSpecifiedOpening)
            assertEquals(2, session.state.value.openingEventCount); assertEquals(2, session.state.value.eventCount)
            assertFalse(session.state.value.canUndo); assertTrue(session.state.value.paused)
            assertEquals(1, session.state.value.game.stones[0]); assertEquals(2, session.state.value.game.stones[1])
        } finally { session.closeAndJoin() }
    }

    @Test fun durableExistingMatchWinsOverAConflictingRouteOpening() = runTest {
        val repository = GoAiMatchSessionTest.Repository().apply {
            saved = GoAiSavedMatch("existing", GoAiMatchConfig(9), byteArrayOf(9, 0, 2))
        }
        val session = session(repository, opening = opening(byteArrayOf(9, 0, 0, 1)))
        try {
            runCurrent(); assertTrue(session.state.value.game.canOperate)
            assertFalse(session.state.value.hasSpecifiedOpening); assertEquals(0, session.state.value.openingEventCount)
            assertEquals("existing", repository.saved!!.matchId)
            assertArrayEquals(byteArrayOf(9, 0, 2), repository.saved!!.copyBytes())
            assertNull(repository.saved!!.copyOpeningBytes())
        } finally { session.closeAndJoin() }
    }

    @Test fun restoredCurrentRecordMustRetainTheEntireOpeningPrefix() = runTest {
        val original = GoAiSavedMatch("bad-prefix", GoAiMatchConfig(9), byteArrayOf(9, 0, 0, 2),
            openingBytes = byteArrayOf(9, 0, 0, 1))
        val repository = GoAiMatchSessionTest.Repository().apply { saved = original }
        val engines = mutableListOf<GoAiMatchSessionTest.Rules>()
        val session = session(repository, engines = engines)
        try {
            runCurrent(); assertFalse(session.state.value.game.canOperate); assertNotNull(session.state.value.game.error)
            assertSame(original, repository.saved); assertTrue(repository.history.isEmpty())
            assertTrue(engines.all { it.closed })
        } finally { session.closeAndJoin() }
    }

    @Test fun humanUndoReturnsToOpeningBoundaryAndCannotEraseImportedHistory() = runTest {
        val repository = GoAiMatchSessionTest.Repository()
        val search = GoAiMatchSessionTest.Search().apply {
            answers.trySend(move(GoAction.Place(BoardPosition(3, 0))))
        }
        val session = session(repository, search, opening(byteArrayOf(9, 0, 0, 1)))
        try {
            runCurrent(); session.resume()
            assertTrue(session.humanAction(GoAction.Place(BoardPosition(2, 0)))); runCurrent()
            assertEquals(4, session.state.value.eventCount)
            assertTrue(session.undo()); runCurrent()
            assertEquals(2, session.state.value.eventCount); assertEquals(2, session.state.value.openingEventCount)
            assertArrayEquals(byteArrayOf(9, 0, 0, 1), repository.saved!!.copyBytes())
            assertArrayEquals(byteArrayOf(9, 0, 0, 1), repository.saved!!.copyOpeningBytes())
            assertFalse(session.undo()); assertFalse(session.state.value.canUndo)
        } finally { session.closeAndJoin() }
    }

    @Test fun restartReturnsToOpeningWithNewIdentityAndFreshAllowances() = runTest {
        val repository = GoAiMatchSessionTest.Repository()
        val session = session(repository, opening = opening(byteArrayOf(13, 5, 0, 1)),
            config = GoAiMatchConfig(13, 5, GoPlayer.WHITE))
        try {
            runCurrent(); val oldId = repository.saved!!.matchId
            assertTrue(session.requestHint()); runCurrent()
            assertTrue(session.humanAction(GoAction.Place(BoardPosition(2, 0)))); runCurrent()
            assertTrue(session.restart()); runCurrent()
            val fresh = repository.saved!!
            assertNotEquals(oldId, fresh.matchId); assertArrayEquals(byteArrayOf(13, 5, 0, 1), fresh.copyBytes())
            assertArrayEquals(byteArrayOf(13, 5, 0, 1), fresh.copyOpeningBytes())
            assertEquals(0, fresh.undoUsed); assertEquals(0, fresh.hintUsed)
            assertEquals(2, session.state.value.eventCount); assertFalse(session.undo()); assertTrue(session.state.value.paused)
        } finally { session.closeAndJoin() }
    }

    @Test fun hintAndModeChangesPreserveOpeningAndTrainingSource() = runTest {
        val repository = GoAiMatchSessionTest.Repository()
        val opening = opening(byteArrayOf(9, 0, 0, 1))
        val session = session(repository, opening = opening)
        try {
            runCurrent(); assertTrue(session.requestHint()); runCurrent()
            assertTrue(session.setAutomatic(true)); runCurrent()
            assertArrayEquals(opening.copyBytes(), repository.saved!!.copyOpeningBytes())
            assertTrue(repository.saved!!.automaticUsed); assertTrue(repository.saved!!.config.automatic)
            assertTrue(session.state.value.hasSpecifiedOpening); assertEquals(2, session.state.value.openingEventCount)
            assertTrue(session.setAutomatic(false)); runCurrent()
            assertArrayEquals(opening.copyBytes(), repository.saved!!.copyOpeningBytes())
            assertTrue(repository.history.all { it.hasSpecifiedOpening })
        } finally { session.closeAndJoin() }
    }

    @Test fun completedStoreAndFreshRestartBothCarryTheOpeningSource() = runTest {
        val openingBytes = byteArrayOf(9, 0, 0, 1)
        val terminal = openingBytes + byteArrayOf(81, 81, 82, 83)
        val repository = GoAiMatchSessionTest.Repository().apply {
            saved = GoAiSavedMatch("opening-result", GoAiMatchConfig(9), terminal, hintUsed = 1, openingBytes = openingBytes)
        }
        var archived: GoAiSavedMatch? = null
        val session = session(repository)
        try {
            runCurrent(); assertTrue(session.restart(GoAiCompletedMatchSink { archived = it })); runCurrent()
            assertArrayEquals(openingBytes, checkNotNull(archived).copyOpeningBytes())
            assertTrue(checkNotNull(archived).completionReserved)
            assertArrayEquals(openingBytes, repository.saved!!.copyBytes())
            assertArrayEquals(openingBytes, repository.saved!!.copyOpeningBytes())
            assertNotEquals("opening-result", repository.saved!!.matchId)
            assertEquals(2, session.state.value.openingEventCount); assertTrue(session.state.value.hasSpecifiedOpening)
        } finally { session.closeAndJoin() }
    }

    @Test fun openingConfigMismatchAndInitialSaveFailureExposeNoPlayableFallback() = runTest {
        val mismatchRepository = GoAiMatchSessionTest.Repository()
        val mismatch = GoAiMatchSession(this, mismatchRepository, GoAiMatchConfig(13), GoAiMatchSessionTest.Search(),
            StandardTestDispatcher(testScheduler), opening(byteArrayOf(9, 0, 0))) { size, _, handicap ->
            GoAiMatchSessionTest.Rules(size, handicap)
        }
        runCurrent(); assertFalse(mismatch.state.value.game.canOperate); assertNull(mismatchRepository.saved)
        mismatch.closeAndJoin()
        val failing = GoAiMatchSessionTest.Repository().apply { fail = true }
        val failed = session(failing, opening = opening(byteArrayOf(9, 0, 0)))
        try {
            runCurrent(); assertFalse(failed.state.value.game.canOperate); assertNull(failing.saved)
            assertNotNull(failed.state.value.game.error)
        } finally { failed.closeAndJoin() }
    }

    @Test fun resumedAutomaticSearchUsesTheFullOpeningRecordWithoutMutatingIt() = runTest {
        val repository = GoAiMatchSessionTest.Repository()
        val search = GoAiMatchSessionTest.Search()
        val opening = opening(byteArrayOf(9, 0, 0, 1))
        val session = session(repository, search, opening,
            config = GoAiMatchConfig(9, humanPlayer = null, difficulty = GoAiDifficulty.HARD))
        try {
            runCurrent(); session.resume(); advanceTimeBy(1_000); runCurrent()
            val request = search.requests.single()
            assertArrayEquals(opening.copyBytes(), request.position.copyBytes())
            assertEquals(GoAiDifficulty.HARD.budget, request.budget)
            assertArrayEquals(opening.copyBytes(), repository.saved!!.copyOpeningBytes())
            session.pause()
        } finally { session.closeAndJoin() }
    }

    @Test fun failedRestartPreservesCurrentContinuationAndOpeningBoundary() = runTest {
        val repository = GoAiMatchSessionTest.Repository()
        val opening = opening(byteArrayOf(9, 0, 0, 1))
        val session = session(repository, opening = opening)
        try {
            runCurrent(); assertTrue(session.humanAction(GoAction.Place(BoardPosition(2, 0)))); runCurrent()
            val current = repository.saved!!; repository.fail = true
            assertTrue(session.restart()); runCurrent()
            assertSame(current, repository.saved); assertArrayEquals(opening.copyBytes(), repository.saved!!.copyOpeningBytes())
            assertEquals(3, session.state.value.eventCount); assertEquals(2, session.state.value.openingEventCount)
            assertTrue(session.state.value.canUndo); assertNotNull(session.state.value.game.error)
        } finally { session.closeAndJoin() }
    }

    @Test fun confirmedReplacementCreatesFreshTrainingMatchAndResetsAllowances() = runTest {
        val repository = GoAiMatchSessionTest.Repository()
        val search = GoAiMatchSessionTest.Search().apply {
            answers.trySend(move(GoAction.Place(BoardPosition(4, 0))))
        }
        val engines = mutableListOf<GoAiMatchSessionTest.Rules>()
        val session = session(repository, search, opening(byteArrayOf(9, 0, 0, 1)), engines,
            GoAiMatchConfig(9, humanPlayer = GoPlayer.BLACK, difficulty = GoAiDifficulty.HARD))
        try {
            runCurrent(); val oldId = repository.saved!!.matchId
            assertTrue(session.requestHint()); runCurrent()
            assertTrue(session.humanAction(GoAction.Place(BoardPosition(2, 0)))); runCurrent()
            assertTrue(session.undo()); runCurrent()
            assertEquals(1, session.state.value.hintUsed); assertEquals(1, session.state.value.undoUsed)

            val replacement = opening(byteArrayOf(13, 5, 0, 1))
            assertTrue(session.replaceOpening(replacement)); runCurrent()
            val saved = repository.saved!!; val state = session.state.value
            assertNotEquals(oldId, saved.matchId)
            assertEquals(13, saved.config.boardSize); assertEquals(5, saved.config.handicapStones)
            assertEquals(GoPlayer.BLACK, saved.config.humanPlayer); assertEquals(GoAiDifficulty.HARD, saved.config.difficulty)
            assertEquals(0, saved.undoUsed); assertEquals(0, saved.hintUsed); assertFalse(saved.automaticUsed)
            assertArrayEquals(replacement.copyBytes(), saved.copyBytes())
            assertArrayEquals(replacement.copyBytes(), saved.copyOpeningBytes())
            assertEquals(0, state.undoUsed); assertEquals(0, state.hintUsed)
            assertEquals(2, state.eventCount); assertEquals(2, state.openingEventCount)
            assertTrue(state.hasSpecifiedOpening); assertTrue(state.paused); assertFalse(state.canUndo)
            assertEquals(1, engines.count { !it.closed })
        } finally { session.closeAndJoin() }
    }

    @Test fun replacementSaveFailurePreservesCurrentIdentityBoardAndAllowances() = runTest {
        val repository = GoAiMatchSessionTest.Repository()
        val session = session(repository, opening = opening(byteArrayOf(9, 0, 0, 1)))
        try {
            runCurrent()
            assertTrue(session.humanAction(GoAction.Place(BoardPosition(2, 0)))); runCurrent()
            val saved = repository.saved!!; val before = session.state.value
            repository.fail = true
            assertTrue(session.replaceOpening(opening(byteArrayOf(13, 5, 0, 1)))); runCurrent()

            val after = session.state.value
            assertSame(saved, repository.saved)
            assertEquals(before.config, after.config); assertEquals(before.eventCount, after.eventCount)
            assertEquals(before.openingEventCount, after.openingEventCount)
            assertEquals(before.undoUsed, after.undoUsed); assertEquals(before.hintUsed, after.hintUsed)
            assertEquals(before.game.stones, after.game.stones); assertTrue(after.hasSpecifiedOpening)
            assertTrue(after.paused); assertNotNull(after.game.error)
        } finally { session.closeAndJoin() }
    }

    @Test fun replacementCancelsInFlightSearchBeforeCommittingAndIgnoresLateAnswer() = runTest {
        val repository = GoAiMatchSessionTest.Repository()
        val search = GoAiMatchSessionTest.Search()
        val session = session(repository, search, config = GoAiMatchConfig(9, humanPlayer = null))
        try {
            runCurrent(); session.resume(); advanceTimeBy(1_000); runCurrent()
            assertEquals(1, search.requests.size)
            val replacement = opening(byteArrayOf(13, 5, 0, 1))
            assertTrue(session.replaceOpening(replacement)); runCurrent()
            assertArrayEquals(replacement.copyBytes(), repository.saved!!.copyBytes())
            assertTrue(repository.saved!!.config.automatic)
            assertTrue(repository.saved!!.automaticUsed)

            search.answers.trySend(move(GoAction.Place(BoardPosition(8, 8))))
            runCurrent()
            assertArrayEquals(replacement.copyBytes(), repository.saved!!.copyBytes())
            assertEquals(2, session.state.value.eventCount)
        } finally { session.closeAndJoin() }
    }

    @Test fun finishedReplacementRequiresSinkThenArchivesOldResultBeforeFreshMatch() = runTest {
        val oldOpening = byteArrayOf(9, 0, 0, 1)
        val terminal = oldOpening + byteArrayOf(81, 81, 82, 83)
        val repository = GoAiMatchSessionTest.Repository().apply {
            saved = GoAiSavedMatch("finished-opening", GoAiMatchConfig(9), terminal,
                undoUsed = 1, hintUsed = 1, openingBytes = oldOpening)
        }
        var archived: GoAiSavedMatch? = null
        val session = session(repository)
        try {
            runCurrent(); val replacement = opening(byteArrayOf(13, 5, 0, 1))
            assertFalse(session.replaceOpening(replacement)); assertEquals("finished-opening", repository.saved!!.matchId)
            assertTrue(session.replaceOpening(replacement, GoAiCompletedMatchSink { archived = it })); runCurrent()

            val old = checkNotNull(archived); val fresh = repository.saved!!
            assertEquals("finished-opening", old.matchId); assertTrue(old.completionReserved)
            assertArrayEquals(oldOpening, old.copyOpeningBytes()); assertArrayEquals(terminal, old.copyBytes())
            assertNotEquals(old.matchId, fresh.matchId); assertFalse(fresh.completionReserved)
            assertArrayEquals(replacement.copyBytes(), fresh.copyOpeningBytes())
            assertEquals(13, session.state.value.config.boardSize); assertTrue(session.state.value.hasSpecifiedOpening)
        } finally { session.closeAndJoin() }
    }

    @Test fun closeWaitsForAcceptedReplacementSaveAndClosesEveryCandidate() = runTest {
        val repository = GoAiMatchSessionTest.Repository()
        val engines = mutableListOf<GoAiMatchSessionTest.Rules>()
        val session = session(repository, engines = engines)
        runCurrent()
        repository.gate = CompletableDeferred()
        assertTrue(session.replaceOpening(opening(byteArrayOf(13, 5, 0, 1)))); runCurrent()
        val closing = async { session.closeAndJoin() }
        runCurrent(); assertFalse(closing.isCompleted)
        repository.gate!!.complete(Unit); advanceUntilIdle(); closing.await()
        assertTrue(engines.isNotEmpty()); assertTrue(engines.all { it.closed })
        assertArrayEquals(byteArrayOf(13, 5, 0, 1), repository.saved!!.copyOpeningBytes())
    }

    private fun TestScope.session(repository: GoAiMatchSessionTest.Repository,
        search: GoAiSearchEngine = GoAiMatchSessionTest.Search(), opening: GoAiOpening? = null,
        engines: MutableList<GoAiMatchSessionTest.Rules> = mutableListOf(), config: GoAiMatchConfig = GoAiMatchConfig(9)) =
        GoAiMatchSession(this, repository, config, search, StandardTestDispatcher(testScheduler), opening) { size, _, handicap ->
            GoAiMatchSessionTest.Rules(size, handicap).also(engines::add)
        }
    private fun opening(bytes: ByteArray) = GoAiOpening.fromSnapshot(bytes) { size, _, handicap ->
        GoAiMatchSessionTest.Rules(size, handicap)
    }
    private fun move(action: GoAction) = GoAiSearchOutcome.Move(GoAiSuggestion(action, 1, 1.0, 0.5, 0.0))
}
