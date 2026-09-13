package com.masterofchessstrategy.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Versioned tutorial completion for one game family. */
@Entity(tableName = "tutorial_progress")
internal data class TutorialProgressEntity(
    @PrimaryKey
    val gameTypeCode: Int,
    val contentVersion: Int,
    val completedStepCount: Int,
    val isCompleted: Boolean,
    val updatedAtEpochMillis: Long,
)
