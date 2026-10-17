package com.masterofchessstrategy.games.go.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import com.masterofchessstrategy.data.MocsDatabase
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class GoLocalRoomMigrationInstrumentedTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MocsDatabase::class.java)

    @Test fun thirteenToFourteenPreservesExistingRecordsTutorialAndCompetitionBytes() {
        val name = "go-local-migration-${UUID.randomUUID()}.db"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            helper.createDatabase(name, 13).use { db ->
                db.execSQL("INSERT INTO game_records (recordId,gameTypeCode,modeCode,difficultyCode,resultCode," +
                    "engineFormatVersion,engineState,moveCount,isFavorite,isEndgame,completedAtEpochMillis,playerIndex) " +
                    "VALUES ('prior',0,0,NULL,3,2,X'010203',4,1,0,99,0)")
                db.execSQL("INSERT INTO tutorial_progress (gameTypeCode,contentVersion,completedStepCount,isCompleted," +
                    "updatedAtEpochMillis) VALUES (0,1,4,1,88)")
                db.execSQL("INSERT INTO competition_mahjong_matches (gameTypeCode,viewerPlayerId,matchId,formatVersion," +
                    "checkpoint,updatedAtEpochMillis) VALUES (2,0,'prior-cm',2,X'040506',77)")
            }
            helper.runMigrationsAndValidate(name, 14, true, MocsDatabase.MIGRATION_13_14).use { db ->
                db.query("SELECT hex(engineState),isFavorite,completedAtEpochMillis FROM game_records WHERE recordId='prior'").use {
                    assertTrue(it.moveToFirst()); assertEquals("010203", it.getString(0))
                    assertEquals(1, it.getInt(1)); assertEquals(99L, it.getLong(2)); assertFalse(it.moveToNext())
                }
                db.query("SELECT completedStepCount,isCompleted FROM tutorial_progress WHERE gameTypeCode=0").use {
                    assertTrue(it.moveToFirst()); assertEquals(4, it.getInt(0)); assertEquals(1, it.getInt(1))
                }
                db.query("SELECT hex(checkpoint),matchId FROM competition_mahjong_matches WHERE gameTypeCode=2").use {
                    assertTrue(it.moveToFirst()); assertEquals("040506", it.getString(0)); assertEquals("prior-cm", it.getString(1))
                }
                for (table in listOf("go_local_sessions", "go_record_sources", "local_data_migrations")) {
                    db.query("SELECT COUNT(*) FROM $table").use { assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0)) }
                }
            }
        } finally { assertTrue(context.deleteDatabase(name)) }
    }
}
