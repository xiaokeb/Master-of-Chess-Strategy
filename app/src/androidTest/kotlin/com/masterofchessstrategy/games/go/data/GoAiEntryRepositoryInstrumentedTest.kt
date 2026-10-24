package com.masterofchessstrategy.games.go.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.games.go.*
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

/** Real rule engines and isolated disk Room; no NN search, installed app data or synthetic wins. */
class GoAiEntryRepositoryInstrumentedTest {
    @Test fun readAndFormalCreationUseThePersistedSpecificationWithoutUpgradingOnRead() = runBlocking {
        owned { f ->
            f.learnAll()
            val empty = f.entry().load()
            assertNull(empty.active)
            assertNull(empty.phase)
            assertFalse(empty.completionStored)
            assertTrue(empty.eligibility.condition(Difficulty.EASY).isUnlocked)
            val config = GoAiMatchConfig(13, humanPlayer = GoPlayer.WHITE)
            val saved = f.entry().create(config, empty.active)
            assertEquals(config, saved.config)
            assertEquals(saved.matchId, UUID.fromString(saved.matchId).toString())
            val loaded = f.entry().load()
            assertArrayEquals(bytes(saved), bytes(loaded.active!!))
            assertEquals(GoPhase.PLAYING, loaded.phase)
            assertFalse(loaded.completionStored)
            assertEquals(1, f.db.goRankedDao().starts().size)
            assertTrue(f.db.matchOutcomeDao().listAll().isEmpty())
        }
    }

    @Test fun sameIdentityWithChangedHistoryRequiresNewConfirmation() = runBlocking {
        owned { f ->
            f.learnAll()
            val old = f.entry().create(GoAiMatchConfig(9), null)
            val changed = NativeGoEngine(9).use {
                assertEquals(RestoreResult.Restored, it.restore(old.copyBytes()))
                assertEquals(ActionResult.Accepted, it.apply(GoAction.Pass))
                GoAiSavedMatch(old.matchId, old.config, it.serialize())
            }
            f.checkpoints.save(changed)
            denied { f.entry().create(GoAiMatchConfig(19), old, abandonOngoing = true) }
            denied { f.entry().create(GoAiMatchConfig(19), null, abandonOngoing = true) }
            assertArrayEquals(bytes(changed), bytes(f.checkpoints.load()!!))
            assertEquals(1, f.db.goRankedDao().starts().size)
        }
    }

    @Test fun bothPlayingAndScoringRequireExplicitAbandonment() = runBlocking {
        for (scoring in listOf(false, true)) owned { f ->
            f.learnAll()
            val old = f.initial(scoring = scoring)
            f.checkpoints.save(old)
            denied { f.entry().create(GoAiMatchConfig(13), old) }
            assertArrayEquals(bytes(old), bytes(f.checkpoints.load()!!))
            val next = f.entry().create(GoAiMatchConfig(13), old, abandonOngoing = true)
            assertNotEquals(old.matchId, next.matchId)
            assertEquals(13, next.config.boardSize)
            assertArrayEquals(bytes(next), bytes(f.checkpoints.load()!!))
            assertEquals(1, f.db.goRankedDao().starts().size)
            assertTrue(f.db.goAiCheckpointDao().listArchives().isEmpty())
        }
    }

    @Test fun lateFailureAndCancellationRestoreTheOldActiveAndCreationFacts() = runBlocking {
        owned { f ->
            f.learnAll()
            val old = f.entry().create(GoAiMatchConfig(9), null)
            val oldRow = f.db.goAiCheckpointDao().findActive(GameType.GO.code)!!
            val oldStart = f.db.goRankedDao().starts().single()
            val oldSeal = f.db.localDataMigrationDao().find(GoLegacyImportScopes.AI_MATCH)
            for (cancel in listOf(false, true)) {
                val failing = f.entry {
                    if (cancel) throw CancellationException("entry after replacement")
                    else throw IOException("entry after replacement")
                }
                try {
                    failing.create(GoAiMatchConfig(19), old, abandonOngoing = true)
                    fail("replacement failure hidden")
                } catch (error: Exception) {
                    assertTrue(if (cancel) error is CancellationException else error is IOException)
                }
                assertArrayEquals(oldRow.checkpoint, f.db.goAiCheckpointDao().findActive(GameType.GO.code)!!.checkpoint)
                assertEquals(oldRow.updatedAtEpochMillis, f.db.goAiCheckpointDao().findActive(GameType.GO.code)!!.updatedAtEpochMillis)
                val start = f.db.goRankedDao().starts().single()
                assertEquals(oldStart.recordId, start.recordId)
                assertArrayEquals(oldStart.initialCheckpoint, start.initialCheckpoint)
                assertEquals(oldSeal, f.db.localDataMigrationDao().find(GoLegacyImportScopes.AI_MATCH))
            }
        }
    }

