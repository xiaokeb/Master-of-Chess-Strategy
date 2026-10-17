package com.masterofchessstrategy.games.go.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.NativeGoEngine
import com.masterofchessstrategy.games.go.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

/** Actual outer Room transaction, not three independently successful migration calls. */
@RunWith(AndroidJUnit4::class)
class GoLocalBackupPreparationInstrumentedTest {
    @Test fun beforeExportImportsEverySourceTogetherAndRetainsEachDiskImage() = runBlocking {
        withOwned { database, directory ->
            val files = sources(directory)
            val originals = files.associateWith { it.readBytes() }
            preparation(database, files).beforeExport()
            assertNotNull(database.goLocalSessionDao().find(GameType.GO.code))
            assertEquals(2, database.goProgressDao().listAll().size)
            assertEquals(setOf("liberties"), RoomGoLearningProgressRepository(database).load().completedLessonIds)
            assertEquals(setOf(GoEndgameLibrary.puzzles.first().id), RoomGoEndgameProgressRepository(database).load().completedIds)
            for (scope in GoLegacyImportScopes.ALL) assertNotNull(database.localDataMigrationDao().find(scope))
            originals.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
        }
    }

    @Test fun invalidThirdSourceRollsBackTheFirstTwoMigrationsAndTheirFences() = runBlocking {
        withOwned { database, directory ->
            val files = sources(directory)
            val backup = File(files[2].path + ".bak").apply { writeBytes(byteArrayOf(0)) }
            val originals = (files + backup).associateWith { it.readBytes() }
            try { preparation(database, files).beforeExport(); fail("third source must fail") }
            catch (_: IOException) { }
            assertNoMigration(database)
            originals.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
        }
    }

    @Test fun cancellationWhileReadingTheThirdSourceRollsBackPriorImports() = runBlocking {
        withOwned { database, directory ->
            val files = sources(directory)
            val coordinator = GoLocalBackupPreparation(database, GoLocalSessionDiskSource(files[0]),
                GoLearningPreferencesDiskSource(files[1]), GoEndgameLegacySource { throw CancellationException("cancel migration") },
                GoUnsupportedBackupSourceGuard { })
            try { coordinator.beforeExport(); fail("must cancel") }
            catch (error: CancellationException) { assertEquals("cancel migration", error.message) }
            assertNoMigration(database)
        }
    }

    @Test fun unsupportedAiSourceRejectsBothBoundariesWithoutReadingLocalData() = runBlocking {
        withOwned { database, directory ->
            var reads = 0
            val ai = File(directory, "ai.bin.bak").apply { writeBytes(byteArrayOf(1, 2)) }
            val coordinator = GoLocalBackupPreparation(database, GoLocalLegacySource { reads++; error("must not read") },
                GoLearningLegacySource { reads++; error("must not read") }, GoEndgameLegacySource { reads++; error("must not read") },
                GoAiDiskBackupGuard(File(directory, "ai.bin")))
            for (export in listOf(true, false)) {
                try { if (export) coordinator.beforeExport() else coordinator.beforeRestore(); fail("must not omit GAI1") }
                catch (_: IOException) { }
            }
            assertEquals(0, reads)
            assertArrayEquals(byteArrayOf(1, 2), ai.readBytes())
            assertNoMigration(database)
        }
    }

    @Test fun beforeRestoreDoesNotImportTheOldDataThatWillBeReplaced() = runBlocking {
        withOwned { database, _ ->
            val coordinator = GoLocalBackupPreparation(database, GoLocalLegacySource { error("must not import") },
                GoLearningLegacySource { error("must not import") }, GoEndgameLegacySource { error("must not import") },
                GoUnsupportedBackupSourceGuard { })
            coordinator.beforeRestore()
            assertNoMigration(database)
        }
    }

    private fun sources(directory: File): List<File> {
        val gos = File(directory, "gos.bin")
        NativeGoEngine(9).use { engine ->
            gos.writeBytes(GoSessionFileCodec.encode(GoSavedSession(9, engine.serialize(), GoScoreDisplayMode.STONES)))
        }
        val xml = File(directory, "learning.xml").apply {
            writeText("<map><set name=\"completed\"><string>liberties</string></set></map>")
        }
        val gep = File(directory, "gep.bin").apply {
            writeBytes(GoEndgameProgressCodec.encode(GoEndgameProgress(
                setOf(GoEndgameLibrary.puzzles.first().id), GoEndgameLibrary.puzzles[1].id)))
        }
        return listOf(gos, xml, gep)
    }

    private fun preparation(database: MocsDatabase, files: List<File>) = GoLocalBackupPreparation(database,
        GoLocalSessionDiskSource(files[0]), GoLearningPreferencesDiskSource(files[1]), GoEndgameDiskSource(files[2]),
        GoUnsupportedBackupSourceGuard { })
    private suspend fun assertNoMigration(database: MocsDatabase) {
        assertTrue(database.goLocalSessionDao().listAll().isEmpty())
        assertTrue(database.goProgressDao().listAll().isEmpty())
        assertTrue(database.goRecordSourceDao().listAll().isEmpty())
        assertTrue(database.gameRecordDao().listAll().isEmpty())
        for (scope in GoLegacyImportScopes.ALL) assertNull(database.localDataMigrationDao().find(scope))
    }
    private suspend fun withOwned(block: suspend (MocsDatabase, File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "go-backup-preparation-${UUID.randomUUID()}")
        assertTrue(directory.mkdir())
        val database = Room.inMemoryDatabaseBuilder(context, MocsDatabase::class.java).build()
        try { block(database, directory) } finally {
            database.close()
            directory.listFiles()?.forEach { assertTrue(it.delete()) }
            assertTrue(directory.delete())
        }
    }
}
