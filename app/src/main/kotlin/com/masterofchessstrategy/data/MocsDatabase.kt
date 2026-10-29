package com.masterofchessstrategy.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.masterofchessstrategy.games.go.data.GoLocalSessionEntity
import com.masterofchessstrategy.games.go.data.GoLocalSessionDao
import com.masterofchessstrategy.games.go.data.GoRecordSourceEntity
import com.masterofchessstrategy.games.go.data.GoRecordSourceDao
import com.masterofchessstrategy.games.go.data.GoProgressEntity
import com.masterofchessstrategy.games.go.data.GoProgressDao
import com.masterofchessstrategy.games.go.data.GoAiSessionEntity
import com.masterofchessstrategy.games.go.data.GoAiArchiveEntity
import com.masterofchessstrategy.games.go.data.GoAiCheckpointDao
import com.masterofchessstrategy.games.go.data.GoAiProjectionEntity
import com.masterofchessstrategy.games.go.data.GoAiProjectionDao
import com.masterofchessstrategy.games.go.data.GoRankedStartEntity
import com.masterofchessstrategy.games.go.data.GoRankedSettlementEntity
import com.masterofchessstrategy.games.go.data.GoRankedDao
import com.masterofchessstrategy.games.lanzhou_fangqi.data.FangqiLearningProgressEntity
import com.masterofchessstrategy.games.lanzhou_fangqi.data.FangqiLearningProgressDao
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongLearningProgressEntity
import com.masterofchessstrategy.games.lanzhou_mahjong.data.LanzhouMahjongLearningProgressDao

@Database(
    entities = [
        ActiveGameEntity::class,
        LastSelectionEntity::class,
        AppSettingsEntity::class,
        TutorialProgressEntity::class,
        MatchOutcomeEntity::class,
        GameRecordEntity::class,
        EndgameProgressEntity::class,
        CompetitionMahjongMatchEntity::class,
        GoLocalSessionEntity::class,
        GoRecordSourceEntity::class,
        LocalDataMigrationEntity::class,
        GoProgressEntity::class,
        GoAiSessionEntity::class,
        GoAiArchiveEntity::class,
        GoAiProjectionEntity::class,
        GoRankedStartEntity::class,
        GoRankedSettlementEntity::class,
        FangqiLearningProgressEntity::class,
        LanzhouMahjongLearningProgressEntity::class,
    ],
    version = 20,
    exportSchema = true,
)
internal abstract class MocsDatabase : RoomDatabase() {
    abstract fun backupStorageInventoryDao(): BackupStorageInventoryDao

    abstract fun activeGameDao(): ActiveGameDao

    abstract fun lastSelectionDao(): LastSelectionDao

    abstract fun appSettingsDao(): AppSettingsDao

    abstract fun tutorialProgressDao(): TutorialProgressDao

    abstract fun matchOutcomeDao(): MatchOutcomeDao

    abstract fun gameRecordDao(): GameRecordDao

    abstract fun endgameProgressDao(): EndgameProgressDao

    abstract fun competitionMahjongMatchDao(): CompetitionMahjongMatchDao

    abstract fun goLocalSessionDao(): GoLocalSessionDao

    abstract fun goRecordSourceDao(): GoRecordSourceDao

    abstract fun localDataMigrationDao(): LocalDataMigrationDao

    abstract fun goProgressDao(): GoProgressDao

    abstract fun goAiCheckpointDao(): GoAiCheckpointDao
    abstract fun goAiProjectionDao(): GoAiProjectionDao
    abstract fun goRankedDao(): GoRankedDao
    abstract fun fangqiLearningProgressDao(): FangqiLearningProgressDao
    abstract fun lanzhouMahjongLearningProgressDao(): LanzhouMahjongLearningProgressDao

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

