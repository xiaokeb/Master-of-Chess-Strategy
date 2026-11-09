package com.masterofchessstrategy.games.competition_mahjong.history

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
import com.masterofchessstrategy.engine.CompetitionMahjongRoundPhase
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchSeating
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongPlayPolicy
import com.masterofchessstrategy.settings.LocalDataWriterRegistry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
internal fun CompetitionMahjongMatchHistoryRoute(
    viewerPlayerId: Int,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    store: CompetitionMahjongMatchHistoryStore,
    writers: LocalDataWriterRegistry,
) {
    val factory = remember(store, viewerPlayerId, writers) {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                CompetitionMahjongMatchHistoryViewModel(store, viewerPlayerId).also(writers::register) as T
        }
    }
    val game: CompetitionMahjongMatchHistoryViewModel = viewModel(
        key = "competition-mahjong-match-history-$viewerPlayerId", factory = factory)
    val state by game.state.collectAsState()
    val pageBack = { if (state.selected != null) game.select(null) else onBack() }
    BackHandler(onBack = pageBack)
    CompetitionMahjongMatchHistoryScreen(state, { game.refresh() }, game::select,
        { id, favorite -> game.setFavorite(id, favorite) }, { game.delete(it) }, pageBack, modifier)
}

@Composable
internal fun CompetitionMahjongMatchHistoryScreen(
    state: CompetitionMahjongMatchHistoryUiState,
    onRefresh: () -> Unit,
    onSelect: (String?) -> Unit,
    onFavorite: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var deleting by remember { mutableStateOf<String?>(null) }
    deleting?.let { matchId ->
        AlertDialog(onDismissRequest = { if (!state.busy) deleting = null },
            title = { Text("删除成绩单？") },
            text = { Text("将删除这场赛事的公开逐盘成绩历史，统计数据不会随之删除。") },
            confirmButton = { TextButton(onClick = { deleting = null; onDelete(matchId) }, enabled = !state.busy,
                modifier = Modifier.testTag("competition_mahjong_history_confirm_delete")) { Text("删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }, enabled = !state.busy) { Text("取消") } })
    }
    Surface(modifier.fillMaxSize().testTag("competition_mahjong_match_history_screen")) {
        Column(Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (state.selected == null) "国标麻将 · 赛事成绩历史" else "十六盘赛事成绩单",
                    style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (state.selected == null) OutlinedButton(onClick = onRefresh,
                    enabled = !state.loading && !state.busy,
                    modifier = Modifier.testTag("competition_mahjong_history_refresh")) { Text("刷新") }
                TextButton(onClick = onBack, enabled = !state.loading && !state.busy) { Text("返回") }
            }
            Text("仅保存公开结算和最终排名，不含暗手、牌墙或逐动作记录。")
            if (state.loading || state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("competition_mahjong_history_error")) }
            val selected = state.selected
            if (selected != null) HistoryDetail(selected, !state.busy, onFavorite) { deleting = selected.matchId }
            else HistoryList(state.history, !state.busy, onSelect, onFavorite) { deleting = it }
        }
    }
}

@Composable
private fun HistoryList(history: CompetitionMahjongMatchHistory, enabled: Boolean,
                        onSelect: (String) -> Unit, onFavorite: (String, Boolean) -> Unit,
                        onDelete: (String) -> Unit) {
    if (history.entries.isEmpty()) {
        Text("暂无已完成的正式十六盘赛事成绩单。", modifier = Modifier.testTag("competition_mahjong_history_empty"))
        return
    }
    history.entries.forEach { entry ->
        Card(Modifier.fillMaxWidth().testTag("competition_mahjong_history_${entry.matchId}")) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text((if (entry.favorite) "★ " else "") + formatTime(entry.settledAtEpochMillis) +
                    " · ${CompetitionMahjongPlayPolicy.difficultyLabel(entry.difficulty)}",
                    style = MaterialTheme.typography.titleMedium)
                Text("第${entry.viewerStanding.rank}名 · ${signed(entry.viewerStanding.score)}分 · 十六盘")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { onSelect(entry.matchId) }, enabled = enabled) { Text("查看") }
                    OutlinedButton(onClick = { onFavorite(entry.matchId, !entry.favorite) }, enabled = enabled) {
                        Text(if (entry.favorite) "取消收藏" else "收藏")
                    }
                    TextButton(onClick = { onDelete(entry.matchId) }, enabled = enabled) { Text("删除") }
                }
            }
        }
    }
}

@Composable
private fun HistoryDetail(entry: CompetitionMahjongMatchHistoryEntry, enabled: Boolean,
                          onFavorite: (String, Boolean) -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("competition_mahjong_history_detail")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(formatTime(entry.settledAtEpochMillis) + " · " +
                CompetitionMahjongPlayPolicy.difficultyLabel(entry.difficulty), style = MaterialTheme.typography.titleMedium)
            Text("最终排名")
            entry.standings.forEach { standing ->
                Text("第${standing.rank}名 · 玩家${standing.playerId + 1} · ${signed(standing.score)}分" +
                    if (standing.playerId == entry.viewerPlayerId) "（你）" else "")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { onFavorite(entry.matchId, !entry.favorite) }, enabled = enabled) {
                    Text(if (entry.favorite) "取消收藏" else "收藏")
                }
                TextButton(onClick = onDelete, enabled = enabled) { Text("删除") }
            }
        }
    }
    entry.hands.forEachIndexed { index, hand ->
        val seating = CompetitionMahjongMatchSeating.forHand(index)
        Card(Modifier.fillMaxWidth().testTag("competition_mahjong_history_hand_$index")) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("第${index + 1}盘 · ${listOf("东", "南", "西", "北")[seating.roundWind]}风 · " +
                    if (hand.phase == CompetitionMahjongRoundPhase.WON) "和牌 ${hand.totalFan}番" else "荒牌",
                    style = MaterialTheme.typography.titleSmall)
                Text((0..3).joinToString(" · ") { player ->
                    "玩家${player + 1} ${signed(hand.scores[player])}分"
                })
                if (hand.phase == CompetitionMahjongRoundPhase.WON) Text(
                    "赢家 玩家${checkNotNull(hand.winnerPlayerId) + 1}" +
                        (hand.payerPlayerId?.let { " · 点炮 玩家${it + 1}" } ?: " · 自摸") +
                        " · 不含花${hand.fanWithoutFlowers}分")
            }
        }
    }
}

private fun formatTime(value: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(value))

private fun signed(value: Long): String = if (value > 0) "+$value" else value.toString()
