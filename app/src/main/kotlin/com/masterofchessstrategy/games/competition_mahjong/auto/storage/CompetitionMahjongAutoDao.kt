package com.masterofchessstrategy.games.competition_mahjong.auto.storage

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoArchive
import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoCheckpoint
import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoCheckpointCodec

@Entity(tableName = "competition_mahjong_auto", primaryKeys = ["viewerPlayerId", "recordKey"])
internal data class CompetitionMahjongAutoRow(
    val viewerPlayerId: Int,
    val recordKey: String,
    val savedAtEpochMillis: Long,
    val favorite: Boolean,
    val payload: ByteArray,
) {
    fun checkpoint(): CompetitionMahjongAutoCheckpoint {
        require(savedAtEpochMillis >= 0)
        val checkpoint = requireNotNull(CompetitionMahjongAutoCheckpointCodec.decode(payload, viewerPlayerId)) {
            "自动演局存档损坏或归属不一致，原记录已保留"
        }
        if (recordKey == ACTIVE_KEY) {
            require(!favorite)
        } else {
            require(CompetitionMahjongAutoArchive(checkpoint).key == recordKey) { "自动赛事归档标识不一致" }
        }
        return checkpoint
    }

    fun archive() = CompetitionMahjongAutoArchive(checkpoint()).also { require(it.key == recordKey) }

    companion object {
        const val ACTIVE_KEY = ""
    }
}

internal data class CompetitionMahjongAutoRowKey(val viewerPlayerId: Int, val recordKey: String)

@Dao
internal interface CompetitionMahjongAutoDao {
    @Query("SELECT * FROM competition_mahjong_auto WHERE viewerPlayerId = :viewer AND recordKey = :key")
    suspend fun find(viewer: Int, key: String): CompetitionMahjongAutoRow?

    @Query("SELECT recordKey FROM competition_mahjong_auto WHERE viewerPlayerId = :viewer AND recordKey <> '' " +
        "ORDER BY savedAtEpochMillis DESC, recordKey ASC")
    suspend fun archiveKeys(viewer: Int): List<String>

    @Query("SELECT viewerPlayerId, recordKey FROM competition_mahjong_auto ORDER BY viewerPlayerId, recordKey")
    suspend fun keys(): List<CompetitionMahjongAutoRowKey>

    @Insert
    suspend fun insert(row: CompetitionMahjongAutoRow)

    @Upsert
    suspend fun upsert(row: CompetitionMahjongAutoRow)

    @Query("UPDATE competition_mahjong_auto SET favorite = :favorite WHERE viewerPlayerId = :viewer AND recordKey = :key")
    suspend fun setFavorite(viewer: Int, key: String, favorite: Boolean): Int

    @Query("DELETE FROM competition_mahjong_auto WHERE viewerPlayerId = :viewer AND recordKey = :key")
    suspend fun delete(viewer: Int, key: String): Int

    @Query("DELETE FROM competition_mahjong_auto")
    suspend fun deleteAll()

    companion object {
        const val CREATE_SQL = "CREATE TABLE IF NOT EXISTS competition_mahjong_auto (" +
            "viewerPlayerId INTEGER NOT NULL, recordKey TEXT NOT NULL, savedAtEpochMillis INTEGER NOT NULL, " +
            "favorite INTEGER NOT NULL, payload BLOB NOT NULL, PRIMARY KEY(viewerPlayerId, recordKey))"
    }
}
