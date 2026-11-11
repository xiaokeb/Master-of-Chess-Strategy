package com.masterofchessstrategy.games.competition_mahjong.auto.replay

import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoArchive
import com.masterofchessstrategy.games.competition_mahjong.auto.CompetitionMahjongAutoCheckpointCodec
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchStanding
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongPlayerScores
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.zip.CRC32

class CompetitionMahjongAutoArchiveSummary internal constructor(
    val key: String,
    val runId: String,
    val seriesIndex: Int,
    val viewerPlayerId: Int,
    playerBotLevels: List<Int>,
    val elapsedMillis: Long,
    val savedAtEpochMillis: Long,
    val favorite: Boolean,
    val scores: CompetitionMahjongPlayerScores,
    standings: List<CompetitionMahjongMatchStanding>,
) {
    val playerBotLevels: List<Int> = Collections.unmodifiableList(playerBotLevels.toList())
    val standings: List<CompetitionMahjongMatchStanding> = Collections.unmodifiableList(standings.toList())
    init {
        require(key == runId + "/" + seriesIndex && seriesIndex >= 0 && viewerPlayerId in 0..3)
        require(playerBotLevels.size == 4 && playerBotLevels.all { it in 0..3 })
        require(elapsedMillis >= 0 && savedAtEpochMillis >= 0)
        require(standings.size == 4 && standings.map { it.playerId }.toSet() == setOf(0, 1, 2, 3))
    }
    override fun toString(): String =
        "CompetitionMahjongAutoArchiveSummary(key=" + key + ", favorite=" + favorite + ")"
}

internal fun CompetitionMahjongAutoArchive.summary(savedAtEpochMillis: Long, favorite: Boolean = false):
    CompetitionMahjongAutoArchiveSummary {
    val value = checkpoint
    return CompetitionMahjongAutoArchiveSummary(key, value.config.runId, value.seriesIndex,
        value.config.viewerPlayerId, value.config.playerBotLevels, value.elapsedMillis, savedAtEpochMillis,
        favorite, value.ledger.cumulativeScores, value.ledger.finalStandings())
}

/** Coordinator implements durable ordering, favorite metadata and atomic archive deletion. */
interface CompetitionMahjongAutoArchiveLibrary {
    /** Sorted newest first. Missing returns an empty list; malformed rows throw and remain untouched. */
    suspend fun list(viewerPlayerId: Int): List<CompetitionMahjongAutoArchiveSummary>
    /** Missing returns null; corruption, unsupported version and wrong ownership throw. */
    suspend fun load(key: String, viewerPlayerId: Int): CompetitionMahjongAutoArchive?
    /** Missing keys and wrong ownership throw; only metadata changes. */
    suspend fun setFavorite(key: String, viewerPlayerId: Int, favorite: Boolean)
    /** Deletes one confirmed archive only, never the active MCAU activity. */
    suspend fun delete(key: String, viewerPlayerId: Int)
}

class CompetitionMahjongAutoExport internal constructor(
    val suggestedFileName: String,
    val mimeType: String,
    bytes: ByteArray,
) {
    private val content = bytes.copyOf()
    val bytes: ByteArray get() = content.copyOf()
    init {
        require(suggestedFileName.matches(Regex("[0-9a-f-]{36}-series-[0-9]+\\.mcae")))
        require(mimeType == MIME_TYPE && content.size in 16..CompetitionMahjongAutoExportCodec.MAX_BYTES)
    }
    override fun toString(): String =
        "CompetitionMahjongAutoExport(file=" + suggestedFileName + ", bytes=" + content.size + ")"
    companion object {
        const val MIME_TYPE = "application/vnd.masterofchessstrategy.competition-mahjong-auto"
    }
}

internal fun CompetitionMahjongAutoArchive.export(): CompetitionMahjongAutoExport =
    CompetitionMahjongAutoExport(checkpoint.config.runId + "-series-" + (checkpoint.seriesIndex + 1) + ".mcae",
        CompetitionMahjongAutoExport.MIME_TYPE, CompetitionMahjongAutoExportCodec.encode(this))

/** MCAE v1 contains a complete private MCAU archive. Import still requires full Native admission. */
internal object CompetitionMahjongAutoExportCodec {
    private const val MAGIC = 0x4d434145
    const val MAX_BYTES = CompetitionMahjongAutoCheckpointCodec.MAX_BYTES + 16

    fun encode(archive: CompetitionMahjongAutoArchive): ByteArray {
        val payload = CompetitionMahjongAutoCheckpointCodec.encode(archive.checkpoint)
        return ByteBuffer.allocate(payload.size + 16).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(MAGIC).put(1).put(0).putShort(0).putInt(payload.size).put(payload)
            putInt(CRC32().apply { update(array(), 0, position()) }.value.toInt())
        }.array()
    }

    fun decode(bytes: ByteArray, viewerPlayerId: Int): CompetitionMahjongAutoArchive? {
        require(viewerPlayerId in 0..3)
        if (bytes.size !in 16..MAX_BYTES) return null
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        if (input.int != MAGIC || input.get().toInt() != 1 || input.get().toInt() != 0 ||
            input.short.toInt() != 0 || input.int != bytes.size - 16 ||
            input.getInt(bytes.size - 4) != CRC32().apply { update(bytes, 0, bytes.size - 4) }.value.toInt()) return null
        return CompetitionMahjongAutoCheckpointCodec.decode(bytes.copyOfRange(12, bytes.size - 4), viewerPlayerId)
            ?.takeIf { it.ledger.isComplete }
            ?.let(::CompetitionMahjongAutoArchive)
    }
}
