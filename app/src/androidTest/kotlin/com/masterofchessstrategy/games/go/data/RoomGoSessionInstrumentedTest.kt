package com.masterofchessstrategy.games.go.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.engine.*
import com.masterofchessstrategy.games.go.*
import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.zip.CRC32

/** Owned in-memory DB and cache-only legacy files; never clears the user's app data. */
@RunWith(AndroidJUnit4::class)
class RoomGoSessionInstrumentedTest {
    @Test fun actualAgreementArchivesOnceAndDoesNotTouchRankedOrEndgameLedgers() = runBlocking {
        withDatabase { database ->
            var now = 10L
            val repository = RoomGoSessionRepository(RoomGoLocalSessionStore(database), clock = { now })
            NativeGoEngine(9).use { engine ->
                val identity = GoSessionIdentity.create(GoSessionOrigin.SGF_IMPORT)
                suspend fun save() = repository.save(saved(engine, identity))
                save()
                assertTrue(database.gameRecordDao().listAll().isEmpty())
                accept(engine, GoAction.Place(BoardPosition(0, 0)))
                enterScoring(engine)
                repeat(3_200) { accept(engine, GoAction.ToggleDeadGroup(BoardPosition(0, 0), GoPlayer.BLACK)) }
                save()
                assertTrue(database.gameRecordDao().listAll().isEmpty())
                accept(engine, GoAction.ConfirmScore(GoPlayer.BLACK, engine.scoringState().proposalRevision))
                save()
                assertTrue(database.gameRecordDao().listAll().isEmpty())
                accept(engine, GoAction.ConfirmScore(GoPlayer.WHITE, engine.scoringState().proposalRevision))
                save()
                val record = database.gameRecordDao().find(identity.recordId)!!
                assertTrue(record.engineState.size > 65_536)
                assertArrayEquals(engine.serialize(), record.engineState)
                assertEquals(3, record.moveCount)
                assertEquals(3, record.engineFormatVersion)
                assertEquals(GoSessionOrigin.SGF_IMPORT.code, database.goRecordSourceDao().find(identity.recordId)!!.originCode)
                database.gameRecordDao().setFavorite(identity.recordId, true)
                now = 20
                save()
                assertEquals(1, database.gameRecordDao().listAll().size)
                assertTrue(database.gameRecordDao().find(identity.recordId)!!.isFavorite)
                assertEquals(10L, database.gameRecordDao().find(identity.recordId)!!.completedAtEpochMillis)
                assertEquals(identity, repository.load()!!.identity)
                assertTrue(database.matchOutcomeDao().listAll().isEmpty())
                assertTrue(database.endgameProgressDao().listAll().isEmpty())
                assertThrows(IllegalArgumentException::class.java) { runBlocking {
                    GameBackupRegistry(emptyList()).requireSupportedSlots(BackupStorageInventory.read(database))
                } }
            }
        }
    }

    @Test fun terminalConflictKeepsTheOldCheckpointAndUndoCreatesAnotherArchive() = runBlocking {
        withDatabase { database ->
            val repository = RoomGoSessionRepository(database)
            NativeGoEngine(13).use { engine ->
                enterScoring(engine); finish(engine)
                val identity = GoSessionIdentity.create()
                repository.save(saved(engine, identity))
                val before = database.goLocalSessionDao().find(GameType.GO.code)!!.checkpoint.copyOf()
                NativeGoEngine(19).use { other ->
                    enterScoring(other); finish(other)
                    expectRejection { repository.save(saved(other, identity)) }
                }
                assertArrayEquals(before, database.goLocalSessionDao().find(GameType.GO.code)!!.checkpoint)
                assertEquals(1, database.gameRecordDao().listAll().size)
                assertTrue(engine.undo())
                expectRejection { repository.save(saved(engine, identity)) }
                val branch = identity.afterTerminalUndo()
                repository.save(saved(engine, branch))
                accept(engine, GoAction.ConfirmScore(GoPlayer.WHITE, engine.scoringState().proposalRevision))
                repository.save(saved(engine, branch))
                assertEquals(2, database.gameRecordDao().listAll().size)
                assertNotNull(database.goRecordSourceDao().find(identity.recordId))
                assertNotNull(database.goRecordSourceDao().find(branch.recordId))
                assertEquals(branch, repository.load()!!.identity)
            }
        }
    }

