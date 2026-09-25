package com.masterofchessstrategy.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Singleton row containing user-editable offline settings. */
@Entity(tableName = "app_settings")
internal data class AppSettingsEntity(
    @PrimaryKey
    val id: Int,
    val defaultDifficultyCode: Int,
    val autoContinueEnabled: Boolean,
    @androidx.room.ColumnInfo(defaultValue = "10")
    val autoContinueGameLimit: Int = 10,
    val soundEnabled: Boolean,
    val gameDurationMinutes: Int?,
    @androidx.room.ColumnInfo(defaultValue = "0")
    val selectedAppearanceCode: Int = 0,
    @androidx.room.ColumnInfo(defaultValue = "0")
    val highlightConditionsMask: Int = 0,
    val updatedAtEpochMillis: Long,
)
