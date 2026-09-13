package com.masterofchessstrategy.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MocsDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        MocsDatabase::class.java,
    )

    @Test
    fun migrateOneToTwoPreservesSessionAndAddsSelectionTable() {
        helper.createDatabase(DATABASE_NAME, 1).apply {
            execSQL(
                """
                INSERT INTO active_games (
                    gameTypeCode,
                    modeCode,
                    difficultyCode,
                    envelopeVersion,
                    engineFormatVersion,
                    engineState,
                    updatedAtEpochMillis
                ) VALUES (?, ?, NULL, 1, 1, ?, 99)
                """.trimIndent(),
                arrayOf(0, 0, byteArrayOf(1, 2, 3)),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            DATABASE_NAME,
            2,
            true,
            MocsDatabase.MIGRATION_1_2,
        )

        assertEquals(1, migrated.singleInt("SELECT COUNT(*) FROM active_games"))
        assertEquals(0, migrated.singleInt("SELECT COUNT(*) FROM last_game_selections"))
        migrated.close()
    }

    @Test
    fun migrateTwoToThreePreservesExistingTablesAndAddsSettings() {
        helper.createDatabase(SETTINGS_DATABASE_NAME, 2).apply {
            execSQL(
                """
                INSERT INTO last_game_selections (
                    gameTypeCode,
                    modeCode,
                    difficultyCode,
                    updatedAtEpochMillis
                ) VALUES (0, 0, NULL, 100)
                """.trimIndent(),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            SETTINGS_DATABASE_NAME,
            3,
            true,
            MocsDatabase.MIGRATION_2_3,
        )

        assertEquals(1, migrated.singleInt("SELECT COUNT(*) FROM last_game_selections"))
        assertEquals(0, migrated.singleInt("SELECT COUNT(*) FROM app_settings"))
        migrated.close()
    }

    @Test
    fun migrateThreeToFourPreservesSettingsAndAddsTutorialProgress() {
        helper.createDatabase(TUTORIAL_DATABASE_NAME, 3).apply {
            execSQL(
                """
                INSERT INTO app_settings (
                    id,
                    defaultDifficultyCode,
                    autoContinueEnabled,
                    soundEnabled,
                    gameDurationMinutes,
                    updatedAtEpochMillis
                ) VALUES (0, 0, 0, 1, NULL, 101)
                """.trimIndent(),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            TUTORIAL_DATABASE_NAME,
            4,
            true,
            MocsDatabase.MIGRATION_3_4,
        )

        assertEquals(1, migrated.singleInt("SELECT COUNT(*) FROM app_settings"))
        assertEquals(0, migrated.singleInt("SELECT COUNT(*) FROM tutorial_progress"))
        migrated.close()
    }

    private fun SupportSQLiteDatabase.singleInt(query: String): Int =
        this.query(query).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private companion object {
        const val DATABASE_NAME = "migration-1-2-test"
        const val SETTINGS_DATABASE_NAME = "migration-2-3-test"
        const val TUTORIAL_DATABASE_NAME = "migration-3-4-test"
    }
}