    @Test fun actualRoomFailureAfterWritingCheckpointRollsBackEveryGoRow() = runBlocking {
        withDatabase { database ->
            val real = RoomGoLocalSessionStore(database)
            val fault = object : GoLocalSessionStore {
                override suspend fun <T> transaction(block: suspend GoLocalSessionTransaction.() -> T): T =
                    real.transaction {
                        val transaction = this
                        block(object : GoLocalSessionTransaction by transaction {
                            override suspend fun writeActive(envelope: ByteArray, updatedAtEpochMillis: Long) {
                                transaction.writeActive(envelope, updatedAtEpochMillis)
                                throw IOException("test-only failure after checkpoint write")
                            }
                        })
                    }
            }
            NativeGoEngine(9).use { engine ->
                enterScoring(engine); finish(engine)
                val identity = GoSessionIdentity.create()
                try { RoomGoSessionRepository(fault).save(saved(engine, identity)); fail("must roll back") }
                catch (_: IOException) { }
                assertNull(database.goLocalSessionDao().find(GameType.GO.code))
                assertNull(database.gameRecordDao().find(identity.recordId))
                assertNull(database.goRecordSourceDao().find(identity.recordId))
                assertNull(database.localDataMigrationDao().find(GoLegacyImportScopes.SESSION))
            }
        }
    }

    @Test fun validLegacyFileMigratesOnceAndClearCannotResurrectIt() = runBlocking {
        withDatabase { database ->
            withLegacyFile { file ->
                NativeGoEngine(9).use { engine ->
                    enterScoring(engine); finish(engine)
                    val state = engine.serialize()
                    val original = ByteBuffer.allocate(20 + state.size).order(ByteOrder.LITTLE_ENDIAN).apply {
                        putInt(0x474F5331); put(2); put(9); put(0); put(0)
                        putInt(state.size); putInt(CRC32().apply { update(state) }.value.toInt()); putInt(-1); put(state)
                    }.array()
                    file.writeBytes(original)
                    val legacy = GoSessionFileRepository(file)
                    val identity = legacy.load()!!.identity
                    val repository = RoomGoSessionRepository(database, GoLocalSessionDiskSource(file))
                    assertEquals(identity, repository.load()!!.identity)
                    assertEquals(GoSessionOrigin.LEGACY_UNKNOWN, identity.origin)
                    assertArrayEquals(original, file.readBytes())
                    assertNotNull(database.gameRecordDao().find(identity.recordId))
                    assertEquals(1, database.localDataMigrationDao().find(GoLegacyImportScopes.SESSION)!!.handledVersion)
                    repository.clear()
                    assertNull(repository.load())
                    assertArrayEquals(original, file.readBytes())
                    assertEquals(1, database.gameRecordDao().listAll().size)
                }
            }
        }
    }

    @Test fun badLegacyNativeHistoryIsRetainedWithoutSuccessMarkerOrAnyRoomWrite() = runBlocking {
        withDatabase { database ->
            withLegacyFile { file ->
                NativeGoEngine(9).use { engine ->
                    accept(engine, GoAction.Pass)
                    val invalid = engine.serialize().apply { this[16] = 99 }
                    val legacy = GoSessionFileRepository(file)
                    legacy.save(GoSavedSession(9, invalid, GoScoreDisplayMode.STONES))
                    val original = file.readBytes()
                    expectRejection { RoomGoSessionRepository(database, GoLocalSessionDiskSource(file)).load() }
                    assertArrayEquals(original, file.readBytes())
                    assertNull(database.goLocalSessionDao().find(GameType.GO.code))
                    assertNull(database.localDataMigrationDao().find(GoLegacyImportScopes.SESSION))
                    assertTrue(database.gameRecordDao().listAll().isEmpty())
                }
            }
        }
    }