        internal val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Historical games always had a red-side human; preserve that identity.
                db.execSQL("ALTER TABLE app_settings ADD COLUMN aiFirstEnabled INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE active_games ADD COLUMN playerIndex INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE game_records ADD COLUMN playerIndex INTEGER NOT NULL DEFAULT 0")
                db.execSQL("UPDATE active_games SET envelopeVersion = 5 WHERE envelopeVersion = 4")
            }
        }

        internal val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add a separate competition row; no old table, save file or identity is rewritten.
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS competition_mahjong_matches (
                        gameTypeCode INTEGER NOT NULL,
                        viewerPlayerId INTEGER NOT NULL,
                        matchId TEXT NOT NULL,
                        formatVersion INTEGER NOT NULL,
                        checkpoint BLOB NOT NULL,
                        updatedAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(gameTypeCode)
                    )
                    """.trimIndent(),
                )
            }
        }

        internal val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Additive only. Legacy files require real admission, not a SQL-side guess.
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS go_local_sessions (
                        gameTypeCode INTEGER NOT NULL,
                        envelopeVersion INTEGER NOT NULL,
                        checkpoint BLOB NOT NULL,
                        updatedAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(gameTypeCode)
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS go_record_sources (
                        recordId TEXT NOT NULL,
                        gameTypeCode INTEGER NOT NULL,
                        sessionId TEXT NOT NULL,
                        originCode INTEGER NOT NULL,
                        archiveRevision INTEGER NOT NULL,
                        PRIMARY KEY(recordId)
                    )
                """.trimIndent())
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_go_record_sources_sessionId_archiveRevision " +
                    "ON go_record_sources (sessionId, archiveRevision)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS local_data_migrations (
                        scope TEXT NOT NULL,
                        handledVersion INTEGER NOT NULL,
                        PRIMARY KEY(scope)
                    )
                """.trimIndent())
            }
        }

        internal val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS go_progress (
                        gameTypeCode INTEGER NOT NULL,
                        kindCode INTEGER NOT NULL,
                        formatVersion INTEGER NOT NULL,
                        contentVersion INTEGER NOT NULL,
                        payload BLOB NOT NULL,
                        updatedAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(gameTypeCode, kindCode)
                    )
                """.trimIndent())
            }
        }

        internal val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Add empty AI tables only. Native legacy admission and permanent
                // source sealing belong in the later repository transaction.
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS go_ai_archives (
                        recordId TEXT NOT NULL,
                        gameTypeCode INTEGER NOT NULL,
                        envelopeVersion INTEGER NOT NULL,
                        checkpoint BLOB NOT NULL,
                        originCode INTEGER NOT NULL,
                        replacedTerminalRecordId TEXT,
                        completedAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(recordId),
                        FOREIGN KEY(replacedTerminalRecordId) REFERENCES go_ai_archives(recordId)
                            ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_go_ai_archives_replacedTerminalRecordId " +
                    "ON go_ai_archives (replacedTerminalRecordId)")
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS go_ai_sessions (
                        gameTypeCode INTEGER NOT NULL,
                        envelopeVersion INTEGER NOT NULL,
                        checkpoint BLOB NOT NULL,
                        originCode INTEGER NOT NULL,
                        replacedTerminalRecordId TEXT,
                        updatedAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(gameTypeCode),
                        FOREIGN KEY(replacedTerminalRecordId) REFERENCES go_ai_archives(recordId)
                            ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS index_go_ai_sessions_replacedTerminalRecordId " +
                    "ON go_ai_sessions (replacedTerminalRecordId)")
            }
        }

        internal val MIGRATION_16_17 = object : Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Existing records stay unknown; never infer AI provenance during schema migration.
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS go_ai_projections (
                        recordId TEXT NOT NULL,
                        projectionVersion INTEGER NOT NULL,
                        PRIMARY KEY(recordId),
                        FOREIGN KEY(recordId) REFERENCES game_records(recordId)
                            ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED,
                        FOREIGN KEY(recordId) REFERENCES go_ai_archives(recordId)
                            ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED
                    )
                """.trimIndent())
            }
        }

        internal val MIGRATION_17_18 = object : Migration(17, 18) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Never upgrade historical AI records or outcomes into formal participation.
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS go_ranked_starts (
                        recordId TEXT NOT NULL,
                        formatVersion INTEGER NOT NULL,
                        initialCheckpoint BLOB NOT NULL,
                        createdAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(recordId)
                    )
                """.trimIndent())
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS go_ranked_settlements (
                        recordId TEXT NOT NULL,
                        formatVersion INTEGER NOT NULL,
                        PRIMARY KEY(recordId),
                        FOREIGN KEY(recordId) REFERENCES go_ranked_starts(recordId)
                            ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED,
                        FOREIGN KEY(recordId) REFERENCES go_ai_projections(recordId)
                            ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED,
                        FOREIGN KEY(recordId) REFERENCES match_outcomes(matchId)
                            ON UPDATE NO ACTION ON DELETE NO ACTION DEFERRABLE INITIALLY DEFERRED
                    )
                """.trimIndent())
            }
        }

        internal val MIGRATION_18_19 = object : Migration(18, 19) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Learning keys are game-owned; never rewrite old tutorials or reward ledgers.
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS fangqi_learning_progress (
                        contentKey TEXT NOT NULL,
                        contentVersion INTEGER NOT NULL,
                        completedAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(contentKey)
                    )
                """.trimIndent())
            }
        }

        internal val MIGRATION_19_20 = object : Migration(19, 20) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Generic four-step rows remain historical facts, never ten-lesson completion.
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS lanzhou_mahjong_learning_progress (
                        singletonId INTEGER NOT NULL,
                        contentVersion INTEGER NOT NULL,
                        completedCount INTEGER NOT NULL,
                        updatedAtEpochMillis INTEGER NOT NULL,
                        PRIMARY KEY(singletonId)
                    )
                """.trimIndent())
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
                        MIGRATION_11_12,
                        MIGRATION_12_13,
                        MIGRATION_13_14,
                        MIGRATION_14_15,
                        MIGRATION_15_16,
                        MIGRATION_16_17,
                        MIGRATION_17_18,
                        MIGRATION_18_19,
                        MIGRATION_19_20,
                    )
                    .build()
                    .also { instance = it }
            }

        private const val DATABASE_NAME = "master-of-chess-strategy.db"
    }
}
