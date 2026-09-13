package com.masterofchessstrategy.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
internal interface TutorialProgressDao {
    @Query("SELECT * FROM tutorial_progress WHERE gameTypeCode = :gameTypeCode LIMIT 1")
    suspend fun find(gameTypeCode: Int): TutorialProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TutorialProgressEntity)

    @Query("DELETE FROM tutorial_progress WHERE gameTypeCode = :gameTypeCode")
    suspend fun delete(gameTypeCode: Int)
}
