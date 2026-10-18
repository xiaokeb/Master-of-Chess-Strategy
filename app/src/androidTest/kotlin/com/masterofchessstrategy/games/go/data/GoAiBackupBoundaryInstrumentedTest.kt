package com.masterofchessstrategy.games.go.data

import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import com.masterofchessstrategy.engine.GameType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** SQL inventory fixtures intentionally use opaque bytes: reject before any Native decoding or clear. */
class GoAiBackupBoundaryInstrumentedTest {
    @Test fun anAiActiveRowWithoutAnAdapterBlocksExportAndReplacement() = runBlocking {
        withOwned { db ->
            val row = active(GameType.GO.code)
            db.goAiCheckpointDao().saveActive(row)
            assertBlocked(db)
            assertArrayEquals(row.checkpoint, db.goAiCheckpointDao().findActive(row.gameTypeCode)!!.checkpoint)
        }
    }

    @Test fun anOrphanAiArchiveWithoutAnAdapterBlocksExportAndReplacement() = runBlocking {
        withOwned { db ->
            val row = archive(GameType.GO.code)
            db.goAiCheckpointDao().insertArchive(row)
            assertBlocked(db)
            assertArrayEquals(row.checkpoint, db.goAiCheckpointDao().findArchive(row.recordId)!!.checkpoint)
            assertTrue(db.goAiCheckpointDao().listActive().isEmpty())
        }
    }

    @Test fun unknownAndForeignActualAiRowsAreNotFilteredOut() = runBlocking {
        for (code in listOf(999, GameType.CHINESE_CHESS.code)) {
            withOwned { db ->
                db.goAiCheckpointDao().saveActive(active(code))
                assertBlocked(db)
                assertArrayEquals(active(code).checkpoint, db.goAiCheckpointDao().findActive(code)!!.checkpoint)
            }
            withOwned { db ->
                val row = archive(code)
                db.goAiCheckpointDao().insertArchive(row)
                assertBlocked(db)
                assertArrayEquals(row.checkpoint, db.goAiCheckpointDao().findArchive(row.recordId)!!.checkpoint)
            }
        }
    }

    private suspend fun assertBlocked(db: MocsDatabase) {
        val settings = AppSettings.DEFAULT.copy(soundEnabled = false, updatedAtEpochMillis = 77)
        RoomAppSettingsRepository(db.appSettingsDao()).save(settings)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val registry = GameBackupRegistry(emptyList()) // No AI adapter is registered in this slice.
        val repository = RoomLocalDataBackupRepository(db,
            ChineseChessEndgamePackParser.loadBundled(context.assets), { false }, registry)
        expectRejected { repository.export(100) }
        expectRejected { repository.restore(LocalDataBackupCodec.encode(LocalDataSnapshot(1), registry)) }
        assertEquals(settings, (RoomAppSettingsRepository(db.appSettingsDao()).load() as LoadAppSettingsResult.Loaded).settings)
        for (scope in GoLegacyImportScopes.ALL) assertNull(db.localDataMigrationDao().find(scope))
    }

    private suspend fun expectRejected(block: suspend () -> Any?) {
        try { block(); fail("Missing AI adapter must reject before modifying data") }
        catch (_: IllegalArgumentException) { }
    }

    private suspend fun withOwned(block: suspend (MocsDatabase) -> Unit) {
        val db = Room.inMemoryDatabaseBuilder(InstrumentationRegistry.getInstrumentation().targetContext,
            MocsDatabase::class.java).build()
        try { block(db) } finally { db.close() }
    }

    private fun active(code: Int) = GoAiSessionEntity(code, 6, byteArrayOf(7, 8, 9), 2, null, 31)
    private fun archive(code: Int) = GoAiArchiveEntity("a".repeat(64), code, 6, byteArrayOf(1, 2, 3), 1, null, 41)
}
