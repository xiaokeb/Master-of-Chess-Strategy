package com.masterofchessstrategy.data

import androidx.room.Dao
import androidx.room.Query

/** Inventory all actual row owners without decoding or silently filtering unsupported games. */
@Dao
internal interface BackupStorageInventoryDao {
    @Query("SELECT gameTypeCode FROM active_games ORDER BY gameTypeCode")
    suspend fun activeGameTypeCodes(): List<Int>

    @Query("SELECT gameTypeCode FROM competition_mahjong_matches ORDER BY gameTypeCode")
    suspend fun competitionGameTypeCodes(): List<Int>
}
