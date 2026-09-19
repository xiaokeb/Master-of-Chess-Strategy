package com.masterofchessstrategy.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
internal interface ActiveGameDao {
    @Query("SELECT * FROM active_games WHERE gameTypeCode = :gameTypeCode LIMIT 1")
    suspend fun find(gameTypeCode: Int): ActiveGameEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ActiveGameEntity)

    @Query("DELETE FROM active_games WHERE gameTypeCode = :gameTypeCode")
    suspend fun delete(gameTypeCode: Int)

    @Query("DELETE FROM active_games")
    suspend fun deleteAll()
}
