package com.masterofchessstrategy.games.competition_mahjong.auto

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.masterofchessstrategy.games.competition_mahjong.CompetitionMahjongTableScreen
import com.masterofchessstrategy.games.competition_mahjong.auto.production.CompetitionMahjongAutoBackground
import com.masterofchessstrategy.games.competition_mahjong.match.policy.CompetitionMahjongPlayPolicy
import java.util.Locale
import java.util.UUID

/** Production storage and qualification are supplied by the navigation owner. */
@Composable
fun CompetitionMahjongAutoRoute(
    store: CompetitionMahjongAutoStore,
    onBack: () -> Unit,
    playPolicy: CompetitionMahjongPlayPolicy?,
    modifier: Modifier = Modifier,
    initialConfig: CompetitionMahjongAutoRunConfig? = null,
    initialSettings: CompetitionMahjongAutoSettings = CompetitionMahjongAutoSettings(),
    openingRecord: ByteArray? = null,
    openings: List<CompetitionMahjongAutoOpening> = emptyList(),
    backgroundAuthorized: Boolean = false,
    ownerKey: String? = null,
    onWriterReady: (CompetitionMahjongAutoPlayViewModel) -> Unit = {},
    useBackgroundService: Boolean = false,
) {
    val config = remember(initialConfig, playPolicy) { initialConfig ?: CompetitionMahjongAutoRunConfig(
        UUID.randomUUID().toString(), System.nanoTime() and Long.MAX_VALUE,
        playerBotLevels = List(4) { playPolicy?.allowedBotLevels?.firstOrNull() ?: 0 }) }
    val game: CompetitionMahjongAutoPlayViewModel = viewModel(
        key = "competition-mahjong-auto-${ownerKey ?: config.viewerPlayerId}",
        factory = remember(store, config, playPolicy, ownerKey) {
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    CompetitionMahjongAutoPlayViewModel(store, config, playPolicy, initialSettings, openingRecord, openings,
                        navigationOwnerKey = ownerKey).also(onWriterReady) as T
            }
        },
    )
    SideEffect { onWriterReady(game) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val power = LocalContext.current.getSystemService(PowerManager::class.java)
    val serviceOwner = if (useBackgroundService) checkNotNull(ownerKey) else null
    DisposableEffect(game, lifecycle, serviceOwner, backgroundAuthorized, power) {
        val host = Any()
        var lastVisible: Boolean? = null
        if (serviceOwner != null) game.attachBackgroundHost(host)
        fun synchronize() {
            val started = lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            val visible = started && (serviceOwner == null || power.isInteractive)
            if (visible != lastVisible) {
                lastVisible = visible
                if (serviceOwner != null) game.updateBackgroundHost(host, visible)
                else game.setHost(visible, backgroundAuthorized)
            }
        }
        val observer = LifecycleEventObserver { _, _ -> synchronize() }
        lifecycle.addObserver(observer)
        synchronize()
        onDispose {
            lifecycle.removeObserver(observer)
            if (serviceOwner != null) game.detachBackgroundHost(host) else game.setHost(false, false)
        }
    }
    val leave = {
        serviceOwner?.let(CompetitionMahjongAutoBackground::stopOwner)
        game.leave(onBack)
        Unit
    }
    BackHandler(onBack = leave)
    CompetitionMahjongAutoContent(game, leave, modifier, serviceOwner)
}

