package com.masterofchessstrategy.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
internal interface GameRecordDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: GameRecordEntity): Long

    @Query("SELECT * FROM game_records ORDER BY completedAtEpochMillis DESC, recordId")
    suspend fun listAll(): List<GameRecordEntity>

    @Query("SELECT * FROM game_records WHERE recordId = :recordId")
    suspend fun find(recordId: String): GameRecordEntity?

    @Query("UPDATE game_records SET isFavorite = :favorite WHERE recordId = :recordId")
    suspend fun setFavorite(recordId: String, favorite: Boolean): Int

    @Query("DELETE FROM game_records")
    suspend fun deleteAll()
}
