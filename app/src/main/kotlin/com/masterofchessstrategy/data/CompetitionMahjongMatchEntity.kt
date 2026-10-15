package com.masterofchessstrategy.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** One complete private competition per game; never stores four-player data in a two-side clock row. */
@Entity(tableName = "competition_mahjong_matches")
internal data class CompetitionMahjongMatchEntity(
    @PrimaryKey val gameTypeCode: Int,
    val viewerPlayerId: Int,
    val matchId: String,
    val formatVersion: Int,
    val checkpoint: ByteArray,
    val updatedAtEpochMillis: Long,
)
