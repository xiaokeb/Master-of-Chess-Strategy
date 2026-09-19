package com.masterofchessstrategy.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One row means the stable level id has been completed at least once. */
@Entity(tableName = "endgame_progress")
internal data class EndgameProgressEntity(
    @PrimaryKey
    val levelId: String,
    val contentVersion: Int,
    val difficultyCode: Int,
    val bestPlayerMoves: Int,
    val starsAwarded: Int,
    val scoreAwarded: Int,
    val completedAtEpochMillis: Long,
)
