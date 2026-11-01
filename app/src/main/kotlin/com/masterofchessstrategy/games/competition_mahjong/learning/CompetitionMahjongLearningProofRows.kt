package com.masterofchessstrategy.games.competition_mahjong.learning

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** Dedicated curriculum facts; neither this table nor its key is shared with legacy tutorial counts. */
@Entity(tableName = "competition_mahjong_learning_proof")
internal data class CompetitionMahjongLearningProofRow(
    @PrimaryKey val singletonId: Int,
    val proof: ByteArray,
)

internal data class CompetitionMahjongLearningProofMetadata(val singletonId: Int, val proofBytes: Long)

@Dao
internal interface CompetitionMahjongLearningProofDao {
    // Two metadata rows reveal a damaged singleton without loading any unbounded BLOB.
    @Query("SELECT singletonId, length(proof) AS proofBytes FROM competition_mahjong_learning_proof " +
        "ORDER BY singletonId LIMIT 2")
    suspend fun readBoundedMetadata(): List<CompetitionMahjongLearningProofMetadata>

    @Query("SELECT substr(proof, 1, :maximumBytes) FROM competition_mahjong_learning_proof " +
        "WHERE singletonId = 0 AND length(proof) BETWEEN :minimumBytes AND :maximumBytes LIMIT 1")
    suspend fun readBoundedPayload(minimumBytes: Int, maximumBytes: Int): ByteArray?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: CompetitionMahjongLearningProofRow)

    @Query("DELETE FROM competition_mahjong_learning_proof")
    suspend fun deleteAll()
}
