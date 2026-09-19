package com.masterofchessstrategy.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
internal interface AppSettingsDao {
    @Query("SELECT * FROM app_settings WHERE id = 0 LIMIT 1")
    suspend fun find(): AppSettingsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: AppSettingsEntity)

    @Query("DELETE FROM app_settings WHERE id = 0")
    suspend fun delete()

    @Query("DELETE FROM app_settings")
    suspend fun deleteAll()
}
