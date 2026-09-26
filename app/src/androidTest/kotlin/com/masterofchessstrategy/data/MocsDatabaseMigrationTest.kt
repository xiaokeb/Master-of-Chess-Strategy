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

    @Test
    fun migrateFourToFiveAddsSessionIdentityAndOutcomeLedger() {
        helper.createDatabase(STATISTICS_DATABASE_NAME, 4).apply {
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
                ) VALUES (?, ?, 0, 1, 2, ?, 102)
                """.trimIndent(),
                arrayOf(0, 1, byteArrayOf(1, 2, 3)),
            )
            execSQL(
                """
                INSERT INTO tutorial_progress (
                    gameTypeCode,
                    contentVersion,
                    completedStepCount,
                    isCompleted,
                    updatedAtEpochMillis
                ) VALUES (0, 1, 4, 1, 103)
                """.trimIndent(),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            STATISTICS_DATABASE_NAME,
            5,
            true,
            MocsDatabase.MIGRATION_4_5,
        )

        assertEquals("", migrated.singleString("SELECT sessionId FROM active_games"))
        assertEquals(1, migrated.singleInt("SELECT COUNT(*) FROM tutorial_progress"))
        assertEquals(0, migrated.singleInt("SELECT COUNT(*) FROM match_outcomes"))
        migrated.close()
    }

    @Test
    fun migrateFiveToSixPreservesSessionAndAddsControlState() {
        helper.createDatabase(CONTROL_STATE_DATABASE_NAME, 5).apply {
            execSQL(
                """
                INSERT INTO active_games (
                    gameTypeCode,
                    modeCode,
                    difficultyCode,
                    envelopeVersion,
                    engineFormatVersion,
                    engineState,
                    updatedAtEpochMillis,
                    sessionId
                ) VALUES (?, ?, 1, 1, 2, ?, 104, 'match-control')
                """.trimIndent(),
                arrayOf(0, 1, byteArrayOf(4, 5, 6)),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            CONTROL_STATE_DATABASE_NAME,
            6,
            true,
            MocsDatabase.MIGRATION_5_6,
        )

        assertEquals(2, migrated.singleInt("SELECT envelopeVersion FROM active_games"))
        assertEquals(0, migrated.singleInt("SELECT acceptedMoveCount FROM active_games"))
        assertEquals(0, migrated.singleInt("SELECT undoUseCount FROM active_games"))
        assertEquals(0, migrated.singleInt("SELECT hintUseCount FROM active_games"))
        assertEquals(
            null,
            migrated.singleNullableInt("SELECT resultOverrideCode FROM active_games"),
        )
        assertEquals(
            "match-control",
            migrated.singleString("SELECT sessionId FROM active_games"),
        )
        migrated.close()
    }

    @Test
    fun migrateSixToSevenAddsClockDrawAndAutoPlayState() {
        helper.createDatabase(AUTOMATION_DATABASE_NAME, 6).apply {
            execSQL(
                """
                INSERT INTO active_games (
                    gameTypeCode,
                    modeCode,
                    difficultyCode,
                    envelopeVersion,
                    engineFormatVersion,
                    engineState,
                    updatedAtEpochMillis,
                    sessionId,
                    acceptedMoveCount,
                    undoUseCount,
                    hintUseCount,
                    resultOverrideCode
                ) VALUES (?, ?, 0, 2, 2, ?, 105, 'match-auto', 3, 0, 0, NULL)
                """.trimIndent(),
                arrayOf(0, 1, byteArrayOf(7, 8, 9)),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            AUTOMATION_DATABASE_NAME,
            7,
            true,
            MocsDatabase.MIGRATION_6_7,
        )

        assertEquals(3, migrated.singleInt("SELECT envelopeVersion FROM active_games"))
        assertEquals(1_000, migrated.singleInt("SELECT autoPlaySpeedPermille FROM active_games"))
        assertEquals(0, migrated.singleInt("SELECT completedAutoGames FROM active_games"))
        assertEquals(
            null,
            migrated.singleNullableInt("SELECT timeControlMinutes FROM active_games"),
        )
        migrated.close()
    }

    @Test
    fun migrateSevenToEightAddsEmptyGameRecordLibrary() {
        helper.createDatabase(RECORD_DATABASE_NAME, 7).close()

        val migrated = helper.runMigrationsAndValidate(
            RECORD_DATABASE_NAME,
            8,
            true,
            MocsDatabase.MIGRATION_7_8,
        )

        assertEquals(0, migrated.singleInt("SELECT COUNT(*) FROM game_records"))
        migrated.close()
    }

    @Test
    fun migrateEightToNineAddsEndgameProgressAndSessionVariant() {
        helper.createDatabase(ENDGAME_DATABASE_NAME, 8).apply {
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
                ) VALUES (?, ?, NULL, 3, 2, ?, 106)
                """.trimIndent(),
                arrayOf(0, 0, byteArrayOf(1, 2, 3)),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            ENDGAME_DATABASE_NAME,
            9,
            true,
            MocsDatabase.MIGRATION_8_9,
        )

        assertEquals(4, migrated.singleInt("SELECT envelopeVersion FROM active_games"))
        assertEquals("", migrated.singleString("SELECT sessionVariantId FROM active_games"))
        assertEquals(0, migrated.singleInt("SELECT COUNT(*) FROM endgame_progress"))
        migrated.close()
    }

    @Test
    fun migrateNineToTenAddsDefaultAppearanceWithoutChangingSettings() {
        helper.createDatabase(PROFILE_DATABASE_NAME, 9).apply {
            execSQL(
                """
                INSERT INTO app_settings (
                    id,
                    defaultDifficultyCode,
                    autoContinueEnabled,
                    autoContinueGameLimit,
                    soundEnabled,
                    gameDurationMinutes,
                    updatedAtEpochMillis
                ) VALUES (0, 2, 1, 15, 0, 45, 107)
                """.trimIndent(),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            PROFILE_DATABASE_NAME,
            10,
            true,
            MocsDatabase.MIGRATION_9_10,
        )

        assertEquals(0, migrated.singleInt("SELECT selectedAppearanceCode FROM app_settings"))
        assertEquals(2, migrated.singleInt("SELECT defaultDifficultyCode FROM app_settings"))
        assertEquals(15, migrated.singleInt("SELECT autoContinueGameLimit FROM app_settings"))
        migrated.close()
    }

    @Test
    fun migrateTenToElevenKeepsSettingsAndLeavesAutoHighlightsDisabled() {
        helper.createDatabase(HIGHLIGHT_DATABASE_NAME, 10).apply {
            execSQL(
                """
                INSERT INTO app_settings (
                    id, defaultDifficultyCode, autoContinueEnabled,
                    autoContinueGameLimit, soundEnabled, gameDurationMinutes,
                    selectedAppearanceCode, updatedAtEpochMillis
                ) VALUES (0, 2, 1, 15, 0, 45, 3, 107)
                """.trimIndent(),
            )
            close()
        }

        val migrated = helper.runMigrationsAndValidate(
            HIGHLIGHT_DATABASE_NAME,
            11,
            true,
            MocsDatabase.MIGRATION_10_11,
        )

        assertEquals(0, migrated.singleInt("SELECT highlightConditionsMask FROM app_settings"))
        assertEquals(3, migrated.singleInt("SELECT selectedAppearanceCode FROM app_settings"))
        assertEquals(15, migrated.singleInt("SELECT autoContinueGameLimit FROM app_settings"))
        migrated.close()
    }

    @Test
    fun migrateElevenToTwelvePreservesGamesAsRedAndDefaultsAiFirstOff() {
        val name = "migration-11-12-test"
        helper.createDatabase(name, 11).apply {
            execSQL("INSERT INTO app_settings (id, defaultDifficultyCode, autoContinueEnabled, soundEnabled, gameDurationMinutes, updatedAtEpochMillis, highlightConditionsMask) VALUES (0, 2, 1, 1, 30, 123, 7)")
            execSQL("INSERT INTO active_games (gameTypeCode, modeCode, difficultyCode, envelopeVersion, engineFormatVersion, engineState, updatedAtEpochMillis, sessionId, acceptedMoveCount) VALUES (0, 1, 0, 4, 2, X'010203', 99, 'old-red', 8)")
            execSQL("INSERT INTO game_records (recordId, gameTypeCode, modeCode, difficultyCode, resultCode, engineFormatVersion, engineState, moveCount, isFavorite, completedAtEpochMillis) VALUES ('old-record', 0, 1, 0, 2, 2, X'040506', 18, 1, 100)")
            close()
        }
        val migrated = helper.runMigrationsAndValidate(name, 12, true, MocsDatabase.MIGRATION_11_12)
        assertEquals(0, migrated.singleInt("SELECT aiFirstEnabled FROM app_settings"))
        assertEquals(7, migrated.singleInt("SELECT highlightConditionsMask FROM app_settings"))
        assertEquals(0, migrated.singleInt("SELECT playerIndex FROM active_games"))
        assertEquals(5, migrated.singleInt("SELECT envelopeVersion FROM active_games"))
        assertEquals(8, migrated.singleInt("SELECT acceptedMoveCount FROM active_games"))
        assertEquals("010203", migrated.singleString("SELECT hex(engineState) FROM active_games"))
        assertEquals(0, migrated.singleInt("SELECT playerIndex FROM game_records"))
        assertEquals(2, migrated.singleInt("SELECT resultCode FROM game_records"))
        assertEquals(1, migrated.singleInt("SELECT isFavorite FROM game_records"))
        assertEquals("040506", migrated.singleString("SELECT hex(engineState) FROM game_records"))
        migrated.close()
    }

    @Test
    fun migrationChainOneToTwelveIsComplete() {
        val name = "migration-1-12-test"
        helper.createDatabase(name, 1).close()
        helper.runMigrationsAndValidate(
            name, 12, true, MocsDatabase.MIGRATION_1_2, MocsDatabase.MIGRATION_2_3,
            MocsDatabase.MIGRATION_3_4, MocsDatabase.MIGRATION_4_5, MocsDatabase.MIGRATION_5_6,
            MocsDatabase.MIGRATION_6_7, MocsDatabase.MIGRATION_7_8, MocsDatabase.MIGRATION_8_9,
            MocsDatabase.MIGRATION_9_10, MocsDatabase.MIGRATION_10_11, MocsDatabase.MIGRATION_11_12,
        ).close()
    }

    private fun SupportSQLiteDatabase.singleInt(query: String): Int =
        this.query(query).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getInt(0)
        }

    private fun SupportSQLiteDatabase.singleString(query: String): String =
        this.query(query).use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private fun SupportSQLiteDatabase.singleNullableInt(query: String): Int? =
        this.query(query).use { cursor ->
            check(cursor.moveToFirst())
            if (cursor.isNull(0)) null else cursor.getInt(0)
        }

    private companion object {
        const val HIGHLIGHT_DATABASE_NAME = "migration-10-11-test"
        const val DATABASE_NAME = "migration-1-2-test"
        const val SETTINGS_DATABASE_NAME = "migration-2-3-test"
        const val TUTORIAL_DATABASE_NAME = "migration-3-4-test"
        const val STATISTICS_DATABASE_NAME = "migration-4-5-test"
        const val CONTROL_STATE_DATABASE_NAME = "migration-5-6-test"
        const val AUTOMATION_DATABASE_NAME = "migration-6-7-test"
        const val RECORD_DATABASE_NAME = "migration-7-8-test"
        const val ENDGAME_DATABASE_NAME = "migration-8-9-test"
        const val PROFILE_DATABASE_NAME = "migration-9-10-test"
    }
}
