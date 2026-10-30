package com.masterofchessstrategy.games.lanzhou_fangqi.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.masterofchessstrategy.data.MatchOutcomeEntity
import com.masterofchessstrategy.games.lanzhou_fangqi.FangqiRankedHistory

/** BLOB conversion owns both directions. It does not validate Native history or ranked provenance. */
internal class FangqiRankedHistoryConverters {
    @TypeConverter
    fun historyFromBytesUnvalidated(bytes: ByteArray): FangqiRankedHistory = FangqiRankedHistory(bytes)

    @TypeConverter
    fun historyToBytesUnvalidated(history: FangqiRankedHistory): ByteArray = history.bytes
}

/** Insert-only formal creation proposal. Registration/migration and trusted writes belong to Root. */
@Entity(tableName = "fangqi_ranked_starts")
@TypeConverters(FangqiRankedHistoryConverters::class)
internal data class FangqiRankedStartEntity(
    @PrimaryKey val sessionId: String,
    val formatVersion: Int,
    val startOrigin: String,
    val gameTypeCode: Int,
    val modeCode: Int,
    val difficultyCode: Int,
    val humanPlayerIndex: Int,
    val rulesVersion: String,
    val engineFormatVersion: Int,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val initialHistory: FangqiRankedHistory,
    val startedAtEpochMillis: Long,
    val acceptedActionCount: Int,
    val undoUseCount: Int,
    val hintUseCount: Int,
    val creationSha256: String,
)

/**
 * No game_records FK: explicit user deletion preserves the immutable receipt, history and growth.
 * Root must check full record companionship when the tombstone is null, absence when non-null.
 */
@Entity(tableName = "fangqi_ranked_settlements", foreignKeys = [
    ForeignKey(entity = FangqiRankedStartEntity::class,
        parentColumns = ["sessionId"], childColumns = ["sessionId"],
        onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION, deferred = true),
    ForeignKey(entity = MatchOutcomeEntity::class,
        parentColumns = ["matchId"], childColumns = ["sessionId"],
        onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION, deferred = true),
])
@TypeConverters(FangqiRankedHistoryConverters::class)
internal data class FangqiRankedSettlementEntity(
    @PrimaryKey val sessionId: String,
    val formatVersion: Int,
    val creationSha256: String,
    val gameTypeCode: Int,
    val modeCode: Int,
    val difficultyCode: Int,
    val humanPlayerIndex: Int,
    val rulesVersion: String,
    val engineFormatVersion: Int,
    @ColumnInfo(typeAffinity = ColumnInfo.BLOB) val terminalHistory: FangqiRankedHistory,
    val updatedAtEpochMillis: Long,
    val acceptedActionCount: Int,
    val undoUseCount: Int,
    val hintUseCount: Int,
    val resultCode: Int,
    val settledAtEpochMillis: Long,
    @ColumnInfo(defaultValue = "NULL") val recordDeletedAtEpochMillis: Long? = null,
)
