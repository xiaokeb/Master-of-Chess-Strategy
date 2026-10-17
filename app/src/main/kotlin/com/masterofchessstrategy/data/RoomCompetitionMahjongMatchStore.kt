package com.masterofchessstrategy.data

import com.masterofchessstrategy.engine.GameType
import com.masterofchessstrategy.engine.NativeCompetitionMahjongSession
import com.masterofchessstrategy.engine.RestoreResult
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchCheckpoint
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchCheckpointCodec
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchStore
import java.io.IOException

/** Full MCMT bytes atomically commit configuration, stable-player ledger and current private hand. */
internal class RoomCompetitionMahjongMatchStore(
    private val dao: CompetitionMahjongMatchDao,
    private val now: () -> Long = System::currentTimeMillis,
) : CompetitionMahjongMatchStore {
    override suspend fun load(viewerPlayerId: Int): CompetitionMahjongMatchCheckpoint? {
        require(viewerPlayerId in 0..3)
        val row = dao.find(GameType.STANDARD_MAHJONG.code) ?: return null
        return try {
            val archive = CompetitionMahjongMatchArchive.fromEntity(row)
            require(archive.viewerPlayerId == viewerPlayerId)
            archive.checkpoint
        } catch (failure: IllegalArgumentException) {
            // Preserve the row: an invalid or differently owned record is not a missing game.
            throw IOException("国标赛事存档损坏、归属不符或版本不兼容", failure)
        }
    }

    override suspend fun save(checkpoint: CompetitionMahjongMatchCheckpoint) {
        val archive = CompetitionMahjongMatchArchive(
            checkpoint.config.viewerPlayerId, checkpoint.config.matchId, now(),
            CompetitionMahjongMatchCheckpointCodec.encode(checkpoint),
        )
        // One REPLACE statement is atomic. Validation/encoding failure never touches the old row.
        dao.upsert(archive.toEntity())
    }
}

/** Immutable backup value; parsing checks MCMT/MCPK/MCMS envelopes, not native replay legality. */
internal class CompetitionMahjongMatchArchive(
    val viewerPlayerId: Int,
    val matchId: String,
    val updatedAtEpochMillis: Long,
    encodedCheckpoint: ByteArray,
) {
    private val bytes = encodedCheckpoint.also { require(it.size in 1..MAX_CHECKPOINT_BYTES) }.copyOf()
    val encodedCheckpoint: ByteArray get() = bytes.copyOf()
    val checkpoint: CompetitionMahjongMatchCheckpoint

    init {
        require(updatedAtEpochMillis >= 0 && viewerPlayerId in 0..3)
        require(bytes.size in 1..MAX_CHECKPOINT_BYTES)
        checkpoint = requireNotNull(CompetitionMahjongMatchCheckpointCodec.decode(bytes, viewerPlayerId))
        require(checkpoint.config.matchId == matchId)
    }

    fun toEntity() = CompetitionMahjongMatchEntity(
        GameType.STANDARD_MAHJONG.code, viewerPlayerId, matchId, FORMAT_VERSION, bytes.copyOf(), updatedAtEpochMillis,
    )

    override fun equals(other: Any?): Boolean = other is CompetitionMahjongMatchArchive &&
        viewerPlayerId == other.viewerPlayerId && matchId == other.matchId &&
        updatedAtEpochMillis == other.updatedAtEpochMillis && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * (31 * (31 * viewerPlayerId + matchId.hashCode()) +
        updatedAtEpochMillis.hashCode()) + bytes.contentHashCode()

    override fun toString() = "CompetitionMahjongMatchArchive(matchId=$matchId, viewer=$viewerPlayerId)"

    companion object {
        const val FORMAT_VERSION = 1
        const val MAX_CHECKPOINT_BYTES = 1_100_000

        fun fromEntity(row: CompetitionMahjongMatchEntity): CompetitionMahjongMatchArchive {
            require(row.gameTypeCode == GameType.STANDARD_MAHJONG.code && row.formatVersion == FORMAT_VERSION)
            return CompetitionMahjongMatchArchive(row.viewerPlayerId, row.matchId, row.updatedAtEpochMillis, row.checkpoint)
        }
    }
}

/** Backup admission additionally checks the actual current hand and terminal ledger before any write. */
internal fun validateCompetitionMahjongMatchArchive(archive: CompetitionMahjongMatchArchive): Boolean {
    val checkpoint = archive.checkpoint
    val hand = checkpoint.config.handConfig(checkpoint.activeHandIndex)
    return try {
        NativeCompetitionMahjongSession(hand.seed, hand.dealerSeat, hand.roundWind, hand.viewerSeat).use { session ->
            if (session.restore(checkpoint.engineRecord) != RestoreResult.Restored) return false
            checkpoint.validateRestoredFrame(session.snapshot())
            true
        }
    } catch (_: IllegalArgumentException) {
        false
    } catch (_: IllegalStateException) {
        false
    }
}
