package com.masterofchessstrategy.games.go.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.games.go.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.util.UUID

/** Actual Native admission and UUID Room/file fixtures; no production factory is switched here. */
class GoCompleteStorageBundleInstrumentedTest {
    @Test fun exportMigratesAllFourSourcesAndIncludesAiWithoutChangingDiskImages() = runBlocking {
        withOwned { database, directory ->
            val files = sources(directory)
            val aiBase = files[3].readBytes()
            File(files[3].path + ".bak").writeBytes(aiBase)
            files[3].writeBytes(byteArrayOf(0)) // Committed .bak must win over a damaged base.
            File(files[3].path + ".new").writeBytes(byteArrayOf(1))
            val images = images(directory)
            val bundle = bundle(database, files)
            val (backup, registry) = backup(database, bundle)
            val bytes = backup.export(100)
            val kinds = LocalDataBackupCodec.decode(bytes, registry).gameExtensions.map { it.kind }.toSet()
            assertEquals(setOf("GO_LOCAL_SESSION", "GO_PROGRESS", "GO_AI_SESSION", "GO_AI_ARCHIVE"), kinds)
            assertNotNull(database.goLocalSessionDao().find(GameType.GO.code))
            assertEquals(2, database.goProgressDao().listAll().size)
            assertArrayEquals(aiBase, database.goAiCheckpointDao().findActive(GameType.GO.code)!!.checkpoint)
            assertEquals(1, database.goAiCheckpointDao().listArchives().size)
            assertTrue(database.gameRecordDao().listAll().isEmpty())
            assertTrue(database.matchOutcomeDao().listAll().isEmpty())
            assertFences(database)
            assertImages(images)
        }
    }

    @Test fun badFourthSourceRollsBackThreeImportsAndAllFences() = runBlocking {
        withOwned { database, directory ->
            val files = sources(directory)
            File(files[3].path + ".bak").writeBytes(byteArrayOf(0))
            val images = images(directory)
            val (backup, _) = backup(database, bundle(database, files))
            try { backup.export(100); fail("Bad committed AI must reject complete export") }
            catch (_: IOException) { }
            assertNoMigration(database)
            assertImages(images)
        }
    }

    @Test fun cancellationAtFourthSourceRollsBackPriorRowsAndTheConnectionCanRetry() = runBlocking {
        withOwned { database, directory ->
            val files = sources(directory)
            var reached = false
            val cancelled = bundle(database, files, GoAiLegacySource {
                assertNotNull(database.goLocalSessionDao().find(GameType.GO.code))
                assertEquals(2, database.goProgressDao().listAll().size)
                reached = true
                throw CancellationException("owned fourth-source cancellation")
            })
            try { backup(database, cancelled).first.export(100); fail("Cancelled migration committed") }
            catch (_: CancellationException) { }
            assertTrue(reached)
            assertNoMigration(database)
            backup(database, bundle(database, files)).first.export(100)
            assertFences(database)
            assertEquals(1, database.goAiCheckpointDao().listArchives().size)
        }
    }

    @Test fun validGaiChecksumWithInvalidNativeHistoryRollsBackAllOtherSources() = runBlocking {
        withOwned { database, directory ->
            val files = sources(directory)
            val valid = aiMatch()
            val native = valid.copyBytes().also { it[0] = 0 }
            files[3].writeBytes(GoAiMatchFileCodec.encode(GoAiSavedMatch(valid.matchId, valid.config,
                native, valid.undoUsed, valid.hintUsed)))
            val images = images(directory)
            try { backup(database, bundle(database, files)).first.export(100); fail("Invalid Native history accepted") }
            catch (_: IllegalArgumentException) { }
            assertNoMigration(database)
            assertImages(images)
        }
    }

