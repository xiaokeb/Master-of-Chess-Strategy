package com.masterofchessstrategy.games.competition_mahjong.history

import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongHandResult
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchCheckpoint
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchLedger
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchStanding
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongPlayPolicy
import java.util.Collections
import java.util.UUID

/** Public-safe sixteen-hand result sheet. It contains no wall, concealed hand or action journal. */
class CompetitionMahjongMatchHistoryEntry internal constructor(
    val matchId: String,
    val viewerPlayerId: Int,
    val difficulty: Difficulty,
    val botLevel: Int,
    val settledAtEpochMillis: Long,
    val favorite: Boolean,
    hands: List<CompetitionMahjongHandResult>,
) {
    val hands: List<CompetitionMahjongHandResult> = Collections.unmodifiableList(hands.toList())
    private val ledger = CompetitionMahjongMatchLedger.restored(this.hands)
    val standings: List<CompetitionMahjongMatchStanding> =
        Collections.unmodifiableList(ledger.finalStandings())
    val viewerStanding: CompetitionMahjongMatchStanding =
        standings.single { it.playerId == viewerPlayerId }

    init {
        require(runCatching { UUID.fromString(matchId).toString() == matchId }.getOrDefault(false))
        require(viewerPlayerId in 0..3 && botLevel in 0..3 && settledAtEpochMillis >= 0)
        require(this.hands.size == 16)
    }

    internal fun withFavorite(value: Boolean) = CompetitionMahjongMatchHistoryEntry(
        matchId, viewerPlayerId, difficulty, botLevel, settledAtEpochMillis, value, hands)

    /** Time and local favorite are metadata; duplicate completion compares the formal result. */
    internal fun sameResult(other: CompetitionMahjongMatchHistoryEntry): Boolean =
        matchId == other.matchId && viewerPlayerId == other.viewerPlayerId &&
            difficulty == other.difficulty && botLevel == other.botLevel && hands == other.hands

    companion object {
        fun from(checkpoint: CompetitionMahjongMatchCheckpoint,
                 playPolicy: CompetitionMahjongPlayPolicy,
                 settledAtEpochMillis: Long): CompetitionMahjongMatchHistoryEntry {
            require(checkpoint.ledger.isComplete) { "只归档已完成的十六盘赛事" }
            val usage = requireNotNull(checkpoint.assistance) { "旧赛事缺少正式难度，不进入成绩历史" }
            playPolicy.requireAuthorized(checkpoint.config.botLevel, usage)
            return CompetitionMahjongMatchHistoryEntry(
                checkpoint.config.matchId,
                checkpoint.config.viewerPlayerId,
                usage.difficulty,
                checkpoint.config.botLevel,
                settledAtEpochMillis,
                false,
                checkpoint.ledger.results,
            )
        }
    }
}

class CompetitionMahjongMatchHistory internal constructor(entries: List<CompetitionMahjongMatchHistoryEntry>) {
    val entries: List<CompetitionMahjongMatchHistoryEntry> = Collections.unmodifiableList(entries.toList())

    init {
        require(entries.map { it.matchId }.toSet().size == entries.size)
        require(entries.map { it.viewerPlayerId }.toSet().size <= 1)
        require(entries.zipWithNext().all { (first, second) ->
            !(!first.favorite && second.favorite) &&
                (first.favorite != second.favorite || first.settledAtEpochMillis >= second.settledAtEpochMillis)
        })
    }

    companion object {
        fun empty() = CompetitionMahjongMatchHistory(emptyList())
        internal fun from(entries: List<CompetitionMahjongMatchHistoryEntry>) =
            CompetitionMahjongMatchHistory(entries.sortedWith(
                compareByDescending<CompetitionMahjongMatchHistoryEntry> { it.favorite }
                    .thenByDescending { it.settledAtEpochMillis }
                    .thenBy { it.matchId }))
    }
}
