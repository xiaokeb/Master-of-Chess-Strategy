package com.masterofchessstrategy.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.NativeChineseChessEngine
import com.masterofchessstrategy.engine.RestoreResult
import com.masterofchessstrategy.engine.games.tiger_goat.NativeTigerGoatSession
import com.masterofchessstrategy.engine.games.tiger_goat.TigerGoatAction
import com.masterofchessstrategy.engine.games.tiger_goat.TigerGoatActionKind
import com.masterofchessstrategy.games.tiger_goat.data.TigerGoatBackupAdapter
import java.util.UUID
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/** UUID-owned real disk databases only; never clears the application/user database. */
class TigerGoatBackupRoomInstrumentedTest {
    private val tigerAdapter = TigerGoatBackupAdapter()
    private val registry = GameBackupRegistry(listOf(tigerAdapter))
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val sourceName = "tiger-backup-source-${UUID.randomUUID()}.db"
    private val targetName = "tiger-backup-target-${UUID.randomUUID()}.db"
    private lateinit var source: MocsDatabase
    private lateinit var target: MocsDatabase

    @Before fun prepare() { source = open(sourceName); target = open(targetName) }
    @After fun cleanup() {
        source.close(); target.close()
        deleteOwnedDatabase(sourceName)
        deleteOwnedDatabase(targetName)
    }

    @Test fun mixedXiangqiAndTigerExportRestoreDiskReopenAndUndo() = runBlocking {
        val chess = NativeChineseChessEngine().use { it.serialize() }
        RoomGameSessionRepository(source.activeGameDao()).save(GameSessionSnapshot(
            GameType.CHINESE_CHESS, StoredGameMode.LOCAL_TWO_PLAYER, null, chess, 7,
            sessionId = "xiangqi-owned"))
        val tiger = openingArchive("tiger-owned", moved = true)
        source.activeGameDao().upsert(tiger.toEntity())
        val settings = AppSettings.DEFAULT.copy(selectedAppearanceCode = 5,
            highlightConditionsMask = 7, aiFirstEnabled = true, updatedAtEpochMillis = 9)
        RoomAppSettingsRepository(source.appSettingsDao()).save(settings)
        val bytes = backup(source).export(20)
        val decoded = LocalDataBackupCodec.decode(bytes, registry)
        assertEquals(1, decoded.activeSessions.size)
        assertEquals(listOf(tiger), decoded.gameExtensions.map(tigerAdapter::decodeRecord))
        assertTrue(bytes.toString(Charsets.UTF_8).startsWith("MOCS-BACKUP|5\n"))
        val summary = backup(target).restore(bytes)
        assertEquals(2, summary.activeSessionCount)
        target.close(); target = open(targetName)
        assertEquals(settings, (RoomAppSettingsRepository(target.appSettingsDao()).load() as LoadAppSettingsResult.Loaded).settings)
        assertEquals(tiger, TigerGoatActiveArchive.fromEntity(target.activeGameDao().find(5)!!))
        assertArrayEquals(chess, target.activeGameDao().find(0)!!.engineState)
        NativeTigerGoatSession().use { restored ->
            assertTrue(restored.restore(target.activeGameDao().find(5)!!.engineState))
            assertEquals(2, restored.snapshot().actionCount)
            assertTrue(restored.undo())
            assertEquals(14, restored.snapshot().reserve)
            assertEquals(2, restored.snapshot().side)
            assertTrue(restored.undo())
            assertEquals(0, restored.snapshot().actionCount)
        }
    }

    @Test fun nativeIllegalReplayWithValidChecksumsDoesNotClearTarget() = runBlocking {
        val old = openingArchive("keep-old")
        target.activeGameDao().upsert(old.toEntity())
        val illegal = openingArchive("invalid-source", moved = true).engineState
        illegal[52] = 6 // A goat cannot force-pass while fourteen goats remain in reserve.
        illegal[53] = (-1).toByte(); illegal[54] = (-1).toByte()
        checksum(illegal)
        val badArchive = TigerGoatActiveArchive("invalid-source", illegal, 2, 3)
        val bytes = LocalDataBackupCodec.encode(LocalDataSnapshot(8,
            gameExtensions = listOf(tigerAdapter.encodeArchive(badArchive))), registry)
        assertFalse(validateTigerGoatActiveArchive(badArchive))
        try { backup(target).restore(bytes); fail("Native replay rejection must abort before clear") }
        catch (_: IllegalArgumentException) { }
        target.close(); target = open(targetName)
        assertEquals(old, TigerGoatActiveArchive.fromEntity(target.activeGameDao().find(5)!!))
    }

    @Test fun exportRejectsUnsupportedTigerMetadataWithoutChangingItsRow() = runBlocking {
        val bad = openingArchive("keep-invalid").toEntity().copy(undoUseCount = 1)
        source.activeGameDao().upsert(bad)
        try { backup(source).export(20); fail("Unsupported metadata must not be discarded") }
        catch (_: IllegalArgumentException) { }
        assertEquals(bad, source.activeGameDao().find(5))
    }