@Composable
internal fun CompetitionMahjongAutoContent(game: CompetitionMahjongAutoPlayViewModel, onBack: () -> Unit,
    modifier: Modifier = Modifier, serviceOwner: String? = null) {
    val state by game.state.collectAsState()
    var settingsOpen by remember { mutableStateOf(false) }
    var newRunOpen by remember { mutableStateOf(false) }
    val activity = state.playback.activity
    BoxWithConstraints(modifier.fillMaxSize().testTag("competition_mahjong_auto_content")) {
        val controls: @Composable (Modifier) -> Unit = { controlsModifier ->
            AutoControls(state, game, onBack, { settingsOpen = true }, { newRunOpen = true }, controlsModifier, serviceOwner)
        }
        val table: @Composable (Modifier) -> Unit = { tableModifier ->
            state.table?.let { tableState ->
                CompetitionMahjongTableScreen(tableState, onAction = {}, onBack = onBack, onRestart = { newRunOpen = true },
                    onSettings = { settingsOpen = true }, onUndo = { game.undo() }, modifier = tableModifier)
            }
        }
        if (state.table == null) {
            controls(Modifier.fillMaxSize())
        } else if (maxWidth >= 650.dp) {
            Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                controls(Modifier.width(260.dp).fillMaxHeight())
                table(Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                controls(Modifier.fillMaxWidth().heightIn(max = 180.dp))
                table(Modifier.fillMaxWidth().weight(1f))
            }
        }
    }
    if (settingsOpen) AutoSettingsDialog(state, { settingsOpen = false }) {
        settingsOpen = false
        game.updateSettings(it)
    }
    if (newRunOpen) AutoNewRunDialog(state, { newRunOpen = false }) { levels, opening ->
        newRunOpen = false
        game.newRun(levels, activity.settings.copy(paused = true), opening)
    }
}

@Composable
private fun AutoControls(state: CompetitionMahjongAutoPlayUiState, game: CompetitionMahjongAutoPlayViewModel,
    onBack: () -> Unit, onSettings: () -> Unit, onNew: () -> Unit, modifier: Modifier, serviceOwner: String?) {
    val playback = state.playback
    val activity = playback.activity
    Card(modifier.padding(8.dp).testTag("competition_mahjong_auto_controls")) {
        Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("国标麻将 · AI自动演局", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onBack, enabled = !playback.busy) { Text("返回") }
                OutlinedButton(onClick = onSettings, enabled = activity.frame != null && !playback.busy && !activity.needsSave,
                    modifier = Modifier.testTag("competition_mahjong_auto_settings")) { Text("设置") }
            }
            if (playback.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (activity.frame == null) {
                Text(playback.error ?: if (activity.closed) "演局已关闭" else "正在恢复演局")
                Button(onClick = { game.retryLoad() }, enabled = !playback.busy && !activity.closed,
                    modifier = Modifier.testTag("competition_mahjong_auto_load")) { Text("重试读取") }
            } else {
                Text("赛事 ${activity.seriesIndex + 1} / ${activity.settings.maxSeries} · 第${activity.handIndex + 1}盘",
                    modifier = Modifier.testTag("competition_mahjong_auto_position"))
                Text("已完成 ${activity.completedHands} / 16盘", modifier = Modifier.testTag("competition_mahjong_auto_progress"))
                val seconds = activity.elapsedMillis / 1000
                Text("运行 ${seconds / 60}分${seconds % 60}秒 · ${String.format(Locale.ROOT, "%.2f", activity.settings.speed)}×")
                state.configuration?.let { config ->
                    Text("观察席：玩家${config.viewerPlayerId + 1}AI")
                    config.playerBotLevels.forEachIndexed { index, level ->
                        val label = state.difficultyOptions.firstOrNull { it.botLevel == level }?.let {
                            CompetitionMahjongPlayPolicy.difficultyLabel(it.difficulty) } ?: "未配置"
                        Text("玩家${index + 1}AI：$label")
                    }
                }
                Text("累计比赛分", style = MaterialTheme.typography.titleSmall)
                repeat(4) { player ->
                    Text("玩家${player + 1}：${activity.scores[player]}", modifier = Modifier.testTag("competition_mahjong_auto_score_$player"))
                }
                if (activity.seriesComplete) {
                    state.configuration?.standings?.forEach { standing ->
                        val points = if (standing.standardPointsNumerator % standing.standardPointsDenominator == 0)
                            (standing.standardPointsNumerator / standing.standardPointsDenominator).toString() else
                            "${standing.standardPointsNumerator}/${standing.standardPointsDenominator}"
                        Text("第${standing.rank}名 玩家${standing.playerId + 1} · 标准分 $points")
                    }
                    Text("AI观战赛事不计人类排位和难度解锁。")
                }
                playback.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                if (activity.needsSave) {
                    Text("保存成功后才能继续演局或返回。")
                    Button(onClick = { game.retrySave() }, enabled = !playback.busy,
                        modifier = Modifier.testTag("competition_mahjong_auto_retry")) { Text("重试保存") }
                } else {
                    Button(onClick = { game.togglePlayback() }, enabled = !playback.busy && !activity.finished,
                        modifier = Modifier.testTag("competition_mahjong_auto_toggle")) {
                        Text(if (activity.settings.paused || playback.halted) "继续演局" else "暂停演局")
                    }
                    if (serviceOwner != null && activity.settings.backgroundEnabled && !activity.finished) {
                        AutoBackgroundControls(serviceOwner, playback, game::pauseForBackgroundStop)
                    }
                    OutlinedButton(onClick = { game.step() }, enabled = playback.canStep,
                        modifier = Modifier.testTag("competition_mahjong_auto_step")) {
                        Text(if (activity.frame.isAutoTerminal()) if (activity.handIndex == 15) "下一赛事" else "下一盘" else "单步前进")
                    }
                    OutlinedButton(onClick = { game.undo() }, enabled = playback.canUndo,
                        modifier = Modifier.testTag("competition_mahjong_auto_undo")) { Text("回退一步") }
                    if (activity.finished) Text("已达到本次演局上限。可在设置中增加上限，或确认新开演局。")
                }
            }
            OutlinedButton(onClick = onNew, enabled = !playback.busy && !activity.needsSave && state.difficultyOptions.any { it.unlocked },
                modifier = Modifier.testTag("competition_mahjong_auto_new")) { Text("新开演局") }
        }
    }
}

