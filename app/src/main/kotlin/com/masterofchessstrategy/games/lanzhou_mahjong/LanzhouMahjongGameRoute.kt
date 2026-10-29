package com.masterofchessstrategy.games.lanzhou_mahjong

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@Composable
internal fun LanzhouMahjongGameRoute(
    viewModel: LanzhouMahjongGameViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsState()
    val table = state.table
    val scope = rememberCoroutineScope()
    var exitPending by remember { mutableStateOf(false) }
    val leave: () -> Unit = {
        if (!exitPending) {
            exitPending = true
            scope.launch {
                var exited = false
                try {
                    if (viewModel.leave()) { exited = true; onBack() }
                } finally {
                    if (!exited) exitPending = false
                }
            }
        }
    }
    BackHandler(enabled = true, onBack = leave)
    if (table != null) {
        LanzhouMahjongTableScreen(
            view = table.view,
            viewerSeat = table.viewerSeat,
            actions = table.actions,
            feedback = state.error ?: table.feedback,
            onAction = viewModel::apply,
            onBack = leave,
            onRestart = viewModel::restart,
            modifier = modifier,
            isBusy = table.isBusy || state.leaving && !state.retired,
            blocked = table.blocked || state.retired,
            needsSave = table.needsSave,
            onRetrySave = viewModel::retrySave,
            needsAdvance = table.needsAdvance,
            onRetryAdvance = viewModel::retryAdvance,
            assistance = table.assistance,
            canUndo = table.canUndo,
            hint = table.hint,
            onHint = viewModel::requestHint,
            onUndo = viewModel::undoDecision,
        )
    } else {
        Column(modifier = modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (state.isLoading) "正在恢复兰州麻将…" else
                state.error ?: "对局暂时无法打开", style = MaterialTheme.typography.titleMedium)
            if (!state.isLoading) {
                Button(onClick = leave) { Text("返回") }
            }
        }
    }
}
