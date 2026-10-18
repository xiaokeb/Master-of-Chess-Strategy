package com.masterofchessstrategy.games.go.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.NativeGoEngine
import com.masterofchessstrategy.games.go.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Full repository replacement before the first Go load, with real Room and preserved disk sources. */
@RunWith(AndroidJUnit4::class)
class GoLegacyRestoreFenceInstrumentedTest {
    @Test fun everySupportedGoFreeContainerSealsAllSourcesBeforeTheirFirstMigration() = runBlocking {
        for (version in 1..5) withOwned { database, directory ->
            val gos = File(directory, "go-session.bin")
            NativeGoEngine(9).use { engine ->
                gos.writeBytes(GoSessionFileCodec.encode(GoSavedSession(9, engine.serialize(), GoScoreDisplayMode.STONES)))
            }
            val gep = File(directory, "go-endgame.bin").apply {
                writeBytes(GoEndgameProgressCodec.encode(GoEndgameProgress(
                    setOf(GoEndgameLibrary.puzzles.first().id), GoEndgameLibrary.puzzles[1].id)))
            }
            val xml = File(directory, "go-learning.xml").apply {
                writeText("<map><set name=\"completed\"><string>liberties</string></set></map>")
            }
            val gai = File(directory, "go-ai-match.bin")
            NativeGoEngine(9).use { engine ->
                gai.writeBytes(GoAiMatchFileCodec.encode(GoAiSavedMatch(
                    "legacy-before-restore", GoAiMatchConfig(9), engine.serialize())))
            }
            val originals = listOf(gos, gep, xml, gai).associateWith { it.readBytes() }
            val higher = LocalDataMigrationEntity(GoLegacyImportScopes.LEARNING, 2)
            database.localDataMigrationDao().insert(higher)
            repository(database).restore(emptyContainer(version))
            assertEquals(higher, database.localDataMigrationDao().find(higher.scope))
            for (scope in GoLegacyImportScopes.ALL) {
                assertTrue("version $version must seal $scope", database.localDataMigrationDao().find(scope)!!.handledVersion >= 1)
            }
            assertNull(RoomGoSessionRepository(database, GoLocalSessionDiskSource(gos)).load())
            assertTrue(RoomGoLearningProgressRepository(database, GoLearningPreferencesDiskSource(xml)).load().completedLessonIds.isEmpty())
            assertTrue(RoomGoEndgameProgressRepository(database, GoEndgameDiskSource(gep)).load().completedIds.isEmpty())
            assertNull(GoAiCheckpointRepository(RoomGoAiCheckpointStore(database), GoAiLegacyDiskSource(gai)).load())
            assertNull(database.goLocalSessionDao().find(GameType.GO.code))
            assertNull(database.goProgressDao().find(GameType.GO.code, GoProgressKind.LEARNING.code))
            assertNull(database.goProgressDao().find(GameType.GO.code, GoProgressKind.ENDGAME.code))
            assertTrue(database.goAiCheckpointDao().listActive().isEmpty())
            assertTrue(database.goAiCheckpointDao().listArchives().isEmpty())
            originals.forEach { (file, bytes) -> assertArrayEquals(bytes, file.readBytes()) }
        }
    }

    @Test fun latePreparedWriteFailureRollsBackReplacementAndAllNewFences() = runBlocking {
        withOwned { database, _ ->
            val settings = AppSettings.DEFAULT.copy(soundEnabled = false, updatedAtEpochMillis = 31)
            RoomAppSettingsRepository(database.appSettingsDao()).save(settings)
            val higher = LocalDataMigrationEntity(GoLegacyImportScopes.LEARNING, 2)
            database.localDataMigrationDao().insert(higher)
            var observedAllFences = false
            val adapter = object : GameBackupAdapter {
                override val descriptor = GameBackupDescriptor("TEST_GO_RESTORE_FENCE", 4,
                    BackupStorageSlot(BackupStorageTable.ACTIVE_GAME, GameType.GO), 1, 0, 100)
                override fun validate(record: GameBackupRecord, containerVersion: Int) {
                    require(record == record())
                }
                fun record() = GameBackupRecord(descriptor.kind, listOf(GameType.GO.code.toString()))
                override suspend fun read(database: MocsDatabase) = emptyList<GameBackupRecord>()
                override fun prepareRestore(record: GameBackupRecord) = object : PreparedGameBackup {
                    override val storageSlot = descriptor.storageSlot
                    override suspend fun write(database: MocsDatabase) {
                        for (scope in GoLegacyImportScopes.ALL) assertNotNull(database.localDataMigrationDao().find(scope))
                        observedAllFences = true
                        throw IllegalStateException("test-only failure after sealing")
                    }
                }
            }
            val registry = GameBackupRegistry(listOf(adapter))
            val bytes = LocalDataBackupCodec.encode(LocalDataSnapshot(40, gameExtensions = listOf(adapter.record())), registry)
            try { repository(database, registry).restore(bytes); fail("must roll back") }
            catch (error: IllegalStateException) { assertEquals("test-only failure after sealing", error.message) }
            assertTrue(observedAllFences)
            assertEquals(settings, (RoomAppSettingsRepository(database.appSettingsDao()).load() as LoadAppSettingsResult.Loaded).settings)
            assertEquals(higher, database.localDataMigrationDao().find(higher.scope))
            assertNull(database.localDataMigrationDao().find(GoLegacyImportScopes.SESSION))
            assertNull(database.localDataMigrationDao().find(GoLegacyImportScopes.ENDGAME))
            assertNull(database.localDataMigrationDao().find(GoLegacyImportScopes.AI_MATCH))
        }
    }

    @Test fun invalidContainerDoesNotSealAnySourceOrReplaceExistingSettings() = runBlocking {
        withOwned { database, _ ->
            val settings = AppSettings.DEFAULT.copy(updatedAtEpochMillis = 55)
            RoomAppSettingsRepository(database.appSettingsDao()).save(settings)
            val broken = emptyContainer(4).also { it[0] = 0 }
            try { repository(database).restore(broken); fail("must reject checksum/header") }
            catch (_: IllegalArgumentException) { }
            for (scope in GoLegacyImportScopes.ALL) assertNull(database.localDataMigrationDao().find(scope))
            assertEquals(settings, (RoomAppSettingsRepository(database.appSettingsDao()).load() as LoadAppSettingsResult.Loaded).settings)
        }
    }

    private fun emptyContainer(version: Int): ByteArray {
        val body = "MOCS-BACKUP|$version\nCREATED|1\n"
        val hash = MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") { "%02x".format(it) }
        return (body + "SHA256|$hash\n").toByteArray()
    }

    private fun repository(database: MocsDatabase, registry: GameBackupRegistry = GameBackupRegistry(emptyList())) =
        RoomLocalDataBackupRepository(database, ChineseChessEndgamePackParser.loadBundled(
            InstrumentationRegistry.getInstrumentation().targetContext.assets), { false }, registry)

    private suspend fun withOwned(block: suspend (MocsDatabase, File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "go-restore-fence-${UUID.randomUUID()}")
        assertTrue(directory.mkdir())
        val database = Room.inMemoryDatabaseBuilder(context, MocsDatabase::class.java).build()
        try { block(database, directory) } finally {
            database.close()
            directory.listFiles()?.forEach { assertTrue(it.delete()) }
            assertTrue(directory.delete())
        }
    }
}
