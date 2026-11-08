package com.masterofchessstrategy.games.competition_mahjong.statistics

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongPlayPolicy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun CompetitionMahjongStatisticsRoute(
    viewerPlayerId: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    store: CompetitionMahjongStatisticsStore,
) {
    val factory = remember(store, viewerPlayerId) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                CompetitionMahjongStatisticsViewModel(store, viewerPlayerId) as T
        }
    }
    val game: CompetitionMahjongStatisticsViewModel = viewModel(
        key = "competition-mahjong-statistics-$viewerPlayerId", factory = factory)
    val state by game.state.collectAsState()
    BackHandler(onBack = onBack)
    CompetitionMahjongStatisticsScreen(state, { game.refresh() }, onBack, modifier)
}

@Composable
internal fun CompetitionMahjongStatisticsScreen(
    state: CompetitionMahjongStatisticsUiState,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val stats = state.statistics
    Surface(modifier.fillMaxSize().testTag("competition_mahjong_statistics_screen")) {
        Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("国标麻将 · 赛事统计", style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f))
                OutlinedButton(onClick = onRefresh, enabled = !state.loading,
                    modifier = Modifier.testTag("competition_mahjong_statistics_refresh")) { Text("刷新") }
                TextButton(onClick = onBack, enabled = !state.loading) { Text("返回") }
            }
            Text("仅统计有正式难度记录的十六盘人机赛事；AI自动演局不计入。")
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("competition_mahjong_statistics_error")) }
            if (!state.loading && stats.seriesCount == 0) {
                Text("暂无已完成的正式十六盘赛事。",
                    modifier = Modifier.testTag("competition_mahjong_statistics_empty"))
            } else {
                Overview(stats)
                DifficultyBreakdown(stats)
                RecentSeries(stats)
            }
        }
    }
}

@Composable
private fun Overview(stats: CompetitionMahjongStatistics) {
    Card(Modifier.fillMaxWidth().testTag("competition_mahjong_statistics_overview")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("总览", style = MaterialTheme.typography.titleMedium)
            Text("完成赛事 ${stats.seriesCount} · 第一名 ${stats.firstPlaceCount} · 前二 ${stats.topTwoCount}")
            Text("名次分布：1位 ${stats.rankCounts[0]} · 2位 ${stats.rankCounts[1]} · " +
                "3位 ${stats.rankCounts[2]} · 4位 ${stats.rankCounts[3]}")
            Text("累计净分 ${signed(stats.cumulativeScore)} · 官方标准分 ${competitionMahjongStandardPoints(stats.standardPointsTwelfths)}")
            Text("和牌 ${stats.handWins} · 自摸 ${stats.selfDrawWins} · 点炮 ${stats.dealInCount}")
            Text("和牌累计番 ${stats.totalFanWon} · 单盘最高 ${stats.highestFan}番 · " +
                "单赛事最高净分 ${stats.bestScore?.let(::signed) ?: "—"}")
        }
    }
}

@Composable
private fun DifficultyBreakdown(stats: CompetitionMahjongStatistics) {
    Card(Modifier.fillMaxWidth().testTag("competition_mahjong_statistics_difficulties")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("分难度", style = MaterialTheme.typography.titleMedium)
            Difficulty.entries.forEach { difficulty ->
                val item = stats.byDifficulty.getValue(difficulty)
                Text(CompetitionMahjongPlayPolicy.difficultyLabel(difficulty) +
                    "：${item.seriesCount}场 · 第一名${item.firstPlaceCount} · 前二${item.topTwoCount} · " +
                    "标准分${competitionMahjongStandardPoints(item.standardPointsTwelfths)}",
                    modifier = Modifier.testTag("competition_mahjong_statistics_${difficulty.name}"))
            }
        }
    }
}

@Composable
private fun RecentSeries(stats: CompetitionMahjongStatistics) {
    Card(Modifier.fillMaxWidth().testTag("competition_mahjong_statistics_recent")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("最近赛事", style = MaterialTheme.typography.titleMedium)
            stats.outcomes.take(20).forEach { outcome ->
                val date = remember(outcome.settledAtEpochMillis) {
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(outcome.settledAtEpochMillis))
                }
                Text("$date · ${CompetitionMahjongPlayPolicy.difficultyLabel(outcome.difficulty)} · " +
                    "第${outcome.rank}名 · ${signed(outcome.score)}分 · " +
                    "和${outcome.handWins}/自摸${outcome.selfDrawWins}/点炮${outcome.dealInCount}",
                    modifier = Modifier.testTag("competition_mahjong_statistics_series_${outcome.matchId}"))
            }
            if (stats.outcomes.size > 20) Text("仅显示最近20场；总计${stats.outcomes.size}场。")
        }
    }
}

private fun signed(value: Long): String = if (value > 0) "+$value" else value.toString()
