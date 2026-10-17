package com.masterofchessstrategy.games.go.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.NativeGoEngine
import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import com.masterofchessstrategy.games.go.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.util.UUID

/** Real disk/XML and Room; all fixtures use unique, test-owned paths and databases. */
@RunWith(AndroidJUnit4::class)
class GoDetailedProgressInstrumentedTest {
    private val lessons = GoLearningLibrary.lessons.map { it.id }
    private val puzzles = GoEndgameLibrary.puzzles.map { it.id }

    @Test fun historicalPreferencesXmlPreservesSubsetAndDefaultsWithoutInventingCompletion() {
        val subset = GoLearningPreferencesXml.decode(xml(setOf(lessons[0], lessons[7]), lessons[7]))
        assertEquals(setOf(lessons[0], lessons[7]), subset.completedLessonIds)
        assertEquals(lessons[7], subset.lastLessonId)
        assertFalse(subset.isCompleted)
        assertEquals(GoLearningProgress(), GoLearningPreferencesXml.decode("<map/>".toByteArray()))
        assertTrue(GoLearningPreferencesXml.decode(xml(lessons.toSet())).isCompleted)
        assertFalse(GoLearningPreferencesXml.decode(xml(lessons.take(4).toSet())).isCompleted)
    }

    @Test fun malformedOrFutureXmlIsRejectedIncludingDoctypeAndDuplicateFields() {
        val bad = listOf(
            "<map><int name=\"version\" value=\"2\"/></map>",
            "<map><boolean name=\"completed\" value=\"true\"/></map>",
            "<map><string name=\"last\">unknown</string></map>",
            "<map><string name=\"future\">x</string></map>",
            "<map><int name=\"version\" value=\"1\"/><int name=\"version\" value=\"1\"/></map>",
            "<map><set name=\"completed\"><string>liberties</string><string>liberties</string></set></map>",
            "<!DOCTYPE map [<!ENTITY remote SYSTEM 'file:///must-not-be-read'>]><map/>",
            "<map><string name=\"last\">&unknown;</string></map>",
            "<map xmlns=\"future\"/>", "<map><set name=\"completed\"><string>unknown</string></set></map>",
        )
        for (value in bad) assertThrows(IOException::class.java) { GoLearningPreferencesXml.decode(value.toByteArray()) }
        assertThrows(IOException::class.java) { GoLearningPreferencesXml.decode(byteArrayOf(0xc3.toByte(), 0x28)) }
        assertThrows(IOException::class.java) { GoLearningPreferencesXml.decode(ByteArray(16_385)) }
    }

    @Test fun frameworkCommittedPreferencesAreReadFromDiskWithoutUsingTheCachedMap() = runBlocking {
        withOwned { database, _ ->
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val name = "go-learning-migration-test-${UUID.randomUUID()}"
            val preferences = context.getSharedPreferences(name, android.content.Context.MODE_PRIVATE)
            val progress = GoLearningProgress(setOf(lessons[1], lessons[8]), lessons[8])
            try {
                // Exercise Android's real historical writer and XML serializer, not hand-written XML alone.
                GoLearningPreferencesRepository(preferences).save(progress)
                val file = File(context.dataDir, "shared_prefs/$name.xml")
                val committed = file.readBytes()
                val repository = RoomGoLearningProgressRepository(database, GoLearningPreferencesDiskSource(file))
                assertEquals(progress, repository.load())
                assertArrayEquals(committed, file.readBytes())
                assertFalse(repository.load().isCompleted)
                assertEquals(1, database.localDataMigrationDao().find(GoLegacyImportScopes.LEARNING)!!.handledVersion)
            } finally {
                assertTrue(context.deleteSharedPreferences(name))
            }
        }
    }

