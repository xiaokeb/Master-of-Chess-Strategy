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
        GameRecordEntity::class,
        EndgameProgressEntity::class,
    ],
    version = 11,
    exportSchema = true,
)
internal abstract class MocsDatabase : RoomDatabase() {
    abstract fun activeGameDao(): ActiveGameDao

    abstract fun lastSelectionDao(): LastSelectionDao

    abstract fun appSettingsDao(): AppSettingsDao

    abstract fun tutorialProgressDao(): TutorialProgressDao

    abstract fun matchOutcomeDao(): MatchOutcomeDao

    abstract fun gameRecordDao(): GameRecordDao

    abstract fun endgameProgressDao(): EndgameProgressDao

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

        internal val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE active_games " +
                        "ADD COLUMN acceptedMoveCount INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE active_games " +
                        "ADD COLUMN undoUseCount INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE active_games " +
                        "ADD COLUMN hintUseCount INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE active_games ADD COLUMN resultOverrideCode INTEGER",
                )
                db.execSQL(
                    "UPDATE active_games SET envelopeVersion = 2 " +
                        "WHERE envelopeVersion = 1",
                )
            }
        }

        internal val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE active_games ADD COLUMN timeControlMinutes INTEGER",
                )
                db.execSQL(
                    "ALTER TABLE active_games ADD COLUMN redRemainingMillis INTEGER",
                )
                db.execSQL(
                    "ALTER TABLE active_games ADD COLUMN blackRemainingMillis INTEGER",
                )
                db.execSQL(
                    "ALTER TABLE active_games ADD COLUMN turnStartedAtEpochMillis INTEGER",
                )
                db.execSQL(
                    "ALTER TABLE active_games ADD COLUMN pendingDrawOfferSideCode INTEGER",
                )
                db.execSQL(
                    "ALTER TABLE active_games " +
                        "ADD COLUMN autoPlayPaused INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE active_games " +
                        "ADD COLUMN autoPlaySpeedPermille INTEGER NOT NULL DEFAULT 1000",
                )
                db.execSQL(
                    "ALTER TABLE active_games " +
                        "ADD COLUMN completedAutoGames INTEGER NOT NULL DEFAULT 0",
                )
                db.execSQL(
                    "ALTER TABLE app_settings " +
                        "ADD COLUMN autoContinueGameLimit INTEGER NOT NULL DEFAULT 10",
                )
                db.execSQL(
                    "UPDATE active_games SET envelopeVersion = 3 " +
                        "WHERE envelopeVersion = 2",
                )
            }
        }

        internal val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS game_records (
                        recordId TEXT NOT NULL,
                        gameTypeCode INTEGER NOT NULL,
                        modeCode INTEGER NOT NULL,
                        difficultyCode INTEGER,
                        resultCode INTEGER NOT NULL,
                        engineFormatVersion INTEGER NOT NULL,
                        engineState BLOB NOT NULL,
                        moveCount INTEGER NOT NULL,
                        isFavorite INTEGER NOT NULL DEFAULT 0,
                        isEndgame INTEGER NOT NULL DEFAULT 0,
                        completedAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(recordId)
                    )
                    """.trimIndent(),
                )
            }
        }

        internal val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE active_games " +
                        "ADD COLUMN sessionVariantId TEXT NOT NULL DEFAULT ''",
                )
                db.execSQL(
                    "UPDATE active_games SET envelopeVersion = 4 " +
                        "WHERE envelopeVersion = 3",
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS endgame_progress (
                        levelId TEXT NOT NULL,
                        contentVersion INTEGER NOT NULL,
                        difficultyCode INTEGER NOT NULL,
                        bestPlayerMoves INTEGER NOT NULL,
                        starsAwarded INTEGER NOT NULL,
                        scoreAwarded INTEGER NOT NULL,
                        completedAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(levelId)
                    )
                    """.trimIndent(),
                )
            }
        }

        internal val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE app_settings " +
                        "ADD COLUMN selectedAppearanceCode INTEGER NOT NULL DEFAULT 0",
                )
            }
        }

        internal val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE app_settings " +
                        "ADD COLUMN highlightConditionsMask INTEGER NOT NULL DEFAULT 0",
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
                        MIGRATION_5_6,
                        MIGRATION_6_7,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10,
                        MIGRATION_10_11,
                    )
                    .build()
                    .also { instance = it }
            }

        private const val DATABASE_NAME = "master-of-chess-strategy.db"
    }
}
