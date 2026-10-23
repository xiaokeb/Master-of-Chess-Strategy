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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Actual disk Room + Native rules, scripted pass search only; no NN/strength/UI claim. */
class GoFormalSessionCompletionInstrumentedTest {
    @Test fun bothConfirmationsImmediatelyStoreOneRecordAndGrowthWithoutRestart() = runBlocking {
        owned { f ->
            val initial = f.prepareFormal()
            val calls = AtomicInteger()
            val session = f.open(initial.config, counting(f.sink(), calls))
            finish(session)
            await(session) { it.completionStored && !it.game.saving }
            assertEquals(initial.matchId, session.state.value.matchId)
            assertEquals(1, calls.get())
            assertTrue(session.state.value.completionReserved)
            assertFalse(onOwner { session.undo() })
            f.assertOneGrowth(initial.matchId)
        }
    }

    @Test fun lateSinkFailureKeepsFixedResultAndRetryIsSameMatchAndIdempotent() = runBlocking {
        owned { f ->
            val initial = f.prepareFormal()
            val fail = AtomicBoolean(true)
            val calls = AtomicInteger()
            val sink = counting(RoomGoAiCompletionRepository(f.db, beforeCommit = {
                if (fail.get()) throw IOException("owned late completion failure")
            }), calls)
            val session = f.open(initial.config, sink)
            finish(session)
            awaitFailure(session)
            assertTrue(f.checkpoints().load()!!.completionReserved)
            assertFalse(onOwner { session.undo() })
            f.assertNoDerivedRows()
            fail.set(false)
            onOwner {
                assertTrue(session.retryCompletion())
                assertFalse(session.retryCompletion()) // One in-flight save, not two requests.
            }
            await(session) { it.completionStored && !it.game.saving }
            assertFalse(onOwner { session.retryCompletion() })
            assertEquals(initial.matchId, session.state.value.matchId)
            assertEquals(2, calls.get())
            f.assertOneGrowth(initial.matchId)
        }
    }

    @Test fun failedCompletionCloseAndActualDiskReopenRetainSameFixedMatchForRetry() = runBlocking {
        owned { f ->
            val initial = f.prepareFormal()
            val broken = RoomGoAiCompletionRepository(f.db, beforeCommit = { throw IOException("owned first failure") })
            val first = f.open(initial.config, broken)
            finish(first)
            awaitFailure(first)
            f.closeSession(first)
            f.reopenDatabase()
            val fail = AtomicBoolean(true)
            val next = f.open(initial.config, RoomGoAiCompletionRepository(f.db, beforeCommit = {
                if (fail.get()) throw IOException("owned restored retry failure")
            }))
            awaitFailure(next) // Formal restore attempts the fixed result without restart.
            assertEquals(initial.matchId, next.state.value.matchId)
            assertTrue(next.state.value.completionReserved)
            assertFalse(onOwner { next.undo() })
            f.assertNoDerivedRows()
            fail.set(false)
            assertTrue(onOwner { next.retryCompletion() })
            await(next) { it.completionStored && !it.game.saving }
            f.assertOneGrowth(initial.matchId)
        }
    }

    @Test fun restoredAlreadyCompletedMatchReplaysSinkWithoutDuplicateGrowthOrLostFavorite() = runBlocking {
        owned { f ->
            val initial = f.prepareFormal()
            val first = f.open(initial.config, f.sink())
            finish(first)
            await(first) { it.completionStored && !it.game.saving }
            val id = admitGoAiCheckpoint(initial).recordId
            assertEquals(1, f.db.gameRecordDao().setFavorite(id, true))
            f.closeSession(first)
            f.reopenDatabase()
            val calls = AtomicInteger()
            val next = f.open(initial.config, counting(f.sink(), calls))
            await(next) { it.completionStored && !it.game.saving }
            assertEquals(1, calls.get())
            assertEquals(initial.matchId, next.state.value.matchId)
            assertFalse(onOwner { next.retryCompletion() })
            assertFalse(onOwner { next.undo() })
            assertTrue(f.db.gameRecordDao().listAll().single().isFavorite)
            f.assertOneGrowth(initial.matchId)
        }
    }

