package com.masterofchessstrategy.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.masterofchessstrategy.engine.Difficulty
import com.masterofchessstrategy.games.go.GoAiDifficulty
import com.masterofchessstrategy.games.go.GoAiSetupViewModel
import com.masterofchessstrategy.games.go.GoOpeningStudyHub
import com.masterofchessstrategy.games.go.data.GoLocalStorage
import com.masterofchessstrategy.settings.LocalDataWriterRegistry

/** Opening study uses the same eligibility, conflict checks and writer retirement as AI setup. */
@Composable
internal fun GoOpeningStudyNavigationRoute(
    storage: GoLocalStorage,
    writers: LocalDataWriterRegistry,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
) {
    val epoch by storage.gate.epoch.collectAsState()
    val repository = remember(storage, epoch) { storage.aiEntryRepository() }
    val model: GoAiSetupViewModel = viewModel(key = "go-opening-study-setup-$epoch", factory = viewModelFactory {
        initializer { GoAiSetupViewModel(repository).also(writers::register) }
    })
    SideEffect { writers.register(model) }
    val state by model.state.collectAsState()
    var leaving by rememberSaveable(epoch) { mutableStateOf(false) }
    val leave: () -> Unit = {
        if (!state.busy && !leaving) {
            leaving = true
            model.beginRetirement()
        }
    }
    BackHandler(onBack = leave)
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { model.resume() }
    LaunchedEffect(model, leaving) { if (leaving) { model.beginRetirement().await(); onBack() } }
    LaunchedEffect(state.readyMatch?.matchId, leaving) {
        if (!leaving) model.takeReadyMatch()?.let { onOpen(it.matchId) }
    }
    val eligibility = state.snapshot?.eligibility
    val difficulties = remember(eligibility) {
        val byDifficulty = mapOf(
            Difficulty.EASY to GoAiDifficulty.SIMPLE, Difficulty.MEDIUM to GoAiDifficulty.MEDIUM,
            Difficulty.HARD to GoAiDifficulty.HARD, Difficulty.MASTER to GoAiDifficulty.MASTER,
        )
        byDifficulty.filterKeys { eligibility?.condition(it)?.isUnlocked == true }.values.toSet()
    }
    Column(Modifier.fillMaxSize().testTag("go_opening_navigation")) {
        if (state.loading || state.busy) LinearProgressIndicator()
        state.error?.let { Text(it, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
        if (eligibility == null) {
            if (!state.loading) TextButton(onClick = model::reload) { Text("重试读取") }
            TextButton(onClick = leave) { Text("返回") }
        } else Box(Modifier.weight(1f)) {
            GoOpeningStudyHub(difficulties, { opening, config -> model.requestCreate(config, opening) }, leave)
        }
    }
    if (state.pendingConfig != null) AlertDialog(
        onDismissRequest = model::dismissAbandon,
        title = { Text("放弃当前围棋对局？") },
        text = { Text("确认后从所选线路末端开始自动续弈。已完成的棋谱会保留。") },
        confirmButton = { TextButton(onClick = model::confirmAbandon,
            modifier = Modifier.testTag("go_opening_confirm_replacement")) { Text("确认开始") } },
        dismissButton = { TextButton(onClick = model::dismissAbandon) { Text("取消") } },
    )
}