@Composable
private fun AutoBackgroundControls(owner: String, playback: CompetitionMahjongAutoPlaybackState, pause: (String) -> Unit) {
    val context = LocalContext.current
    val returnTo = LocalActivity.current?.componentName
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val current by CompetitionMahjongAutoBackground.lease.collectAsState()
    val own = current?.takeIf { it.owner == owner }
    var message by remember(owner) { mutableStateOf<String?>(null) }
    fun start() {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) ||
            !CompetitionMahjongAutoBackground.start(context, owner, playback, returnTo, pause)) {
            message = "后台演局未启动，请在牌桌可见时重试。"
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else message = "请开启通知，以便从通知中停止后台演局。"
    }
    OutlinedButton(onClick = {
        message = null
        if (own != null) CompetitionMahjongAutoBackground.requestStop(own.token)
        else if (CompetitionMahjongAutoBackground.notificationsAllowed(context)) start()
        else if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context,
            Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else message = "请开启通知，以便从通知中停止后台演局。"
    }, enabled = own != null || (!playback.busy && CompetitionMahjongAutoBackground.eligible(playback)),
        modifier = Modifier.testTag("competition_mahjong_auto_background_toggle")) {
        Text(if (own != null) "停止后台演局" else "启动后台演局")
    }
    if (own != null) Text(if (own.ready) "后台演局已启用" else "正在启动后台演局",
        modifier = Modifier.testTag("competition_mahjong_auto_background_status"))
    else if (playback.activity.settings.paused) Text("继续演局后可启动后台执行。")
    message?.let {
        Text(it)
        if (!CompetitionMahjongAutoBackground.notificationsAllowed(context)) {
            TextButton(onClick = {
                val intent = if (Build.VERSION.SDK_INT >= 26) Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
                context.startActivity(intent)
            }) { Text("通知设置") }
        }
    }
}

