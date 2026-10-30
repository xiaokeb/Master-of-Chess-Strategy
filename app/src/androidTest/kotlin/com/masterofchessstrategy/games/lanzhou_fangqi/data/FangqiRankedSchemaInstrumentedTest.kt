package com.masterofchessstrategy.games.lanzhou_fangqi.data

import androidx.room.testing.MigrationTestHelper
import androidx.room.withTransaction
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.*
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.games.lanzhou_fangqi.*
import com.masterofchessstrategy.games.lanzhou_mahjong.data.withOwnedLanzhouDatabase
import java.io.IOException
import java.util.UUID
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Real SQLite constraints/legacy guards, not ranked provenance or terminal verification. */
class FangqiRankedSchemaInstrumentedTest {
    @get:Rule val migration = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MocsDatabase::class.java)

    @Test fun migration20To21PreservesLearningAndCreatesEmptySources() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "fq-ranked-migration-${UUID.randomUUID()}.db"
        assertFalse(context.getDatabasePath(name).exists())
        try {
            migration.createDatabase(name, 20).use {
                it.execSQL("INSERT INTO fangqi_learning_progress VALUES ('lesson:opening',1,55)")
                it.execSQL("INSERT INTO lanzhou_mahjong_learning_progress VALUES (0,2,10,77)")
            }
            migration.runMigrationsAndValidate(name, 21, true, MocsDatabase.MIGRATION_20_21).use { db ->
                for (table in listOf("fangqi_ranked_starts", "fangqi_ranked_settlements")) {
                    db.query("SELECT COUNT(*) FROM $table").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
                }
                db.query("SELECT completedAtEpochMillis FROM fangqi_learning_progress").use { assertTrue(it.moveToFirst()); assertEquals(55L,it.getLong(0)) }
                db.query("SELECT updatedAtEpochMillis FROM lanzhou_mahjong_learning_progress").use { assertTrue(it.moveToFirst()); assertEquals(77L,it.getLong(0)) }
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun bothForeignKeysAreDeferredAndNeitherParentCanBeDeleted() = withOwnedLanzhouDatabase { f ->
        f.db.withTransaction {
            f.db.fangqiRankedDao().insertSettlement(settlement("linked"))
            f.db.fangqiRankedDao().insertStart(start("linked"))
            f.db.matchOutcomeDao().insert(outcome("linked"))
        }
        assertNotNull(f.db.fangqiRankedDao().findSettlement("linked"))
        for (parent in listOf("fangqi_ranked_starts", "match_outcomes")) {
            assertTrue(runCatching { f.db.withTransaction {
                f.db.openHelper.writableDatabase.execSQL("DELETE FROM $parent")
            } }.isFailure)
            assertNotNull(f.db.fangqiRankedDao().findStart("linked"))
            assertNotNull(f.db.matchOutcomeDao().find("linked"))
        }
        assertTrue(runCatching { f.db.withTransaction {
            f.db.fangqiRankedDao().insertSettlement(settlement("orphan"))
        } }.isFailure)
        assertNull(f.db.fangqiRankedDao().findSettlement("orphan"))
    }

    @Test fun blobOwnershipAndInsertOnlyRowsSurviveRealRoomRoundTrip() = withOwnedLanzhouDatabase { f ->
        val row = start("owned")
        f.db.fangqiRankedDao().insertStart(row)
        val read = requireNotNull(f.db.fangqiRankedDao().findStart("owned"))
        assertEquals(row, read)
        read.initialHistory.bytes.fill(0)
        assertEquals(row, f.db.fangqiRankedDao().findStart("owned"))
        assertTrue(runCatching { f.db.fangqiRankedDao().insertStart(row.copy(startedAtEpochMillis = 99)) }.isFailure)
        assertEquals(row, f.db.fangqiRankedDao().findStart("owned"))
        f.reopen()
        assertEquals(row, f.db.fangqiRankedDao().findStart("owned"))
    }

    @Test fun oldWriterCannotReadOverwriteOrReplaceAFormalSourceId() = withOwnedLanzhouDatabase { f ->
        val store = RoomFangqiSessionStore(f.db)
        val original = local("protected")
        store.save(original)
        val active = f.db.activeGameDao().find(4)
        // Deliberately raw storage fixture; an ID in the source table must fence legacy writers even if inconsistent.
        f.db.fangqiRankedDao().insertStart(start("protected"))
        assertTrue(runCatching { store.load() }.exceptionOrNull() is IOException)
        for (candidate in listOf(original, local("other"))) {
            assertTrue(runCatching { store.save(candidate) }.exceptionOrNull() is IOException)
            assertEquals(active, f.db.activeGameDao().find(4))
        }
    }

    @Test fun unsupportedRankedSlotsBlockBackupAndRestoreBeforeAnyClear() = withOwnedLanzhouDatabase { f ->
        // Deliberately omit all game adapters: the production factory now supports ranked Fangqi.
        val incomplete = RoomLocalDataBackupRepository(f.db,
            com.masterofchessstrategy.endgame.ChineseChessEndgamePackParser.loadBundled(
                InstrumentationRegistry.getInstrumentation().targetContext.assets), { true }, GameBackupRegistry(emptyList()))
        val empty = incomplete.export(10)
        val row = start("no-adapter")
        f.db.fangqiRankedDao().insertStart(row)
        assertTrue(BackupStorageSlot(BackupStorageTable.FANGQI_RANKED_START,
            com.masterofchessstrategy.engine.GameType.LANZHOU_SQUARE_CHESS) in BackupStorageInventory.read(f.db))
        assertTrue(runCatching { incomplete.export(20) }.isFailure)
        assertTrue(runCatching { incomplete.restore(empty) }.isFailure)
        assertEquals(row, f.db.fangqiRankedDao().findStart("no-adapter"))
    }

    @Test fun ordinaryLegacySlotWithoutRankedSourceStillSavesAndLoads() = withOwnedLanzhouDatabase { f ->
        val store = RoomFangqiSessionStore(f.db)
        val expected = local("legacy")
        store.save(expected)
        val read = requireNotNull(store.load())
        assertEquals(expected.sessionId, read.sessionId)
        assertArrayEquals(expected.engineState, read.engineState)
        assertTrue(f.db.fangqiRankedDao().readStarts().isEmpty())
    }

    private fun local(id: String) = FangqiStoredSession(FangqiStoredMode.LOCAL_TWO_PLAYER, null,
        FangqiRankedLedger.standardInitialHistory().bytes, id, 10)
    private fun start(id: String) = FangqiRankedStartEntity(id, 1, "NEW_STANDARD_HUMAN_GAME",4,1,0,0,
        FangqiRankedLedger.RULES_VERSION,1,FangqiRankedLedger.standardInitialHistory(),10,0,0,0,"0".repeat(64))
    private fun settlement(id: String) = FangqiRankedSettlementEntity(id,1,"0".repeat(64),4,1,0,0,
        FangqiRankedLedger.RULES_VERSION,1,FangqiRankedLedger.standardInitialHistory(),11,1,0,0,1,12)
    private fun outcome(id: String) = MatchOutcomeEntity(id,4,1,Difficulty.EASY.code,0,1,12)
}
