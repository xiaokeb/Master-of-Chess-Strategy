package com.masterofchessstrategy.games.lanzhou_mahjong.data

import androidx.room.testing.MigrationTestHelper
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real SQLite and production backup composition; every database is UUID-owned by this test. */
class LanzhouMahjongLearningRoomInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule val migration = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MocsDatabase::class.java)

    @Test fun migration19To20PreservesLegacyFourStepRowAndAddsEmptyIndependentProgress() {
        val name = "lzmj-learning-migration-${UUID.randomUUID()}.db"
        assertFalse(context.getDatabasePath(name).exists())
        try {
            migration.createDatabase(name, 19).use { db ->
                db.execSQL("INSERT INTO tutorial_progress VALUES (3,1,4,1,77)")
                db.execSQL("INSERT INTO fangqi_learning_progress VALUES ('lesson:opening',1,55)")
            }
            migration.runMigrationsAndValidate(name, 20, true, MocsDatabase.MIGRATION_19_20).use { db ->
                db.query("SELECT contentVersion,completedStepCount,isCompleted,updatedAtEpochMillis FROM tutorial_progress WHERE gameTypeCode=3").use {
                    assertTrue(it.moveToFirst())
                    assertEquals(1, it.getInt(0)); assertEquals(4, it.getInt(1))
                    assertEquals(1, it.getInt(2)); assertEquals(77L, it.getLong(3))
                }
                db.query("SELECT COUNT(*) FROM lanzhou_mahjong_learning_progress").use {
                    assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
                }
                db.query("SELECT completedAtEpochMillis FROM fangqi_learning_progress WHERE contentKey='lesson:opening'").use {
                    assertTrue(it.moveToFirst()); assertEquals(55L, it.getLong(0))
                }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun allTenCompletionsSurviveReopenWithoutChangingLegacyProgressOrIssuingRewards() = withOwnedLanzhouDatabase { f ->
        val legacy = TutorialProgressEntity(3, 1, 4, true, 77)
        f.db.tutorialProgressDao().upsert(legacy)
        val repo = RoomLanzhouMahjongLearningRepository(f.db)
        assertNull(repo.load())
        var current: LanzhouMahjongLearningSnapshot? = null
        for (count in 1..10) {
            val request = LanzhouMahjongLearningAdvance(current, progress(count))
            current = repo.advance(request)
            assertEquals(current, repo.advance(request))
            assertEquals(count == 10, current.isCompleted)
        }
        assertEquals(legacy, f.db.tutorialProgressDao().find(3))
        assertTrue(f.db.matchOutcomeDao().listAll().isEmpty())
        f.reopen()
        assertEquals(progress(10), RoomLanzhouMahjongLearningRepository(f.db).load())
        assertEquals(legacy, f.db.tutorialProgressDao().find(3))
    }

    @Test fun realSqlFailurePreservesOldProgressAndTheExactAdvanceCanBeRetried() = withOwnedLanzhouDatabase { f ->
        val repo = RoomLanzhouMahjongLearningRepository(f.db)
        repo.advance(LanzhouMahjongLearningAdvance(null, progress(1)))
        val request = LanzhouMahjongLearningAdvance(progress(1), progress(2))
        f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER lzmj_learning_fault BEFORE INSERT ON " +
            "lanzhou_mahjong_learning_progress BEGIN SELECT RAISE(ABORT, 'owned learning write fault'); END")
        try {
            assertTrue(runCatching { repo.advance(request) }.exceptionOrNull() is android.database.sqlite.SQLiteException)
            assertEquals(progress(1), repo.load())
        } finally { f.db.openHelper.writableDatabase.execSQL("DROP TRIGGER lzmj_learning_fault") }
        assertEquals(request.after, repo.advance(request))
        assertEquals(request.after, repo.load())
    }

    @Test fun cancellationAfterActualInsertRollsBackTheRoomTransaction() = withOwnedLanzhouDatabase { f ->
        val actual = f.db.lanzhouMahjongLearningProgressDao()
        val dao = object : LanzhouMahjongLearningProgressDao by actual {
            override suspend fun upsert(row: LanzhouMahjongLearningProgressEntity) {
                actual.upsert(row)
                throw CancellationException("owned after-insert cancellation")
            }
        }
        val gate = LanzhouMahjongStorageEpochGate.forDatabase(f.db)
        val repo = RoomLanzhouMahjongLearningRepository(dao, gate, gate.captureOwner(), Dispatchers.IO) { operation ->
            f.db.withTransaction { operation() }
        }
        assertTrue(runCatching { repo.advance(LanzhouMahjongLearningAdvance(null, progress(1))) }.exceptionOrNull() is CancellationException)
        assertTrue(actual.readBoundedRows().isEmpty())
    }

    @Test fun productionBackupPreservesExactProgressAndRetiresPreviousWriter() = withOwnedLanzhouDatabase { f ->
        val repo = RoomLanzhouMahjongLearningRepository(f.db)
        repo.advance(LanzhouMahjongLearningAdvance(null, progress(1)))
        val backup = f.repository().export(900)
        assertTrue(backup.toString(Charsets.UTF_8).contains("\nLANZHOU_MAHJONG_LEARNING|3|1|2|1|100\n"))
        repo.advance(LanzhouMahjongLearningAdvance(progress(1), progress(2)))
        f.repository().restore(backup)
        assertEquals(progress(1), RoomLanzhouMahjongLearningRepository(f.db).load())
        assertTrue(runCatching { repo.advance(LanzhouMahjongLearningAdvance(progress(2), progress(3))) }.exceptionOrNull() is IOException)
        f.reopen()
        assertEquals(progress(1), RoomLanzhouMahjongLearningRepository(f.db).load())
    }

    @Test fun failedRestoreRollsBackClearingAndLegacyContainerClearsOnlyThroughWholeRestore() = withOwnedLanzhouDatabase { f ->
        var repo = RoomLanzhouMahjongLearningRepository(f.db)
        repo.advance(LanzhouMahjongLearningAdvance(null, progress(1)))
        val backup = f.repository().export(901)
        repo.advance(LanzhouMahjongLearningAdvance(progress(1), progress(2)))
        f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER lzmj_learning_restore_fault BEFORE INSERT ON " +
            "lanzhou_mahjong_learning_progress BEGIN SELECT RAISE(ABORT, 'owned learning restore fault'); END")
        try {
            assertTrue(runCatching { f.repository().restore(backup) }.exceptionOrNull() is android.database.sqlite.SQLiteException)
            assertEquals(progress(2), RoomLanzhouMahjongLearningRepository(f.db).load())
        } finally { f.db.openHelper.writableDatabase.execSQL("DROP TRIGGER lzmj_learning_restore_fault") }
        repo = RoomLanzhouMahjongLearningRepository(f.db)
        val old = LocalDataBackupCodec.encode(LocalDataSnapshot(902), GameBackupRegistry(emptyList()))
        f.repository().restore(old)
        assertNull(RoomLanzhouMahjongLearningRepository(f.db).load())
        assertTrue(runCatching { repo.advance(LanzhouMahjongLearningAdvance(progress(2), progress(3))) }.exceptionOrNull() is IOException)
    }

    @Test fun missingAdapterOrMalformedLocalRowBlocksExportWithoutErasingAnything() = withOwnedLanzhouDatabase { f ->
        val repo = RoomLanzhouMahjongLearningRepository(f.db)
        repo.advance(LanzhouMahjongLearningAdvance(null, progress(1)))
        val missing = RoomLocalDataBackupRepository(f.db, ChineseChessEndgamePackParser.loadBundled(context.assets),
            { false }, GameBackupRegistry(emptyList()))
        assertTrue(runCatching { missing.export(903) }.isFailure)
        assertEquals(progress(1), repo.load())
        f.db.openHelper.writableDatabase.execSQL("UPDATE lanzhou_mahjong_learning_progress SET contentVersion=99")
        assertTrue(runCatching { repo.load() }.exceptionOrNull() is IOException)
        assertTrue(runCatching { f.repository().export(904) }.isFailure)
        assertEquals(99, f.db.lanzhouMahjongLearningProgressDao().readBoundedRows().single().contentVersion)
    }

    private fun progress(count: Int) = LanzhouMahjongLearningSnapshot(2, count, count * 100L)
}
