package com.masterofchessstrategy.games.competition_mahjong.auto.replay

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
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
import com.masterofchessstrategy.games.competition_mahjong.CompetitionMahjongTableScreen
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongPlayPolicy
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Private automatic-series library. SAF writing remains an injected coordinator responsibility. */
@Composable
fun CompetitionMahjongAutoReplayRoute(
    library: CompetitionMahjongAutoArchiveLibrary,
    viewerPlayerId: Int,
    playPolicy: CompetitionMahjongPlayPolicy?,
    onBack: () -> Unit,
    onExport: suspend (CompetitionMahjongAutoExport) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val game: CompetitionMahjongAutoReplayViewModel = viewModel(
        key = "competition-mahjong-auto-replay-" + viewerPlayerId,
        factory = remember(library, viewerPlayerId) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CompetitionMahjongAutoReplayViewModel(library, viewerPlayerId) as T
            }
        },
    )
    val state by game.state.collectAsState()
    val back = {
        if (state.selectedKey != null) game.closeReplay() else game.leave(onBack)
        Unit
    }
    BackHandler(onBack = back)
    CompetitionMahjongAutoReplayContent(game, playPolicy, onBack = back, onExport = onExport,
        modifier = modifier)
}

@Composable
internal fun CompetitionMahjongAutoReplayContent(
    game: CompetitionMahjongAutoReplayViewModel,
    playPolicy: CompetitionMahjongPlayPolicy?,
    onBack: () -> Unit,
    onExport: suspend (CompetitionMahjongAutoExport) -> Boolean,
    modifier: Modifier = Modifier,
) {
    val state by game.state.collectAsState()
    var exportTarget by remember { mutableStateOf<CompetitionMahjongAutoArchiveSummary?>(null) }
    var deleteTarget by remember { mutableStateOf<CompetitionMahjongAutoArchiveSummary?>(null) }
    var replaySettings by remember { mutableStateOf(false) }
    Surface(modifier.fillMaxSize().testTag("competition_mahjong_auto_replay_content")) {
        if (state.selectedKey == null) {
            ArchiveList(state, playPolicy, onBack, game::showFavorites, game::refresh, game::select,
                { entry -> game.setFavorite(entry.key, !entry.favorite) },
                { exportTarget = it }, { deleteTarget = it })
        } else {
            ReplayContent(state, game, onBack, { replaySettings = true })
        }
    }
    exportTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { exportTarget = null },
            modifier = Modifier.testTag("competition_mahjong_auto_export_dialog"),
            title = { Text("导出完整私有棋谱？") },
            text = {
                Text("文件包含十六盘完整事件、各席暗牌和牌墙，可用于精确回放。只保存到可信位置，不要当作公开观战文件分享。")
            },
            confirmButton = {
                TextButton(onClick = {
                    exportTarget = null
                    game.export(target.key, onExport)
                }, modifier = Modifier.testTag("competition_mahjong_auto_export_confirm")) { Text("确认导出") }
            },
            dismissButton = {
                TextButton(onClick = { exportTarget = null },
                    modifier = Modifier.testTag("competition_mahjong_auto_export_cancel")) { Text("取消") }
            },
        )
    }
    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            modifier = Modifier.testTag("competition_mahjong_auto_delete_dialog"),
            title = { Text("删除这份自动赛事棋谱？") },
            text = { Text("只删除所选完成赛事归档，不影响当前自动演局活动。删除后无法从应用内恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    deleteTarget = null
                    game.delete(target.key)
                }, modifier = Modifier.testTag("competition_mahjong_auto_delete_confirm")) { Text("确认删除") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null },
                    modifier = Modifier.testTag("competition_mahjong_auto_delete_cancel")) { Text("取消") }
            },
        )
    }
    if (replaySettings) {
        AlertDialog(
            onDismissRequest = { replaySettings = false },
            modifier = Modifier.testTag("competition_mahjong_auto_replay_settings"),
            title = { Text("棋谱播放速度") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(String.format(Locale.ROOT, "%.2f×", state.speed))
                    Slider(state.speed, game::setSpeed, valueRange = .5f..4f,
                        modifier = Modifier.testTag("competition_mahjong_auto_replay_speed_dialog"))
                }
            },
            confirmButton = {
                TextButton(onClick = { replaySettings = false }) { Text("完成") }
            },
        )
    }
}

@Composable
private fun ArchiveList(
    state: CompetitionMahjongAutoReplayUiState,
    playPolicy: CompetitionMahjongPlayPolicy?,
    onBack: () -> Unit,
    onFavorites: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onSelect: (String) -> Unit,
    onFavorite: (CompetitionMahjongAutoArchiveSummary) -> Unit,
    onExport: (CompetitionMahjongAutoArchiveSummary) -> Unit,
    onDelete: (CompetitionMahjongAutoArchiveSummary) -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onBack, enabled = !state.busy) { Text("返回") }
            Text("国标麻将 · 自动赛事棋谱", style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onRefresh, enabled = !state.busy,
                modifier = Modifier.testTag("competition_mahjong_auto_archive_refresh")) { Text("刷新") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(!state.favoritesOnly, { onFavorites(false) }, label = { Text("全部") },
                modifier = Modifier.testTag("competition_mahjong_auto_archive_all"))
            FilterChip(state.favoritesOnly, { onFavorites(true) }, label = { Text("精彩收藏") },
                modifier = Modifier.testTag("competition_mahjong_auto_archive_favorites"))
        }
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.notice?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("competition_mahjong_auto_archive_error")) }
        if (!state.busy && state.visibleEntries.isEmpty()) {
            Text(if (state.favoritesOnly) "暂无收藏的自动赛事棋谱" else "暂无已保存的自动赛事棋谱",
                modifier = Modifier.testTag("competition_mahjong_auto_archive_empty"))
        }
        Column(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.visibleEntries.forEach { entry ->
                ArchiveCard(entry, playPolicy, state.busy, onSelect, onFavorite, onExport, onDelete)
            }
        }
        Text("自动赛事棋谱不计人类排位、胜场或难度解锁。完整导出包含隐藏信息。")
    }
}

