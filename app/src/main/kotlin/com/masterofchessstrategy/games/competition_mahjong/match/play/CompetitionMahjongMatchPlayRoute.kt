package com.masterofchessstrategy.games.competition_mahjong.match.play

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.masterofchessstrategy.games.competition_mahjong.COMPETITION_MAHJONG_AUTO_SPEEDS
import com.masterofchessstrategy.games.competition_mahjong.CompetitionMahjongDeclarationContent
import com.masterofchessstrategy.games.competition_mahjong.CompetitionMahjongTableScreen
import com.masterofchessstrategy.games.competition_mahjong.competitionMahjongTileName
import com.masterofchessstrategy.games.competition_mahjong.competitionMahjongActionId
import com.masterofchessstrategy.games.competition_mahjong.match.hint.CompetitionMahjongHintAdvisor
import com.masterofchessstrategy.games.competition_mahjong.match.hint.CompetitionMahjongPublicShapeHintAdvisor
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongCompetitionConfig
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchStore
import com.masterofchessstrategy.games.competition_mahjong.match.CompetitionMahjongMatchSummary
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongPlayPolicy
import com.masterofchessstrategy.settings.LocalDataWriterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.util.UUID

/** A series entry requiring the coordinator's production store; no silent file-store fallback. */
@Composable
internal fun CompetitionMahjongMatchRoute(
    store: CompetitionMahjongMatchStore,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialConfig: CompetitionMahjongCompetitionConfig? = null,
    playPolicy: CompetitionMahjongPlayPolicy? = null,
    hintAdvisor: CompetitionMahjongHintAdvisor? = CompetitionMahjongPublicShapeHintAdvisor,
    ownerKey: String? = null,
    ownerIsCurrent: (() -> Boolean)? = null,
    writers: LocalDataWriterRegistry? = null,
    policyProvider: (() -> CompetitionMahjongPlayPolicy)? = null,
) {
    val config = remember(initialConfig, playPolicy) {
        initialConfig ?: CompetitionMahjongCompetitionConfig(UUID.randomUUID().toString(),
            System.currentTimeMillis().coerceAtLeast(0), botLevel = playPolicy?.allowedBotLevels?.firstOrNull() ?: 1)
    }
    val game: CompetitionMahjongMatchPlayViewModel = viewModel(
        key = "competition-mahjong-series-${config.viewerPlayerId}-$ownerKey",
        factory = remember(store, config, playPolicy, hintAdvisor, ownerKey, writers, policyProvider) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CompetitionMahjongMatchPlayViewModel(store, config, playPolicy = playPolicy,
                        hintAdvisor = hintAdvisor,
                        ownerIsCurrent = { ownerKey != null && writers != null && ownerIsCurrent?.invoke() == true },
                        policyProvider = policyProvider)
                        .also { writers?.register(it) } as T
            }
        },
    )
    SideEffect { writers?.register(game) }
    val scope = rememberCoroutineScope()
    val currentBack by rememberUpdatedState(onBack)
    var leaving by remember(game) { mutableStateOf(false) }
    fun leaveAfterRetirement() {
        if (leaving) return
        leaving = true
        val completion = game.beginRetirement()
        scope.launch {
            try { completion.await(); currentBack() }
            catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                leaving = false // The stable failed receipt remains registered; navigation stays here.
            }
        }
    }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, game) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> game.setForeground(true)
                Lifecycle.Event.ON_STOP -> game.setForeground(false)
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        game.setForeground(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        onDispose { lifecycle.removeObserver(observer); game.setForeground(false) }
    }
    CompetitionMahjongMatchPlayContent(game, ::leaveAfterRetirement, modifier)
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun CompetitionMahjongMatchPlayContent(
    game: CompetitionMahjongMatchPlayViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by game.state.collectAsState()
    val handIndex = state.series.activeHandIndex
    var showSettings by remember { mutableStateOf(false) }
    var showLeave by remember { mutableStateOf(false) }
    var declarationHand by remember { mutableStateOf<Int?>(null) }
    fun leave() {
        if (state.busy || state.series.needsSave) showLeave = true else onBack()
    }
    BackHandler(onBack = ::leave)
    Column(modifier.fillMaxSize().testTag("competition_mahjong_match_play")) {
        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (state.error != null) {
            Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(requireNotNull(state.error), color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.testTag("competition_mahjong_match_play_error"))
                if (state.table == null) Button(onClick = game::retryLoad, enabled = !state.busy,
                    modifier = Modifier.testTag("competition_mahjong_match_retry_load")) { Text("重试读取") }
            }
        }
        val table = state.table
        if (table == null) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (state.loading || state.busy) "正在读取十六盘赛事…" else "尚未载入赛事牌桌")
                OutlinedButton(onClick = ::leave) { Text("返回") }
                OutlinedButton(onClick = { showSettings = true }, enabled = !state.busy) { Text("重开赛事") }
            }
        } else {
            FlowRow(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { declarationHand = handIndex }, enabled = state.canDeclareWin,
                    modifier = Modifier.testTag("competition_mahjong_match_declare")) {
                    Text("主动报和")
                }
                if (state.hintAdvisorAvailable || state.hint != null) {
                    OutlinedButton(onClick = { game.requestHint(handIndex) }, enabled = state.canHint,
                        modifier = Modifier.testTag("competition_mahjong_match_hint")) {
                        Text(if (state.hint != null) "查看已保存提示" else
                            "提示（剩余${state.assistance?.hintRemaining?.toString() ?: "不限"}）")
                    }
                }
            }
            state.hint?.let { receipt ->
                val label = table.availableActions.firstOrNull { it.id == competitionMahjongActionId(receipt.action) }?.label
                if (label != null) Text("建议：$label。由你选择是否执行；再次查看不扣次数。",
                    modifier = Modifier.padding(horizontal = 12.dp).testTag("competition_mahjong_match_hint_advice"))
            }
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                @Composable fun Summary(panelModifier: Modifier) {
                    CompetitionMahjongMatchSummary(state.series.copy(error = null), game::nextHand, game::retrySave,
                        modifier = panelModifier, busy = state.busy)
                }
                @Composable fun Table(panelModifier: Modifier) {
                    CompetitionMahjongTableScreen(table,
                        onAction = { game.performAction(it, handIndex) }, onBack = ::leave,
                        onRestart = { showSettings = true }, onSettings = { showSettings = true },
                        onToggleAutoPlay = game::toggleBots, onAutoPlaySpeedChanged = game::setSpeed,
                        onUndo = { game.undo(handIndex) }, modifier = panelModifier)
                }
                if (maxWidth >= 850.dp) {
                    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Summary(Modifier.width(280.dp).padding(8.dp))
                        Table(Modifier.weight(1f))
                    }
                } else {
                    Column(Modifier.fillMaxSize()) {
                        Summary(Modifier.fillMaxWidth().heightIn(max = 180.dp).padding(horizontal = 8.dp))
                        Table(Modifier.weight(1f))
                    }
                }
            }
        }
    }
    if (showSettings) {
        var selectedLevel by remember(state.difficultyOptions) {
            mutableIntStateOf(state.botLevel.takeIf { level -> state.difficultyOptions.any { it.botLevel == level && it.unlocked } }
                ?: state.difficultyOptions.firstOrNull { it.unlocked }?.botLevel ?: -1)
        }
        AlertDialog(
            onDismissRequest = { showSettings = false },
            modifier = Modifier.testTag("competition_mahjong_match_settings"),
            title = { Text("赛事设置") },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("其他三席AI")
                    OutlinedButton(onClick = game::toggleBots, enabled = state.table != null && !state.series.needsSave,
                        modifier = Modifier.testTag("competition_mahjong_match_pause")) {
                        Text(if (state.paused) "继续AI" else "暂停AI")
                    }
                    Text("用户暂停与速度自动保存；离开前台会停止推进，返回后仍遵从已保存的暂停选择。")
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        COMPETITION_MAHJONG_AUTO_SPEEDS.forEach { speed ->
                            FilterChip(selected = state.speed == speed, onClick = { game.setSpeed(speed) },
                                enabled = !state.series.needsSave,
                                modifier = Modifier.testTag("competition_mahjong_match_speed_${(speed * 1000).toInt()}"),
                                label = { Text("${speed}x") })
                        }
                    }
                    Text("新赛事难度")
                    state.difficultyOptions.forEach { option ->
                        FilterChip(selected = selectedLevel == option.botLevel, onClick = { selectedLevel = option.botLevel },
                            enabled = option.unlocked && !state.busy && !state.series.needsSave,
                            label = { Text(CompetitionMahjongPlayPolicy.difficultyLabel(option.difficulty) +
                                if (option.unlocked) "" else " · ${option.requirement}") },
                            modifier = Modifier.testTag("competition_mahjong_match_level_${option.botLevel}"))
                    }
                    state.assistance?.let { usage ->
                        Text("本十六盘赛事悔棋剩余：${usage.undoRemaining?.toString() ?: "不限"}；换盘不重置。")
                    }
                    Text("新开将替换当前十六盘赛事。此前的累计与当前盘不再续接。")
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettings = false; game.newMatch(selectedLevel) },
                    enabled = !state.busy && !state.series.needsSave &&
                        state.difficultyOptions.any { it.botLevel == selectedLevel && it.unlocked },
                    modifier = Modifier.testTag("competition_mahjong_match_confirm_new")) { Text("新开十六盘") }
            },
            dismissButton = { TextButton(onClick = { showSettings = false }) { Text("返回牌桌") } },
        )
    }
    val declaredHand = declarationHand
    if (declaredHand != null) {
        var tile by remember(declaredHand, state.declarationTiles, state.table?.drawnTile) {
            mutableIntStateOf(state.table?.drawnTile?.takeIf { it in state.declarationTiles }
                ?: state.declarationTiles.lastOrNull() ?: 0)
        }
        AlertDialog(
            onDismissRequest = { declarationHand = null },
            modifier = Modifier.testTag("competition_mahjong_match_declaration"),
            title = { Text("确认报和") },
            text = {
                CompetitionMahjongDeclarationContent(state.declarationTiles, tile, { tile = it },
                    tagPrefix = "competition_mahjong_match_declaration")
            },
            confirmButton = {
                TextButton(onClick = { declarationHand = null; game.declareWin(tile, declaredHand) },
                    enabled = state.canDeclareWin && handIndex == declaredHand && tile in state.declarationTiles,
                    modifier = Modifier.testTag("competition_mahjong_match_confirm_declaration")) { Text("确认报和") }
            },
            dismissButton = { TextButton(onClick = { declarationHand = null }) { Text("取消") } },
        )
    }
    if (showLeave) AlertDialog(
        onDismissRequest = { showLeave = false },
        modifier = Modifier.testTag("competition_mahjong_match_leave"),
        title = { Text(if (state.busy) "正在处理牌局" else if (state.series.needsSave) "本次操作尚未保存" else "操作已保存") },
        text = { Text(if (state.busy) "正在处理与保存，请稍候。" else if (state.series.needsSave)
            "离开后将只能恢复上一次成功保存的局面。可先重试保存。" else "可以返回，重进将恢复当前赛事。") },
        confirmButton = {
            TextButton(onClick = {
                showLeave = false
                if (state.series.needsSave) game.retrySave() else onBack()
            }, enabled = !state.busy,
                modifier = Modifier.testTag("competition_mahjong_match_leave_confirm")) {
                Text(if (state.series.needsSave) "重试保存" else "返回")
            }
        },
        dismissButton = {
            Column {
                TextButton(onClick = { showLeave = false }) { Text("留在牌桌") }
                if (state.series.needsSave) TextButton(onClick = { showLeave = false; onBack() }, enabled = !state.busy,
                    modifier = Modifier.testTag("competition_mahjong_match_leave_anyway")) { Text("仍然离开") }
            }
        },
    )
}
