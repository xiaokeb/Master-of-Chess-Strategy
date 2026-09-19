package com.masterofchessstrategy.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
internal interface LastSelectionDao {
    @Query(
        "SELECT * FROM last_game_selections " +
            "WHERE gameTypeCode = :gameTypeCode LIMIT 1",
    )
    suspend fun find(gameTypeCode: Int): LastSelectionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: LastSelectionEntity)

    @Query("DELETE FROM last_game_selections WHERE gameTypeCode = :gameTypeCode")
    suspend fun delete(gameTypeCode: Int)

    @Query("DELETE FROM last_game_selections")
    suspend fun deleteAll()
}