    @Test fun defaultTrainingFlagDoesNotStoreImmediatelyAndAllowsTerminalUndo() = runBlocking {
        owned { f ->
            val initial = fresh()
            f.checkpoints().save(initial) // No formal source for this ordinary training fixture.
            val calls = AtomicInteger()
            val session = f.open(initial.config, counting(f.sink(), calls), useConstructorDefault = true)
            finish(session)
            await(session) { it.game.phase == GoPhase.FINISHED && it.game.canOperate }
            assertEquals(0, calls.get())
            assertFalse(session.state.value.completionStored)
            assertFalse(session.state.value.completionReserved)
            assertTrue(session.state.value.canUndo)
            f.assertNoDerivedRows()
            assertTrue(onOwner { session.undo() })
            await(session) { it.game.canOperate && it.undoUsed == 1 && it.game.phase != GoPhase.FINISHED }
            assertEquals(0, calls.get())
            assertNotEquals(initial.matchId, session.state.value.matchId)
            val active = GoAiCheckpointRows.entry(f.db.goAiCheckpointDao().findActive(GameType.GO.code)!!)
            assertEquals(GoAiCheckpointOrigin.TERMINAL_CONTINUATION, active.lineage.origin)
            assertEquals(admitGoAiCheckpoint(initial).recordId, active.lineage.replacedTerminalRecordId)
            assertEquals(1, f.db.goAiCheckpointDao().listArchives().size)
        }
    }

    @Test fun formalSourceWithAutomaticParticipationStillStoresOrdinaryCompletionWithoutGrowth() = runBlocking {
        owned { f ->
            val initial = f.prepareFormal()
            f.checkpoints().save(GoAiSavedMatch(initial.matchId, initial.config, initial.copyBytes(), automaticUsed = true))
            val session = f.open(initial.config, f.sink())
            finish(session)
            await(session) { it.completionStored && !it.game.saving }
            assertTrue(session.state.value.automaticUsed)
            assertEquals(initial.matchId, session.state.value.matchId)
            assertEquals(1, f.db.gameRecordDao().listAll().size)
            assertEquals(1, f.db.goAiProjectionDao().listAll().size)
            assertEquals(1, f.db.goRankedDao().starts().size)
            assertTrue(f.db.goRankedDao().settlements().isEmpty())
            assertTrue(f.db.matchOutcomeDao().listAll().isEmpty())
        }
    }

    private suspend fun finish(session: GoAiMatchSession) {
        await(session) { it.game.canOperate && !it.game.loading }
        onOwner { session.setSpeed(4f); session.resume() }
        await(session) { it.humanCanPlay && it.game.currentPlayer == GoPlayer.WHITE }
        assertTrue(onOwner { session.humanAction(GoAction.Pass) })
        await(session) { it.game.phase == GoPhase.SCORING && it.game.canOperate }
        val revision = session.state.value.game.proposalRevision
        assertTrue(onOwner { session.humanAction(GoAction.ConfirmScore(GoPlayer.BLACK, revision)) })
        await(session) { it.game.blackConfirmed && it.game.canOperate }
        assertTrue(onOwner { session.humanAction(GoAction.ConfirmScore(GoPlayer.WHITE, revision)) })
    }

    private suspend fun awaitFailure(session: GoAiMatchSession) {
        await(session) { it.game.phase == GoPhase.FINISHED && it.completionReserved &&
            !it.completionStored && !it.game.saving && it.game.error != null }
    }
    private suspend fun await(session: GoAiMatchSession, predicate: (GoAiMatchUiState) -> Boolean) =
        try { withTimeout(20_000) { session.state.first(predicate) } }
        catch (timeout: TimeoutCancellationException) {
            throw AssertionError("Formal session did not reach expected state: ${session.state.value}", timeout)
        }
    private suspend fun <T> onOwner(block: suspend () -> T): T = withContext(Dispatchers.Main.immediate) { block() }
    private fun counting(delegate: GoAiCompletedMatchSink, calls: AtomicInteger) = GoAiCompletedMatchSink {
        calls.incrementAndGet()
        delegate.storeCompleted(it)
    }
    private fun fresh() = NativeGoEngine(9).use {
        GoAiSavedMatch(UUID.randomUUID().toString(), GoAiMatchConfig(9, humanPlayer = GoPlayer.WHITE), it.serialize())
    }