    @Test fun anUnarchivedTerminalRowCannotBeHiddenByANewGame() = runBlocking {
        withDatabase { database ->
            NativeGoEngine(9).use { engine ->
                enterScoring(engine); finish(engine)
                val checkpoint = GoSessionFileCodec.encode(saved(engine, GoSessionIdentity.create()))
                database.goLocalSessionDao().save(GoLocalSessionEntity(GameType.GO.code, 3, checkpoint, 1))
                val repository = RoomGoSessionRepository(database)
                expectRejection { repository.load() }
                NativeGoEngine(19).use { next -> expectRejection { repository.save(saved(next, GoSessionIdentity.create())) } }
                assertArrayEquals(checkpoint, database.goLocalSessionDao().find(GameType.GO.code)!!.checkpoint)
                assertTrue(database.gameRecordDao().listAll().isEmpty())
            }
        }
    }

    @Test fun standaloneGoRecordWithoutSourceBlocksRestoreBeforeAnyDelete() = runBlocking {
        withDatabase { database ->
            NativeGoEngine(9).use { engine ->
                enterScoring(engine); finish(engine)
                val record = GameRecord("standalone-go-${UUID.randomUUID()}", GameType.GO,
                    StoredGameMode.LOCAL_TWO_PLAYER, null, engine.gameResult(), engine.serialize(), 2,
                    completedAtEpochMillis = 1)
                assertTrue(RoomGameRecordRepository(database.gameRecordDao()).saveCompleted(record))
                assertNull(database.goRecordSourceDao().find(record.recordId))
                assertNull(database.goLocalSessionDao().find(GameType.GO.code))
                val context = InstrumentationRegistry.getInstrumentation().targetContext
                val registry = GameBackupRegistry(emptyList())
                val repository = RoomLocalDataBackupRepository(database,
                    ChineseChessEndgamePackParser.loadBundled(context.assets), { false }, registry)
                val emptyBackup = LocalDataBackupCodec.encode(LocalDataSnapshot(2), registry)
                expectRejection { repository.restore(emptyBackup) }
                assertNotNull(database.gameRecordDao().find(record.recordId))
                assertEquals(1, database.gameRecordDao().listAll().size)
                assertNull(database.localDataMigrationDao().find(GoLegacyImportScopes.SESSION))
            }
        }
    }

    private suspend fun withDatabase(block: suspend (MocsDatabase) -> Unit) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val database = Room.inMemoryDatabaseBuilder(context, MocsDatabase::class.java).build()
        try { block(database) } finally { database.close() }
    }

    private suspend fun withLegacyFile(block: suspend (File) -> Unit) {
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "go-room-${UUID.randomUUID()}")
        assertTrue(directory.mkdir())
        try { block(File(directory, "session.bin")) }
        finally { directory.listFiles()?.forEach { assertTrue(it.delete()) }; assertTrue(directory.delete()) }
    }

    private fun saved(engine: NativeGoEngine, identity: GoSessionIdentity) =
        GoSavedSession(engine.boardSize, engine.serialize(), GoScoreDisplayMode.STONES, identity = identity)

    private fun enterScoring(engine: NativeGoEngine) { accept(engine, GoAction.Pass); accept(engine, GoAction.Pass) }
    private fun finish(engine: NativeGoEngine) {
        val revision = engine.scoringState().proposalRevision
        accept(engine, GoAction.ConfirmScore(GoPlayer.BLACK, revision))
        accept(engine, GoAction.ConfirmScore(GoPlayer.WHITE, revision))
        assertEquals(GoPhase.FINISHED, engine.phase)
    }
    private fun accept(engine: NativeGoEngine, action: GoAction) = assertEquals(ActionResult.Accepted, engine.apply(action))
    private suspend fun expectRejection(block: suspend () -> Unit) {
        try { block(); fail("expected invalid history or archive rejection") } catch (_: IllegalArgumentException) { }
    }
}