@Composable
private fun AutoSettingsDialog(state: CompetitionMahjongAutoPlayUiState, onDismiss: () -> Unit,
    onSave: (CompetitionMahjongAutoSettings) -> Unit) {
    val original = state.playback.activity.settings
    var draft by remember(original) { mutableStateOf(original) }
    var step by remember(original) { mutableStateOf(original.stepDelayMillis.toString()) }
    var handBreak by remember(original) { mutableStateOf(original.handBreakMillis.toString()) }
    var maxSeries by remember(original) { mutableStateOf(original.maxSeries.toString()) }
    var backgroundDelay by remember(original) { mutableStateOf(original.backgroundDelayMillis.toString()) }
    var fanEnabled by remember(original) { mutableStateOf(original.archiveFanThreshold != null) }
    var longEnabled by remember(original) { mutableStateOf(original.archiveLongHandEvents != null) }
    var fan by remember(original) { mutableStateOf((original.archiveFanThreshold ?: 8).toString()) }
    var longHand by remember(original) { mutableStateOf((original.archiveLongHandEvents ?: 300).toString()) }
    val candidate = runCatching { draft.copy(
        stepDelayMillis = step.toLong(), handBreakMillis = handBreak.toLong(), maxSeries = maxSeries.toInt(),
        backgroundDelayMillis = backgroundDelay.toLong(), archiveFanThreshold = if (fanEnabled) fan.toInt() else null,
        archiveLongHandEvents = if (longEnabled) longHand.toInt() else null,
    ) }.getOrNull()?.takeIf { it.maxSeries > state.playback.activity.seriesIndex }
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("competition_mahjong_auto_settings_dialog"),
        title = { Text("播放与保存设置") },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AutoCheck("暂停演局", draft.paused, "pause") { draft = draft.copy(paused = it) }
                Text("播放速度 ${String.format(Locale.ROOT, "%.2f", draft.speed)}×")
                Slider(value = draft.speed, onValueChange = { draft = draft.copy(speed = it) }, valueRange = .5f..4f,
                    modifier = Modifier.testTag("competition_mahjong_auto_speed"))
                AutoNumber("单步间隔（毫秒，0–60000）", step, { step = it }, "step_delay")
                AutoNumber("盘间停顿（毫秒，0–60000）", handBreak, { handBreak = it }, "hand_delay")
                AutoCheck("自动续赛事", draft.autoContinue, "auto_continue") { draft = draft.copy(autoContinue = it) }
                AutoNumber("最多连续赛事（1–1000，每赛事16盘）", maxSeries, { maxSeries = it }, "max_series")
                AutoCheck("允许后台演局", draft.backgroundEnabled, "background") { draft = draft.copy(backgroundEnabled = it) }
                if (draft.backgroundEnabled && !state.playback.backgroundAuthorized) {
                    Text("保存后，在牌桌继续演局并启动后台执行。")
                }
                AutoNumber("后台最小间隔（毫秒，2000–60000）", backgroundDelay, { backgroundDelay = it }, "background_delay")
                Text("完成赛事的保存条件", style = MaterialTheme.typography.titleSmall)
                AutoCheck("保存全部完成赛事", draft.archiveAll, "archive_all") { draft = draft.copy(archiveAll = it) }
                AutoCheck("达到总番阈值", fanEnabled, "archive_fan") { fanEnabled = it }
                if (fanEnabled) AutoNumber("总番阈值（1–10000）", fan, { fan = it }, "fan_threshold")
                AutoCheck("包含超长盘", longEnabled, "archive_long") { longEnabled = it }
                if (longEnabled) AutoNumber("单盘事件阈值（1–4096，包含回应）", longHand, { longHand = it }, "long_threshold")
                AutoCheck("最终第一名曾落后", draft.archiveComeback, "archive_comeback") { draft = draft.copy(archiveComeback = it) }
                AutoCheck("包含大师AI", draft.archiveMaster, "archive_master") { draft = draft.copy(archiveMaster = it) }
                Text("任一条件满足即保存；全部关闭仍保留当前活动。")
                if (candidate == null) Text("请填写有效范围；赛事上限至少为${state.playback.activity.seriesIndex + 1}。",
                    color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = { candidate?.let(onSave) },
            enabled = candidate != null && !state.playback.busy && !state.playback.activity.needsSave,
            modifier = Modifier.testTag("competition_mahjong_auto_settings_save")) { Text("保存设置") } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("competition_mahjong_auto_settings_cancel")) { Text("取消") } })
}

