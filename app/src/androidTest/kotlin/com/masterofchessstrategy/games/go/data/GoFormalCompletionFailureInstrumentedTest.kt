package com.masterofchessstrategy.games.go.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.games.go.*
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Actual disk Room/Native with explicit boundary faults; scripted search is not NN evidence. */
class GoFormalCompletionFailureInstrumentedTest {
    @Test fun restoredUnreservedTerminalCannotUndoWhenReservationWriteFailsAndRetriesSameUuid() = runBlocking {
        owned { f ->
            val original = terminal(fresh())
            val real = f.checkpoints()
            real.save(original) // Ordinary historical source, deliberately not a formal creation.
            val rejectReservation = AtomicBoolean(true)
            val wrapped = object : GoAiMatchRepository by real {
                override suspend fun save(match: GoAiSavedMatch) {
                    if (match.completionReserved && rejectReservation.get()) throw IOException("owned before reservation write")
                    real.save(match)
                }
            }
            val session = f.open(wrapped)
            val pending = await(session) { it.game.sessionReady && !it.game.saving && it.game.error != null }
            assertTrue(pending.game.canOperate)
            assertEquals(GoPhase.FINISHED, pending.game.phase)
            assertTrue(pending.completionPending)
            assertFalse(pending.completionReserved)
            assertFalse(pending.completionStored)
            assertFalse(pending.canUndo)
            assertFalse(onOwner { session.undo() })
            assertFalse(real.load()!!.completionReserved)
            assertTrue(f.db.gameRecordDao().listAll().isEmpty())
            rejectReservation.set(false)
            assertTrue(onOwner { session.retryCompletion() })
            await(session) { it.completionStored && !it.game.saving }
            assertEquals(original.matchId, session.state.value.matchId)
            assertEquals(original.matchId, real.load()!!.matchId)
            assertTrue(real.load()!!.completionReserved)
            assertEquals(1, f.db.gameRecordDao().listAll().size)
            assertTrue(f.db.goRankedDao().starts().isEmpty())
            assertTrue(f.db.goRankedDao().settlements().isEmpty())
            assertTrue(f.db.matchOutcomeDao().listAll().isEmpty())
        }
    }

    @Test fun realSinkCommitThenLostAcknowledgementRetriesWithoutDuplicateGrowthOrFavoriteLoss() = runBlocking {
        owned { f ->
            val initial = f.prepareFormal()
            val realSink = RoomGoAiCompletionRepository(f.db)
            val calls = AtomicInteger()
            val sink = GoAiCompletedMatchSink { match ->
                realSink.storeCompleted(match)
                if (calls.incrementAndGet() == 1) throw IOException("owned acknowledgement lost after Room commit")
            }
            val session = f.open(f.checkpoints(), sink)
            finish(session)
            await(session) { it.completionPending && !it.game.saving && it.game.error != null }
            f.assertOneGrowth(initial.matchId)
            assertFalse(session.state.value.completionStored)
            assertTrue(session.state.value.completionReserved)
            assertFalse(onOwner { session.undo() })
            val id = admitGoAiCheckpoint(initial).recordId
            assertEquals(1, f.db.gameRecordDao().setFavorite(id, true))
            assertTrue(onOwner { session.retryCompletion() })
            await(session) { it.completionStored && !it.game.saving }
            assertEquals(2, calls.get())
            assertFalse(onOwner { session.retryCompletion() })
            assertEquals(initial.matchId, session.state.value.matchId)
            assertTrue(f.db.gameRecordDao().listAll().single().isFavorite)
            f.assertOneGrowth(initial.matchId)
        }
    }