    @Test fun firstCreateFailureAlsoRollsBackTheNewLegacyFence() = runBlocking {
        owned { f ->
            f.learnAll()
            assertNull(f.db.localDataMigrationDao().find(GoLegacyImportScopes.AI_MATCH))
            try { f.entry { throw IOException("after first save") }.create(GoAiMatchConfig(9), null); fail() }
            catch (_: IOException) { }
            assertNull(f.db.goAiCheckpointDao().findActive(GameType.GO.code))
            assertNull(f.db.localDataMigrationDao().find(GoLegacyImportScopes.AI_MATCH))
            assertTrue(f.db.goRankedDao().starts().isEmpty())
        }
    }

    @Test fun cancellingAnEnteredCreationJobRollsBackTheReplacement() = runBlocking {
        owned { f ->
            f.learnAll()
            val old = f.entry().create(GoAiMatchConfig(9), null)
            val entered = CompletableDeferred<Unit>()
            val pending = launch {
                f.entry { entered.complete(Unit); awaitCancellation() }
                    .create(GoAiMatchConfig(13), old, abandonOngoing = true)
            }
            try {
                withTimeout(15_000) { entered.await() }
            } finally {
                pending.cancelAndJoin()
            }
            assertArrayEquals(bytes(old), bytes(f.checkpoints.load()!!))
            assertEquals(1, f.db.goRankedDao().starts().size)
            assertTrue(f.db.goAiCheckpointDao().listArchives().isEmpty())
        }
    }

    @Test fun unfinishedTerminalCannotBeDiscardedEvenWithAbandonmentConfirmation() = runBlocking {
        owned { f ->
            f.learnAll()
            val done = f.initial(finished = true)
            f.checkpoints.save(done)
            assertFalse(f.entry().load().completionStored)
            denied { f.entry().create(GoAiMatchConfig(13), done, abandonOngoing = true) }
            val reserved = GoAiSavedMatch(done.matchId, done.config, done.copyBytes(), completionReserved = true)
            f.checkpoints.save(reserved)
            assertFalse(f.entry().load().completionStored)
            denied { f.entry().create(GoAiMatchConfig(13), reserved, abandonOngoing = true) }
            RoomGoAiCompletionRepository(f.db).storeCompleted(reserved)
            assertTrue(f.entry().load().completionStored)
            f.entry().create(GoAiMatchConfig(13), reserved)
            assertEquals(1, f.db.goAiCheckpointDao().listArchives().size)
            assertEquals(1, f.db.gameRecordDao().listAll().size)
            assertEquals(1, f.db.goRankedDao().starts().size)
            assertTrue(f.db.matchOutcomeDao().listAll().isEmpty()) // Old terminal never gets a new formal origin.
        }
    }

    @Test fun automaticAndHandicapCreationStayUnrankedButKeepAllUnlockGates() = runBlocking {
        val configs = listOf(GoAiMatchConfig(9, humanPlayer = null), GoAiMatchConfig(9, handicapStones = 2))
        for (config in configs) owned { f ->
            denied { f.entry().create(config, null) }
            assertNull(f.db.goAiCheckpointDao().findActive(GameType.GO.code))
            f.learnAll()
            denied { f.entry().create(config.copy(difficulty = GoAiDifficulty.MEDIUM), null) }
            val saved = f.entry().create(config, null)
            assertEquals(config, saved.config)
            assertArrayEquals(bytes(saved), bytes(f.checkpoints.load()!!))
            assertTrue(f.db.goRankedDao().starts().isEmpty())
            assertTrue(f.db.matchOutcomeDao().listAll().isEmpty())
        }
    }

