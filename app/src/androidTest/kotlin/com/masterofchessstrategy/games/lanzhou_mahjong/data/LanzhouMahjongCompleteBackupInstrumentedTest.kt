package com.masterofchessstrategy.games.lanzhou_mahjong.data

import com.masterofchessstrategy.data.GameBackupRegistry
import com.masterofchessstrategy.data.LocalDataBackupCodec
import com.masterofchessstrategy.data.LocalDataSnapshot
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

/** The actual shared production factory and complete restore transaction, using only an owned UUID disk database. */
class LanzhouMahjongCompleteBackupInstrumentedTest {
    @Test fun productionExportRestorePreservesEveryFieldAndRetiresTheOldLanzhouStore() = withOwnedLanzhouDatabase { f ->
        val store = f.store()
        val original = LanzhouMahjongNativeFixtures.snapshot(rule = 1)
        store.save(original)
        val before = requireNotNull(f.row())
        val bytes = f.repository().export(900)
        assertTrue(bytes.toString(Charsets.UTF_8).contains("\nLANZHOU_MAHJONG_ACTIVE|3|1|123|"))
        store.save(LanzhouMahjongNativeFixtures.snapshot(rule = 2, usage = 1, timestamp = 456))
        assertNotEquals(before, f.row())
        f.repository().restore(bytes)
        assertEquals(before, f.row())
        assertTrue(runCatching { store.save(original) }.exceptionOrNull() is IOException)
        val loaded = requireNotNull(f.store().load())
        assertEquals(69, before.sessionId.length)
        assertEquals(original.record.assistance, loaded.record.assistance)
        assertEquals(original.record.decisions, loaded.record.decisions)
        assertEquals(original.record.nextChoiceSeed, loaded.record.nextChoiceSeed)
        assertArrayEquals(original.record.nativeState, loaded.record.nativeState)
        f.reopen()
        assertEquals(before, f.row())
        assertArrayEquals(original.record.nativeState, requireNotNull(f.store().load()).record.nativeState)
    }

    @Test fun checksummedIllegalHistoryFailsPrepareBeforeAnyDatabaseClearing() = withOwnedLanzhouDatabase { f ->
        val store = f.store()
        val original = LanzhouMahjongNativeFixtures.snapshot()
        store.save(original)
        val before = f.row()
        val adapter = LanzhouMahjongBackupAdapter()
        val bad = adapter.encode(LanzhouMahjongNativeFixtures.illegalDrawSnapshot())
        adapter.validate(bad, 5) // Full checksums/structure are valid; the history is deliberately illegal.
        val bytes = LocalDataBackupCodec.encode(LocalDataSnapshot(901, gameExtensions = listOf(bad)),
            GameBackupRegistry(listOf(adapter)))
        val failure = runCatching { f.repository().restore(bytes) }.exceptionOrNull()
        assertTrue("Expected real Native prepare rejection, got $failure", failure is IOException)
        assertEquals(before, f.row())
        assertTrue(runCatching { store.save(original) }.exceptionOrNull() is IOException)
        assertArrayEquals(original.record.nativeState, requireNotNull(f.store().load()).record.nativeState)
    }

    @Test fun aRealSqlInsertFaultRollsBackTheCompleteClearingAndPreparedWriteTransaction() = withOwnedLanzhouDatabase { f ->
        val store = f.store()
        val original = LanzhouMahjongNativeFixtures.snapshot(rule = 1)
        store.save(original)
        val backup = f.repository().export(902)
        val retained = LanzhouMahjongNativeFixtures.snapshot(rule = 2, usage = 1, timestamp = 456)
        store.save(retained)
        val before = f.row()
        f.db.openHelper.writableDatabase.execSQL("""
            CREATE TRIGGER lzmj_owned_restore_fault BEFORE INSERT ON active_games
            WHEN NEW.gameTypeCode = 3 BEGIN SELECT RAISE(ABORT, 'owned Lanzhou restore fault'); END
        """.trimIndent())
        try {
            val failure = runCatching { f.repository().restore(backup) }.exceptionOrNull()
            assertNotNull("Restore must report the actual SQL write failure", failure)
            assertTrue(failure is android.database.sqlite.SQLiteException)
            assertEquals(before, f.row())
        } finally {
            f.db.openHelper.writableDatabase.execSQL("DROP TRIGGER lzmj_owned_restore_fault")
        }
        assertTrue(runCatching { store.save(retained) }.exceptionOrNull() is IOException)
        f.reopen()
        assertEquals(before, f.row())
        assertArrayEquals(retained.record.nativeState, requireNotNull(f.store().load()).record.nativeState)
    }

    @Test fun aLegacyContainerWithoutLanzhouClearsItsSlotAndNeverLetsTheOldOwnerRefillIt() = withOwnedLanzhouDatabase { f ->
        val store = f.store()
        val original = LanzhouMahjongNativeFixtures.snapshot()
        store.save(original)
        val old = LocalDataBackupCodec.encode(LocalDataSnapshot(903), GameBackupRegistry(emptyList()))
        assertTrue(old.toString(Charsets.UTF_8).startsWith("MOCS-BACKUP|4\n"))
        f.repository().restore(old)
        assertNull(f.row())
        assertTrue(runCatching { store.save(original) }.exceptionOrNull() is IOException)
        assertNull(f.store().load())
        f.reopen()
        assertNull(f.store().load())
    }
}
