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
    ],
    version = 3,
    exportSchema = true,
)
internal abstract class MocsDatabase : RoomDatabase() {
    abstract fun activeGameDao(): ActiveGameDao

    abstract fun lastSelectionDao(): LastSelectionDao

    abstract fun appSettingsDao(): AppSettingsDao

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

        @Volatile
        private var instance: MocsDatabase? = null

        fun getInstance(context: Context): MocsDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MocsDatabase::class.java,
                    DATABASE_NAME,
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }

        private const val DATABASE_NAME = "master-of-chess-strategy.db"
    }
}
