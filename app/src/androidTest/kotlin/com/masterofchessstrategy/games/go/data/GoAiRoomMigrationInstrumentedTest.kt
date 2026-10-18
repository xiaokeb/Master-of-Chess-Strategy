package com.masterofchessstrategy.games.go.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.GoLegacyImportScopes
import com.masterofchessstrategy.data.MocsDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

/** Schema migration creates empty tables only; file admission/legacy sealing happen later. */
class GoAiRoomMigrationInstrumentedTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MocsDatabase::class.java)

    @Test fun fifteenToSixteenPreservesOldBytesFavoritesTimeAndFencesWithoutSealingAi() {
        val name = "go-ai-migration-${UUID.randomUUID()}.db"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            helper.createDatabase(name, 15).use { db ->
                db.execSQL("INSERT INTO game_records (recordId,gameTypeCode,modeCode,difficultyCode,resultCode," +
                    "engineFormatVersion,engineState,moveCount,isFavorite,isEndgame,completedAtEpochMillis,playerIndex) " +
                    "VALUES ('prior',0,0,NULL,3,2,X'010203',4,1,0,99,0)")
                db.execSQL("INSERT INTO competition_mahjong_matches (gameTypeCode,viewerPlayerId,matchId,formatVersion," +
                    "checkpoint,updatedAtEpochMillis) VALUES (2,0,'prior-cm',2,X'040506',77)")
                db.execSQL("INSERT INTO go_local_sessions VALUES (1,3,X'070809',66)")
                db.execSQL("INSERT INTO go_record_sources VALUES ('prior-go',1,'preserved-session',0,3)")
                db.execSQL("INSERT INTO go_progress VALUES (1,0,1,1,X'0A0B0C',55)")
                db.execSQL("INSERT INTO local_data_migrations VALUES (?,7)", arrayOf(GoLegacyImportScopes.LEARNING))
            }
            helper.runMigrationsAndValidate(name, 16, true, MocsDatabase.MIGRATION_15_16).use { db ->
                db.query("SELECT hex(engineState),isFavorite,completedAtEpochMillis FROM game_records WHERE recordId='prior'").use {
                    assertTrue(it.moveToFirst()); assertEquals("010203", it.getString(0))
                    assertEquals(1, it.getInt(1)); assertEquals(99L, it.getLong(2))
                }
                for ((table, column, bytes, time) in listOf(
                    listOf("competition_mahjong_matches", "checkpoint", "040506", "77"),
                    listOf("go_local_sessions", "checkpoint", "070809", "66"),
                    listOf("go_progress", "payload", "0A0B0C", "55"))) {
                    db.query("SELECT hex($column),updatedAtEpochMillis FROM $table").use {
                        assertTrue(it.moveToFirst()); assertEquals(bytes, it.getString(0)); assertEquals(time.toLong(), it.getLong(1))
                    }
                }
                db.query("SELECT sessionId,archiveRevision FROM go_record_sources WHERE recordId='prior-go'").use {
                    assertTrue(it.moveToFirst()); assertEquals("preserved-session", it.getString(0)); assertEquals(3, it.getInt(1))
                }
                db.query("SELECT scope,handledVersion FROM local_data_migrations").use {
                    assertTrue(it.moveToFirst()); assertEquals(GoLegacyImportScopes.LEARNING, it.getString(0))
                    assertEquals(7, it.getInt(1)); assertFalse(it.moveToNext())
                }
                for (table in listOf("go_ai_sessions", "go_ai_archives")) {
                    db.query("SELECT COUNT(*) FROM $table").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
                    db.query("SELECT sql FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use {
                        assertTrue(it.moveToFirst())
                        val sql = it.getString(0).uppercase()
                        assertTrue(sql.contains("DEFERRABLE INITIALLY DEFERRED"))
                        assertTrue(sql.contains("ON DELETE NO ACTION") && sql.contains("ON UPDATE NO ACTION"))
                    }
                }
                db.query("PRAGMA foreign_key_check").use { assertFalse(it.moveToFirst()) }
            }
        } finally { assertTrue(context.deleteDatabase(name)) }
    }
}
