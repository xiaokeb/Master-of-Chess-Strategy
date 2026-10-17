package com.masterofchessstrategy.games.go.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Index
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** The whole GOS1 envelope keeps identity and native bytes in the same durable checkpoint. */
@Entity(tableName = "go_local_sessions")
internal data class GoLocalSessionEntity(
    @PrimaryKey val gameTypeCode: Int,
    val envelopeVersion: Int,
    val checkpoint: ByteArray,
    val updatedAtEpochMillis: Long,
)

@Dao
internal interface GoLocalSessionDao {
    @Query("SELECT * FROM go_local_sessions ORDER BY gameTypeCode")
    suspend fun listAll(): List<GoLocalSessionEntity>

    @Query("SELECT * FROM go_local_sessions WHERE gameTypeCode = :gameTypeCode")
    suspend fun find(gameTypeCode: Int): GoLocalSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: GoLocalSessionEntity)

    @Query("DELETE FROM go_local_sessions WHERE gameTypeCode = :gameTypeCode")
    suspend fun clear(gameTypeCode: Int)

    @Query("DELETE FROM go_local_sessions")
    suspend fun deleteAll()
}

/** Separate provenance never implies a ranked outcome or an endgame reward. */
@Entity(tableName = "go_record_sources", indices = [Index(value = ["sessionId", "archiveRevision"], unique = true)])
internal data class GoRecordSourceEntity(
    @PrimaryKey val recordId: String,
    val gameTypeCode: Int,
    val sessionId: String,
    val originCode: Int,
    val archiveRevision: Int,
)

@Dao
internal interface GoRecordSourceDao {
    @Query("SELECT * FROM go_record_sources ORDER BY recordId")
    suspend fun listAll(): List<GoRecordSourceEntity>

    @Query("SELECT * FROM go_record_sources WHERE recordId = :recordId")
    suspend fun find(recordId: String): GoRecordSourceEntity?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: GoRecordSourceEntity): Long

    @Query("DELETE FROM go_record_sources")
    suspend fun deleteAll()
}