    @Test fun retirementSealsImmediatelyButCloseWaitsForEnteredSinkWithoutOpeningNewGame() = runBlocking {
        owned { f ->
            val initial = f.prepareFormal()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val closingEntered = CompletableDeferred<Unit>()
            val sink = GoAiCompletedMatchSink {
                entered.complete(Unit)
                release.await()
                RoomGoAiCompletionRepository(f.db).storeCompleted(it)
            }
            val session = f.open(f.checkpoints(), sink)
            var closing: Deferred<Unit>? = null
            try {
                finish(session)
                withTimeout(20_000) { entered.await() }
                onOwner {
                    session.beginRetirement()
                    assertTrue(session.state.value.closed)
                    assertFalse(session.retryCompletion())
                    assertFalse(session.restart())
                    assertFalse(session.humanAction(GoAction.Pass))
                }
                val closeJob = async {
                    onOwner {
                        closingEntered.complete(Unit)
                        session.closeAndJoin()
                    }
                }
                closing = closeJob
                withTimeout(20_000) { closingEntered.await() }
                // This is a bounded assertion on the entered close, not a timing-based startup guess.
                assertNull(withTimeoutOrNull(200) { closeJob.await(); true })
                assertFalse(closeJob.isCompleted)
                assertEquals(initial.matchId, f.checkpoints().load()!!.matchId)
                assertTrue(f.db.gameRecordDao().listAll().isEmpty())
                release.complete(Unit)
                withTimeout(20_000) { closeJob.await() }
                assertEquals(initial.matchId, f.checkpoints().load()!!.matchId)
                f.assertOneGrowth(initial.matchId)
            } finally {
                release.complete(Unit) // Never strand the private owner if an assertion fails.
                closing?.let { withContext(NonCancellable) { withTimeout(20_000) { it.await() } } }
            }
        }
    }

    @Test fun lostCreationAcknowledgementNeedsRealSourceAsWellAsMatchingCheckpoint() = runBlocking {
        for (withSource in listOf(true, false)) owned { f ->
            f.completeLessons()
            val real = f.formal()
            val created = AtomicReference<GoAiSavedMatch?>()
            val confirmations = AtomicInteger()
            val wrapper = object : GoAiFormalMatchRepository by real {
                override suspend fun createStandardMatch(match: GoAiSavedMatch) {
                    created.set(match)
                    if (withSource) real.createStandardMatch(match) else real.save(match)
                    throw IOException("owned create acknowledgement lost")
                }
                override suspend fun confirmsStandardCreation(match: GoAiSavedMatch): Boolean {
                    confirmations.incrementAndGet()
                    return real.confirmsStandardCreation(match)
                }
            }
            val session = f.open(wrapper)
            if (withSource) {
                val ready = await(session) { it.game.canOperate }
                assertFalse(ready.closed)
                assertEquals(created.get()!!.matchId, ready.matchId)
                assertEquals(1, f.db.goRankedDao().starts().size)
            } else {
                val sealed = await(session) { it.closed && it.game.error != null }
                assertFalse(sealed.game.sessionReady)
                assertFalse(onOwner { session.humanAction(GoAction.Pass) })
                assertFalse(onOwner { session.retryCompletion() })
                assertTrue(f.db.goRankedDao().starts().isEmpty())
            }
            assertEquals(1, confirmations.get())
            assertArrayEquals(GoAiMatchFileCodec.encode(created.get()!!),
                GoAiMatchFileCodec.encode(f.checkpoints().load()!!))
            assertTrue(f.db.goAiCheckpointDao().listArchives().isEmpty())
            assertTrue(f.db.gameRecordDao().listAll().isEmpty())
            assertTrue(f.db.goRankedDao().settlements().isEmpty())
            assertTrue(f.db.matchOutcomeDao().listAll().isEmpty())
        }
    }

