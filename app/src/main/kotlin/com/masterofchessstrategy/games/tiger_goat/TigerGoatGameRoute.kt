package com.masterofchessstrategy.games.tiger_goat

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** The host owns the ViewModel; only consistent checkpoints may silently leave this page. */
@Composable
internal fun TigerGoatGameRoute(viewModel: TigerGoatGameViewModel, onBack: () -> Unit) {
    val page by viewModel.uiState.collectAsState()
    var confirmRestart by remember { mutableStateOf(false) }
    var confirmLeave by remember { mutableStateOf(false) }
    var awaitingLeave by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun finishLeave() {
        if (awaitingLeave) return
        awaitingLeave = true
        scope.launch {
            try { if (viewModel.leave()) onBack() } finally { awaitingLeave = false }
        }
    }
    fun leave() {
        if (page.retiring || awaitingLeave) return
        if (!page.retired && (page.busy || page.canRetrySave)) confirmLeave = true else finishLeave()
    }
    BackHandler { leave() }
    val spec = page.boardSpec
    val game = page.game
    if (spec != null && game != null) {
        TigerGoatGameScreen(spec, game, viewModel::onPointTap, viewModel::endChain,
            viewModel::undo, viewModel::restart, viewModel::resign, ::leave, viewModel::passGoat)
    }
    if (page.busy && game == null) {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("正在载入老虎吃羊棋…")
            TextButton(onClick = ::leave) { Text("返回") }
        }
    }
    if (page.retiring) {
        AlertDialog(onDismissRequest = {}, confirmButton = {}, text = { Text("正在关闭对局…") })
    } else if (page.error != null || page.retired) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(if (page.retired) "对局已停止" else "对局暂未就绪") },
            text = { Text(page.error ?: "请返回后重新打开虎羊棋") },
            confirmButton = {
                Button(onClick = {
                    if (page.retired) finishLeave()
                    else if (page.canRetrySave) viewModel.retrySave() else viewModel.retryLoad()
                }, enabled = !awaitingLeave) {
                    Text(if (page.retired) "返回" else if (page.canRetrySave) "重试保存" else "重试载入")
                }
            },
            dismissButton = {
                if (!page.retired) Column {
                    if (page.recoveryFailed && game != null) {
                        TextButton(onClick = { confirmRestart = true }) { Text("确认重开") }
                    }
                    TextButton(onClick = ::leave) { Text("返回") }
                }
            },
        )
    }
    if (confirmRestart && !page.retired) {
        AlertDialog(
            onDismissRequest = { confirmRestart = false },
            title = { Text("覆盖原存档并重开？") },
            text = { Text("原存档仅在新开局保存成功后替换。") },
            confirmButton = { TextButton(onClick = {
                confirmRestart = false
                viewModel.restartAfterRecoveryFailure()
            }) { Text("重开") } },
            dismissButton = { TextButton(onClick = { confirmRestart = false }) { Text("取消") } },
        )
    }
    if (confirmLeave && !page.retired) {
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text("对局尚未保存") },
            text = { Text("现在返回可能丢失尚未保存的动作；下次载入以最后成功保存的棋局为准。") },
            confirmButton = { TextButton(onClick = { confirmLeave = false; finishLeave() }) { Text("仍然返回") } },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text("继续保存") } },
        )
    }
}
