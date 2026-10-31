package com.masterofchessstrategy.games.competition_mahjong.match.storage

import com.masterofchessstrategy.games.competition_mahjong.history.CompetitionMahjongMatchHistory
import com.masterofchessstrategy.games.competition_mahjong.history.CompetitionMahjongMatchHistoryEntry
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchCheckpoint
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongPlayPolicy
import com.masterofchessstrategy.games.competition_mahjong.statistics.CompetitionMahjongSeriesOutcome
import com.masterofchessstrategy.games.competition_mahjong.statistics.CompetitionMahjongStatistics
import java.util.Collections
import java.util.UUID

/** Private transaction state. UI consumers receive only the statistics/history projections. */
internal class CompetitionMahjongFormalSnapshot(
    val viewerPlayerId: Int,
    val active: CompetitionMahjongMatchCheckpoint? = null,
    val statistics: CompetitionMahjongStatistics = CompetitionMahjongStatistics.empty(),
    val history: CompetitionMahjongMatchHistory = CompetitionMahjongMatchHistory.empty(),
    deletedMatchIds: Set<String> = emptySet(),
) {
    val deletedMatchIds: Set<String> = Collections.unmodifiableSet(deletedMatchIds.toSortedSet())

    init {
        require(viewerPlayerId in 0..3)
        require(this.deletedMatchIds.all { runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) })
        require(this.deletedMatchIds.size <= MAX_DELETIONS)
        require(statistics.outcomes.all { it.viewerPlayerId == viewerPlayerId })
        require(history.entries.all { it.viewerPlayerId == viewerPlayerId && it.matchId !in this.deletedMatchIds })
        val outcomes = statistics.outcomes.associateBy { it.matchId }
        history.entries.forEach { entry ->
            require(outcomes[entry.matchId] == entry.toOutcome()) { "成绩单与统计不一致" }
        }
        val visibleIds = history.entries.map { it.matchId }.toSet()
        require(outcomes.keys.all { it in visibleIds || it in this.deletedMatchIds }) { "统计缺少成绩单或删除标记" }
        active?.let { checkpoint ->
            require(checkpoint.config.viewerPlayerId == viewerPlayerId && checkpoint.assistance != null)
            val outcome = outcomes[checkpoint.config.matchId]
            if (checkpoint.ledger.isComplete) {
                requireNotNull(outcome) { "完成赛事未在同一事务结算" }
                val expected = checkpoint.toHistory(outcome.settledAtEpochMillis)
                require(outcome == expected.toOutcome()) { "当前赛事与统计不一致" }
                history.entries.firstOrNull { it.matchId == expected.matchId }?.let {
                    require(it.sameResult(expected)) { "当前赛事与历史不一致" }
                }
            } else require(outcome == null) { "未完成赛事仍有正式成绩" }
        }
    }

    fun save(checkpoint: CompetitionMahjongMatchCheckpoint, policy: CompetitionMahjongPlayPolicy,
             now: Long): CompetitionMahjongFormalSnapshot {
        require(now >= 0 && checkpoint.config.viewerPlayerId == viewerPlayerId)
        policy.requireAuthorized(checkpoint.config.botLevel, checkpoint.assistance)
        val id = checkpoint.config.matchId
        val existing = statistics.outcomes.firstOrNull { it.matchId == id }
        if (active?.config?.matchId == id) {
            require(active.config == checkpoint.config) { "进行中赛事配置不可替换" }
            val previousUsage = checkNotNull(active.assistance)
            val nextUsage = checkNotNull(checkpoint.assistance)
            require(nextUsage.undoUsed >= previousUsage.undoUsed && nextUsage.hintUsed >= previousUsage.hintUsed) {
                "赛事辅助用量不可回退"
            }
            require(checkpoint.activeHandIndex in active.activeHandIndex..minOf(active.activeHandIndex + 1, 15)) {
                "不可跳盘或退回过去盘"
            }
            require(checkpoint.ledger.results.take(active.activeHandIndex) == active.ledger.results.take(active.activeHandIndex)) {
                "已离开的盘不可修改成绩"
            }
            if (checkpoint.activeHandIndex > active.activeHandIndex)
                require(active.ledger.completedHandCount == checkpoint.activeHandIndex &&
                    checkpoint.ledger.results.take(checkpoint.activeHandIndex) == active.ledger.results) {
                    "当前盘完成后才能换盘，已结算成绩不可修改"
                }
            if (active.ledger.isComplete && checkpoint.ledger.isComplete)
                require(active.ledger.results == checkpoint.ledger.results) { "完成赛事需先撤回才能修改" }
        }
        else require(existing == null && id !in deletedMatchIds) { "已归档赛事编号不能重新开局" }

        if (!checkpoint.ledger.isComplete) {
            if (existing != null) require(active?.activeHandIndex == 15 && checkpoint.activeHandIndex == 15 &&
                checkpoint.ledger.completedHandCount == 15) { "只能撤回当前赛事末盘成绩" }
            return CompetitionMahjongFormalSnapshot(viewerPlayerId, checkpoint,
                CompetitionMahjongStatistics.from(statistics.outcomes.filterNot { it.matchId == id }),
                CompetitionMahjongMatchHistory.from(history.entries.filterNot { it.matchId == id }), deletedMatchIds)
        }

        val time = existing?.settledAtEpochMillis ?: now
        val outcome = CompetitionMahjongSeriesOutcome.from(checkpoint, policy, time)
        val entry = CompetitionMahjongMatchHistoryEntry.from(checkpoint, policy, time)
        if (existing != null) {
            require(existing == outcome) { "同一赛事结果冲突，需先保存合法撤回" }
            history.entries.firstOrNull { it.matchId == id }?.let { require(it.sameResult(entry)) }
            return CompetitionMahjongFormalSnapshot(viewerPlayerId, checkpoint, statistics, history, deletedMatchIds)
        }
        return CompetitionMahjongFormalSnapshot(viewerPlayerId, checkpoint,
            CompetitionMahjongStatistics.from(statistics.outcomes + outcome),
            if (id in deletedMatchIds) history else CompetitionMahjongMatchHistory.from(history.entries + entry),
            deletedMatchIds)
    }

    fun favorite(matchId: String, favorite: Boolean): CompetitionMahjongFormalSnapshot {
        require(history.entries.any { it.matchId == matchId }) { "赛事成绩单不存在" }
        return CompetitionMahjongFormalSnapshot(viewerPlayerId, active, statistics,
            CompetitionMahjongMatchHistory.from(history.entries.map {
                if (it.matchId == matchId) it.withFavorite(favorite) else it
            }), deletedMatchIds)
    }

    fun delete(matchId: String): CompetitionMahjongFormalSnapshot {
        if (matchId in deletedMatchIds) return this
        require(history.entries.any { it.matchId == matchId }) { "赛事成绩单不存在" }
        return CompetitionMahjongFormalSnapshot(viewerPlayerId, active, statistics,
            CompetitionMahjongMatchHistory.from(history.entries.filterNot { it.matchId == matchId }),
            deletedMatchIds + matchId)
    }

    companion object { const val MAX_DELETIONS = 10_000 }
}

private fun CompetitionMahjongMatchCheckpoint.toHistory(time: Long): CompetitionMahjongMatchHistoryEntry =
    CompetitionMahjongMatchHistoryEntry(config.matchId, config.viewerPlayerId,
        checkNotNull(assistance).difficulty, config.botLevel, time, false, ledger.results)

private fun CompetitionMahjongMatchHistoryEntry.toOutcome(): CompetitionMahjongSeriesOutcome {
    val wins = hands.filter { it.winnerPlayerId == viewerPlayerId }
    return CompetitionMahjongSeriesOutcome(matchId, viewerPlayerId, difficulty, settledAtEpochMillis,
        viewerStanding.rank, viewerStanding.score, viewerStanding.standardPointsNumerator,
        viewerStanding.standardPointsDenominator, wins.size, wins.count { it.payerPlayerId == null },
        hands.count { it.payerPlayerId == viewerPlayerId }, wins.sumOf { it.totalFan },
        wins.maxOfOrNull { it.totalFan } ?: 0)
}
