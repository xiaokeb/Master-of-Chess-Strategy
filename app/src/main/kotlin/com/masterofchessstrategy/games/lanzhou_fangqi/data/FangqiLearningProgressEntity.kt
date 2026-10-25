package com.masterofchessstrategy.games.lanzhou_fangqi.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** Dedicated content keys; never share Xiangqi tutorial steps, endgames or reward rows. */
@Entity(tableName = "fangqi_learning_progress")
internal data class FangqiLearningProgressEntity(
    @PrimaryKey val contentKey: String,
    val contentVersion: Int,
    val completedAtEpochMillis: Long,
)

@Dao
internal interface FangqiLearningProgressDao {
    // Nine authored keys plus one corruption sentinel; never load an unbounded bad table.
    @Query("SELECT * FROM fangqi_learning_progress ORDER BY contentKey LIMIT 10")
    suspend fun readAll(): List<FangqiLearningProgressEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(row: FangqiLearningProgressEntity)

    // For the future coordinated backup adapter, inside its enclosing transaction.
    @Query("DELETE FROM fangqi_learning_progress")
    suspend fun clearAll()
}
