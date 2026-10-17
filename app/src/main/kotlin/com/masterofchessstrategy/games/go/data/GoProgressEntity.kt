package com.masterofchessstrategy.games.go.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.masterofchessstrategy.data.GoLegacyImportScopes
import com.masterofchessstrategy.games.go.GoEndgameLibrary
import com.masterofchessstrategy.games.go.GoEndgameProgress
import com.masterofchessstrategy.games.go.GoEndgameProgressCodec
import com.masterofchessstrategy.games.go.GoLearningLibrary
import com.masterofchessstrategy.games.go.GoLearningProgress

internal enum class GoProgressKind(val code: Int, val legacyScope: String) {
    LEARNING(0, GoLegacyImportScopes.LEARNING), ENDGAME(1, GoLegacyImportScopes.ENDGAME);

    val contentVersion: Int get() = when (this) {
        LEARNING -> GoLearningLibrary.CONTENT_VERSION
        ENDGAME -> GoEndgameLibrary.CONTENT_VERSION
    }

    fun emptyBytes(): ByteArray = when (this) {
        LEARNING -> GoLearningProgressCodec.encode(GoLearningProgress())
        ENDGAME -> GoEndgameProgressCodec.encode(GoEndgameProgress())
    }

    fun normalize(bytes: ByteArray): ByteArray = when (this) {
        LEARNING -> GoLearningProgressCodec.encode(requireNotNull(GoLearningProgressCodec.decode(bytes)) {
            "Invalid Go learning progress"
        })
        ENDGAME -> GoEndgameProgressCodec.encode(requireNotNull(GoEndgameProgressCodec.decode(bytes)) {
            "Invalid Go endgame progress"
        })
    }
}

/** Payloads are game-owned codecs; Room's wrapper version does not replace their format versions. */
@Entity(tableName = "go_progress", primaryKeys = ["gameTypeCode", "kindCode"])
internal data class GoProgressEntity(
    val gameTypeCode: Int,
    val kindCode: Int,
    val formatVersion: Int,
    val contentVersion: Int,
    val payload: ByteArray,
    val updatedAtEpochMillis: Long,
)

@Dao
internal interface GoProgressDao {
    @Query("SELECT * FROM go_progress ORDER BY gameTypeCode,kindCode")
    suspend fun listAll(): List<GoProgressEntity>

    @Query("SELECT * FROM go_progress WHERE gameTypeCode = :gameTypeCode AND kindCode = :kindCode")
    suspend fun find(gameTypeCode: Int, kindCode: Int): GoProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun save(entity: GoProgressEntity)

    @Query("DELETE FROM go_progress WHERE gameTypeCode = :gameTypeCode AND kindCode = :kindCode")
    suspend fun clear(gameTypeCode: Int, kindCode: Int)

    @Query("DELETE FROM go_progress")
    suspend fun deleteAll()
}