    @Test fun committedBackupMigratesAndMergesWithoutMutatingEitherPreferencesImage() = runBlocking {
        withOwned { database, directory ->
            val file = File(directory, "learning.xml").apply { writeBytes(xml(lessons.toSet())) }
            val backupBytes = xml(lessons.take(2).toSet(), lessons[1])
            val backup = File(file.path + ".bak").apply { writeBytes(backupBytes) }
            val original = file.readBytes()
            val repository = RoomGoLearningProgressRepository(database, GoLearningPreferencesDiskSource(file))
            assertEquals(lessons.take(2).toSet(), repository.load().completedLessonIds)
            assertFalse(repository.load().isCompleted)
            val stale = repository.load()
            val other = RoomGoLearningProgressRepository(database)
            other.save(GoLearningProgress(setOf(lessons[8]), lessons[8]))
            repository.save(stale.copy(completedLessonIds = stale.completedLessonIds + lessons[2], lastLessonId = lessons[2]))
            assertEquals(setOf(lessons[0], lessons[1], lessons[2], lessons[8]), repository.load().completedLessonIds)
            assertEquals(lessons[2], repository.load().lastLessonId)
            assertArrayEquals(original, file.readBytes())
            assertArrayEquals(backupBytes, backup.readBytes())
            assertEquals(1, database.localDataMigrationDao().find(GoLegacyImportScopes.LEARNING)!!.handledVersion)
            repository.clear()
            assertFalse(repository.load().isCompleted)
            assertTrue(repository.load().completedLessonIds.isEmpty())
            assertArrayEquals(backupBytes, backup.readBytes())
        }
    }

    @Test fun badBackupNeverFallsBackToAnApparentlyCompletedBaseFile() = runBlocking {
        withOwned { database, directory ->
            val file = File(directory, "learning.xml").apply { writeBytes(xml(lessons.toSet())) }
            val backup = File(file.path + ".bak").apply { writeBytes("<map><broken>".toByteArray()) }
            val original = file.readBytes(); val backupBytes = backup.readBytes()
            expectRejected { RoomGoLearningProgressRepository(database, GoLearningPreferencesDiskSource(file)).load() }
            assertNull(database.goProgressDao().find(GameType.GO.code, GoProgressKind.LEARNING.code))
            assertNull(database.localDataMigrationDao().find(GoLegacyImportScopes.LEARNING))
            assertArrayEquals(original, file.readBytes()); assertArrayEquals(backupBytes, backup.readBytes())
        }
    }

    @Test fun roomFailureRollsBackProgressAndFenceWithoutAtomicFileRecoverySideEffects() = runBlocking {
        withOwned { database, directory ->
            val file = File(directory, "learning.xml").apply { writeBytes(xml(lessons.toSet())) }
            val backupBytes = xml(lessons.take(3).toSet(), lessons[2])
            val backup = File(file.path + ".bak").apply { writeBytes(backupBytes) }
            val original = file.readBytes()
            val real = RoomGoProgressStore(database)
            val fault = object : GoProgressStore {
                override suspend fun <T> transaction(block: suspend GoProgressTransaction.() -> T): T = real.transaction {
                    val transaction = this
                    block(object : GoProgressTransaction by transaction {
                        override suspend fun markLegacyHandled(kind: GoProgressKind) {
                            transaction.markLegacyHandled(kind)
                            throw IOException("test-only failure after progress/fence writes")
                        }
                    })
                }
            }
            expectRejected { RoomGoLearningProgressRepository(fault, GoLearningPreferencesDiskSource(file)).load() }
            assertNull(database.goProgressDao().find(GameType.GO.code, GoProgressKind.LEARNING.code))
            assertNull(database.localDataMigrationDao().find(GoLegacyImportScopes.LEARNING))
            assertArrayEquals(original, file.readBytes()); assertArrayEquals(backupBytes, backup.readBytes())
        }
    }

    @Test fun gosAndGepMigrationSourcesNeverConsumeBackupsOrPendingWrites() = runBlocking {
        withOwned { _, directory ->
            NativeGoEngine(9).use { engine ->
                val baseSession = GoSavedSession(9, engine.serialize(), GoScoreDisplayMode.STONES)
                val backupSession = baseSession.copy(identity = GoSessionIdentity.create(GoSessionOrigin.SGF_IMPORT))
                val file = File(directory, "gos.bin").apply { writeBytes(GoSessionFileCodec.encode(baseSession)) }
                val backup = File(file.path + ".bak").apply { writeBytes(GoSessionFileCodec.encode(backupSession)) }
                val pending = File(file.path + ".new").apply { writeBytes(byteArrayOf(7)) }
                val baseBytes = file.readBytes(); val backupBytes = backup.readBytes()
                assertEquals(backupSession.identity, GoLocalSessionDiskSource(file).readCommitted()!!.identity)
                assertArrayEquals(baseBytes, file.readBytes()); assertArrayEquals(backupBytes, backup.readBytes())
                assertArrayEquals(byteArrayOf(7), pending.readBytes())
            }
            val file = File(directory, "gep.bin").apply { writeBytes(GoEndgameProgressCodec.encode(GoEndgameProgress())) }
            val progress = GoEndgameProgress(setOf(puzzles[0]), puzzles[1])
            val backupBytes = GoEndgameProgressCodec.encode(progress)
            val backup = File(file.path + ".bak").apply { writeBytes(backupBytes) }
            val baseBytes = file.readBytes()
            assertEquals(progress.completedIds, GoEndgameDiskSource(file).readCommitted().completedIds)
            assertArrayEquals(baseBytes, file.readBytes()); assertArrayEquals(backupBytes, backup.readBytes())
        }
    }

