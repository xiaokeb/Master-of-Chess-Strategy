package com.masterofchessstrategy.games.go.data

import androidx.room.*
import com.masterofchessstrategy.data.MatchOutcomeEntity
import com.masterofchessstrategy.data.MocsDatabase

/** Local formal-creation fact. No migration, import or ordinary checkpoint save may mint it. */
@Entity(tableName = "go_ranked_starts")
internal data class GoRankedStartEntity(
    @PrimaryKey val recordId: String,
    val formatVersion: Int,
    val initialCheckpoint: ByteArray,
    val createdAtEpochMillis: Long,
)

/** The outcome payload remains exclusively owned by match_outcomes. */
@Entity(tableName = "go_ranked_settlements", foreignKeys = [
    ForeignKey(entity = GoRankedStartEntity::class, parentColumns = ["recordId"], childColumns = ["recordId"],
        onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION, deferred = true),
    ForeignKey(entity = GoAiProjectionEntity::class, parentColumns = ["recordId"], childColumns = ["recordId"],
        onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION, deferred = true),
    ForeignKey(entity = MatchOutcomeEntity::class, parentColumns = ["matchId"], childColumns = ["recordId"],
        onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION, deferred = true),
])
internal data class GoRankedSettlementEntity(@PrimaryKey val recordId: String, val formatVersion: Int = 1)

@Dao
internal interface GoRankedDao {
    @Query("SELECT * FROM go_ranked_starts ORDER BY recordId")
    suspend fun starts(): List<GoRankedStartEntity>
    @Query("SELECT * FROM go_ranked_starts WHERE recordId = :recordId")
    suspend fun start(recordId: String): GoRankedStartEntity?
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertStart(row: GoRankedStartEntity)
    @Query("SELECT * FROM go_ranked_settlements ORDER BY recordId")
    suspend fun settlements(): List<GoRankedSettlementEntity>
    @Query("SELECT * FROM go_ranked_settlements WHERE recordId = :recordId")
    suspend fun settlement(recordId: String): GoRankedSettlementEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSettlement(row: GoRankedSettlementEntity): Long
    @Query("DELETE FROM go_ranked_settlements")
    suspend fun deleteSettlements()
    @Query("DELETE FROM go_ranked_starts")
    suspend fun deleteStarts()
    @Query("SELECT EXISTS(SELECT 1 FROM go_ranked_settlements AS s " +
        "LEFT JOIN go_ranked_starts AS c ON c.recordId = s.recordId " +
        "LEFT JOIN go_ai_projections AS p ON p.recordId = s.recordId " +
        "LEFT JOIN match_outcomes AS o ON o.matchId = s.recordId " +
        "WHERE s.formatVersion != 1 OR c.recordId IS NULL OR c.formatVersion != 1 " +
        "OR c.createdAtEpochMillis < 0 OR p.recordId IS NULL OR p.projectionVersion != 1 " +
        "OR o.matchId IS NULL OR o.gameTypeCode != 1 OR o.modeCode != 1)")
    suspend fun hasInvalidParents(): Boolean
}

internal fun GoRankedStart.toEntity() = GoRankedStartEntity(recordId, version, copyBytes(), createdAtEpochMillis)
internal fun GoRankedStartEntity.toStart() = GoRankedStart(recordId, initialCheckpoint, createdAtEpochMillis, formatVersion)

internal suspend fun MocsDatabase.requireGoRankedParents() {
    check(!goRankedDao().hasInvalidParents()) { "Invalid Go ranked settlement association before commit" }
}