    private val config = GoAiMatchConfig(9, humanPlayer = GoPlayer.WHITE)
    private fun fresh() = NativeGoEngine(9).use { GoAiSavedMatch(UUID.randomUUID().toString(), config, it.serialize()) }
    private fun terminal(match: GoAiSavedMatch) = NativeGoEngine(9).use {
        assertEquals(RestoreResult.Restored, it.restore(match.copyBytes()))
        repeat(2) { _ -> assertEquals(ActionResult.Accepted, it.apply(GoAction.Pass)) }
        val revision = it.scoringState().proposalRevision
        GoPlayer.entries.forEach { player -> assertEquals(ActionResult.Accepted, it.apply(GoAction.ConfirmScore(player, revision))) }
        assertEquals(GameResult.SECOND_PLAYER_WIN, it.gameResult())
        GoAiSavedMatch(match.matchId, match.config, it.serialize())
    }
    private suspend fun finish(session: GoAiMatchSession) {
        await(session) { it.game.canOperate }
        onOwner { session.setSpeed(4f); session.resume() }
        await(session) { it.humanCanPlay && it.game.currentPlayer == GoPlayer.WHITE }
        assertTrue(onOwner { session.humanAction(GoAction.Pass) })
        await(session) { it.game.phase == GoPhase.SCORING && it.game.canOperate }
        val revision = session.state.value.game.proposalRevision
        assertTrue(onOwner { session.humanAction(GoAction.ConfirmScore(GoPlayer.BLACK, revision)) })
        await(session) { it.game.blackConfirmed && it.game.canOperate }
        assertTrue(onOwner { session.humanAction(GoAction.ConfirmScore(GoPlayer.WHITE, revision)) })
    }
    private suspend fun <T> onOwner(block: suspend () -> T): T = withContext(Dispatchers.Main.immediate) { block() }
    private suspend fun await(session: GoAiMatchSession, predicate: (GoAiMatchUiState) -> Boolean) =
        try { withTimeout(20_000) { session.state.first(predicate) } }
        catch (timeout: TimeoutCancellationException) {
            throw AssertionError("Completion failure state was not reached: ${session.state.value}", timeout)
        }

    private inner class Fixture {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val name = "go-formal-failure-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, MocsDatabase::class.java, name).build()
        private val owners = linkedMapOf<GoAiMatchSession, Job>()
        fun checkpoints() = GoAiCheckpointRepository(RoomGoAiCheckpointStore(db), clock = { 200L })
        private fun learning() = RoomGoLearningProgressRepository(db)
        fun formal() = RoomGoFormalAiMatchRepository(db, checkpoints(), learning(), GoStorageEpochGate.forDatabase(db))
        suspend fun completeLessons() = learning().save(GoLearningProgress(GoLearningLibrary.lessons.map { it.id }.toSet()))
        suspend fun prepareFormal(): GoAiSavedMatch {
            completeLessons()
            val initial = fresh()
            RoomGoRankedMatchCreator(db, checkpoints(), learning(), clock = { 100L }).create(initial)
            return initial
        }
        suspend fun open(repository: GoAiMatchRepository,
            sink: GoAiCompletedMatchSink = RoomGoAiCompletionRepository(db)): GoAiMatchSession = onOwner {
            val parent = SupervisorJob()
            val scope = CoroutineScope(parent + Dispatchers.Main.immediate)
            val script = GoAiSearchEngine { GoAiSearchOutcome.Move(GoAiSuggestion(GoAction.Pass, 1, 0.0, 0.5, 0.0)) }
            GoAiMatchSession(scope, repository, config, script, completedSink = sink,
                completeHumanImmediately = true).also { owners[it] = parent }
        }
        suspend fun assertOneGrowth(identity: String) {
            assertEquals(identity, checkpoints().load()!!.matchId)
            assertEquals(1, db.gameRecordDao().listAll().size)
            assertEquals(1, db.goAiProjectionDao().listAll().size)
            assertEquals(1, db.goRankedDao().settlements().size)
            val outcome = db.matchOutcomeDao().listAll().single().toMatchOutcome()!!
            assertEquals(admitGoAiCheckpoint(checkpoints().load()!!).recordId, outcome.matchId)
            assertTrue(outcome.isWin)
            assertEquals(200L, outcome.settledAtEpochMillis)
            val stats = (RoomMatchStatisticsRepository(db).load() as LoadMatchStatisticsResult.Loaded).statistics
            assertEquals(1, stats.totalWins)
            assertEquals(1, stats.completedMatches)
        }
        suspend fun close() {
            try {
                owners.forEach { (session, parent) ->
                    try { onOwner { session.closeAndJoin() } } finally { parent.cancelAndJoin() }
                }
            } finally { db.close(); check(context.deleteDatabase(name)) }
        }
    }
    private suspend fun owned(block: suspend (Fixture) -> Unit) {
        val fixture = Fixture()
        try { block(fixture) } finally { fixture.close() }
    }
}
