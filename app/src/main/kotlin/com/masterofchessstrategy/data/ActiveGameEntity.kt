package com.masterofchessstrategy.data

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.PrimaryKey

/** One recoverable active session per game type. */
@Entity(tableName = "active_games")
internal data class ActiveGameEntity(
    @PrimaryKey
    val gameTypeCode: Int,
    val modeCode: Int,
    val difficultyCode: Int?,
    val envelopeVersion: Int,
    val engineFormatVersion: Int,
    val engineState: ByteArray,
    val updatedAtEpochMillis: Long,
    @ColumnInfo(defaultValue = "''")
    val sessionId: String = "",
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (
                other is ActiveGameEntity &&
                    gameTypeCode == other.gameTypeCode &&
                    modeCode == other.modeCode &&
                    difficultyCode == other.difficultyCode &&
                    envelopeVersion == other.envelopeVersion &&
                    engineFormatVersion == other.engineFormatVersion &&
                    engineState.contentEquals(other.engineState) &&
                    updatedAtEpochMillis == other.updatedAtEpochMillis
                    && sessionId == other.sessionId
                )

    override fun hashCode(): Int {
        var result = gameTypeCode
        result = 31 * result + modeCode
        result = 31 * result + (difficultyCode ?: 0)
        result = 31 * result + envelopeVersion
        result = 31 * result + engineFormatVersion
        result = 31 * result + engineState.contentHashCode()
        result = 31 * result + updatedAtEpochMillis.hashCode()
        result = 31 * result + sessionId.hashCode()
        return result
    }
}