    private inner class Fixture {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val name = "go-formal-session-${UUID.randomUUID()}.db"
        var db = openDatabase()
            private set
        private val owners = linkedMapOf<GoAiMatchSession, Job>()
        private fun openDatabase() = Room.databaseBuilder(context, MocsDatabase::class.java, name).build()
        fun checkpoints() = GoAiCheckpointRepository(RoomGoAiCheckpointStore(db), clock = { 200L })
        fun sink() = RoomGoAiCompletionRepository(db)
        suspend fun prepareFormal(): GoAiSavedMatch {
            val learning = RoomGoLearningProgressRepository(db)
            learning.save(GoLearningProgress(GoLearningLibrary.lessons.map { it.id }.toSet()))
            val match = fresh()
            RoomGoRankedMatchCreator(db, checkpoints(), learning, clock = { 100L }).create(match)
            return match
        }
        suspend fun open(config: GoAiMatchConfig, sink: GoAiCompletedMatchSink,
            useConstructorDefault: Boolean = false): GoAiMatchSession = onOwner {
            val parent = SupervisorJob()
            val scope = CoroutineScope(parent + Dispatchers.Main.immediate)
            val script = GoAiSearchEngine {
                GoAiSearchOutcome.Move(GoAiSuggestion(GoAction.Pass, 1, 0.0, 0.5, 0.0))
            }
            val session = if (useConstructorDefault) {
                GoAiMatchSession(scope, checkpoints(), config, script, completedSink = sink)
            } else {
                GoAiMatchSession(scope, checkpoints(), config, script, completedSink = sink, completeHumanImmediately = true)
            }
            owners[session] = parent
            session
        }
        suspend fun closeSession(session: GoAiMatchSession) {
            try { onOwner { session.closeAndJoin() } }
            finally { owners.remove(session)?.cancelAndJoin() }
        }
        fun reopenDatabase() {
            check(owners.isEmpty()) { "Cannot close Room while a Session owns it" }
            db.close(); db = openDatabase()
        }
        suspend fun assertOneGrowth(matchId: String) {
            assertEquals(1, db.gameRecordDao().listAll().size)
            assertEquals(1, db.goAiProjectionDao().listAll().size)
            assertEquals(1, db.goAiCheckpointDao().listArchives().size)
            assertEquals(1, db.goRankedDao().settlements().size)
            val outcome = db.matchOutcomeDao().listAll().single().toMatchOutcome()!!
            val id = admitGoAiCheckpoint(checkpoints().load()!!).recordId
            assertEquals(matchId, checkpoints().load()!!.matchId)
            assertEquals(id, outcome.matchId)
            assertEquals(GameResult.SECOND_PLAYER_WIN, outcome.result)
            assertEquals(1, outcome.playerIndex)
            assertEquals(200L, outcome.settledAtEpochMillis)
            assertTrue(outcome.isWin)
            val stats = (RoomMatchStatisticsRepository(db).load() as LoadMatchStatisticsResult.Loaded).statistics
            assertEquals(1, stats.totalWins)
            assertEquals(1, stats.completedMatches)
        }
        suspend fun assertNoDerivedRows() {
            assertTrue(db.gameRecordDao().listAll().isEmpty())
            assertTrue(db.goAiProjectionDao().listAll().isEmpty())
            assertTrue(db.matchOutcomeDao().listAll().isEmpty())
            assertTrue(db.goRankedDao().settlements().isEmpty())
        }
        suspend fun close() {
            try { owners.keys.toList().forEach { closeSession(it) } }
            finally { db.close(); check(context.deleteDatabase(name)) }
        }
    }
    private suspend fun owned(block: suspend (Fixture) -> Unit) {
        val fixture = Fixture()
        try { block(fixture) } finally { fixture.close() }
    }
}
