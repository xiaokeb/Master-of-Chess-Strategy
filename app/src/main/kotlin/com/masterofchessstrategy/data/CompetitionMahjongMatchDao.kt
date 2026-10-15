package com.masterofchessstrategy.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
internal interface CompetitionMahjongMatchDao {
    @Query("SELECT * FROM competition_mahjong_matches WHERE gameTypeCode = :gameTypeCode LIMIT 1")
    suspend fun find(gameTypeCode: Int): CompetitionMahjongMatchEntity?

    @Query("SELECT * FROM competition_mahjong_matches ORDER BY gameTypeCode")
    suspend fun listAll(): List<CompetitionMahjongMatchEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: CompetitionMahjongMatchEntity)

    @Query("DELETE FROM competition_mahjong_matches")
    suspend fun deleteAll()
}
