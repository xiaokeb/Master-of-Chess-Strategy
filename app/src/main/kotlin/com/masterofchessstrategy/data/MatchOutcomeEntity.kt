package com.masterofchessstrategy.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Immutable, idempotent ledger row for one completed player match. */
@Entity(tableName = "match_outcomes")
internal data class MatchOutcomeEntity(
    @PrimaryKey
    val matchId: String,
    val gameTypeCode: Int,
    val modeCode: Int,
    val difficultyCode: Int,
    val playerIndex: Int,
    val resultCode: Int,
    val settledAtEpochMillis: Long,
)
