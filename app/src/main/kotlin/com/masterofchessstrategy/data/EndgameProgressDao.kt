package com.masterofchessstrategy.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
internal interface EndgameProgressDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: EndgameProgressEntity): Long

    @Query("SELECT * FROM endgame_progress ORDER BY completedAtEpochMillis, levelId")
    suspend fun listAll(): List<EndgameProgressEntity>

    @Query(
        "UPDATE endgame_progress SET bestPlayerMoves = :moves " +
            "WHERE levelId = :levelId AND bestPlayerMoves > :moves",
    )
    suspend fun updateBest(levelId: String, moves: Int): Int
}
