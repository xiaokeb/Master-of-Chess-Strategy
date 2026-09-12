package com.masterofchessstrategy.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ActiveGameEntity::class],
    version = 1,
    exportSchema = true,
)
internal abstract class MocsDatabase : RoomDatabase() {
    abstract fun activeGameDao(): ActiveGameDao

    companion object {
        @Volatile
        private var instance: MocsDatabase? = null

        fun getInstance(context: Context): MocsDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MocsDatabase::class.java,
                    DATABASE_NAME,
                ).build().also { instance = it }
            }

        private const val DATABASE_NAME = "master-of-chess-strategy.db"
    }
}
