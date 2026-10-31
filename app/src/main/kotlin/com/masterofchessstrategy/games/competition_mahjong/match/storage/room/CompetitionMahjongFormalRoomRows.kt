package com.masterofchessstrategy.games.competition_mahjong.match.storage.room

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/** Local fencing metadata is never exported or restored from a backup. */
@Entity(tableName = "competition_mahjong_formal")
internal data class CompetitionMahjongFormalRoomRow(
    @PrimaryKey val viewerPlayerId: Int,
    val ownerId: String,
    val epoch: Long,
    val revision: Long,
    val mctx: ByteArray?,
)

internal data class CompetitionMahjongFormalRoomMetadata(
    val viewerPlayerId: Int,
    val ownerId: String,
    val epoch: Long,
    val revision: Long,
    val mctxSize: Int?,
)

/** Synchronous methods run on the repository's IO dispatcher, never via runBlocking. */
@Dao
internal interface CompetitionMahjongFormalRoomDao {
    @Query("SELECT viewerPlayerId, ownerId, epoch, revision, length(mctx) AS mctxSize " +
        "FROM competition_mahjong_formal WHERE viewerPlayerId = :viewer")
    fun find(viewer: Int): CompetitionMahjongFormalRoomMetadata?

    @Query("SELECT substr(mctx, :offset, :count) FROM competition_mahjong_formal " +
        "WHERE viewerPlayerId = :viewer AND ownerId = :owner AND epoch = :epoch AND revision = :revision")
    fun readChunk(viewer: Int, owner: String, epoch: Long, revision: Long, offset: Int, count: Int): ByteArray?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insert(row: CompetitionMahjongFormalRoomRow)

    @Query("UPDATE competition_mahjong_formal SET ownerId = :nextOwner, epoch = :nextEpoch, " +
        "revision = :nextRevision WHERE viewerPlayerId = :viewer AND ownerId = :owner " +
        "AND epoch = :epoch AND revision = :revision")
    fun claim(viewer: Int, owner: String, epoch: Long, revision: Long,
              nextOwner: String, nextEpoch: Long, nextRevision: Long): Int

    @Query("UPDATE competition_mahjong_formal SET mctx = :bytes, revision = :nextRevision " +
        "WHERE viewerPlayerId = :viewer AND ownerId = :owner AND epoch = :epoch AND revision = :revision")
    fun replace(viewer: Int, owner: String, epoch: Long, revision: Long,
                nextRevision: Long, bytes: ByteArray): Int

    companion object {
        // Schema 22 creates an empty table; old MCMT sources remain explicitly unmigrated.
        const val CREATE_SQL = "CREATE TABLE IF NOT EXISTS `competition_mahjong_formal` " +
            "(`viewerPlayerId` INTEGER NOT NULL, `ownerId` TEXT NOT NULL, `epoch` INTEGER NOT NULL, " +
            "`revision` INTEGER NOT NULL, `mctx` BLOB, PRIMARY KEY(`viewerPlayerId`))"
    }
}
