package com.masterofchessstrategy.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ActiveGameEntity::class,
        LastSelectionEntity::class,
        AppSettingsEntity::class,
        TutorialProgressEntity::class,
        MatchOutcomeEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
internal abstract class MocsDatabase : RoomDatabase() {
    abstract fun activeGameDao(): ActiveGameDao

    abstract fun lastSelectionDao(): LastSelectionDao

    abstract fun appSettingsDao(): AppSettingsDao

    abstract fun tutorialProgressDao(): TutorialProgressDao

    abstract fun matchOutcomeDao(): MatchOutcomeDao

    companion object {
        internal val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS last_game_selections (
                        gameTypeCode INTEGER NOT NULL,
                        modeCode INTEGER NOT NULL,
                        difficultyCode INTEGER,
                        updatedAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(gameTypeCode)
                    )
                    """.trimIndent(),
                )
            }
        }

        internal val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS app_settings (
                        id INTEGER NOT NULL,
                        defaultDifficultyCode INTEGER NOT NULL,
                        autoContinueEnabled INTEGER NOT NULL,
                        soundEnabled INTEGER NOT NULL,
                        gameDurationMinutes INTEGER,
                        updatedAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(id)
                    )
                    """.trimIndent(),
                )
            }
        }

        internal val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS tutorial_progress (
                        gameTypeCode INTEGER NOT NULL,
                        contentVersion INTEGER NOT NULL,
                        completedStepCount INTEGER NOT NULL,
                        isCompleted INTEGER NOT NULL,
                        updatedAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(gameTypeCode)
                    )
                    """.trimIndent(),
                )
            }
        }

        internal val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE active_games " +
                        "ADD COLUMN sessionId TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS match_outcomes (
                        matchId TEXT NOT NULL,
                        gameTypeCode INTEGER NOT NULL,
                        modeCode INTEGER NOT NULL,
                        difficultyCode INTEGER NOT NULL,
                        playerIndex INTEGER NOT NULL,
                        resultCode INTEGER NOT NULL,
                        settledAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(matchId)
                    )
                    """.trimIndent(),
                )
            }
        }

        @Volatile
        private var instance: MocsDatabase? = null

        fun getInstance(context: Context): MocsDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MocsDatabase::class.java,
                    DATABASE_NAME,
                )
                    .addMigrations(
                        MIGRATION_1_2,
                        MIGRATION_2_3,
                        MIGRATION_3_4,
                        MIGRATION_4_5,
                    )
                    .build()
                    .also { instance = it }
            }

        private const val DATABASE_NAME = "master-of-chess-strategy.db"
    }
}