    @Test fun emptyAiReplacementRemainsSealedAfterActualDatabaseCloseAndReopen() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val directory = File(context.cacheDir, "go-complete-reopen-$id")
        check(directory.mkdir())
        val databaseName = "go-complete-reopen-$id.db"
        var database = Room.databaseBuilder(context, MocsDatabase::class.java, databaseName).build()
        try {
            val files = sources(directory)
            val images = images(directory)
            val initial = bundle(database, files)
            val (backup, registry) = backup(database, initial)
            backup.export(100)
            assertNotNull(database.goAiCheckpointDao().findActive(GameType.GO.code))
            backup.restore(LocalDataBackupCodec.encode(LocalDataSnapshot(200), registry))
            database.close()
            database = Room.databaseBuilder(context, MocsDatabase::class.java, databaseName).build()
            assertFences(database)
            val reopened = bundle(database, files)
            assertNull(reopened.aiRepository().load())
            assertTrue(database.goAiCheckpointDao().listArchives().isEmpty())
            val (nextBackup, nextRegistry) = backup(database, reopened)
            val wires = LocalDataBackupCodec.decode(nextBackup.export(300), nextRegistry).gameExtensions
            assertFalse(wires.any { it.kind == "GO_AI_SESSION" || it.kind == "GO_AI_ARCHIVE" })
            assertImages(images)
        } finally {
            database.close()
            check(context.deleteDatabase(databaseName))
            directory.listFiles()!!.forEach { file -> check(file.isFile && file.delete()) }
            check(directory.delete())
        }
    }

    @Test fun goFreeRestoreIgnoresDamagedSourcesSealsAllFourAndRetiresOldEpoch() = runBlocking {
        withOwned { database, directory ->
            val files = sources(directory)
            files.forEach { file ->
                file.writeBytes(byteArrayOf(0))
                File(file.path + ".bak").writeBytes(byteArrayOf(1))
                File(file.path + ".new").writeBytes(byteArrayOf(2))
            }
            val images = images(directory)
            val bundle = bundle(database, files)
            val stale = bundle.aiRepository()
            val (backup, registry) = backup(database, bundle)
            val empty = LocalDataBackupCodec.encode(LocalDataSnapshot(100), registry)
            assertEquals(0, backup.restore(empty).activeSessionCount)
            assertFences(database)
            try { stale.load(); fail("Old AI facade crossed replacement epoch") } catch (_: IOException) { }
            assertNull(bundle.aiRepository().load())
            assertNull(RoomGoSessionRepository(database, GoLocalSessionDiskSource(files[0])).load())
            assertTrue(RoomGoLearningProgressRepository(database, GoLearningPreferencesDiskSource(files[1])).load().completedLessonIds.isEmpty())
            assertTrue(RoomGoEndgameProgressRepository(database, GoEndgameDiskSource(files[2])).load().completedIds.isEmpty())
            assertTrue(database.goAiCheckpointDao().listArchives().isEmpty())
            assertImages(images)
        }
    }

    @Test fun failedReplacementDoesNotImportOldSourcesOrSealAndStillInvalidatesOldPages() = runBlocking {
        withOwned { database, directory ->
            val files = sources(directory)
            var reads = 0
            val bundle = GoCompleteStorageBundle(database,
                GoLocalLegacySource { reads++; error("Must not import on restore") },
                GoLearningLegacySource { reads++; error("Must not import on restore") },
                GoEndgameLegacySource { reads++; error("Must not import on restore") },
                GoAiLegacySource { reads++; error("Must not import on restore") })
            val stale = bundle.aiRepository()
            val (backup, _) = backup(database, bundle)
            try { backup.restore(byteArrayOf(0)); fail("Invalid backup accepted") } catch (_: IllegalArgumentException) { }
            assertEquals(0, reads)
            assertNoMigration(database)
            try { stale.save(aiMatch()); fail("Old owner remained writable") } catch (_: IOException) { }
            assertEquals(0, reads)
            assertEquals(4, files.size)
        }
    }

    @Test fun cancelledReplacementKeepsDataAndFencesUnchangedButRetiresOldPages() = runBlocking {
        withOwned { database, directory ->
            val files = sources(directory)
            val bundle = bundle(database, files)
            val stale = bundle.aiRepository()
            var suppliedKinds: Set<String> = emptySet()
            val cancelled = bundle.backupRepository { adapters ->
                suppliedKinds = adapters.map { it.descriptor.kind }.toSet()
                object : LocalDataBackupRepository {
                    override suspend fun export(createdAtEpochMillis: Long) = error("unused")
                    override suspend fun restore(bytes: ByteArray): LocalDataRestoreSummary =
                        throw CancellationException("owned cancelled replacement")
                }
            }
            assertEquals(setOf("GO_LOCAL_SESSION", "GO_LOCAL_RECORD", "GO_PROGRESS", "GO_AI_SESSION", "GO_AI_ARCHIVE"), suppliedKinds)
            try { cancelled.restore(byteArrayOf(0)); fail("Cancellation hidden") } catch (_: CancellationException) { }
            assertNoMigration(database)
            try { stale.load(); fail("Old owner remained readable") } catch (_: IOException) { }
        }
    }

    private fun bundle(database: MocsDatabase, files: List<File>, ai: GoAiLegacySource = GoAiLegacyDiskSource(files[3])) =
        GoCompleteStorageBundle(database, GoLocalSessionDiskSource(files[0]), GoLearningPreferencesDiskSource(files[1]),
            GoEndgameDiskSource(files[2]), ai)

    private fun backup(database: MocsDatabase, bundle: GoCompleteStorageBundle): Pair<LocalDataBackupRepository, GameBackupRegistry> {
        var registry: GameBackupRegistry? = null
        val repository = bundle.backupRepository { adapters ->
            val selected = GameBackupRegistry(adapters).also { registry = it }
            RoomLocalDataBackupRepository(database, ChineseChessEndgamePackParser.loadBundled(
                InstrumentationRegistry.getInstrumentation().targetContext.assets), { false }, selected)
        }
        return repository to checkNotNull(registry)
    }

    private fun sources(directory: File): List<File> {
        val gos = File(directory, "local.bin")
        NativeGoEngine(9).use { engine ->
            gos.writeBytes(GoSessionFileCodec.encode(GoSavedSession(9, engine.serialize(), GoScoreDisplayMode.STONES)))
        }
        val learning = File(directory, "learning.xml").apply {
            writeText("<map><set name=\"completed\"><string>liberties</string></set></map>")
        }
        val endgame = File(directory, "endgame.bin").apply {
            writeBytes(GoEndgameProgressCodec.encode(GoEndgameProgress(
                setOf(GoEndgameLibrary.puzzles.first().id), GoEndgameLibrary.puzzles[1].id)))
        }
        val ai = File(directory, "ai.bin").apply { writeBytes(GoAiMatchFileCodec.encode(aiMatch())) }
        return listOf(gos, learning, endgame, ai)
    }

    private fun aiMatch(): GoAiSavedMatch = NativeGoEngine(9).use { engine ->
        assertEquals(ActionResult.Accepted, engine.apply(GoAction.Place(BoardPosition(0, 0))))
        repeat(2) { assertEquals(ActionResult.Accepted, engine.apply(GoAction.Pass)) }
        val revision = engine.scoringState().proposalRevision
        assertEquals(ActionResult.Accepted, engine.apply(GoAction.ConfirmScore(GoPlayer.BLACK, revision)))
        assertEquals(ActionResult.Accepted, engine.apply(GoAction.ConfirmScore(GoPlayer.WHITE, revision)))
        GoAiSavedMatch("complete-bundle-ai", GoAiMatchConfig(9), engine.serialize(), undoUsed = 1, hintUsed = 1)
    }

    private suspend fun assertFences(database: MocsDatabase) {
        assertEquals(4, GoLegacyImportScopes.ALL.size)
        GoLegacyImportScopes.ALL.forEach { assertNotNull(database.localDataMigrationDao().find(it)) }
    }

    private suspend fun assertNoMigration(database: MocsDatabase) {
        assertTrue(database.goLocalSessionDao().listAll().isEmpty())
        assertTrue(database.goProgressDao().listAll().isEmpty())
        assertTrue(database.goAiCheckpointDao().listActive().isEmpty())
        assertTrue(database.goAiCheckpointDao().listArchives().isEmpty())
        assertTrue(database.gameRecordDao().listAll().isEmpty())
        GoLegacyImportScopes.ALL.forEach { assertNull(database.localDataMigrationDao().find(it)) }
    }

    private fun images(directory: File) = directory.listFiles()!!.associateWith { it.readBytes() }
    private fun assertImages(images: Map<File, ByteArray>) = images.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }

    private suspend fun withOwned(block: suspend (MocsDatabase, File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val directory = File(context.cacheDir, "go-complete-bundle-$id")
        check(directory.mkdir())
        val databaseName = "go-complete-bundle-$id.db"
        val database = Room.databaseBuilder(context, MocsDatabase::class.java, databaseName).build()
        try { block(database, directory) } finally {
            database.close()
            check(context.deleteDatabase(databaseName))
            directory.listFiles()!!.forEach { file -> check(file.isFile && file.delete()) }
            check(directory.delete())
        }
    }
}
