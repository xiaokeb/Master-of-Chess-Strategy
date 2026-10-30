package com.masterofchessstrategy.games.lanzhou_fangqi.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/** Raw rows and explicit deletion primitives; qualification and transaction authority stay in the repository. */
@Dao
internal interface FangqiRankedDao {
    @Query("SELECT * FROM fangqi_ranked_starts WHERE sessionId = :sessionId LIMIT 1")
    suspend fun findStart(sessionId: String): FangqiRankedStartEntity?

    @Query("SELECT * FROM fangqi_ranked_starts ORDER BY sessionId")
    suspend fun readStarts(): List<FangqiRankedStartEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertStart(row: FangqiRankedStartEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertStarts(rows: List<FangqiRankedStartEntity>)

    @Query("SELECT * FROM fangqi_ranked_settlements WHERE sessionId = :sessionId LIMIT 1")
    suspend fun findSettlement(sessionId: String): FangqiRankedSettlementEntity?

    @Query("SELECT * FROM fangqi_ranked_settlements ORDER BY sessionId")
    suspend fun readSettlements(): List<FangqiRankedSettlementEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSettlement(row: FangqiRankedSettlementEntity)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSettlements(rows: List<FangqiRankedSettlementEntity>)

    /** Only the enclosing verified deletion transaction may set this once. */
    @Query("UPDATE fangqi_ranked_settlements SET recordDeletedAtEpochMillis = :deletedAtEpochMillis " +
        "WHERE sessionId = :sessionId AND recordDeletedAtEpochMillis IS NULL")
    suspend fun markRecordDeleted(sessionId: String, deletedAtEpochMillis: Long): Int

    @Query("DELETE FROM game_records WHERE recordId = :recordId")
    suspend fun deleteCompletedRecord(recordId: String): Int
}
