package com.masterofchessstrategy.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.masterofchessstrategy.data.MocsDatabase
import com.masterofchessstrategy.data.StoredGameMode
import com.masterofchessstrategy.engine.games.tiger_goat.NativeTigerGoatSession
import com.masterofchessstrategy.games.tiger_goat.NativeTigerGoatRulesSession
import com.masterofchessstrategy.games.tiger_goat.RoomTigerGoatSessionStore
import com.masterofchessstrategy.games.tiger_goat.TigerGoatGameRoute
import com.masterofchessstrategy.games.tiger_goat.TigerGoatGameViewModel
import com.masterofchessstrategy.games.tiger_goat.TigerGoatTutorialProgress
import com.masterofchessstrategy.games.tiger_goat.TigerGoatTutorialScreen
import com.masterofchessstrategy.games.tiger_goat.ai.TigerGoatFormalConfig
import com.masterofchessstrategy.games.tiger_goat.ai.TigerGoatFormalRoute
import com.masterofchessstrategy.games.tiger_goat.ai.TigerGoatFormalViewModel
import com.masterofchessstrategy.games.tiger_goat.data.GatedTigerGoatSessionStore
import com.masterofchessstrategy.games.tiger_goat.data.TigerGoatModeChange
import com.masterofchessstrategy.games.tiger_goat.data.TigerGoatStorageEpochGate
import com.masterofchessstrategy.settings.LocalDataWriterRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private enum class TigerGoatPage { MODES, LOCAL, FORMAL, TUTORIAL, LOCAL_TUTORIAL }

@Composable
internal fun TigerGoatNavigationRoute(
    database: MocsDatabase,
    owner: ViewModelStoreOwner,
    writers: LocalDataWriterRegistry,
    onBack: () -> Unit,
) {
    var page by rememberSaveable { mutableStateOf(TigerGoatPage.MODES) }
    val gate = remember(database) { TigerGoatStorageEpochGate.forDatabase(database) }
    when (page) {
        TigerGoatPage.MODES -> TigerGoatModePicker(database, gate, onBack,
            onLocal = { page = TigerGoatPage.LOCAL }, onFormal = { page = TigerGoatPage.FORMAL },
            onTutorial = { page = TigerGoatPage.TUTORIAL })
        TigerGoatPage.FORMAL -> {
            val factory = remember(database) { viewModelFactory { initializer { TigerGoatFormalViewModel(database) } } }
            val game: TigerGoatFormalViewModel = viewModel(viewModelStoreOwner = owner, factory = factory)
            writers.register(game)
            TigerGoatFormalRoute(game, onBack)
        }
        TigerGoatPage.LOCAL, TigerGoatPage.LOCAL_TUTORIAL -> {
            val factory = remember(database, gate) {
                viewModelFactory {
                    initializer {
                        TigerGoatGameViewModel(
                            store = GatedTigerGoatSessionStore(RoomTigerGoatSessionStore(database.activeGameDao()), gate),
                            sessionFactory = { NativeTigerGoatRulesSession(NativeTigerGoatSession()) },
                        )
                    }
                }
            }
            val game: TigerGoatGameViewModel = viewModel(viewModelStoreOwner = owner, factory = factory)
            writers.register(game)
            if (page == TigerGoatPage.LOCAL_TUTORIAL) {
                val progress = remember(database) { TigerGoatTutorialProgress(database) }
                TigerGoatTutorialScreen(progress, onBack = { page = TigerGoatPage.LOCAL })
            } else TigerGoatGameRoute(game, onBack, onTutorial = { page = TigerGoatPage.LOCAL_TUTORIAL })
        }
        TigerGoatPage.TUTORIAL -> {
            val progress = remember(database) { TigerGoatTutorialProgress(database) }
            TigerGoatTutorialScreen(progress, onBack = { page = TigerGoatPage.MODES }, backLabel = "返回模式选择")
        }
    }
}

@Composable
private fun TigerGoatModePicker(
    database: MocsDatabase,
    gate: TigerGoatStorageEpochGate,
    onBack: () -> Unit,
    onLocal: () -> Unit,
    onFormal: () -> Unit,
    onTutorial: () -> Unit,
) {
    val epoch by gate.epoch.collectAsState()
    val change = remember(database, epoch) { TigerGoatModeChange(database) }
    val scope = rememberCoroutineScope()
    var selection by remember(change) { mutableStateOf<TigerGoatModeChange.Selection?>(null) }
    var proposal by remember(change) { mutableStateOf<TigerGoatModeChange.Proposal?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }

    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "无法打开虎羊棋，请重试" }
            finally { busy = false }
        }
    }

    LaunchedEffect(change, retry) {
        try { selection = change.selection() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { error = "存档信息读取失败，请重试" }
    }

    suspend fun open(confirmed: TigerGoatModeChange.Proposal) {
        change.start(confirmed)
        if (confirmed.formal == null) onLocal() else onFormal()
    }
    fun start(formal: TigerGoatFormalConfig?) = perform {
        val next = change.prepare(formal)
        if (next.replacesSavedGame) proposal = next else open(next)
    }

    val current = selection
    val local = current?.savedMode == StoredGameMode.LOCAL_TWO_PLAYER.code
    val formal = current?.savedMode == StoredGameMode.HUMAN_VS_AI.code
    val supported = current?.savedMode == null || local || formal
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp)
        .testTag("tiger_goat_modes"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("老虎吃羊棋", style = MaterialTheme.typography.titleLarge)
        if (current == null && error == null) Text("正在读取存档…")
        if (!supported) Text("当前存档模式无法读取")
        if (current != null && supported) {
            Button(onClick = { if (local) onLocal() else start(null) }, enabled = !busy,
                modifier = Modifier.testTag("tiger_goat_choose_local")) {
                Text(if (local) "继续本地双人" else "开始本地双人")
            }
            if (formal) {
                Button(onClick = onFormal, enabled = !busy && current.qualified,
                    modifier = Modifier.testTag("tiger_goat_resume_formal")) { Text("继续正式人机") }
            } else {
                for (side in 1..2) {
                    Button(onClick = { start(TigerGoatFormalConfig(humanSide = side)) },
                        enabled = !busy && current.qualified, modifier = Modifier.testTag("tiger_goat_start_formal_$side")) {
                        Text(if (side == 1) "正式人机 · 执虎" else "正式人机 · 执羊")
                    }
                }
            }
            if (!current.qualified) Text("完成规则教程后可开始简单人机")
        }
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = { error = null; retry += 1 }, enabled = !busy) { Text("刷新存档") }
        }
        TextButton(onClick = onTutorial, enabled = !busy,
            modifier = Modifier.testTag("tiger_goat_modes_tutorial")) { Text("规则教程") }
        TextButton(onClick = onBack, enabled = !busy) { Text("返回") }
    }
    proposal?.let { pending ->
        AlertDialog(onDismissRequest = { proposal = null }, title = { Text("替换当前棋局？") },
            text = { Text("确认后将以新模式的开局替换现有存档。") },
            confirmButton = {
                TextButton(onClick = { proposal = null; perform { open(pending) } }) { Text("确认替换") }
            },
            dismissButton = { TextButton(onClick = { proposal = null }) { Text("取消") } })
    }
}
