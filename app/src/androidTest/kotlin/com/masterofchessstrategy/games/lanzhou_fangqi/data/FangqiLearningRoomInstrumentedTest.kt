package com.masterofchessstrategy.games.lanzhou_fangqi.data

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import com.masterofchessstrategy.engine.GameResult
import com.masterofchessstrategy.engine.NativeChineseChessEngine
import com.masterofchessstrategy.engine.RestoreResult
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiSide
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.NativeLanzhouFangqiSession
import com.masterofchessstrategy.games.go.data.GoLocalStorage
import com.masterofchessstrategy.games.lanzhou_fangqi.LanzhouFangqiLearningContent
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real owned SQLite/Native fixtures; never open or wipe the default user database. */
class FangqiLearningRoomInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    @get:Rule val migration = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MocsDatabase::class.java)

    @Test fun migration18To19PreservesExistingBytesAndAddsOnlyEmptyLearningTable() {
        val name = "fangqi-learning-migration-${UUID.randomUUID()}.db"
        try {
            migration.createDatabase(name, 18).use { db ->
                // Byte-preservation fixtures, not claims of legal stored positions.
                db.execSQL("INSERT INTO active_games (gameTypeCode,modeCode,difficultyCode,envelopeVersion," +
                    "engineFormatVersion,engineState,updatedAtEpochMillis) VALUES (4,0,NULL,5,1,X'010203',55)")
                db.execSQL("INSERT INTO go_local_sessions VALUES (1,3,X'070809',99)")
                db.execSQL("INSERT INTO local_data_migrations VALUES ('go-legacy-learning-preferences',2)")
            }
            migration.runMigrationsAndValidate(name, 19, true, MocsDatabase.MIGRATION_18_19).use { db ->
                db.query("SELECT hex(engineState),updatedAtEpochMillis FROM active_games WHERE gameTypeCode=4").use {
                    assertTrue(it.moveToFirst()); assertEquals("010203", it.getString(0)); assertEquals(55L, it.getLong(1))
                }
                db.query("SELECT hex(checkpoint),updatedAtEpochMillis FROM go_local_sessions WHERE gameTypeCode=1").use {
                    assertTrue(it.moveToFirst()); assertEquals("070809", it.getString(0)); assertEquals(99L, it.getLong(1))
                }
                db.query("SELECT handledVersion FROM local_data_migrations WHERE scope='go-legacy-learning-preferences'").use {
                    assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0))
                }
                db.query("SELECT COUNT(*) FROM fangqi_learning_progress").use {
                    assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
                }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun allAuthoredLessonsAndActualNativePracticeWinsSurviveDiskCloseAndReopen() = withFixture { f ->
        val store = f.store()
        LanzhouFangqiLearningContent.lessons.forEach { store.acknowledgeLesson(it.id) }
        LanzhouFangqiLearningContent.practices.forEach { store.completePractice(it.id, wonHistory(it.id)) }
        val saved = store.load()
        assertEquals(9, saved.rows.size)
        assertTrue(saved.tutorialComplete)
        val firstTimes = saved.rows
        store.acknowledgeLesson("opening")
        assertEquals(firstTimes, store.load().rows)
        f.reopen()
        assertEquals(firstTimes, f.store().load().rows)
        assertTrue(f.db.matchOutcomeDao().listAll().isEmpty())
    }

    @Test fun wrongPracticeSeedAndUnfinishedHistoryNeverCreateLearningCompletion() = withFixture { f ->
        val store = f.store()
        val original = store.acknowledgeLesson("opening").rows
        val wrong = wonHistory("lfq-practice-square-01")
        assertTrue(runCatching { store.completePractice("lfq-practice-double-01", wrong) }.isFailure)
        val practice = LanzhouFangqiLearningContent.practices.first()
        NativeLanzhouFangqiSession.fromPosition(practice.board, FangqiSide.RED).use {
            assertTrue(runCatching { store.completePractice(practice.id, it.serialize()) }.isFailure)
        }
        assertEquals(original, store.load().rows)
    }

    @Test fun completeBackupRestoresFirstTimesRetiresOldWriterAndOldContainerClearsLearning() = withFixture { f ->
        val oldStore = f.store()
        oldStore.acknowledgeLesson("opening")
        oldStore.completePractice("lfq-practice-square-01", wonHistory("lfq-practice-square-01"))
        val original = oldStore.load().rows
        val repository = f.repository()
        val backup = repository.export(700)
        assertTrue(backup.toString(Charsets.UTF_8).contains("\nFANGQI_LEARNING|4|1|2|"))
        oldStore.acknowledgeLesson("step")
        repository.restore(backup)
        assertEquals(original, f.store().load().rows)
        assertTrue(runCatching { oldStore.acknowledgeLesson("step") }.exceptionOrNull() is IOException)
        val nextStore = f.store()
        val oldBackup = LocalDataBackupCodec.encode(LocalDataSnapshot(701), GameBackupRegistry(emptyList()))
        repository.restore(oldBackup)
        assertTrue(f.store().load().rows.isEmpty())
        assertTrue(runCatching { nextStore.acknowledgeLesson("step") }.exceptionOrNull() is IOException)
    }

    @Test fun realRestoreInsertFailureRollsBackLearningAndExposesFailure() = withFixture { f ->
        val store = f.store()
        store.acknowledgeLesson("opening")
        val repository = f.repository()
        val backup = repository.export(702)
        store.acknowledgeLesson("step")
        val retained = store.load().rows
        f.db.openHelper.writableDatabase.execSQL("CREATE TRIGGER fangqi_learning_restore_fault BEFORE INSERT ON " +
            "fangqi_learning_progress BEGIN SELECT RAISE(ABORT, 'owned learning restore failure'); END")
        try {
            val error = runCatching { repository.restore(backup) }.exceptionOrNull()
            assertTrue("Real SQLite fault must surface: $error", error is android.database.sqlite.SQLiteException)
            assertEquals(retained, f.store().load().rows)
        } finally { f.db.openHelper.writableDatabase.execSQL("DROP TRIGGER fangqi_learning_restore_fault") }
    }

    @Test fun unregisteredLearningTableAndCorruptStoredContentBlockExportWithoutClearing() = withFixture { f ->
        f.store().acknowledgeLesson("opening")
        val pack = ChineseChessEndgamePackParser.loadBundled(context.assets)
        val missing = RoomLocalDataBackupRepository(f.db, pack, { false }, GameBackupRegistry(emptyList()))
        assertTrue(runCatching { missing.export(703) }.isFailure)
        assertEquals(1, f.db.fangqiLearningProgressDao().readAll().size)
        f.db.openHelper.writableDatabase.execSQL("UPDATE fangqi_learning_progress SET contentVersion=99")
        assertTrue(runCatching { f.store().load() }.exceptionOrNull() is IOException)
        assertTrue(runCatching { f.repository().export(704) }.isFailure)
        assertEquals(99, f.db.fangqiLearningProgressDao().readAll().single().contentVersion)
    }

    private fun wonHistory(id: String): ByteArray {
        val practice = LanzhouFangqiLearningContent.practices.single { it.id == id }
        return NativeLanzhouFangqiSession.fromPosition(practice.board, FangqiSide.RED).use { native ->
            practice.exampleLine.forEach { assertTrue(native.apply(it).accepted) }
            assertEquals(GameResult.FIRST_PLAYER_WIN, native.snapshot().result)
            assertEquals(0, native.snapshot().pendingCaptures)
            native.serialize()
        }
    }

    private inner class Fixture {
        val name = "fangqi-learning-store-${UUID.randomUUID()}.db"
        private val directory = File(context.cacheDir, "fangqi-learning-backup-${UUID.randomUUID()}").apply { check(mkdir()) }
        var db = open()
            private set
        private fun open() = Room.databaseBuilder(context, MocsDatabase::class.java, name).build()
        fun store() = RoomFangqiLearningStore(db, db.fangqiLearningProgressDao())
        fun reopen() { db.close(); db = open() }
        fun repository(): LocalDataBackupRepository {
            val isolated = object : ContextWrapper(context) {
                override fun getApplicationContext(): Context = this
                override fun getDataDir(): File = directory
                override fun getFileStreamPath(name: String): File {
                    require(name.matches(Regex("[a-z0-9-]+\\.bin")))
                    return File(directory, name)
                }
            }
            return createProductionGameBackupRepository(db, ChineseChessEndgamePackParser.loadBundled(context.assets),
                GoLocalStorage(db, isolated), FangqiLocalStorage.forDatabase(db)) { bytes ->
                NativeChineseChessEngine().use { it.restore(bytes) == RestoreResult.Restored }
            }
        }
        fun close() {
            db.close()
            assertTrue(context.deleteDatabase(name))
            assertTrue(directory.listFiles()!!.isEmpty())
            assertTrue(directory.delete())
        }
    }

    private fun withFixture(block: suspend (Fixture) -> Unit) = runBlocking {
        val fixture = Fixture()
        try { block(fixture) } finally { fixture.close() }
    }
}
