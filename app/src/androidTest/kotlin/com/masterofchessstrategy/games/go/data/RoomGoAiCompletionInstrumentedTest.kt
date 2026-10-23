package com.masterofchessstrategy.games.go.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.games.go.*
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Real Native admission and UUID disk Room only; never touches the installation database. */
class RoomGoAiCompletionInstrumentedTest {
    @Test fun reservedCompletionIsIdempotentAndFavoriteSurvivesActualDatabaseReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "go-completion-reopen-${UUID.randomUUID()}.db"
        var database = Room.databaseBuilder(context, MocsDatabase::class.java, name).build()
        try {
            val match = terminal("first-record")
            persistReserved(database, match)
            val sink = RoomGoAiCompletionRepository(database)
            sink.storeCompleted(reserved(match))
            val id = admitGoAiCheckpoint(match).recordId
            assertEquals(1, database.gameRecordDao().setFavorite(id, true))
            sink.storeCompleted(reserved(match))
            database.close()
            database = Room.databaseBuilder(context, MocsDatabase::class.java, name).build()
            RoomGoAiCompletionRepository(database).storeCompleted(reserved(match))
            val record = database.gameRecordDao().listAll().single()
            assertEquals(id, record.recordId)
            assertTrue(record.isFavorite)
            assertEquals(100L, record.completedAtEpochMillis)
            assertArrayEquals(match.copyBytes(), record.engineState)
            assertEquals(3, record.moveCount) // Place + two passes; confirmations are not moves.
            assertEquals(1, database.goAiCheckpointDao().listArchives().size)
            assertEquals(listOf(GoAiProjectionEntity(id)), database.goAiProjectionDao().listAll())
            assertNoRewards(database)
        } finally {
            database.close()
            check(context.deleteDatabase(name))
        }
    }

    @Test fun callerReservationCannotInventTheDurableArchiveOrDatabaseReservation() = runBlocking {
        withOwned { database ->
            val match = terminal("unreserved")
            val sink = RoomGoAiCompletionRepository(database)
            expectIllegal { sink.storeCompleted(reserved(match)) }
            val repository = checkpointRepository(database)
            repository.save(match)
            val original = database.goAiCheckpointDao().findActive(GameType.GO.code)!!.checkpoint.copyOf()
            expectIllegal { sink.storeCompleted(match) }
            expectIllegal { sink.storeCompleted(reserved(match)) }
            assertTrue(database.gameRecordDao().listAll().isEmpty())
            assertArrayEquals(original, database.goAiCheckpointDao().findActive(GameType.GO.code)!!.checkpoint)
            assertNoRewards(database)
        }
    }

    @Test fun lateFailureRollsBackProjectionAndKeepsFixedResultForRetry() = runBlocking {
        withOwned { database ->
            val match = terminal("late-failure")
            persistReserved(database, match)
            val active = database.goAiCheckpointDao().findActive(GameType.GO.code)!!.checkpoint.copyOf()
            val failing = RoomGoAiCompletionRepository(database, beforeCommit = { throw IOException("owned late failure") })
            try { failing.storeCompleted(reserved(match)); fail("late error hidden") } catch (_: IOException) { }
            assertTrue(database.gameRecordDao().listAll().isEmpty())
            assertTrue(database.goAiProjectionDao().listAll().isEmpty())
            assertArrayEquals(active, database.goAiCheckpointDao().findActive(GameType.GO.code)!!.checkpoint)
            RoomGoAiCompletionRepository(database).storeCompleted(reserved(match))
            assertEquals(1, database.gameRecordDao().listAll().size)
            assertNoRewards(database)
        }
    }

    @Test fun cancellationAtTheTransactionEdgeRollsBackAndConnectionCanRetry() = runBlocking {
        withOwned { database ->
            val match = terminal("cancelled-completion")
            persistReserved(database, match)
            val cancelled = RoomGoAiCompletionRepository(database, beforeCommit = { throw CancellationException("owned cancellation") })
            try { cancelled.storeCompleted(reserved(match)); fail("cancellation hidden") } catch (_: CancellationException) { }
            assertTrue(database.gameRecordDao().listAll().isEmpty())
            assertTrue(database.goAiProjectionDao().listAll().isEmpty())
            assertEquals(1, database.goAiCheckpointDao().listArchives().size)
            RoomGoAiCompletionRepository(database).storeCompleted(reserved(match))
            assertEquals(1, database.gameRecordDao().listAll().size)
            assertNoRewards(database)
        }
    }

    @Test fun sameIdentityDifferentHistoryOrFirstTimeIsRejectedWithoutOverwrite() = runBlocking {
        withOwned { database ->
            val match = terminal("conflicting-record")
            persistReserved(database, match)
            val sink = RoomGoAiCompletionRepository(database)
            expectIllegal { sink.storeCompleted(reserved(terminal(match.matchId, column = 1))) }
            assertTrue(database.gameRecordDao().listAll().isEmpty())
            val archive = GoAiCheckpointRows.archive(database.goAiCheckpointDao().listArchives().single())
            val conflict = projectGoAiRecord(archive).copy(completedAtEpochMillis = 101L, isFavorite = true)
            database.gameRecordDao().insert(conflict.toGameRecordEntity())
            expectIllegal { sink.storeCompleted(reserved(match)) }
            assertEquals(conflict.toGameRecordEntity(), database.gameRecordDao().listAll().single())
            assertNoRewards(database)
        }
    }

    @Test fun staleSinkCannotWriteOrBackfillAfterEpochChangeButFreshSinkCan() = runBlocking {
        withOwned { database ->
            val match = terminal("epoch-completion")
            persistReserved(database, match)
            val gate = GoStorageEpochGate.forDatabase(database)
            val stale = RoomGoAiCompletionRepository(database, gate)
            gate.backup(replacing = true) { } // Gate contract only, not an actual restore test.
            try { stale.storeCompleted(reserved(match)); fail("stale sink wrote") } catch (_: IOException) { }
            try { stale.projectExistingArchives(); fail("stale backfill wrote") } catch (_: IOException) { }
            assertTrue(database.gameRecordDao().listAll().isEmpty())
            RoomGoAiCompletionRepository(database, gate).storeCompleted(reserved(match))
            assertEquals(1, database.gameRecordDao().listAll().size)
            assertNoRewards(database)
        }
    }

    @Test fun explicitBackfillIsAtomicAndRetainsUnreservedHistoricalSourcesWithoutRewards() = runBlocking {
        withOwned { database ->
            val first = terminal("historical-first")
            val second = terminal("historical-second", column = 1)
            val repository = checkpointRepository(database)
            repository.save(first)
            repository.clear()
            repository.save(second)
            repository.clear()
            val original = database.goAiCheckpointDao().listArchives().map { it.checkpoint.copyOf() }
            val failing = RoomGoAiCompletionRepository(database, beforeCommit = { throw IOException("owned backfill failure") })
            try { failing.projectExistingArchives(); fail("partial backfill accepted") } catch (_: IOException) { }
            assertTrue(database.gameRecordDao().listAll().isEmpty())
            val sink = RoomGoAiCompletionRepository(database)
            assertEquals(2, sink.projectExistingArchives())
            assertEquals(0, sink.projectExistingArchives())
            assertNull(database.goAiCheckpointDao().findActive(GameType.GO.code))
            database.goAiCheckpointDao().listArchives().forEachIndexed { index, row ->
                assertArrayEquals(original[index], row.checkpoint)
            }
            assertTrue(database.gameRecordDao().listAll().all { it.completedAtEpochMillis == 100L })
            assertNoRewards(database)
        }
    }

    @Test fun cyclicRawAncestryRejectsFirstCompletionAndBackfillWithoutAnyProjection() = runBlocking {
        withOwned { database ->
            val first = reserved(terminal("cycle-first"))
            val second = terminal("cycle-second", column = 1)
            val firstId = admitGoAiCheckpoint(first).recordId
            val secondId = admitGoAiCheckpoint(second).recordId
            val firstEntry = GoAiCheckpointEntry(GoAiMatchFileCodec.encode(first),
                GoAiCheckpointLineage(GoAiCheckpointOrigin.TERMINAL_CONTINUATION, secondId))
            val secondEntry = GoAiCheckpointEntry(GoAiMatchFileCodec.encode(second),
                GoAiCheckpointLineage(GoAiCheckpointOrigin.TERMINAL_CONTINUATION, firstId))
            RoomGoAiCheckpointStore(database).transaction {
                insertArchive(GoAiCheckpointArchive(firstId, firstEntry, 100))
                insertArchive(GoAiCheckpointArchive(secondId, secondEntry, 100))
                writeActive(firstEntry, 100)
                markLegacyHandled()
            }
            val sink = RoomGoAiCompletionRepository(database)
            expectIllegal { sink.storeCompleted(first) }
            expectIllegal { sink.projectExistingArchives() }
            assertTrue(database.gameRecordDao().listAll().isEmpty())
            assertEquals(2, database.goAiCheckpointDao().listArchives().size)
            assertNoRewards(database)
        }
    }

    private fun checkpointRepository(database: MocsDatabase) =
        GoAiCheckpointRepository(RoomGoAiCheckpointStore(database), clock = { 100L })

    private suspend fun persistReserved(database: MocsDatabase, match: GoAiSavedMatch) {
        checkpointRepository(database).apply { save(match); save(reserved(match)) }
    }

    private fun terminal(id: String, column: Int = 0): GoAiSavedMatch = NativeGoEngine(9).use { engine ->
        assertEquals(ActionResult.Accepted, engine.apply(GoAction.Place(BoardPosition(column, 0))))
        repeat(2) { assertEquals(ActionResult.Accepted, engine.apply(GoAction.Pass)) }
        val revision = engine.scoringState().proposalRevision
        assertEquals(ActionResult.Accepted, engine.apply(GoAction.ConfirmScore(GoPlayer.BLACK, revision)))
        assertEquals(ActionResult.Accepted, engine.apply(GoAction.ConfirmScore(GoPlayer.WHITE, revision)))
        GoAiSavedMatch(id, GoAiMatchConfig(9), engine.serialize())
    }

    private fun reserved(match: GoAiSavedMatch) = GoAiSavedMatch(match.matchId, match.config, match.copyBytes(),
        match.undoUsed, match.hintUsed, true, match.rememberedHuman, match.automaticUsed,
        match.copyOpeningBytes(), match.autoContinueEnabled, match.autoContinueMaxGames, match.autoContinueGameNumber)

    private suspend fun expectIllegal(block: suspend () -> Unit) {
        try { block(); fail("invalid completion accepted") } catch (_: IllegalArgumentException) { }
    }

    private suspend fun assertNoRewards(database: MocsDatabase) {
        assertTrue(database.matchOutcomeDao().listAll().isEmpty())
        assertTrue(database.goRecordSourceDao().listAll().isEmpty())
    }

    private suspend fun withOwned(block: suspend (MocsDatabase) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "go-completion-${UUID.randomUUID()}.db"
        val database = Room.databaseBuilder(context, MocsDatabase::class.java, name).build()
        try { block(database) } finally {
            database.close()
            check(context.deleteDatabase(name))
        }
    }
}
