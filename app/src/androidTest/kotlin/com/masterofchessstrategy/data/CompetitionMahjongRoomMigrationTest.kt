package com.masterofchessstrategy.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class CompetitionMahjongRoomMigrationTest {
    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), MocsDatabase::class.java)

    @Test fun twelveToThirteenPreservesOldXiangqiAndTutorialAndAddsAnEmptyCompetitionTable() {
        val name = "mcmt-migration-qa-${UUID.randomUUID()}.db"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        try {
            helper.createDatabase(name, 12).use { db ->
                db.execSQL("INSERT INTO active_games (gameTypeCode,modeCode,difficultyCode,envelopeVersion," +
                    "engineFormatVersion,engineState,updatedAtEpochMillis,playerIndex) VALUES (0,0,NULL,5,1,X'010203',99,1)")
                db.execSQL("INSERT INTO tutorial_progress (gameTypeCode,contentVersion,completedStepCount,isCompleted," +
                    "updatedAtEpochMillis) VALUES (0,1,4,1,88)")
            }
            helper.runMigrationsAndValidate(name, 13, true, MocsDatabase.MIGRATION_12_13).use { db ->
                db.query("SELECT engineState,playerIndex,updatedAtEpochMillis FROM active_games").use { cursor ->
                    assertTrue(cursor.moveToFirst()); assertArrayEquals(byteArrayOf(1,2,3), cursor.getBlob(0))
                    assertEquals(1, cursor.getInt(1)); assertEquals(99L, cursor.getLong(2)); assertFalse(cursor.moveToNext())
                }
                db.query("SELECT completedStepCount,isCompleted FROM tutorial_progress").use { cursor ->
                    assertTrue(cursor.moveToFirst()); assertEquals(4, cursor.getInt(0)); assertEquals(1, cursor.getInt(1))
                }
                db.query("SELECT COUNT(*) FROM competition_mahjong_matches").use { cursor ->
                    assertTrue(cursor.moveToFirst()); assertEquals(0, cursor.getInt(0))
                }
            }
        } finally { assertTrue(context.deleteDatabase(name)) }
    }
}