    @Test fun importingOldV4IsExplicitWholeDataReplacementNotMerge() = runBlocking {
        target.activeGameDao().upsert(openingArchive("old-tiger").toEntity())
        val bytes = LocalDataBackupCodec.encode(LocalDataSnapshot(10), registry)
        assertTrue(bytes.toString(Charsets.UTF_8).startsWith("MOCS-BACKUP|4\n"))
        assertEquals(0, backup(target).restore(bytes).activeSessionCount)
        assertNull(target.activeGameDao().find(5))
    }

    @Test fun insertionFailureRollsBackAllTablesAfterClear() = runBlocking {
        val old = openingArchive("old-rollback")
        target.activeGameDao().upsert(old.toEntity())
        val chess = NativeChineseChessEngine().use { it.serialize() }
        RoomGameSessionRepository(target.activeGameDao()).save(GameSessionSnapshot(
            GameType.CHINESE_CHESS, StoredGameMode.LOCAL_TWO_PLAYER, null, chess, 7,
            sessionId = "old-chess"))
        // Deliberate write failure in this UUID-owned test DB, not in the production database.
        target.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER tiger_backup_fail BEFORE INSERT ON active_games " +
                "WHEN NEW.gameTypeCode = 5 BEGIN SELECT RAISE(ABORT, 'tiger-backup-write-rejected'); END",
        )
        val bytes = LocalDataBackupCodec.encode(LocalDataSnapshot(8,
            gameExtensions = listOf(tigerAdapter.encodeArchive(openingArchive("replacement", moved = true)))), registry)
        val failure = try { backup(target).restore(bytes); null } catch (error: Exception) { error }
        assertNotNull("The actual SQL trigger must abort insertion", failure)
        assertTrue(generateSequence<Throwable>(failure) { it.cause }.any {
            it.message?.contains("tiger-backup-write-rejected") == true
        })
        target.close(); target = open(targetName)
        assertEquals(old, TigerGoatActiveArchive.fromEntity(target.activeGameDao().find(5)!!))
        assertArrayEquals(chess, target.activeGameDao().find(0)!!.engineState)
        assertEquals("old-chess", target.activeGameDao().find(0)!!.sessionId)
    }

    @Test fun missingTigerAdapterRejectsExportAndKeepsDiskRow() = runBlocking {
        val old = openingArchive("missing-adapter-export")
        source.activeGameDao().upsert(old.toEntity())
        try { backup(source, GameBackupRegistry(emptyList())).export(20); fail("Missing adapter must not omit a stored row") }
        catch (_: IllegalArgumentException) { }
        source.close(); source = open(sourceName)
        assertEquals(old, TigerGoatActiveArchive.fromEntity(source.activeGameDao().find(5)!!))
    }

    @Test fun missingTigerAdapterRejectsOldEmptyV4RestoreAndKeepsDiskRow() = runBlocking {
        val old = openingArchive("missing-adapter-restore")
        target.activeGameDao().upsert(old.toEntity())
        val empty = GameBackupRegistry(emptyList())
        val bytes = LocalDataBackupCodec.encode(LocalDataSnapshot(10), empty)
        assertTrue(bytes.toString(Charsets.UTF_8).startsWith("MOCS-BACKUP|4\n"))
        try { backup(target, empty).restore(bytes); fail("Missing adapter must not clear a stored row") }
        catch (_: IllegalArgumentException) { }
        target.close(); target = open(targetName)
        assertEquals(old, TigerGoatActiveArchive.fromEntity(target.activeGameDao().find(5)!!))
    }

    private fun backup(database: MocsDatabase, gameBackups: GameBackupRegistry = registry) = RoomLocalDataBackupRepository(
        database, ChineseChessEndgamePackParser.loadBundled(context.assets),
        validateEngineState = { bytes -> NativeChineseChessEngine().use { it.restore(bytes) == RestoreResult.Restored } },
        gameBackups = gameBackups,
    )

    private fun open(name: String) = Room.databaseBuilder(context, MocsDatabase::class.java, name).build()

    private fun deleteOwnedDatabase(name: String) {
        val base = context.getDatabasePath(name)
        val files = listOf(base, File(base.path + "-wal"), File(base.path + "-shm"), File(base.path + "-journal"))
        // Room opens lazily: a built but unused DB has no file and nothing to delete.
        if (files.any(File::exists)) assertTrue(context.deleteDatabase(name))
        files.forEach { assertFalse("Owned database artifact remains", it.exists()) }
    }

    private fun openingArchive(id: String, moved: Boolean = false): TigerGoatActiveArchive =
        NativeTigerGoatSession().use { native ->
            if (moved) {
                assertEquals(0, native.apply(TigerGoatAction(TigerGoatActionKind.REMOVE_GOAT, 6)))
                assertEquals(0, native.apply(TigerGoatAction(TigerGoatActionKind.PLACE_GOAT, to = 0)))
            }
            TigerGoatActiveArchive(id, native.serialize(), native.snapshot().actionCount, 3)
        }

    private fun checksum(bytes: ByteArray) {
        var hash = 0x811c9dc5.toInt()
        for (index in 0 until bytes.size - 4) hash = (hash xor (bytes[index].toInt() and 255)) * 16777619
        repeat(4) { bytes[bytes.size - 4 + it] = (hash ushr (8 * it)).toByte() }
    }
}
