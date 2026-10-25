package com.masterofchessstrategy.games.lanzhou_fangqi.data

import android.content.Context
import android.content.ContextWrapper
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser
import com.masterofchessstrategy.engine.NativeChineseChessEngine
import com.masterofchessstrategy.engine.RestoreResult
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiAction
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.FangqiPoint
import com.masterofchessstrategy.engine.games.lanzhou_fangqi.NativeLanzhouFangqiSession
import com.masterofchessstrategy.games.go.data.GoLocalStorage
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiStoredMode
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiStoredSession
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

/** Actual production factory, container, Native admission and Room transactions; no user database. */
class FangqiCompleteBackupInstrumentedTest {
    @Test fun completeExportRestoreKeepsEveryActiveFieldAndRetiresTheOldStore() = withOwnedDatabase { f ->
        val store = RoomFangqiSessionStore(f.db)
        val saved = f.snapshot(1)
        store.save(saved)
        val original = f.row()
        val bytes = f.repository.export(900L)
        assertTrue(bytes.toString(Charsets.UTF_8).contains("\nFANGQI_ACTIVE|4|5|1|0|-|"))
        store.save(f.snapshot(2))
        assertNotEquals(original, f.row())
        f.repository.restore(bytes)
        assertEquals(original, f.row())
        assertTrue(runCatching { store.save(saved) }.exceptionOrNull() is IOException)
        val restored = checkNotNull(RoomFangqiSessionStore(f.db).load())
        assertEquals(saved.sessionId, restored.sessionId)
        assertEquals(saved.undoUseCount, restored.undoUseCount)
        assertEquals(saved.hintUseCount, restored.hintUseCount)
        assertArrayEquals(saved.engineState, restored.engineState)
    }

    @Test fun oldBackupWithoutFangqiClearsItsSlotAndRejectsTheOldOwner() = withOwnedDatabase { f ->
        val store = RoomFangqiSessionStore(f.db)
        val saved = f.snapshot(1)
        store.save(saved)
        val old = LocalDataBackupCodec.encode(LocalDataSnapshot(901L), GameBackupRegistry(emptyList()))
        assertTrue(old.toString(Charsets.UTF_8).startsWith("MOCS-BACKUP|4\n"))
        f.repository.restore(old)
        assertNull(f.row())
        assertTrue(runCatching { store.save(saved) }.exceptionOrNull() is IOException)
        assertNull(RoomFangqiSessionStore(f.db).load())
    }

    @Test fun checksummedButIllegalNativeHistoryIsRejectedBeforeClearingExistingData() = withOwnedDatabase { f ->
        val saved = f.snapshot(1)
        RoomFangqiSessionStore(f.db).save(saved)
        val original = f.row()
        val adapter = FangqiBackupAdapter()
        val good = adapter.encode(saved)
        val parts = saved.engineState.toString(Charsets.US_ASCII).split('|', limit = 6).toMutableList()
        parts[4] = "1"
        parts[5] = "m,1,0,1" // Red cannot move the black piece on the standard top rank.
        val fields = good.fields.toMutableList()
        fields[10] = parts.joinToString("|").toByteArray(Charsets.US_ASCII).joinToString("") {
            (it.toInt() and 255).toString(16).padStart(2, '0')
        }
        val bad = GameBackupRecord(FangqiBackupAdapter.KIND, fields)
        adapter.validate(bad, 5) // Envelope is valid; only real Native replay can reject this.
        val wire = LocalDataBackupCodec.encode(LocalDataSnapshot(902L, gameExtensions = listOf(bad)),
            GameBackupRegistry(listOf(adapter)))
        val error = runCatching { f.repository.restore(wire) }.exceptionOrNull()
        assertTrue("Expected actual Native history rejection, got $error", error is IOException)
        assertEquals(original, f.row())
    }

    @Test fun failureDuringPreparedWriteRollsBackTheRealClearingTransaction() = withOwnedDatabase { f ->
        val store = RoomFangqiSessionStore(f.db)
        store.save(f.snapshot(1))
        val bytes = f.repository.export(903L)
        store.save(f.snapshot(2))
        val retained = f.row()
        f.db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER fangqi_restore_test_failure BEFORE INSERT ON active_games
            WHEN NEW.gameTypeCode = 4 BEGIN SELECT RAISE(ABORT, 'owned Fangqi restore fault'); END
        """.trimIndent())
        try {
            val failure = runCatching { f.repository.restore(bytes) }.exceptionOrNull()
            assertNotNull("Restore must expose the actual SQLite write failure", failure)
            assertTrue(failure is android.database.sqlite.SQLiteException)
            assertEquals(retained, f.row())
        } finally {
            f.db.openHelper.writableDatabase.execSQL("DROP TRIGGER fangqi_restore_test_failure")
        }
    }

    private class Fixture {
        private val context = InstrumentationRegistry.getInstrumentation().targetContext
        private val directory = File(context.cacheDir, "fangqi-backup-${UUID.randomUUID()}").apply { check(mkdir()) }
        val db = Room.inMemoryDatabaseBuilder(context, MocsDatabase::class.java).build()
        private val isolated = object : ContextWrapper(context) {
            override fun getApplicationContext(): Context = this
            override fun getDataDir(): File = directory
            override fun getFileStreamPath(name: String): File {
                require(name.matches(Regex("[a-z0-9-]+\\.bin")))
                return File(directory, name)
            }
        }
        val repository = createProductionGameBackupRepository(db,
            ChineseChessEndgamePackParser.loadBundled(context.assets),
            GoLocalStorage(db, isolated), FangqiLocalStorage.forDatabase(db)) { bytes ->
            NativeChineseChessEngine().use { it.restore(bytes) == RestoreResult.Restored }
        }
        suspend fun row() = db.activeGameDao().find(4)
        fun snapshot(actions: Int): FangqiStoredSession = NativeLanzhouFangqiSession().use { native ->
            if (actions >= 1) assertTrue(native.apply(FangqiAction.Move(FangqiPoint(0, 5), FangqiPoint(0, 4))).accepted)
            if (actions >= 2) assertTrue(native.apply(FangqiAction.Move(FangqiPoint(0, 1), FangqiPoint(0, 2))).accepted)
            FangqiStoredSession(FangqiStoredMode.LOCAL_TWO_PLAYER, null, native.serialize(),
                UUID.randomUUID().toString(), 100L + actions, actions, 7, 9)
        }
        fun close() {
            db.close()
            assertTrue("Isolated legacy source was unexpectedly written", directory.listFiles()!!.isEmpty())
            assertTrue(directory.delete())
        }
    }

    private fun withOwnedDatabase(block: suspend (Fixture) -> Unit) = runBlocking {
        val fixture = Fixture()
        try { block(fixture) } finally { fixture.close() }
    }
}