@Composable
private fun ArchiveCard(
    entry: CompetitionMahjongAutoArchiveSummary,
    playPolicy: CompetitionMahjongPlayPolicy?,
    busy: Boolean,
    onSelect: (String) -> Unit,
    onFavorite: (CompetitionMahjongAutoArchiveSummary) -> Unit,
    onExport: (CompetitionMahjongAutoArchiveSummary) -> Unit,
    onDelete: (CompetitionMahjongAutoArchiveSummary) -> Unit,
) {
    Card(Modifier.fillMaxWidth().testTag("competition_mahjong_auto_archive_" + entry.key)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("自动赛事 " + (entry.seriesIndex + 1), style = MaterialTheme.typography.titleMedium)
            val date = remember(entry.savedAtEpochMillis) {
                SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT).format(Date(entry.savedAtEpochMillis))
            }
            Text("保存时间 " + date + " · 用时 " + (entry.elapsedMillis / 1000) + "秒")
            Text("四席AI：" + entry.playerBotLevels.mapIndexed { index, level ->
                val option = playPolicy?.options?.firstOrNull { it.botLevel == level }
                "玩家" + (index + 1) + " " +
                    (option?.let { CompetitionMahjongPlayPolicy.difficultyLabel(it.difficulty) } ?: "策略未绑定")
            }.joinToString("、"))
            entry.standings.sortedWith(compareBy({ it.rank }, { it.playerId })).forEach { standing ->
                Text("第" + standing.rank + "名 玩家" + (standing.playerId + 1) + "：" + standing.score)
            }
            Row(Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { onSelect(entry.key) }, enabled = !busy,
                    modifier = Modifier.testTag("competition_mahjong_auto_archive_replay_" + entry.key)) { Text("回放") }
                OutlinedButton(onClick = { onFavorite(entry) }, enabled = !busy,
                    modifier = Modifier.testTag("competition_mahjong_auto_archive_favorite_" + entry.key)) {
                    Text(if (entry.favorite) "取消收藏" else "收藏")
                }
                OutlinedButton(onClick = { onExport(entry) }, enabled = !busy,
                    modifier = Modifier.testTag("competition_mahjong_auto_archive_export_" + entry.key)) { Text("导出") }
                TextButton(onClick = { onDelete(entry) }, enabled = !busy,
                    modifier = Modifier.testTag("competition_mahjong_auto_archive_delete_" + entry.key)) { Text("删除") }
            }
        }
    }
}

@Composable
private fun ReplayContent(
    state: CompetitionMahjongAutoReplayUiState,
    game: CompetitionMahjongAutoReplayViewModel,
    onBack: () -> Unit,
    onSettings: () -> Unit,
) {
    val table = state.table
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val controls: @Composable (Modifier) -> Unit = { controlsModifier ->
            Card(controlsModifier.padding(8.dp).testTag("competition_mahjong_auto_replay_controls")) {
                Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = onBack, enabled = !state.busy) { Text("返回棋谱") }
                        Text("赛事 " + ((state.selected?.seriesIndex ?: 0) + 1),
                            style = MaterialTheme.typography.titleMedium)
                    }
                    if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                    state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    Text("第" + (state.replay.handIndex + 1) + " / 16盘 · 事件 " +
                        state.replay.eventIndex + " / " + state.replay.eventCount,
                        modifier = Modifier.testTag("competition_mahjong_auto_replay_position"))
                    Row(Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        repeat(16) { hand ->
                            FilterChip(state.replay.handIndex == hand, { game.selectHand(hand) },
                                enabled = !state.busy, label = { Text((hand + 1).toString()) },
                                modifier = Modifier.testTag("competition_mahjong_auto_replay_hand_" + hand))
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedButton(onClick = { game.backward() },
                            enabled = state.replay.canBackward && !state.busy,
                            modifier = Modifier.testTag("competition_mahjong_auto_replay_previous")) { Text("上一步") }
                        Button(onClick = game::togglePlayback,
                            enabled = !state.busy && !state.replay.atEnd,
                            modifier = Modifier.testTag("competition_mahjong_auto_replay_toggle")) {
                            Text(if (state.playing) "暂停" else "播放")
                        }
                        OutlinedButton(onClick = { game.forward() },
                            enabled = state.replay.canForward && !state.busy,
                            modifier = Modifier.testTag("competition_mahjong_auto_replay_next")) { Text("下一步") }
                    }
                    Text("速度 " + String.format(Locale.ROOT, "%.2f×", state.speed))
                    Slider(state.speed, game::setSpeed, valueRange = .5f..4f,
                        enabled = !state.busy,
                        modifier = Modifier.testTag("competition_mahjong_auto_replay_speed"))
                    if (state.replay.atEnd) Text("十六盘回放结束。")
                }
            }
        }
        val board: @Composable (Modifier) -> Unit = { boardModifier ->
            table?.let {
                CompetitionMahjongTableScreen(it, onAction = {}, onBack = onBack, onRestart = {},
                    onSettings = onSettings, modifier = boardModifier)
            }
        }
        if (table == null) {
            controls(Modifier.fillMaxSize())
        } else if (maxWidth >= 650.dp) {
            Row(Modifier.fillMaxSize()) {
                controls(Modifier.width(280.dp).fillMaxHeight())
                board(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                controls(Modifier.fillMaxWidth().heightIn(max = 210.dp))
                board(Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
}