@Composable
private fun AutoNewRunDialog(state: CompetitionMahjongAutoPlayUiState, onDismiss: () -> Unit,
    onConfirm: (List<Int>, String?) -> Unit) {
    val fallback = state.difficultyOptions.firstOrNull { it.unlocked }?.botLevel ?: -1
    var levels by remember { mutableStateOf(state.configuration?.playerBotLevels ?: List(4) { fallback }) }
    var openingId by remember { mutableStateOf<String?>(null) }
    val opening = state.openings.firstOrNull { it.id == openingId }
    val selected = opening?.playerBotLevels ?: levels
    val allowed = selected.size == 4 && selected.all { level -> state.difficultyOptions.any { it.botLevel == level && it.unlocked } } &&
        (opening == null || opening.available)
    AlertDialog(onDismissRequest = onDismiss, modifier = Modifier.testTag("competition_mahjong_auto_new_dialog"),
        title = { Text("确认新开演局") },
        text = {
            Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("将替换当前活动，从第一个十六盘赛事开始；已保存的完成赛事仍保留。")
                Text("新活动默认暂停，播放与保存设置沿用当前配置。")
                if (state.openings.isNotEmpty()) {
                    FilterChip(selected = openingId == null, onClick = { openingId = null }, label = { Text("标准发牌") })
                    state.openings.forEach { choice ->
                        FilterChip(selected = openingId == choice.id, onClick = { openingId = choice.id }, enabled = choice.available,
                            label = { Text(choice.title) }, modifier = Modifier.testTag("competition_mahjong_auto_opening_${choice.id}"))
                    }
                    if (opening != null) Text("指定起点随每个新赛事第一盘复用，四席策略保持该开局配置。")
                }
                repeat(4) { player ->
                    val current = state.difficultyOptions.firstOrNull { it.botLevel == selected[player] }
                    Text("玩家${player + 1}AI：${current?.let { CompetitionMahjongPlayPolicy.difficultyLabel(it.difficulty) } ?: "未配置"}")
                    state.difficultyOptions.forEach { option ->
                        FilterChip(selected = selected[player] == option.botLevel,
                            onClick = { levels = levels.mapIndexed { index, old -> if (index == player) option.botLevel else old } },
                            enabled = openingId == null && option.unlocked,
                            label = { Text(CompetitionMahjongPlayPolicy.difficultyLabel(option.difficulty) +
                                if (option.unlocked) "" else " · ${option.requirement}") },
                            modifier = Modifier.testTag("competition_mahjong_auto_player_${player}_level_${option.botLevel}"))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(selected, openingId) },
            enabled = allowed && !state.playback.busy && !state.playback.activity.needsSave,
            modifier = Modifier.testTag("competition_mahjong_auto_new_confirm")) { Text("确认新开") } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.testTag("competition_mahjong_auto_new_cancel")) { Text("取消") } })
}

@Composable
private fun AutoNumber(label: String, value: String, onChange: (String) -> Unit, tag: String) {
    OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth().testTag("competition_mahjong_auto_$tag"))
}

@Composable
private fun AutoCheck(label: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        Checkbox(checked, onChange, modifier = Modifier.testTag("competition_mahjong_auto_$tag"))
        Text(label, modifier = Modifier.weight(1f).padding(top = 12.dp))
    }
}