    @Test fun invalidGosAndGepBackupsRetainValidBasesAndCommitNoRowsOrFences() = runBlocking {
        withOwned { database, directory ->
            val gos = File(directory, "gos-bad-backup.bin")
            NativeGoEngine(9).use { engine ->
                gos.writeBytes(GoSessionFileCodec.encode(GoSavedSession(9, engine.serialize(), GoScoreDisplayMode.STONES)))
            }
            val gep = File(directory, "gep-bad-backup.bin").apply {
                writeBytes(GoEndgameProgressCodec.encode(GoEndgameProgress(setOf(puzzles[0]), puzzles[1])))
            }
            val images = listOf(gos, gep).flatMap { file ->
                listOf(file, File(file.path + ".bak").apply { writeBytes(byteArrayOf(0, 1, 2)) },
                    File(file.path + ".new").apply { writeBytes(byteArrayOf(3, 4)) })
            }.associateWith { it.readBytes() }
            expectRejected { RoomGoSessionRepository(database, GoLocalSessionDiskSource(gos)).load() }
            expectRejected { RoomGoEndgameProgressRepository(database, GoEndgameDiskSource(gep)).load() }
            assertNull(database.goLocalSessionDao().find(GameType.GO.code))
            assertNull(database.goProgressDao().find(GameType.GO.code, GoProgressKind.ENDGAME.code))
            assertNull(database.localDataMigrationDao().find(GoLegacyImportScopes.SESSION))
            assertNull(database.localDataMigrationDao().find(GoLegacyImportScopes.ENDGAME))
            assertTrue(database.gameRecordDao().listAll().isEmpty())
            images.forEach { (file, bytes) -> assertArrayEquals(file.name, bytes, file.readBytes()) }
        }
    }

    @Test fun progressOnlyDatabaseBlocksIncompleteRestoreAndDoesNotGrantPublicRewards() = runBlocking {
        withOwned { database, _ ->
            val repository = RoomGoLearningProgressRepository(database)
            repository.save(GoLearningProgress(lessons.toSet(), lessons.last()))
            assertTrue(repository.load().isCompleted)
            val registry = GameBackupRegistry(emptyList())
            assertThrows(IllegalArgumentException::class.java) { runBlocking {
                registry.requireSupportedSlots(BackupStorageInventory.read(database))
            } }
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val backup = RoomLocalDataBackupRepository(database,
                ChineseChessEndgamePackParser.loadBundled(context.assets), { false }, registry)
            expectRejected { backup.restore(LocalDataBackupCodec.encode(LocalDataSnapshot(2), registry)) }
            assertTrue(repository.load().isCompleted)
            assertNull(database.tutorialProgressDao().find(GameType.CHINESE_CHESS.code))
            assertNull(database.tutorialProgressDao().find(GameType.GO.code))
            assertTrue(database.matchOutcomeDao().listAll().isEmpty())
            assertTrue(database.endgameProgressDao().listAll().isEmpty())
            assertTrue(database.gameRecordDao().listAll().isEmpty())
        }
    }

    private fun xml(completed: Set<String>, last: String = lessons.first()) = buildString {
        append("<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map>")
        append("<int name=\"version\" value=\"1\"/><string name=\"last\">$last</string><set name=\"completed\">")
        completed.forEach { append("<string>$it</string>") }
        append("</set></map>")
    }.toByteArray(Charsets.UTF_8)

    private suspend fun withOwned(block: suspend (MocsDatabase, File) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "go-progress-${UUID.randomUUID()}")
        assertTrue(directory.mkdir())
        val database = Room.inMemoryDatabaseBuilder(context, MocsDatabase::class.java).build()
        try { block(database, directory) } finally {
            database.close()
            directory.listFiles()?.forEach { assertTrue(it.delete()) }
            assertTrue(directory.delete())
        }
    }

    private suspend fun expectRejected(block: suspend () -> Unit) {
        try { block(); fail("expected invalid source or transaction failure") }
        catch (_: IOException) { } catch (_: IllegalArgumentException) { }
    }
}
