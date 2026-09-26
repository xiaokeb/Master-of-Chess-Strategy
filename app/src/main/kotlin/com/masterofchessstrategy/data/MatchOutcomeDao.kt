package com.masterofchessstrategy.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
internal interface MatchOutcomeDao {
    @Query("SELECT * FROM match_outcomes WHERE matchId = :matchId LIMIT 1")
    suspend fun find(matchId: String): MatchOutcomeEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: MatchOutcomeEntity): Long

    @Query("SELECT * FROM match_outcomes ORDER BY settledAtEpochMillis, matchId")
    suspend fun listAll(): List<MatchOutcomeEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(entities: List<MatchOutcomeEntity>)

    @Query("DELETE FROM match_outcomes")
    suspend fun deleteAll()
}
