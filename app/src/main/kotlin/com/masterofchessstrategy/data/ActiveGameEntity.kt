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
    @ColumnInfo(defaultValue = "0")
    val acceptedMoveCount: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val undoUseCount: Int = 0,
    @ColumnInfo(defaultValue = "0")
    val hintUseCount: Int = 0,
    val resultOverrideCode: Int? = null,
    val timeControlMinutes: Int? = null,
    val redRemainingMillis: Long? = null,
    val blackRemainingMillis: Long? = null,
    val turnStartedAtEpochMillis: Long? = null,
    val pendingDrawOfferSideCode: Int? = null,
    @ColumnInfo(defaultValue = "0")
    val autoPlayPaused: Boolean = false,
    @ColumnInfo(defaultValue = "1000")
    val autoPlaySpeedPermille: Int = 1_000,
    @ColumnInfo(defaultValue = "0")
    val completedAutoGames: Int = 0,
    @ColumnInfo(defaultValue = "''")
    val sessionVariantId: String = "",
    @ColumnInfo(defaultValue = "0")
    val playerIndex: Int = 0,
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
                    updatedAtEpochMillis == other.updatedAtEpochMillis &&
                    sessionId == other.sessionId &&
                    acceptedMoveCount == other.acceptedMoveCount &&
                    undoUseCount == other.undoUseCount &&
                    hintUseCount == other.hintUseCount &&
                    resultOverrideCode == other.resultOverrideCode &&
                    timeControlMinutes == other.timeControlMinutes &&
                    redRemainingMillis == other.redRemainingMillis &&
                    blackRemainingMillis == other.blackRemainingMillis &&
                    turnStartedAtEpochMillis == other.turnStartedAtEpochMillis &&
                    pendingDrawOfferSideCode == other.pendingDrawOfferSideCode &&
                    autoPlayPaused == other.autoPlayPaused &&
                    autoPlaySpeedPermille == other.autoPlaySpeedPermille &&
                    completedAutoGames == other.completedAutoGames &&
                    sessionVariantId == other.sessionVariantId &&
                    playerIndex == other.playerIndex
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
        result = 31 * result + acceptedMoveCount
        result = 31 * result + undoUseCount
        result = 31 * result + hintUseCount
        result = 31 * result + (resultOverrideCode ?: 0)
        result = 31 * result + (timeControlMinutes ?: 0)
        result = 31 * result + (redRemainingMillis?.hashCode() ?: 0)
        result = 31 * result + (blackRemainingMillis?.hashCode() ?: 0)
        result = 31 * result + (turnStartedAtEpochMillis?.hashCode() ?: 0)
        result = 31 * result + (pendingDrawOfferSideCode ?: 0)
        result = 31 * result + autoPlayPaused.hashCode()
        result = 31 * result + autoPlaySpeedPermille
        result = 31 * result + completedAutoGames
        result = 31 * result + sessionVariantId.hashCode()
        result = 31 * result + playerIndex
        return result
    }
}
