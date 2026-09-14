package com.masterofchessstrategy.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Immutable completed-game payload; only the favorite flag may change. */
@Entity(tableName = "game_records")
internal data class GameRecordEntity(
    @PrimaryKey
    val recordId: String,
    val gameTypeCode: Int,
    val modeCode: Int,
    val difficultyCode: Int?,
    val resultCode: Int,
    val engineFormatVersion: Int,
    val engineState: ByteArray,
    val moveCount: Int,
    @ColumnInfo(defaultValue = "0")
    val isFavorite: Boolean = false,
    @ColumnInfo(defaultValue = "0")
    val isEndgame: Boolean = false,
    val completedAtEpochMillis: Long,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is GameRecordEntity &&
                    recordId == other.recordId &&
                    gameTypeCode == other.gameTypeCode &&
                    modeCode == other.modeCode &&
                    difficultyCode == other.difficultyCode &&
                    resultCode == other.resultCode &&
                    engineFormatVersion == other.engineFormatVersion &&
                    engineState.contentEquals(other.engineState) &&
                    moveCount == other.moveCount &&
                    isFavorite == other.isFavorite &&
                    isEndgame == other.isEndgame &&
                    completedAtEpochMillis == other.completedAtEpochMillis
                )

    override fun hashCode(): Int {
        var result = recordId.hashCode()
        result = 31 * result + gameTypeCode
        result = 31 * result + modeCode
        result = 31 * result + (difficultyCode ?: 0)
        result = 31 * result + resultCode
        result = 31 * result + engineFormatVersion
        result = 31 * result + engineState.contentHashCode()
        result = 31 * result + moveCount
        result = 31 * result + isFavorite.hashCode()
        result = 31 * result + isEndgame.hashCode()
        result = 31 * result + completedAtEpochMillis.hashCode()
        return result
    }
}
