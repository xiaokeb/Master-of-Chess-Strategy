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

    @Query("SELECT gameTypeCode FROM go_local_sessions ORDER BY gameTypeCode")
    suspend fun goLocalGameTypeCodes(): List<Int>

    // Code 1 is Go's stable persisted/JNI code. Standalone pre-transaction Go records count too.
    @Query("SELECT gameTypeCode FROM go_record_sources UNION ALL " +
        "SELECT gameTypeCode FROM game_records WHERE gameTypeCode = 1 ORDER BY gameTypeCode")
    suspend fun goRecordSourceGameTypeCodes(): List<Int>

    @Query("SELECT gameTypeCode FROM go_progress ORDER BY gameTypeCode,kindCode")
    suspend fun goProgressGameTypeCodes(): List<Int>

    @Query("SELECT gameTypeCode FROM go_ai_sessions ORDER BY gameTypeCode")
    suspend fun goAiSessionGameTypeCodes(): List<Int>

    @Query("SELECT gameTypeCode FROM go_ai_archives ORDER BY gameTypeCode,recordId")
    suspend fun goAiArchiveGameTypeCodes(): List<Int>
}