    @Test fun readingLegacyMatchPreservesItsUnknownOriginInsteadOfMintingFormalCreation() = runBlocking {
        owned { f ->
            f.learnAll()
            val old = f.initial()
            val legacyCheckpoints = GoAiCheckpointRepository(RoomGoAiCheckpointStore(f.db), GoAiLegacySource { old })
            val entry = RoomGoAiEntryRepository(f.db, legacyCheckpoints, f.learning)
            val snapshot = entry.load()
            assertArrayEquals(bytes(old), bytes(snapshot.active!!))
            assertEquals(GoAiCheckpointOrigin.LEGACY_UNVERIFIED,
                GoAiCheckpointRows.entry(f.db.goAiCheckpointDao().findActive(GameType.GO.code)!!).lineage.origin)
            assertTrue(f.db.goRankedDao().starts().isEmpty())
        }
    }

    @Test fun capturedEpochRejectsBothReadAndCreateAfterReplacement() = runBlocking {
        owned { f ->
            f.learnAll()
            val stale = f.entry()
            f.gate.backup(replacing = true) { }
            assertFalse(stale.isCurrent)
            try { stale.load(); fail("stale read") } catch (_: IOException) { }
            try { stale.create(GoAiMatchConfig(9), null); fail("stale create") } catch (_: IOException) { }
            assertNull(f.db.goAiCheckpointDao().findActive(GameType.GO.code))
            assertTrue(f.entry().isCurrent)
            f.entry().create(GoAiMatchConfig(9), null)
            assertEquals(1, f.db.goRankedDao().starts().size)
        }
    }

    @Test fun failedConsistentReadRollsBackItsLegacyMigrationAndFence() = runBlocking {
        owned { f ->
            val old = f.initial()
            val legacyCheckpoints = GoAiCheckpointRepository(RoomGoAiCheckpointStore(f.db), GoAiLegacySource { old })
            val failingLearning = object : GoLearningProgressRepository {
                override suspend fun load(): GoLearningProgress = throw IOException("learning read failed")
                override suspend fun save(progress: GoLearningProgress) = error("read-only fixture")
            }
            val entry = RoomGoAiEntryRepository(f.db, legacyCheckpoints, failingLearning)
            try { entry.load(); fail("inconsistent snapshot published") } catch (_: IOException) { }
            assertNull(f.db.goAiCheckpointDao().findActive(GameType.GO.code))
            assertNull(f.db.localDataMigrationDao().find(GoLegacyImportScopes.AI_MATCH))
            assertTrue(f.db.goRankedDao().starts().isEmpty())
        }
    }

    private fun bytes(match: GoAiSavedMatch) = GoAiMatchFileCodec.encode(match)
    private suspend fun denied(block: suspend () -> Unit) {
        try { block(); fail("unsafe entry accepted") } catch (_: IllegalArgumentException) { }
    }
    private inner class Fixture {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val name = "go-ai-entry-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, MocsDatabase::class.java, name).build()
        val gate = GoStorageEpochGate.forDatabase(db)
        val checkpoints = GoAiCheckpointRepository(RoomGoAiCheckpointStore(db), GoAiLegacySource { null })
        val learning = RoomGoLearningProgressRepository(db)
        fun entry(beforeCommit: suspend () -> Unit = {}) = RoomGoAiEntryRepository(db, checkpoints, learning,
            gate, beforeCommit = beforeCommit)
        suspend fun learnAll() = learning.save(GoLearningProgress(GoLearningLibrary.lessons.map { it.id }.toSet()))
        fun initial(scoring: Boolean = false, finished: Boolean = false): GoAiSavedMatch = NativeGoEngine(9).use {
            if (scoring || finished) {
                check(it.apply(GoAction.Pass) == ActionResult.Accepted)
                check(it.apply(GoAction.Pass) == ActionResult.Accepted)
            }
            if (finished) {
                val revision = it.scoringState().proposalRevision
                check(it.apply(GoAction.ConfirmScore(GoPlayer.BLACK, revision)) == ActionResult.Accepted)
                check(it.apply(GoAction.ConfirmScore(GoPlayer.WHITE, revision)) == ActionResult.Accepted)
            }
            GoAiSavedMatch(UUID.randomUUID().toString(), GoAiMatchConfig(9), it.serialize())
        }
        fun close() { db.close(); if (context.getDatabasePath(name).exists()) check(context.deleteDatabase(name)) }
    }
    private suspend fun owned(block: suspend (Fixture) -> Unit) {
        val fixture = Fixture()
        try { block(fixture) } finally { fixture.close() }
    }
}
