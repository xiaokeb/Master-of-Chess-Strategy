package com.masterofchessstrategy.games.competition_mahjong.auto

import com.masterofchessstrategy.engine.CompetitionMahjongAutoConfig
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongCompetitionConfig
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchSeating
import java.util.UUID

/** Stable player strategies; physical seats follow the official sixteen-hand seating. */
class CompetitionMahjongAutoRunConfig(
    val runId: String,
    val seed: Long,
    val viewerPlayerId: Int = 0,
    playerBotLevels: List<Int> = List(4) { 0 },
    val decisionSeed: Long = seed,
) {
    private val levels = playerBotLevels.toList()
    val playerBotLevels: List<Int> get() = levels.toList()
    init {
        require(runCatching { UUID.fromString(runId).toString() == runId }.getOrDefault(false))
        require(seed >= 0 && decisionSeed >= 0 && viewerPlayerId in 0..3)
        require(levels.size == 4 && levels.all { it in 0..3 })
    }

    internal fun seriesConfig(index: Int, hasOpening: Boolean): CompetitionMahjongCompetitionConfig {
        require(index in 0 until MAX_SERIES)
        val id = if (index == 0) runId else UUID.nameUUIDFromBytes("$runId/series/$index".toByteArray(Charsets.US_ASCII)).toString()
        return CompetitionMahjongCompetitionConfig(id, if (hasOpening) seed else seedAt(seed, index), viewerPlayerId, 0)
    }

    internal fun handConfig(series: Int, hand: Int, hasOpening: Boolean): CompetitionMahjongAutoConfig {
        val single = seriesConfig(series, hasOpening).handConfig(hand)
        val seating = CompetitionMahjongMatchSeating.forHand(hand)
        val decisionIndex = if (hasOpening && hand == 0) 0 else series * 16 + hand
        return CompetitionMahjongAutoConfig(single.seed, single.dealerSeat, single.roundWind, single.viewerSeat,
            (0..3).map { seat -> levels[seating.playerAtSeat(seat)] }, seedAt(decisionSeed, decisionIndex))
    }

    override fun equals(other: Any?): Boolean = other is CompetitionMahjongAutoRunConfig &&
        runId == other.runId && seed == other.seed && viewerPlayerId == other.viewerPlayerId && levels == other.levels && decisionSeed == other.decisionSeed
    override fun hashCode(): Int = listOf(runId, seed, viewerPlayerId, levels, decisionSeed).hashCode()
    override fun toString(): String = "CompetitionMahjongAutoRunConfig(runId=$runId, viewerPlayerId=$viewerPlayerId)"

    companion object {
        const val MAX_SERIES = 1000 // Finite scheduling/storage guard, not an earned-game quota.
        private fun seedAt(base: Long, index: Int): Long {
            if (index == 0) return base
            var value = base + index * 0x9e3779b97f4a7c15UL.toLong()
            value = (value xor (value ushr 30)) * 0xbf58476d1ce4e5b9UL.toLong()
            value = (value xor (value ushr 27)) * 0x94d049bb133111ebUL.toLong()
            return (value xor (value ushr 31)) and Long.MAX_VALUE
        }
    }
}

data class CompetitionMahjongAutoSettings(
    val paused: Boolean = true,
    val speed: Float = 1f,
    val stepDelayMillis: Long = 400,
    val handBreakMillis: Long = 1500,
    val autoContinue: Boolean = false,
    val maxSeries: Int = 1,
    val backgroundEnabled: Boolean = false,
    val backgroundDelayMillis: Long = 3000,
    val archiveAll: Boolean = true,
    val archiveFanThreshold: Int? = null,
    val archiveLongHandEvents: Int? = null,
    val archiveComeback: Boolean = false,
    val archiveMaster: Boolean = false,
) {
    init {
        require(speed.isFinite() && speed in 0.5f..4f)
        require(stepDelayMillis in 0..60_000 && handBreakMillis in 0..60_000)
        require(maxSeries in 1..CompetitionMahjongAutoRunConfig.MAX_SERIES)
        require(backgroundDelayMillis in 2000..60_000)
        require(archiveFanThreshold == null || archiveFanThreshold in 1..10_000)
        require(archiveLongHandEvents == null || archiveLongHandEvents in 1..4096)
    }

    fun delayMillis(terminal: Boolean, foreground: Boolean): Long {
        val base = if (terminal) handBreakMillis else stepDelayMillis
        val delay = (base / speed).toLong().coerceAtLeast(16) // Yield even when the configured pause is zero.
        return if (foreground) delay else delay.coerceAtLeast(backgroundDelayMillis)
    }
}
