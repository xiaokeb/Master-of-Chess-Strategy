package com.masterofchessstrategy.games.go.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.GoLegacyImportScopes
import com.masterofchessstrategy.data.LocalDataMigrationEntity
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.games.go.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.UUID

/** Real Room/Native admission. This stage deliberately writes neither ranking nor shared records. */
@RunWith(AndroidJUnit4::class)
class RoomGoAiCheckpointStoreInstrumentedTest {
    @Test fun diskReopenKeepsFirstArchiveParentControlsAndPermanentClearWithoutReimport() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "go-ai-disk-${UUID.randomUUID()}.db"
        fun open() = Room.databaseBuilder(context, MocsDatabase::class.java, name).build()
        var database: MocsDatabase? = null
        val parent = match("disk-parent", terminal = true, automatic = true, series = true)
        val parentId = admitGoAiCheckpoint(parent).recordId
        val child = match("disk-child")
        var legacyReads = 0
        val forbiddenLegacy = GoAiLegacySource { legacyReads++; error("Sealed legacy source was read again") }
        try {
            var now = 17L
            var current = open().also { database = it }
            var repository = repository(current, clock = { now })
            repository.save(parent)
            now = 81
            repository.save(controls(parent, reserved = true, series = false))
            now = 82
            repository.save(child)
            current.close()
            database = null

            current = open().also { database = it }
            repository = repository(current, forbiddenLegacy, clock = { 99L })
            assertArrayEquals(image(child), image(repository.load()!!))
            val active = current.goAiCheckpointDao().findActive(GameType.GO.code)!!
            assertEquals(3, active.originCode)
            assertEquals(parentId, active.replacedTerminalRecordId)
            assertEquals(82L, active.updatedAtEpochMillis)
            val first = current.goAiCheckpointDao().findArchive(parentId)!!
            assertArrayEquals(image(parent), first.checkpoint)
            assertEquals(17L, first.completedAtEpochMillis)
            assertEquals(1, current.goAiCheckpointDao().listArchives().size)
            assertNotNull(current.localDataMigrationDao().find(GoLegacyImportScopes.AI_MATCH))
            assertNoRewards(current)
            repository.clear()
            current.close()
            database = null

            current = open().also { database = it }
            assertNull(repository(current, forbiddenLegacy).load())
            assertEquals(0, legacyReads)
            assertEquals(1, current.goAiCheckpointDao().listArchives().size)
            assertArrayEquals(image(parent), current.goAiCheckpointDao().findArchive(parentId)!!.checkpoint)
            assertEquals(17L, current.goAiCheckpointDao().findArchive(parentId)!!.completedAtEpochMillis)
            current.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToNext()) }
            assertNoRewards(current)
        } finally {
            database?.close()
            assertTrue(context.deleteDatabase(name)) // Only this test's UUID-owned database.
        }
    }

    @Test fun fullPolicyCheckpointAndEveryReadAreOwnedAndNoRankOrRecordIsWritten() = runBlocking {
        withDatabase { database ->
            val saved = match("policy", automatic = true, series = true)
            val repository = repository(database)
            repository.save(saved)
            val row = database.goAiCheckpointDao().findActive(GameType.GO.code)!!
            assertEquals(2, row.originCode)
            assertEquals(6, row.envelopeVersion)
            assertArrayEquals(image(saved), row.checkpoint)
            row.checkpoint.fill(0)
            repository.load()!!.copyBytes().fill(0)
            assertArrayEquals(image(saved), image(repository.load()!!))
            assertTrue(database.goAiCheckpointDao().listArchives().isEmpty())
            assertNotNull(database.localDataMigrationDao().find(GoLegacyImportScopes.AI_MATCH))
            assertNoRewards(database)
        }
    }

    @Test fun repeatedReservationAndSeriesEndPreserveFirstFullTerminalEnvelopeAndTime() = runBlocking {
        withDatabase { database ->
            var now = 17L
            val repository = repository(database, clock = { now })
            val original = match("terminal", terminal = true, automatic = true, series = true)
            val id = admitGoAiCheckpoint(original).recordId
            repository.save(original)
            now = 81
            val reserved = controls(original, reserved = true, series = true)
            repository.save(reserved)
            repository.save(controls(original, reserved = true, series = false))
            val archive = database.goAiCheckpointDao().findArchive(id)!!
            assertArrayEquals(image(original), archive.checkpoint)
            assertEquals(17L, archive.completedAtEpochMillis)
            assertEquals(1, database.goAiCheckpointDao().listArchives().size)
            val active = repository.load()!!
            assertTrue(active.completionReserved)
            assertFalse(active.autoContinueEnabled)
            assertEquals(1, active.autoContinueMaxGames)
            rejects { repository.save(reserved) } // A stopped series cannot be re-enabled.
            assertArrayEquals(image(original), database.goAiCheckpointDao().findArchive(id)!!.checkpoint)
            assertNoRewards(database)
        }
    }

    @Test fun terminalReplacementKeepsParentLineageAndClearNeverDeletesEitherArchive() = runBlocking {
        withDatabase { database ->
            val repository = repository(database)
            val parent = match("parent", terminal = true)
            val parentId = admitGoAiCheckpoint(parent).recordId
            repository.save(parent)
            repository.save(match("child"))
            var child = database.goAiCheckpointDao().findActive(GameType.GO.code)!!
            assertEquals(3, child.originCode)
            assertEquals(parentId, child.replacedTerminalRecordId)
            repository.save(match("child", terminal = true))
            child = database.goAiCheckpointDao().findActive(GameType.GO.code)!!
            assertEquals(parentId, child.replacedTerminalRecordId)
            repository.clear()
            assertNull(repository.load())
            assertEquals(2, database.goAiCheckpointDao().listArchives().size)
            assertArrayEquals(image(parent), database.goAiCheckpointDao().findArchive(parentId)!!.checkpoint)
            assertNoRewards(database)
        }
    }

    @Test fun firstDirectSaveImportsOldTerminalOnceAndDoesNotUpgradeItsSource() = runBlocking {
        withDatabase { database ->
            val old = match("legacy-parent", terminal = true, automatic = true)
            var reads = 0
            val repository = repository(database, GoAiLegacySource { reads++; old })
            repository.save(match("fresh"))
            val archive = database.goAiCheckpointDao().findArchive(admitGoAiCheckpoint(old).recordId)!!
            assertEquals(1, archive.originCode)
            assertArrayEquals(image(old), archive.checkpoint)
            assertEquals("fresh", repository.load()!!.matchId)
            repository.clear()
            assertNull(repository.load())
            assertEquals(1, reads)
            assertNoRewards(database)
        }
    }

    @Test fun failuresAfterEachLegacyAndReplacementWriteRollBackTheEntireRealTransaction() = runBlocking {
        for (stage in listOf("archive", "active1", "marker1", "active2", "marker2")) {
            for (cancel in listOf(false, true)) withDatabase { database ->
                val old = match("old", terminal = true)
                val problem = if (cancel) CancellationException("after-$stage") else IOException("after-$stage")
                val faulty = faultAfter(RoomGoAiCheckpointStore(database), stage, problem)
                try {
                    repository(database, GoAiLegacySource { old }, faulty).save(match("new"))
                    fail("Fault was not propagated: $stage")
                } catch (actual: Exception) {
                    assertEquals(problem.javaClass, actual.javaClass)
                    assertEquals(problem.message, actual.message)
                }
                assertTrue(database.goAiCheckpointDao().listActive().isEmpty())
                assertTrue(database.goAiCheckpointDao().listArchives().isEmpty())
                assertNull(database.localDataMigrationDao().find(GoLegacyImportScopes.AI_MATCH))
                assertNoRewards(database)
            }
        }
    }

    @Test fun actualJobCancellationAfterAllWritesRollsBackArchiveActiveAndSeal() = runBlocking {
        withDatabase { database ->
            val store = RoomGoAiCheckpointStore(database)
            val saved = match("cancelled", terminal = true)
            val archive = archive(saved)
            val wrote = CompletableDeferred<Unit>()
            val writing = launch(Dispatchers.Default) {
                store.transaction {
                    insertArchive(archive)
                    writeActive(archive.checkpoint, 23)
                    markLegacyHandled()
                    wrote.complete(Unit)
                    awaitCancellation()
                }
            }
            try {
                withTimeout(15_000) { wrote.await() }
            } finally {
                // A failed rendezvous must not leave a live transaction while the DB is closing.
                withContext(NonCancellable) { withTimeout(15_000) { writing.cancelAndJoin() } }
            }
            assertTrue(writing.isCancelled && writing.isCompleted)
            assertTrue(database.goAiCheckpointDao().listActive().isEmpty())
            assertTrue(database.goAiCheckpointDao().listArchives().isEmpty())
            assertNull(database.localDataMigrationDao().find(GoLegacyImportScopes.AI_MATCH))
        }
    }

    @Test fun archiveInsertAbortsRatherThanReplacingFirstEnvelopeOrCompletionTime() = runBlocking {
        withDatabase { database ->
            val store = RoomGoAiCheckpointStore(database)
            val first = archive(match("insert-only", terminal = true))
            store.transaction { insertArchive(first) }
            try {
                store.transaction { insertArchive(GoAiCheckpointArchive(first.recordId, first.checkpoint, 999)) }
                fail("Duplicate archive replaced its first row")
            } catch (_: SQLiteConstraintException) { }
            val restored = store.transaction { findArchive(first.recordId)!! }
            assertEquals(first.completedAtEpochMillis, restored.completedAtEpochMillis)
            assertArrayEquals(first.checkpoint.copyBytes(), restored.checkpoint.copyBytes())
        }
    }

    @Test fun deferredParentReferencesPermitChildFirstButRejectMissingParentAndPreserveParentsOnClear() = runBlocking {
        withDatabase { database ->
            val parent = archive(match("fk-parent", terminal = true))
            val child = archive(match("fk-child", terminal = true), GoAiCheckpointLineage(
                GoAiCheckpointOrigin.TERMINAL_CONTINUATION, parent.recordId))
            val dao = database.goAiCheckpointDao()
            // Child-first insertion would fail here if either relationship were immediate.
            database.withTransaction {
                dao.insertArchive(GoAiCheckpointRows.archive(child))
                dao.saveActive(GoAiCheckpointRows.active(child.checkpoint, 27))
                dao.insertArchive(GoAiCheckpointRows.archive(parent))
            }
            dao.clearActive(GameType.GO.code)
            assertEquals(2, dao.listArchives().size)
            val orphan = archive(match("orphan", terminal = true), GoAiCheckpointLineage(
                GoAiCheckpointOrigin.TERMINAL_CONTINUATION, "f".repeat(64)))
            val store = RoomGoAiCheckpointStore(database)
            try {
                store.transaction { insertArchive(orphan) }
                fail("Orphan parent reference committed")
            } catch (_: SQLiteConstraintException) { }
            assertNull(dao.findArchive(orphan.recordId))
            try {
                store.transaction { writeActive(orphan.checkpoint, 28) }
                fail("Active orphan parent reference committed")
            } catch (_: SQLiteConstraintException) { }
            assertTrue(dao.listActive().isEmpty())
            database.withTransaction {
                dao.saveActive(GoAiCheckpointRows.active(child.checkpoint, 28))
                dao.deleteAllActive()
                dao.deleteAllArchives()
            }
            assertTrue(dao.listArchives().isEmpty())
        }
    }

    @Test fun malformedStoredMetadataAndInvalidSealsFailClosedWithoutSilentlyOverwritingRows() = runBlocking {
        withDatabase { database ->
            val dao = database.goAiCheckpointDao()
            val entry = GoAiCheckpointEntry(image(match("bad-row")), GoAiCheckpointLineage(GoAiCheckpointOrigin.UNVERIFIED_SESSION))
            dao.saveActive(GoAiCheckpointRows.active(entry, 1).copy(originCode = 99))
            rejects { repository(database).load() }
            assertEquals(99, dao.findActive(GameType.GO.code)!!.originCode)
            dao.deleteAllActive()
            database.localDataMigrationDao().insert(LocalDataMigrationEntity(GoLegacyImportScopes.AI_MATCH, 0))
            rejects { repository(database).save(match("bad-marker")) }
            assertTrue(dao.listActive().isEmpty())
            assertEquals(0, database.localDataMigrationDao().find(GoLegacyImportScopes.AI_MATCH)!!.handledVersion)
        }
    }

    @Test fun higherInstallationSealIsPreservedAndCompleteEnvelopeStorageDoesNotTruncate() = runBlocking {
        withDatabase { database ->
            val store = RoomGoAiCheckpointStore(database)
            database.localDataMigrationDao().insert(LocalDataMigrationEntity(GoLegacyImportScopes.AI_MATCH, 7))
            // Storage capacity only: arbitrary payloads here are intentionally not Native-admitted.
            val full = GoAiSavedMatch("a".repeat(128), GoAiMatchConfig(19), ByteArray(1_000_000) { 1 },
                openingBytes = ByteArray(1_000_000) { 2 })
            val image = image(full)
            assertEquals(GoAiMatchFileCodec.MAX_BYTES, image.size)
            store.transaction {
                writeActive(GoAiCheckpointEntry(image, GoAiCheckpointLineage(GoAiCheckpointOrigin.UNVERIFIED_SESSION)), 33)
                markLegacyHandled()
            }
            val restored = store.transaction { readActive()!! }
            assertArrayEquals(image, restored.copyBytes())
            restored.copyBytes().fill(0)
            assertArrayEquals(image, store.transaction { readActive()!!.copyBytes() })
            assertEquals(7, database.localDataMigrationDao().find(GoLegacyImportScopes.AI_MATCH)!!.handledVersion)
        }
    }

    private fun repository(database: MocsDatabase, legacy: GoAiLegacySource? = null,
        store: GoAiCheckpointStore = RoomGoAiCheckpointStore(database), clock: () -> Long = { 17L }) =
        GoAiCheckpointRepository(store, legacy, clock = clock)

    private fun image(saved: GoAiSavedMatch) = GoAiMatchFileCodec.encode(saved)

    private fun archive(saved: GoAiSavedMatch,
        lineage: GoAiCheckpointLineage = GoAiCheckpointLineage(GoAiCheckpointOrigin.UNVERIFIED_SESSION)) =
        admitGoAiCheckpoint(saved).let { GoAiCheckpointArchive(it.recordId, GoAiCheckpointEntry(image(it.match()), lineage), 17) }

    private fun match(id: String, terminal: Boolean = false, automatic: Boolean = false, series: Boolean = false): GoAiSavedMatch =
        NativeGoEngine(9).use { engine ->
            assertEquals(ActionResult.Accepted, engine.apply(GoAction.Place(BoardPosition(0, 0))))
            if (terminal) {
                repeat(2) { assertEquals(ActionResult.Accepted, engine.apply(GoAction.Pass)) }
                val revision = engine.scoringState().proposalRevision
                assertEquals(ActionResult.Accepted, engine.apply(GoAction.ConfirmScore(GoPlayer.BLACK, revision)))
                assertEquals(ActionResult.Accepted, engine.apply(GoAction.ConfirmScore(GoPlayer.WHITE, revision)))
                assertEquals(GoPhase.FINISHED, engine.phase)
            }
            GoAiSavedMatch(id, GoAiMatchConfig(9, humanPlayer = if (automatic) null else GoPlayer.BLACK),
                engine.serialize(), undoUsed = 1, hintUsed = 1, rememberedHuman = GoPlayer.BLACK,
                autoContinueEnabled = series, autoContinueMaxGames = if (series) 7 else 1,
                autoContinueGameNumber = if (series) 3 else 1)
        }

    private fun controls(original: GoAiSavedMatch, reserved: Boolean, series: Boolean) = GoAiSavedMatch(
        original.matchId, original.config, original.copyBytes(), original.undoUsed, original.hintUsed, reserved,
        original.rememberedHuman, original.automaticUsed, original.copyOpeningBytes(), series,
        if (series) original.autoContinueMaxGames else 1, if (series) original.autoContinueGameNumber else 1)

    private fun faultAfter(delegate: GoAiCheckpointStore, stage: String, failure: Exception) = object : GoAiCheckpointStore {
        override suspend fun <T> transaction(block: suspend GoAiCheckpointTransaction.() -> T): T = delegate.transaction {
            val access = this
            var writes = 0
            var markers = 0
            block(object : GoAiCheckpointTransaction by access {
                override suspend fun insertArchive(archive: GoAiCheckpointArchive) {
                    access.insertArchive(archive)
                    if (stage == "archive") throw failure
                }
                override suspend fun writeActive(entry: GoAiCheckpointEntry, updatedAtEpochMillis: Long) {
                    access.writeActive(entry, updatedAtEpochMillis)
                    if (stage == "active${++writes}") throw failure
                }
                override suspend fun markLegacyHandled() {
                    access.markLegacyHandled()
                    if (stage == "marker${++markers}") throw failure
                }
            })
        }
    }

    private suspend fun rejects(block: suspend () -> Any?) {
        try { block(); fail("Invalid AI Room state accepted") } catch (_: IllegalArgumentException) { }
    }

    private suspend fun assertNoRewards(database: MocsDatabase) {
        assertTrue(database.gameRecordDao().listAll().isEmpty())
        assertTrue(database.matchOutcomeDao().listAll().isEmpty())
        assertTrue(database.endgameProgressDao().listAll().isEmpty())
    }

    private suspend fun withDatabase(block: suspend (MocsDatabase) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, MocsDatabase::class.java).build()
        try { block(database) } finally { database.close() }
    }
}
