package com.masterofchessstrategy.games.go.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.games.go.*
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Production bundle creation facade + actual Native/Room Session; no model search or app reset. */
class GoFormalCreationSessionInstrumentedTest {
    @Test fun formalSessionCreatesSourceBeforeBecomingReadyAndReopeningDoesNotCreateAnother() = runBlocking {
        owned { f ->
            f.learnAll()
            val first = f.open()
            await(first) { it.game.sessionReady && !it.game.loading }
            val match = f.bundle.aiRepository().load()!!
            assertEquals(first.state.value.matchId, match.matchId)
            assertEquals(1, f.db.goRankedDao().starts().size)
            assertTrue(f.bundle.formalAiRepository().confirmsStandardCreation(match))
            f.close(first)
            val second = f.open()
            await(second) { it.game.sessionReady && !it.game.loading }
            assertEquals(match.matchId, second.state.value.matchId)
            assertEquals(1, f.db.goRankedDao().starts().size)
            assertTrue(f.db.matchOutcomeDao().listAll().isEmpty())
        }
    }

    @Test fun unfinishedTutorialDoesNotSilentlyStartAnUnrankedFallbackGame() = runBlocking {
        owned { f ->
            val session = f.open()
            await(session) { !it.game.loading && it.game.error != null }
            assertFalse(session.state.value.game.sessionReady)
            assertNull(f.bundle.aiRepository().load())
            assertTrue(f.db.goRankedDao().starts().isEmpty())
        }
    }

    @Test fun existingUnknownCheckpointIsResumedWithoutMintingFormalCreation() = runBlocking {
        owned { f ->
            f.learnAll()
            val old = NativeGoEngine(9).use { GoAiSavedMatch(UUID.randomUUID().toString(), GoAiMatchConfig(9), it.serialize()) }
            f.bundle.aiRepository().save(old)
            val session = f.open()
            await(session) { it.game.sessionReady }
            assertEquals(old.matchId, session.state.value.matchId)
            assertFalse(f.bundle.formalAiRepository().confirmsStandardCreation(old))
            assertTrue(f.db.goRankedDao().starts().isEmpty())
        }
    }

    @Test fun formalRestartArchivesOldResultThenCreatesANewSourceWithANewIdentity() = runBlocking {
        owned { f ->
            f.learnAll()
            val session = f.open(GoAiMatchConfig(9, humanPlayer = GoPlayer.WHITE))
            await(session) { it.game.sessionReady }
            val firstId = session.state.value.matchId
            onOwner { session.resume() }
            await(session) { it.humanCanPlay && it.game.currentPlayer == GoPlayer.WHITE }
            assertTrue(onOwner { session.humanAction(GoAction.Pass) })
            await(session) { it.canReviewScore }
            assertTrue(onOwner { session.humanAction(GoAction.ConfirmScore(GoPlayer.BLACK, session.state.value.game.proposalRevision)) })
            await(session) { it.canReviewScore && it.game.blackConfirmed }
            assertTrue(onOwner { session.humanAction(GoAction.ConfirmScore(GoPlayer.WHITE, session.state.value.game.proposalRevision)) })
            await(session) { it.completionStored }
            assertTrue(onOwner { session.restart() })
            await(session) { it.game.sessionReady && it.game.phase == GoPhase.PLAYING && it.matchId != firstId }
            assertEquals(0, session.state.value.eventCount)
            assertEquals(2, f.db.goRankedDao().starts().size)
            assertEquals(1, f.db.goRankedDao().settlements().size)
            assertEquals(1, f.db.matchOutcomeDao().listAll().size)
            assertEquals(1, f.db.gameRecordDao().listAll().size)
            assertTrue(f.bundle.formalAiRepository().confirmsStandardCreation(f.bundle.aiRepository().load()!!))
        }
    }

    @Test fun oneCapturedEpochAppliesToLoadSaveCreateAndCreationConfirmation() = runBlocking {
        owned { f ->
            f.learnAll()
            val stale = f.bundle.formalAiRepository()
            val initial = NativeGoEngine(9).use { GoAiSavedMatch(UUID.randomUUID().toString(), GoAiMatchConfig(9), it.serialize()) }
            f.bundle.gate.backup(replacing = true) { } // Epoch contract; actual restore covered by data node.
            for (operation in listOf<suspend () -> Unit>(
                { stale.load(); Unit }, { stale.save(initial) }, { stale.createStandardMatch(initial) },
                { stale.confirmsStandardCreation(initial); Unit },
            )) {
                try { operation(); fail("stale formal owner survived replacement") } catch (_: IOException) { }
            }
            assertTrue(f.db.goRankedDao().starts().isEmpty())
            val fresh = f.bundle.formalAiRepository()
            fresh.createStandardMatch(initial)
            assertTrue(fresh.confirmsStandardCreation(initial))
        }
    }

    private suspend fun await(session: GoAiMatchSession, ready: (GoAiMatchUiState) -> Boolean) {
        withTimeout(15_000) { session.state.first(ready) }
    }
    private suspend fun <T> onOwner(block: suspend () -> T): T = withContext(Dispatchers.Main.immediate) { block() }
    private inner class Fixture {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val name = "go-formal-create-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, MocsDatabase::class.java, name).build()
        val bundle = GoCompleteStorageBundle(db, GoLocalLegacySource { null },
            GoLearningLegacySource { GoLearningProgress() }, GoEndgameLegacySource { GoEndgameProgress() }, GoAiLegacySource { null })
        private val owners = linkedMapOf<GoAiMatchSession, Job>()
        suspend fun learnAll() = RoomGoLearningProgressRepository(db).save(
            GoLearningProgress(GoLearningLibrary.lessons.map { it.id }.toSet()))
        suspend fun open(config: GoAiMatchConfig = GoAiMatchConfig(9)) = onOwner {
            val job = SupervisorJob()
            GoAiMatchSession(CoroutineScope(job + Dispatchers.Main.immediate), bundle.formalAiRepository(), config,
                GoAiSearchEngine { GoAiSearchOutcome.Move(GoAiSuggestion(GoAction.Pass, 1, 0.0, 0.5, 0.0)) },
                completedSink = bundle.completedMatchSink(), completeHumanImmediately = true).also { owners[it] = job }
        }
        suspend fun close(session: GoAiMatchSession) {
            try { onOwner { session.closeAndJoin() } } finally { owners.remove(session)?.cancelAndJoin() }
        }
        suspend fun closeAll() {
            try { owners.keys.toList().forEach { close(it) } }
            finally { db.close(); if (context.getDatabasePath(name).exists()) check(context.deleteDatabase(name)) }
        }
    }
    private suspend fun owned(block: suspend (Fixture) -> Unit) {
        val fixture = Fixture()
        try { block(fixture) } finally { fixture.closeAll() }
    }
}
