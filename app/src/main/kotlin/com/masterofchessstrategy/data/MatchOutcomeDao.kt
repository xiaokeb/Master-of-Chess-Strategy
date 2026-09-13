package com.masterofchessstrategy.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
internal interface MatchOutcomeDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: MatchOutcomeEntity): Long

    @Query("SELECT * FROM match_outcomes ORDER BY settledAtEpochMillis, matchId")
    suspend fun listAll(): List<MatchOutcomeEntity>
}
