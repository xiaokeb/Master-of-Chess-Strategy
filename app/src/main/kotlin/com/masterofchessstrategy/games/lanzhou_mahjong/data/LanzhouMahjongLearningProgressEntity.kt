package com.masterofchessstrategy.games.lanzhou_mahjong.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

@Entity(tableName = "lanzhou_mahjong_learning_progress")
internal data class LanzhouMahjongLearningProgressEntity(
    @PrimaryKey val singletonId: Int,
    val contentVersion: Int,
    val completedCount: Int,
    val updatedAtEpochMillis: Long,
)

@Dao
internal interface LanzhouMahjongLearningProgressDao {
    // Two rows suffice to reject any corrupt singleton inventory without unbounded allocation.
    @Query("SELECT * FROM lanzhou_mahjong_learning_progress ORDER BY singletonId LIMIT 2")
    suspend fun readBoundedRows(): List<LanzhouMahjongLearningProgressEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: LanzhouMahjongLearningProgressEntity)

    @Query("DELETE FROM lanzhou_mahjong_learning_progress")
    suspend fun deleteAll()
}
