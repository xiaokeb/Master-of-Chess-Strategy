package com.masterofchessstrategy.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import com.masterofchessstrategy.engine.GameType
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** Real disk/transaction orchestration; fake providers do not certify any game's Native rules. */
class RegisteredBackupRepositoryInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val name = "registered-backup-${UUID.randomUUID()}.db"
    private lateinit var database: MocsDatabase
    private val oldSettings = AppSettings.DEFAULT.copy(soundEnabled = false, updatedAtEpochMillis = 17)

    @Before fun prepare(): Unit = runBlocking {
        database = open()
        RoomAppSettingsRepository(database.appSettingsDao()).save(oldSettings)
    }

    @After fun cleanup() {
        database.close()
        val base = context.getDatabasePath(name)
        val artifacts = listOf(base, File(base.path + "-wal"), File(base.path + "-shm"), File(base.path + "-journal"))
        if (artifacts.any(File::exists)) assertTrue(context.deleteDatabase(name))
        artifacts.forEach { assertFalse("Owned test artifact remains", it.exists()) }
    }

    @Test fun missingKnownAndUnknownActiveSlotsRejectExportAndRestore(): Unit = runBlocking {
        val registry = GameBackupRegistry(emptyList())
        val bytes = LocalDataBackupCodec.encode(LocalDataSnapshot(20), registry)
        for (code in listOf(GameType.LANZHOU_SQUARE_CHESS.code, 999)) {
            val row = active(code)
            database.activeGameDao().upsert(row)
            assertRejected { repository(registry).export(20) }
            assertRejected { repository(registry).restore(bytes) }
            reopen()
            assertEquals(row, database.activeGameDao().find(code))
            assertSettingsRetained()
            database.activeGameDao().deleteAll()
        }
    }

    @Test fun unavailableInputKindRejectsBeforePreparingOrClearing(): Unit = runBlocking {
        val present = Provider("TEST_GO", GameType.GO)
        val absent = Provider("TEST_FANGQI", GameType.LANZHOU_SQUARE_CHESS)
        val encoding = GameBackupRegistry(listOf(present, absent))
        val bytes = LocalDataBackupCodec.encode(LocalDataSnapshot(20,
            gameExtensions = listOf(present.record(), absent.record())), encoding)
        assertRejected { repository(GameBackupRegistry(listOf(present))).restore(bytes) }
        assertEquals(0, present.prepareCount)
        assertEquals(0, present.writeCount)
        reopen()
        assertSettingsRetained()
    }

    @Test fun everyPreparationFinishesBeforeAnyDatabaseClear(): Unit = runBlocking {
        val first = Provider("TEST_GO", GameType.GO)
        val second = Provider("TEST_FANGQI", GameType.LANZHOU_SQUARE_CHESS, order = 1)
        second.beforePrepare = { throw IllegalArgumentException("admission rejected") }
        val registry = GameBackupRegistry(listOf(first, second))
        assertRejected { repository(registry).restore(encoded(registry, first, second)) }
        assertEquals(1, first.prepareCount)
        assertEquals(1, second.prepareCount)
        assertEquals(0, first.writeCount)
        reopen()
        assertSettingsRetained()
    }

    @Test fun inventoryInsertedDuringPreparationIsRecheckedInsideRestoreTransaction(): Unit = runBlocking {
        val provider = Provider("TEST_GO", GameType.GO)
        val inserted = active(999)
        // Controlled seam simulates a writer appearing before the transaction's final inventory check.
        provider.beforePrepare = { runBlocking { database.activeGameDao().upsert(inserted) } }
        val registry = GameBackupRegistry(listOf(provider))
        assertRejected { repository(registry).restore(encoded(registry, provider)) }
        assertEquals(1, provider.prepareCount)
        assertEquals(0, provider.writeCount)
        reopen()
        assertEquals(inserted, database.activeGameDao().find(999))
        assertSettingsRetained()
    }

    @Test fun preparedWriteFailureRollsBackEarlierWritesAndAllClears(): Unit = runBlocking {
        val original = active(GameType.CHINESE_CHESS.code)
        database.activeGameDao().upsert(original)
        val first = Provider("TEST_GO", GameType.GO)
        val second = Provider("TEST_FANGQI", GameType.LANZHOU_SQUARE_CHESS, order = 1)
        first.onWrite = { it.activeGameDao().upsert(active(GameType.GO.code)) }
        second.onWrite = { throw IllegalStateException("prepared write rejected") }
        val registry = GameBackupRegistry(listOf(first, second))
        val failure = try { repository(registry).restore(encoded(registry, first, second)); null }
            catch (error: IllegalStateException) { error }
        assertEquals("prepared write rejected", failure?.message)
        assertEquals(1, first.writeCount)
        assertEquals(1, second.writeCount)
        reopen()
        assertEquals(original, database.activeGameDao().find(GameType.CHINESE_CHESS.code))
        assertNull(database.activeGameDao().find(GameType.GO.code))
        assertSettingsRetained()
    }

    private fun open() = Room.databaseBuilder(context, MocsDatabase::class.java, name).build()
    private fun reopen() { database.close(); database = open() }
    private fun repository(registry: GameBackupRegistry) = RoomLocalDataBackupRepository(
        database, ChineseChessEndgamePackParser.loadBundled(context.assets),
        validateEngineState = { false }, gameBackups = registry,
    )
    private fun encoded(registry: GameBackupRegistry, vararg providers: Provider) =
        LocalDataBackupCodec.encode(LocalDataSnapshot(20, gameExtensions = providers.map { it.record() }), registry)
    private suspend fun assertSettingsRetained() {
        assertEquals(oldSettings, (RoomAppSettingsRepository(database.appSettingsDao()).load()
            as LoadAppSettingsResult.Loaded).settings)
    }
    private suspend fun assertRejected(action: suspend () -> Any) {
        val rejected = try { action(); false } catch (_: IllegalArgumentException) { true }
        assertTrue("Must reject before discarding stored data", rejected)
    }
    private fun active(code: Int) = ActiveGameEntity(code, 0, null, 5, 1, byteArrayOf(1, 2, 3), 7,
        sessionId = "owned-inventory-fixture")

    private class Provider(kind: String, gameType: GameType, order: Int = 0) : GameBackupAdapter {
        override val descriptor = GameBackupDescriptor(kind, 4,
            BackupStorageSlot(BackupStorageTable.ACTIVE_GAME, gameType), 1, order, 1_000)
        var prepareCount = 0
        var writeCount = 0
        var beforePrepare: () -> Unit = {}
        var onWrite: suspend (MocsDatabase) -> Unit = {}
        fun record() = GameBackupRecord(descriptor.kind, listOf(descriptor.storageSlot.gameType.code.toString()))
        override fun validate(record: GameBackupRecord, containerVersion: Int) {
            require(record == record())
        }
        override suspend fun read(database: MocsDatabase) = emptyList<GameBackupRecord>()
        override fun prepareRestore(record: GameBackupRecord): PreparedGameBackup {
            prepareCount++
            beforePrepare()
            return object : PreparedGameBackup {
                override val storageSlot = descriptor.storageSlot
                override suspend fun write(database: MocsDatabase) { writeCount++; onWrite(database) }
            }
        }
    }
}
