package com.masterofchessstrategy.games.go.data

import androidx.room.*
import com.masterofchessstrategy.data.GameRecordEntity
import com.masterofchessstrategy.data.MocsDatabase

/** Versioned association only, never a second record payload or proof of ranked participation. */
@Entity(tableName = "go_ai_projections", foreignKeys = [
    ForeignKey(entity = GameRecordEntity::class, parentColumns = ["recordId"], childColumns = ["recordId"],
        onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION, deferred = true),
    ForeignKey(entity = GoAiArchiveEntity::class, parentColumns = ["recordId"], childColumns = ["recordId"],
        onDelete = ForeignKey.NO_ACTION, onUpdate = ForeignKey.NO_ACTION, deferred = true),
])
internal data class GoAiProjectionEntity(@PrimaryKey val recordId: String, val projectionVersion: Int = 1)

@Dao
internal interface GoAiProjectionDao {
    @Query("SELECT * FROM go_ai_projections ORDER BY recordId")
    suspend fun listAll(): List<GoAiProjectionEntity>
    @Query("SELECT * FROM go_ai_projections WHERE recordId = :recordId")
    suspend fun find(recordId: String): GoAiProjectionEntity?
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: GoAiProjectionEntity): Long
    @Query("DELETE FROM go_ai_projections")
    suspend fun deleteAll()
    @Query("SELECT EXISTS(SELECT 1 FROM go_ai_projections AS p " +
        "LEFT JOIN game_records AS r ON r.recordId = p.recordId " +
        "LEFT JOIN go_ai_archives AS a ON a.recordId = p.recordId " +
        "WHERE p.projectionVersion != 1 OR r.recordId IS NULL OR a.recordId IS NULL " +
        "OR r.gameTypeCode != 1 OR a.gameTypeCode != 1)")
    suspend fun hasInvalidParents(): Boolean
}

/** Reject deferred violations inside the transaction body, before Room attempts COMMIT. */
internal suspend fun MocsDatabase.requireGoAiProjectionParents() {
    check(!goAiProjectionDao().hasInvalidParents()) { "Invalid Go AI projection association before commit" }
}
