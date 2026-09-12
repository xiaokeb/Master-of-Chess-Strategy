package com.masterofchessstrategy.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Last successfully selected mode for one game type. */
@Entity(tableName = "last_game_selections")
internal data class LastSelectionEntity(
    @PrimaryKey
    val gameTypeCode: Int,
    val modeCode: Int,
    val difficultyCode: Int?,
    val updatedAtEpochMillis: Long,
)
